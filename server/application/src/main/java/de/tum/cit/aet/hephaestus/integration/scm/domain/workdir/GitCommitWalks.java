package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails.FileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange.ChangeType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager.GitOperationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The commit walks of {@link GitRepositoryManager}: JGit walks over a mirror's {@link Repository} that the manager
 * opened under the mirror's read lock, which hand each commit on with its details or its message. It also holds the
 * permits that bound the commit rows written at once.
 */
final class GitCommitWalks {
    private static final Logger log = LoggerFactory.getLogger(GitCommitWalks.class);
    private static final int PAGE_SIZE = 256;

    /** A walk stops after this many pages that needed capture; the next sync cycle resumes from the captured set. */
    private static final int MAX_DETAIL_PAGES_PER_WALK = 32;

    private final Semaphore ingestionPermits;

    GitCommitWalks(int maxConcurrentIngestions) {
        this.ingestionPermits = new Semaphore(maxConcurrentIngestions);
    }

    void forEachCommitInRange(
            Repository repo,
            @Nullable String fromSha,
            String toSha,
            Function<List<String>, Set<String>> captured,
            Consumer<CommitDetails> consumer)
            throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(repo.resolve(toSha)));
            if (fromSha != null) walk.markUninteresting(walk.parseCommit(repo.resolve(fromSha)));
            visit(repo, walk, captured, consumer);
        }
    }

    void forEachCommitBetween(Repository repo, String base, String head, Consumer<CommitDetails> consumer)
            throws IOException {
        ObjectId from = repo.resolve(base);
        ObjectId to = repo.resolve(head);
        if (from == null || to == null) {
            throw new IllegalStateException("The mirror does not hold the pinned range " + base + ".." + head);
        }
        try (RevWalk walk = new RevWalk(repo)) {
            // REVERSE buffers the whole range before yielding, so the byte budget
            // cannot stop it early.
            walk.setRetainBody(false);
            walk.markStart(walk.parseCommit(to));
            walk.markUninteresting(walk.parseCommit(from));
            for (RevCommit commit : walk) {
                checkInterrupted();
                CommitDetails read;
                try {
                    walk.parseBody(commit);
                    read = details(repo, commit);
                } finally {
                    commit.disposeBody();
                }
                consumer.accept(read);
            }
        }
    }

    void forEachCommitMessage(Repository repo, String base, String head, Consumer<String> consumer) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(repo.resolve(head)));
            walk.markUninteresting(walk.parseCommit(repo.resolve(base)));
            for (RevCommit commit : walk) {
                checkInterrupted();
                consumer.accept(commit.getFullMessage());
            }
        }
    }

    void forEachMissingCommit(
            Repository repo, Function<List<String>, Set<String>> captured, Consumer<CommitDetails> consumer)
            throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            for (Ref ref : repo.getRefDatabase().getRefs()) {
                ObjectId id = ref.getObjectId();
                if (id == null) continue;
                // A tag on a tree or blob is witnessed, not walked.
                if (walk.peel(walk.parseAny(id)) instanceof RevCommit commit) walk.markStart(commit);
            }
            visit(repo, walk, captured, consumer);
        }
    }

    private void visit(
            Repository repo,
            RevWalk walk,
            Function<List<String>, Set<String>> captured,
            Consumer<CommitDetails> consumer)
            throws IOException {
        List<RevCommit> page = new ArrayList<>(PAGE_SIZE);
        int capturedPages = 0;
        for (RevCommit commit : walk) {
            checkInterrupted();
            page.add(commit);
            if (page.size() == PAGE_SIZE) {
                if (capturedPages == MAX_DETAIL_PAGES_PER_WALK) {
                    log.info("Git walk paused at its page budget: repository={}", repo.getDirectory());
                    return;
                }
                if (visitPage(repo, page, captured, consumer)) capturedPages++;
                page.clear();
            }
        }
        if (!page.isEmpty() && capturedPages < MAX_DETAIL_PAGES_PER_WALK) visitPage(repo, page, captured, consumer);
    }

    /** @return whether any commit of the page still needed capture */
    private boolean visitPage(
            Repository repo,
            List<RevCommit> page,
            Function<List<String>, Set<String>> captured,
            Consumer<CommitDetails> consumer)
            throws IOException {
        Set<String> existing =
                captured.apply(page.stream().map(RevCommit::getName).toList());
        boolean any = false;
        for (RevCommit commit : page) {
            if (existing.contains(commit.getName())) continue;
            any = true;
            checkInterrupted();
            CommitDetails details = details(repo, commit);
            acquireIngestionPermit();
            try {
                consumer.accept(details);
            } finally {
                ingestionPermits.release();
            }
        }
        return any;
    }

    private static CommitDetails details(Repository repo, RevCommit commit) throws IOException {
        PersonIdent author = commit.getAuthorIdent();
        PersonIdent committer = commit.getCommitterIdent();
        List<FileChange> changes = fileChanges(repo, commit);
        int additions = 0;
        int deletions = 0;
        for (FileChange change : changes) {
            additions = Math.addExact(additions, change.additions());
            deletions = Math.addExact(deletions, change.deletions());
        }
        String message = commit.getFullMessage().stripTrailing();
        int newline = message.indexOf('\n');
        String body = newline < 0 ? null : message.substring(newline + 1).strip();
        return new CommitDetails(
                commit.getName(),
                newline < 0 ? message : message.substring(0, newline),
                body == null || body.isEmpty() ? null : body,
                author.getName(),
                author.getEmailAddress(),
                author.getWhenAsInstant(),
                committer.getName(),
                committer.getEmailAddress(),
                committer.getWhenAsInstant(),
                additions,
                deletions,
                changes.size(),
                changes,
                Stream.of(commit.getParents()).map(RevCommit::getName).toList(),
                commit.getFullMessage());
    }

    /** A merge's changes are those against its first parent, as {@code --diff-merges=first-parent} lists them. */
    private static List<FileChange> fileChanges(Repository repo, RevCommit commit) throws IOException {
        List<FileChange> changes = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo);
                ObjectReader reader = repo.newObjectReader();
                DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repo);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);
            var newTree = new CanonicalTreeParser();
            newTree.reset(reader, commit.getTree());
            List<DiffEntry> entries;
            if (commit.getParentCount() > 0) {
                var oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, walk.parseCommit(commit.getParent(0)).getTree());
                entries = formatter.scan(oldTree, newTree);
            } else {
                entries = formatter.scan(new EmptyTreeIterator(), newTree);
            }
            for (DiffEntry entry : entries) {
                int added = 0;
                int deleted = 0;
                for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
                    deleted += edit.getEndA() - edit.getBeginA();
                    added += edit.getEndB() - edit.getBeginB();
                }
                boolean renamedOrCopied = entry.getChangeType() == DiffEntry.ChangeType.RENAME
                        || entry.getChangeType() == DiffEntry.ChangeType.COPY;
                changes.add(new FileChange(
                        entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath(),
                        switch (entry.getChangeType()) {
                            case ADD -> ChangeType.ADDED;
                            case MODIFY -> ChangeType.MODIFIED;
                            case DELETE -> ChangeType.REMOVED;
                            case RENAME -> ChangeType.RENAMED;
                            case COPY -> ChangeType.COPIED;
                        },
                        added,
                        deleted,
                        Math.addExact(added, deleted),
                        renamedOrCopied ? entry.getOldPath() : null));
            }
        }
        return List.copyOf(changes);
    }

    /** Bounds the commit rows written at once, not what is read from Git. */
    private void acquireIngestionPermit() {
        try {
            ingestionPermits.acquire();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new GitOperationException("Git ingestion admission interrupted", failure);
        }
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new GitOperationException("Git operation interrupted", new InterruptedException());
    }
}

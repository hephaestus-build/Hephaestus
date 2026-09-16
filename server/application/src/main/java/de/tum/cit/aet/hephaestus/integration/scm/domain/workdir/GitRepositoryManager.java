package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails.FileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange.ChangeType;
import java.io.IOException;
import java.io.Serial;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

/**
 * Bare mirrors under the fabric root, one per workspace and repository, and the checkouts a review
 * reads from them. Every operation runs in this process; a role that runs Git owns its own mirrors.
 */
@Service
@EnableConfigurationProperties(GitRepositoryProperties.class)
public class GitRepositoryManager {
    public static final String TREE_LIMITATION_SUBMODULE = "SUBMODULE_EXCLUDED";
    public static final String TREE_LIMITATION_UNSAFE_PATH = "UNSAFE_PATH_EXCLUDED";
    /** Name prefix of the checkouts this manager stages under the fabric root, so a sweep can tell its leftovers apart. */
    public static final String GIT_SNAPSHOT_PREFIX = "git-snapshot-";
    /** Where a fetched review head lives in the mirror; content-addressed, so a refetch is idempotent. */
    public static final String REVIEW_REFS = "refs/hephaestus/reviews/";
    /** What the review container may see of the mirror's refs, with their types; the admission reads it back. */
    public static final String CAPTURED_REFS = "hephaestus-captured-refs";

    private static final String MIRRORS = "mirrors";
    private static final String PROCESS_SPOOL_ID = UUID.randomUUID() + "-";
    private static final Logger log = LoggerFactory.getLogger(GitRepositoryManager.class);
    private static final int PAGE_SIZE = 256;
    /** A walk stops after this many pages that needed capture; the next sync cycle resumes from the captured set. */
    private static final int MAX_DETAIL_PAGES_PER_WALK = 32;

    private static final List<RefSpec> MIRROR_REFSPECS =
            List.of(new RefSpec("+refs/heads/*:refs/heads/*"), new RefSpec("+refs/tags/*:refs/tags/*"));
    /** The mirror's heads become the checkout's remote branches, as in any developer's clone. */
    private static final List<RefSpec> SNAPSHOT_REFSPECS = List.of(
            new RefSpec("+refs/heads/*:refs/remotes/origin/*"),
            new RefSpec("+refs/tags/*:refs/tags/*"),
            new RefSpec("+" + REVIEW_REFS + "*:" + REVIEW_REFS + "*"));

    private final GitRepositoryProperties properties;
    private final GitRepositoryLockManager locks;
    private final FabricLayout layout;
    private final Semaphore ingestionPermits;

    public GitRepositoryManager(
            GitRepositoryProperties properties, GitRepositoryLockManager locks, FabricLayout layout) {
        this.properties = properties;
        this.locks = locks;
        this.layout = layout;
        this.ingestionPermits = new Semaphore(properties.maxConcurrentIngestions());
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    public boolean isRepositoryCloned(RepositoryKey repository) {
        return isEnabled() && Files.isRegularFile(mirror(repository).resolve(Constants.HEAD));
    }

    /** Every workspace's mirror of the repository. */
    public void deleteOrphanedRepository(long repositoryId) {
        Path mirrors = layout.root().resolve(MIRRORS);
        if (!Files.isDirectory(mirrors)) return;
        try (Stream<Path> workspaces = Files.list(mirrors)) {
            for (Path workspace : workspaces.toList()) {
                Path mirror = workspace.resolve(repositoryId + ".git");
                // A fetch that just finished may still be packing in the background; a file it removes
                // under the walk is not a failure to delete.
                if (Files.isDirectory(mirror)) {
                    FileUtils.delete(mirror.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
                }
            }
        } catch (IOException e) {
            throw new GitOperationException("Cannot delete repository mirrors", e);
        }
    }

    public void ensureRepository(RepositoryKey repository, String cloneUrl, @Nullable String token) {
        requireEnabled();
        locks.withWriteLock(repository, () -> {
            Path mirror = mirror(repository);
            try {
                if (!isRepositoryCloned(repository) || !hasOrigin(mirror, cloneUrl)) {
                    // A mirror is a cache keyed by ids a restore can reuse for another upstream: rebuild
                    // rather than retarget, so no old objects survive.
                    FileSystemUtils.deleteRecursively(mirror);
                    Files.createDirectories(mirror.getParent());
                    try (Git git = Git.init()
                            .setBare(true)
                            .setDirectory(mirror.toFile())
                            .call()) {
                        StoredConfig config = git.getRepository().getConfig();
                        config.setString("remote", "origin", "url", cloneUrl);
                        // A redirect would carry the credential to a host the clone URL never named.
                        config.setBoolean("http", null, "followRedirects", false);
                        // A checkout may pin a commit no ref reaches any more; it is fetched from the mirror by id.
                        config.setBoolean("uploadpack", null, "allowAnySHA1InWant", true);
                        config.save();
                    }
                }
                try (Git git = Git.open(mirror.toFile())) {
                    git.fetch()
                            .setRemote("origin")
                            .setRefSpecs(MIRROR_REFSPECS)
                            .setTagOpt(TagOpt.NO_TAGS)
                            .setRemoveDeletedRefs(true)
                            .setCredentialsProvider(credentials(token))
                            .call();
                }
                return null;
            } catch (GitAPIException | IOException e) {
                throw new GitOperationException("Cannot fetch repository " + repository.repositoryId(), e);
            }
        });
    }

    /** Fetches the provider's review ref for a head no branch reaches, such as a fork's, and pins it. */
    public boolean fetchRemoteCommit(
            RepositoryKey repository, String cloneUrl, String remoteRef, String expectedSha, @Nullable String token) {
        requireEnabled();
        if (!Repository.isValidRefName(remoteRef) || !ObjectId.isId(expectedSha)) {
            throw new IllegalArgumentException("Invalid remote review ref or expected commit SHA");
        }
        return locks.withWriteLock(repository, () -> {
            String localRef = REVIEW_REFS + expectedSha.toLowerCase(Locale.ROOT);
            try (Git git = Git.open(mirror(repository).toFile())) {
                if (!hasOrigin(mirror(repository), cloneUrl)) throw new IOException("Mirror origin differs");
                git.fetch()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("+" + remoteRef + ":" + localRef))
                        .setTagOpt(TagOpt.NO_TAGS)
                        .setCredentialsProvider(credentials(token))
                        .call();
                ObjectId resolved = git.getRepository().resolve(localRef);
                return resolved != null && resolved.getName().equals(expectedSha.toLowerCase(Locale.ROOT));
            } catch (GitAPIException | IOException e) {
                throw new GitOperationException("Cannot fetch review commit " + expectedSha, e);
            }
        });
    }

    public @Nullable String resolveBranchHead(RepositoryKey repository, String branch) {
        if (!isEnabled() || !Repository.isValidRefName(Constants.R_HEADS + branch)) return null;
        return read(repository, repo -> {
            Ref ref = repo.exactRef(Constants.R_HEADS + branch);
            return ref == null || ref.getObjectId() == null
                    ? null
                    : ref.getObjectId().getName();
        });
    }

    public boolean commitExists(RepositoryKey repository, String sha) {
        if (!isEnabled() || !ObjectId.isId(sha)) return false;
        return Boolean.TRUE.equals(read(repository, repo -> {
            try (RevWalk walk = new RevWalk(repo)) {
                walk.parseCommit(ObjectId.fromString(sha));
                return true;
            } catch (IOException e) {
                return false;
            }
        }));
    }

    /** {@link RepositoryDiff#reviewBase} on the mirror; null when the range is not a review. */
    public @Nullable String reviewBase(RepositoryKey repository, String targetSha, String headSha) {
        if (!isEnabled() || !ObjectId.isId(targetSha) || !ObjectId.isId(headSha)) return null;
        return read(repository, repo -> {
            ObjectId base =
                    RepositoryDiff.reviewBase(repo, ObjectId.fromString(targetSha), ObjectId.fromString(headSha));
            return base == null ? null : base.getName();
        });
    }

    /** {@link RepositoryDiff#changedPaths} on the mirror. */
    public Set<String> changedPaths(RepositoryKey repository, String baseSha, String headSha) {
        Set<String> paths = read(
                repository,
                repo -> RepositoryDiff.changedPaths(repo, ObjectId.fromString(baseSha), ObjectId.fromString(headSha)));
        return paths == null ? Set.of() : paths;
    }

    /** {@link RepositoryDiff#unifiedDiff} on the mirror. */
    public String unifiedDiff(RepositoryKey repository, String baseSha, String headSha) {
        String text = read(
                repository,
                repo -> RepositoryDiff.unifiedDiff(repo, ObjectId.fromString(baseSha), ObjectId.fromString(headSha)));
        return text == null ? "" : text;
    }

    public void forEachCommitInRange(
            RepositoryKey repository,
            @Nullable String fromSha,
            String toSha,
            Function<List<String>, Set<String>> captured,
            Consumer<CommitDetails> consumer) {
        if (!isEnabled()) return;
        read(repository, repo -> {
            try (RevWalk walk = new RevWalk(repo)) {
                walk.markStart(walk.parseCommit(repo.resolve(toSha)));
                if (fromSha != null) walk.markUninteresting(walk.parseCommit(repo.resolve(fromSha)));
                visit(repo, walk, captured, consumer);
            }
            return null;
        });
    }

    public void forEachCommitSubject(RepositoryKey repository, String base, String head, Consumer<String> consumer) {
        if (!isEnabled()) return;
        read(repository, repo -> {
            try (RevWalk walk = new RevWalk(repo)) {
                walk.markStart(walk.parseCommit(repo.resolve(head)));
                walk.markUninteresting(walk.parseCommit(repo.resolve(base)));
                for (RevCommit commit : walk) {
                    checkInterrupted();
                    consumer.accept(commit.getShortMessage());
                }
            }
            return null;
        });
    }

    /** Every commit reachable from any ref; completion markers skip individual SHAs, never ancestor ranges. */
    public void forEachMissingCommit(
            RepositoryKey repository, Function<List<String>, Set<String>> captured, Consumer<CommitDetails> consumer) {
        if (!isEnabled()) return;
        read(repository, repo -> {
            try (RevWalk walk = new RevWalk(repo)) {
                for (Ref ref : repo.getRefDatabase().getRefs()) {
                    ObjectId id = ref.getObjectId();
                    if (id == null) continue;
                    // A tag on a tree or blob is witnessed, not walked.
                    if (walk.peel(walk.parseAny(id)) instanceof RevCommit commit) walk.markStart(commit);
                }
                visit(repo, walk, captured, consumer);
            }
            return null;
        });
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
                Stream.of(commit.getParents()).map(RevCommit::getName).toList());
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

    /**
     * A checkout of {@code sha} with its own {@code .git}: the mirror's branches and tags as remote
     * refs, the reviewed commit detached at HEAD, symbolic links written as files holding their target,
     * no remote configuration and no credential. Measured before anything is written; a repository over
     * the bound is refused whole. The caller closes the snapshot to delete the directory.
     */
    public GitTreeSnapshot readTreeSnapshot(RepositoryKey repository, String sha) {
        requireEnabled();
        if (!ObjectId.isId(sha)) throw new GitOperationException("Invalid commit", new IOException(sha));
        return locks.withReadLock(repository, () -> {
            Path mirror = mirror(repository);
            Path staging = null;
            try (Git source = Git.open(mirror.toFile())) {
                Repository repo = source.getRepository();
                RevCommit commit;
                try (RevWalk walk = new RevWalk(repo)) {
                    commit = walk.parseCommit(ObjectId.fromString(sha));
                }
                Set<String> limitations = new TreeSet<>();
                long bytes = 0;
                long files = 0;
                List<String> excluded = new ArrayList<>();
                try (ObjectReader reader = repo.newObjectReader();
                        TreeWalk tree = new TreeWalk(reader)) {
                    tree.addTree(commit.getTree());
                    tree.setRecursive(true);
                    while (tree.next()) {
                        checkInterrupted();
                        FileMode mode = tree.getFileMode(0);
                        if (FileMode.GITLINK.equals(mode)) {
                            limitations.add(TREE_LIMITATION_SUBMODULE);
                            continue;
                        }
                        if (tree.getPathString().contains("\\")) {
                            limitations.add(TREE_LIMITATION_UNSAFE_PATH);
                            excluded.add(tree.getPathString());
                            continue;
                        }
                        bytes = Math.addExact(bytes, reader.getObjectSize(tree.getObjectId(0), Constants.OBJ_BLOB));
                        files++;
                    }
                }
                long history = directorySize(mirror.resolve(Constants.OBJECTS));
                if (bytes + history > properties.maxSnapshotBytes()) {
                    throw new GitOperationException(
                            "Repository snapshot exceeds hephaestus.git.max-snapshot-bytes", new IOException());
                }
                Files.createDirectories(layout.root());
                staging = Files.createTempDirectory(layout.root(), GIT_SNAPSHOT_PREFIX + PROCESS_SPOOL_ID);
                try (Git git = Git.init().setDirectory(staging.toFile()).call()) {
                    StoredConfig config = git.getRepository().getConfig();
                    // A link in the checkout would let the review read outside it; it is a file holding the target.
                    config.setBoolean("core", null, "symlinks", false);
                    config.save();
                    git.fetch()
                            .setRemote(mirror.toUri().toString())
                            .setRefSpecs(SNAPSHOT_REFSPECS)
                            .setTagOpt(TagOpt.NO_TAGS)
                            .call();
                    if (!git.getRepository().getObjectDatabase().has(commit)) {
                        // The reviewed commit sits on no branch, tag or review ref: it was pinned by id only.
                        git.fetch()
                                .setRemote(mirror.toUri().toString())
                                .setRefSpecs(new RefSpec(sha + ":" + REVIEW_REFS + sha))
                                .setTagOpt(TagOpt.NO_TAGS)
                                .call();
                    }
                    git.checkout().setName(sha).call();
                    for (String path : excluded) Files.deleteIfExists(staging.resolve(path));
                    writeCapturedRefs(git.getRepository());
                }
                return new GitTreeSnapshot(
                        staging, sha, commit.getTree().getName(), bytes, files, limitations.isEmpty(), limitations);
            } catch (GitOperationException e) {
                if (staging != null) deleteTreeQuietly(staging);
                throw e;
            } catch (GitAPIException | IOException | RuntimeException e) {
                if (staging != null) deleteTreeQuietly(staging);
                throw new GitOperationException("Cannot prepare Git snapshot", e);
            }
        });
    }

    /** Tags are peeled and every line carries its type, so a tag on a tree or blob is witnessed, not walked. */
    private static void writeCapturedRefs(Repository repo) throws IOException {
        try (RevWalk walk = new RevWalk(repo);
                Writer out = Files.newBufferedWriter(
                        repo.getDirectory().toPath().resolve(CAPTURED_REFS), StandardCharsets.UTF_8)) {
            List<Ref> refs = new ArrayList<>(repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + "origin/"));
            refs.addAll(repo.getRefDatabase().getRefsByPrefix(Constants.R_TAGS));
            for (Ref ref : refs) {
                ObjectId id = ref.getObjectId();
                if (id == null) continue;
                Ref peeled = repo.getRefDatabase().peel(ref);
                ObjectId target = peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : id;
                out.write(Constants.typeString(walk.parseAny(target).getType()) + " " + target.getName() + " "
                        + ref.getName() + "\n");
            }
        }
    }

    private static long directorySize(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> paths = Files.walk(directory)) {
            long total = 0;
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path)) total = Math.addExact(total, Files.size(path));
            }
            return total;
        }
    }

    /** The fabric root is process-local; only a previous process's checkout may be swept by age. */
    public static boolean isCurrentProcessSpool(Path path) {
        return path.getFileName().toString().startsWith(GIT_SNAPSHOT_PREFIX + PROCESS_SPOOL_ID);
    }

    /** A read of the mirror's object and ref databases. */
    public interface MirrorRead<T> {
        @Nullable
        T apply(Repository repo) throws IOException;
    }

    /** Runs a read against the mirror under its read lock; a fetch cannot rewrite refs underneath it. */
    public <T> @Nullable T read(RepositoryKey repository, MirrorRead<T> operation) {
        return locks.withReadLock(repository, () -> {
            try (Git git = Git.open(mirror(repository).toFile())) {
                return operation.apply(git.getRepository());
            } catch (IOException e) {
                throw new GitOperationException("Cannot read repository " + repository.repositoryId(), e);
            }
        });
    }

    private Path mirror(RepositoryKey repository) {
        return layout.root()
                .resolve(MIRRORS)
                .resolve(Long.toString(repository.workspaceId()))
                .resolve(repository.repositoryId() + ".git");
    }

    /** JGit fetches from the first configured URL but reads back the last; a stale value ahead of the current one is a change. */
    private static boolean hasOrigin(Path mirror, String cloneUrl) throws IOException {
        try (Git git = Git.open(mirror.toFile())) {
            String[] urls = git.getRepository().getConfig().getStringList("remote", "origin", "url");
            return urls.length == 1 && cloneUrl.equals(urls[0]);
        }
    }

    private static @Nullable UsernamePasswordCredentialsProvider credentials(@Nullable String token) {
        return token == null || token.isBlank() ? null : new UsernamePasswordCredentialsProvider("oauth2", token);
    }

    private void requireEnabled() {
        if (!isEnabled()) throw new IllegalStateException("Git repository preparation is disabled");
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

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new GitOperationException("Git operation interrupted", new InterruptedException());
    }

    static void deleteTreeQuietly(Path root) {
        try {
            FileSystemUtils.deleteRecursively(root);
        } catch (IOException e) {
            log.warn("Could not remove Git snapshot directory {}", root, e);
        }
    }

    public record GitTreeSnapshot(
            Path stagingDir,
            String commitSha,
            String treeSha,
            long totalBytes,
            long visitedFiles,
            boolean complete,
            Set<String> limitations)
            implements AutoCloseable {
        public GitTreeSnapshot {
            limitations = Set.copyOf(limitations);
        }

        @Override
        public void close() {
            deleteTreeQuietly(stagingDir);
        }
    }

    public static class GitOperationException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        public GitOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

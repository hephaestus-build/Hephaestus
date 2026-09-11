package de.tum.cit.aet.hephaestus.agent.context.providers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Diff and commit-log operations on local git clones. Diff output uses
 * {@link DiffAlgorithm.SupportedAlgorithm#HISTOGRAM} to match git CLI 2.34+ defaults; renames
 * use a 50% similarity floor (git's {@code -M} default) rather than JGit's 60%.
 */
@Component
public class GitDiffOperations {

    private static final Logger log = LoggerFactory.getLogger(GitDiffOperations.class);
    static final int MAX_DIFF_BYTES = 20 * 1024 * 1024;

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    @FunctionalInterface
    private interface RepoOp<T> {
        @Nullable
        T apply(Repository repo) throws IOException;
    }

    @Nullable
    private <T> T withRepo(Path repoPath, String operation, RepoOp<T> op) {
        try (Git git = Git.open(repoPath.toFile())) {
            return op.apply(git.getRepository());
        } catch (MissingObjectException e) {
            log.debug("{}: unresolved object in {}: {}", operation, repoPath, e.getMessage());
            return null;
        } catch (IOException e) {
            log.warn("{} failed for {}: {}", operation, repoPath, e.getMessage());
            return null;
        }
    }

    private static ObjectId @Nullable [] resolveRange(Repository repo, String baseRef, String headRef)
            throws IOException {
        ObjectId baseId = repo.resolve(baseRef);
        ObjectId headId = repo.resolve(headRef);
        return (baseId == null || headId == null) ? null : new ObjectId[] {baseId, headId};
    }

    /**
     * Resolve {@code [baseSha, headSha]} for a PR/MR diff. Tries, in order:
     * <ol>
     *   <li>Resolve the target and pinned head, then prefer their merge-base so target-only
     *       changes never appear as phantom review changes.</li>
     *   <li>A merge commit reachable from origin/target has {@code headSha} as its second parent
     *       → {@code [firstParent, headSha]}. Handles squash-and-merge / post-merge force-push.</li>
     *   <li>Merge-base of origin/target and {@code headSha}, accepted only when it differs from
     *       {@code headSha}.</li>
     * </ol>
     */
    public String @Nullable [] resolveDiffRange(
            Path repoPath, String targetBranch, String sourceBranch, @Nullable String headSha) {
        if (headSha == null || headSha.isBlank()) {
            return null;
        }
        return withRepo(repoPath, "resolveDiffRange", repo -> {
            ObjectId head = repo.resolve(headSha);
            if (head == null) {
                return null;
            }

            ObjectId branchBase = repo.resolve("refs/remotes/origin/" + targetBranch);
            ObjectId branchHead = repo.resolve("refs/remotes/origin/" + sourceBranch);
            // Do NOT short-circuit to [targetBranchTip, head] when the source ref matches head: that is a
            // 2-dot range (git diff target..head) which, once the target branch has advanced past the
            // fork point, surfaces files the target changed as phantom diffs — the developer never
            // touched them. Always fall through to the merge-base so the range is 3-dot
            // (git diff target...head = only what THIS branch added since it diverged).
            if (branchBase != null && branchHead != null && !branchHead.equals(head) && log.isDebugEnabled()) {
                // Informational only: the source tip is never used as a range endpoint (we always use the
                // merge-base for a 3-dot range), so a source ref that differs from head is the normal expected
                // condition, not an actionable problem.
                log.debug(
                        "source ref origin/{} resolves to {} not head {}, using merge-base range",
                        sourceBranch,
                        branchHead.getName(),
                        headSha);
            }

            ObjectId target = branchBase != null ? branchBase : repo.resolve(targetBranch);
            if (target == null) {
                return null;
            }

            try (RevWalk walk = new RevWalk(repo)) {
                walk.setRetainBody(false);
                walk.markStart(walk.parseCommit(target));
                walk.markUninteresting(walk.parseCommit(head));
                for (RevCommit commit : walk) {
                    RevCommit[] parents = commit.getParents();
                    if (parents.length >= 2 && parents[1].getId().equals(head)) {
                        return new String[] {parents[0].getId().getName(), head.getName()};
                    }
                }
            }

            try (RevWalk walk = new RevWalk(repo)) {
                walk.setRevFilter(RevFilter.MERGE_BASE);
                walk.markStart(walk.parseCommit(target));
                walk.markStart(walk.parseCommit(head));
                RevCommit base = walk.next();
                if (base != null && !base.getId().equals(head)) {
                    return new String[] {base.getId().getName(), head.getName()};
                }
            }

            // No usable 3-dot base: the source is already an ancestor of the target (merged → a 3-dot
            // diff is legitimately empty) or the histories are disjoint. Both legitimately resolve to null.
            return null;
        });
    }

    /**
     * Produce a unified diff between {@code baseRef..headRef}. Equivalent to
     * {@code git -c diff.algorithm=histogram diff base..head}.
     */
    @Nullable
    public String diff(Path repoPath, String baseRef, String headRef) {
        return withRepo(repoPath, "diff", repo -> {
            ObjectId[] range = resolveRange(repo, baseRef, headRef);
            if (range == null) return null;

            try (LimitedOutputStream out = new LimitedOutputStream(MAX_DIFF_BYTES);
                    ObjectReader reader = repo.newObjectReader();
                    RevWalk walk = new RevWalk(repo);
                    DiffFormatter formatter = newDiffFormatter(repo, out)) {
                formatter.format(treeIterator(reader, walk, range[0]), treeIterator(reader, walk, range[1]));
                formatter.flush();
                return out.content();
            }
        });
    }

    private static final class LimitedOutputStream extends OutputStream {

        private final int limit;
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        private LimitedOutputStream(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
        }

        private void ensureCapacity(int additionalBytes) throws IOException {
            if (delegate.size() + additionalBytes > limit) {
                throw new IOException("Diff exceeds " + limit + " bytes");
            }
        }

        private String content() {
            return delegate.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Per-file diff statistics shaped like {@code git diff --stat}: {@code  path | N} for text
     * files, {@code  path | Bin} for binaries, renamed files as {@code old => new}. No summary
     * footer — emitted verbatim as {@code diff_stat.txt} for the agent to read.
     */
    @Nullable
    public String diffStat(Path repoPath, String baseRef, String headRef) {
        return withRepo(repoPath, "diffStat", repo -> {
            ObjectId[] range = resolveRange(repo, baseRef, headRef);
            if (range == null) return null;

            StringBuilder out = new StringBuilder();
            try (ObjectReader reader = repo.newObjectReader();
                    RevWalk walk = new RevWalk(repo);
                    DiffFormatter formatter = newDiffFormatter(repo, null)) {
                List<DiffEntry> entries =
                        formatter.scan(treeIterator(reader, walk, range[0]), treeIterator(reader, walk, range[1]));
                for (DiffEntry entry : entries) {
                    out.append(' ')
                            .append(displayPath(entry))
                            .append(" | ")
                            .append(statColumn(formatter, entry))
                            .append('\n');
                }
            }
            return out.toString();
        });
    }

    /** One path per line; renames return the new path (matches {@code git diff --name-only}). */
    @Nullable
    public String diffNameOnly(Path repoPath, String baseRef, String headRef) {
        return withRepo(repoPath, "diffNameOnly", repo -> {
            ObjectId[] range = resolveRange(repo, baseRef, headRef);
            if (range == null) return null;

            StringBuilder out = new StringBuilder();
            try (ObjectReader reader = repo.newObjectReader();
                    RevWalk walk = new RevWalk(repo);
                    DiffFormatter formatter = newDiffFormatter(repo, null)) {
                for (DiffEntry entry :
                        formatter.scan(treeIterator(reader, walk, range[0]), treeIterator(reader, walk, range[1]))) {
                    out.append(singlePath(entry)).append('\n');
                }
            }
            return out.toString();
        });
    }

    /** The commits of one range, oldest first; {@code truncated} when the range held more than were kept. */
    public record CommitLog(List<CommitLogEntry> commits, boolean truncated) {}

    /**
     * One commit as {@code git log} shows it: {@code subject} is git's title paragraph and {@code body} the
     * rest; {@code changedFiles} counts the paths that differ from the first parent, so a merge commit has none.
     */
    public record CommitLogEntry(
            String sha,
            String subject,
            @Nullable String body,
            Instant authoredAt,
            Instant committedAt,
            int parentCount,
            @Nullable Integer changedFiles) {}

    /**
     * {@code git log --topo-order --reverse base..head}, keeping at most {@code limit} commits. Topological
     * order keeps a merged branch's commits together where commit-time order would interleave them with the
     * other line of history.
     */
    @Nullable
    public CommitLog commitLog(Path repoPath, String baseRef, String headRef, int limit) {
        return withRepo(repoPath, "commitLog", repo -> {
            ObjectId[] range = resolveRange(repo, baseRef, headRef);
            if (range == null) return null;

            List<CommitLogEntry> entries = new ArrayList<>();
            boolean truncated = false;
            try (RevWalk walk = new RevWalk(repo);
                    ObjectReader reader = repo.newObjectReader()) {
                walk.sort(RevSort.TOPO_KEEP_BRANCH_TOGETHER);
                walk.sort(RevSort.REVERSE, true);
                // The walk loads the whole range before it yields its first commit; keeping bodies only for
                // the kept commits bounds what an oversized range holds in memory to its graph.
                walk.setRetainBody(false);
                walk.markStart(walk.parseCommit(range[1]));
                walk.markUninteresting(walk.parseCommit(range[0]));
                for (RevCommit commit : walk) {
                    if (entries.size() == limit) {
                        truncated = true;
                        break;
                    }
                    entries.add(toLogEntry(commit, walk, reader));
                }
            }
            return new CommitLog(List.copyOf(entries), truncated);
        });
    }

    private static CommitLogEntry toLogEntry(RevCommit commit, RevWalk walk, ObjectReader reader) throws IOException {
        walk.parseBody(commit);
        String[] paragraphs = commit.getFullMessage().split("\\R\\R", 2);
        String body = paragraphs.length == 2 ? paragraphs[1].strip() : "";

        Integer changedFiles = null;
        if (commit.getParentCount() == 1) {
            // A tree walk counts the paths that differ without reading a blob, so a long history stays cheap.
            try (TreeWalk treeWalk = new TreeWalk(reader)) {
                treeWalk.addTree(walk.parseCommit(commit.getParent(0)).getTree());
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                changedFiles = DiffEntry.scan(treeWalk).size();
            }
        }
        return new CommitLogEntry(
                commit.getName(),
                commit.getShortMessage(),
                body.isEmpty() ? null : body,
                commit.getAuthorIdent().getWhenAsInstant(),
                commit.getCommitterIdent().getWhenAsInstant(),
                commit.getParentCount(),
                changedFiles);
    }

    private static String statColumn(DiffFormatter formatter, DiffEntry entry) {
        try {
            FileHeader header = formatter.toFileHeader(entry);
            if (header.getPatchType() == FileHeader.PatchType.BINARY) {
                return "Bin";
            }
            int additions = 0;
            int deletions = 0;
            for (Edit edit : header.toEditList()) {
                deletions += edit.getEndA() - edit.getBeginA();
                additions += edit.getEndB() - edit.getBeginB();
            }
            return Integer.toString(additions + deletions);
        } catch (IOException e) {
            log.debug("Skipped stat for {}: {}", entry.getNewPath(), e.getMessage());
            return "0";
        }
    }

    private static DiffFormatter newDiffFormatter(Repository repo, @Nullable OutputStream out) {
        DiffFormatter formatter =
                new DiffFormatter(out != null ? out : org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE);
        formatter.setRepository(repo);
        formatter.setDiffAlgorithm(DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM));
        formatter.setDiffComparator(RawTextComparator.DEFAULT);
        formatter.setDetectRenames(true);
        // Match `git diff -M` default (50% similarity); JGit's RenameDetector defaults to 60%.
        formatter.getRenameDetector().setRenameScore(50);
        return formatter;
    }

    private static AbstractTreeIterator treeIterator(ObjectReader reader, RevWalk walk, ObjectId commitId)
            throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, walk.parseCommit(commitId).getTree());
        return parser;
    }

    private static String displayPath(DiffEntry entry) {
        return switch (entry.getChangeType()) {
            case DELETE -> entry.getOldPath();
            case RENAME, COPY -> entry.getOldPath() + " => " + entry.getNewPath();
            default -> entry.getNewPath();
        };
    }

    /**
     * Single-path form for {@code --name-only}-style output: renames return only the new path
     * (deletes return the old path), so downstream {@code diffFiles.contains(path)} checks against
     * concrete file paths succeed even when the file was renamed.
     */
    private static String singlePath(DiffEntry entry) {
        return entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
    }

    /**
     * Annotate unified-diff content with {@code [L<n>]} line numbers from the applicable side.
     */
    public static String annotateDiffWithLineNumbers(String diff) {
        String[] lines = diff.split("\n", -1);
        StringBuilder out = new StringBuilder(diff.length() + lines.length * 6);
        Integer oldLineNum = null;
        Integer newLineNum = null;

        for (String line : lines) {
            // A new file's header resets the counter: without this, the metadata + first lines of the SECOND
            // file in a multi-file diff inherit the FIRST file's trailing line number until that file's first
            // hunk header is reached, mis-stamping every [L<n>] marker. Emit the header verbatim.
            if (line.startsWith("diff --git")) {
                oldLineNum = null;
                newLineNum = null;
                out.append(line).append('\n');
                continue;
            }

            Matcher m = HUNK_HEADER.matcher(line);
            if (m.find()) {
                oldLineNum = Integer.parseInt(m.group(1));
                newLineNum = Integer.parseInt(m.group(2));
                out.append(line).append('\n');
                continue;
            }

            if (newLineNum == null) {
                out.append(line).append('\n');
                continue;
            }

            // The trailing element of split("\n", -1) is "" because the JGit diff ends with '\n'. When the diff
            // ended inside a hunk (newLineNum non-null) that empty element would otherwise hit the context branch
            // below and emit a spurious "[L<n>] " line the model reads as a real (empty) source line. An in-hunk
            // blank context line is a single space, never empty, so skipping the empty element is safe.
            if (line.isEmpty()) {
                out.append('\n');
                continue;
            }

            // The "\ No newline at end of file" marker is diff metadata, not source content — emit it verbatim
            // and do NOT advance the line counter (it does not correspond to a new-side source line).
            if (line.startsWith("\\")) {
                out.append(line).append('\n');
                continue;
            }

            if (line.startsWith("+")) {
                out.append("[L").append(newLineNum).append("] ").append(line).append('\n');
                newLineNum++;
            } else if (oldLineNum == null) {
                throw new IllegalStateException("Diff line outside a hunk");
            } else if (line.startsWith("-")) {
                out.append("[L").append(oldLineNum).append("] ").append(line).append('\n');
                oldLineNum++;
            } else {
                out.append("[L").append(newLineNum).append("] ").append(line).append('\n');
                oldLineNum++;
                newLineNum++;
            }
        }

        return out.toString();
    }
}

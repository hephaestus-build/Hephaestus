package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** What a review reads of a change: its range, the annotated patch and the commits in between. */
@Component
public class GitDiffOperations {
    /** What one diff capture stages, and what {@code scm.pull-request.diff} owns among the staged files. */
    public static final Set<String> FILES = Set.of("diff.patch", "diff_stat.txt", "diff_summary.md", "diff_paths.nul");

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
    private static final int RENAME_SCORE = 50;

    private final GitRepositoryManager git;
    private final ObjectMapper mapper;

    public GitDiffOperations(GitRepositoryManager git, ObjectMapper mapper) {
        this.git = git;
        this.mapper = mapper;
    }

    /**
     * The pinned range of a review: a merge reachable from the target whose second parent is the head
     * names its first parent (a squash or a force-push after merging), otherwise the merge base. Null
     * when the head is already part of the target or the histories are disjoint.
     */
    public String @Nullable [] resolveDiffRange(RepositoryKey repository, String targetSha, String headSha) {
        return git.read(repository, repo -> {
            ObjectId target = repo.resolve(targetSha);
            ObjectId head = repo.resolve(headSha);
            if (target == null || head == null) return null;
            try (RevWalk walk = new RevWalk(repo)) {
                walk.setRetainBody(false);
                walk.markStart(walk.parseCommit(target));
                walk.markUninteresting(walk.parseCommit(head));
                for (RevCommit commit : walk) {
                    RevCommit[] parents = commit.getParents();
                    if (parents.length >= 2 && parents[1].getId().equals(head)) {
                        return new String[] {parents[0].getName(), head.getName()};
                    }
                }
            }
            try (RevWalk walk = new RevWalk(repo)) {
                walk.setRevFilter(RevFilter.MERGE_BASE);
                walk.markStart(walk.parseCommit(target));
                walk.markStart(walk.parseCommit(head));
                RevCommit base = walk.next();
                return base == null || base.getId().equals(head) ? null : new String[] {base.getName(), head.getName()};
            }
        });
    }

    public DiffCapture capture(RepositoryKey repository, String base, String head) {
        Path directory;
        try {
            directory = Files.createTempDirectory("review-diff-");
        } catch (IOException exception) {
            throw new JobPreparationException("Could not allocate diff staging", exception);
        }
        try {
            git.read(repository, repo -> {
                try (ObjectReader reader = repo.newObjectReader();
                        RevWalk walk = new RevWalk(repo);
                        OutputStream patch = Files.newOutputStream(directory.resolve("diff.patch"));
                        DiffFormatter formatter = formatter(repo, new LineAnnotatingOutputStream(patch))) {
                    var oldTree = tree(reader, walk, repo.resolve(base));
                    var newTree = tree(reader, walk, repo.resolve(head));
                    List<DiffEntry> entries = formatter.scan(oldTree, newTree);
                    formatter.format(entries);
                    formatter.flush();
                    writeStat(directory.resolve("diff_stat.txt"), formatter, entries);
                    writePaths(directory.resolve("diff_paths.nul"), entries);
                }
                Files.writeString(
                        directory.resolve("diff_summary.md"),
                        "# Diff summary\n\nSee `diff_stat.txt` for the file overview and `diff.patch` for the change,"
                                + " annotated with source line numbers.\n");
                return null;
            });
            Map<String, Path> files = new LinkedHashMap<>();
            for (String name : List.of("diff.patch", "diff_stat.txt", "diff_summary.md", "diff_paths.nul")) {
                files.put(name, directory.resolve(name));
            }
            return new DiffCapture(directory, Map.copyOf(files));
        } catch (RuntimeException exception) {
            FileUtils.deleteQuietly(directory.toFile());
            throw new JobPreparationException("Could not prepare repository diff", exception);
        }
    }

    /** The commits between base and head, oldest first, as the review reads them. */
    public CommitCapture captureCommits(RepositoryKey repository, String base, String head) {
        Path path;
        try {
            path = Files.createTempFile("review-commits-", ".json");
        } catch (IOException exception) {
            throw new JobPreparationException("Could not allocate commit staging", exception);
        }
        try {
            git.read(repository, repo -> {
                try (RevWalk walk = new RevWalk(repo)) {
                    // git log --topo-order: a merged branch's commits stay together whatever their times.
                    walk.sort(RevSort.TOPO_KEEP_BRANCH_TOGETHER);
                    walk.markStart(walk.parseCommit(repo.resolve(head)));
                    walk.markUninteresting(walk.parseCommit(repo.resolve(base)));
                    List<RevCommit> commits = new ArrayList<>();
                    for (RevCommit commit : walk) commits.add(commit);
                    ObjectNode document = mapper.createObjectNode();
                    ArrayNode list = document.putArray("commits");
                    for (int index = commits.size() - 1; index >= 0; index--) {
                        RevCommit commit = commits.get(index);
                        ObjectNode node = list.addObject();
                        node.put("sha", commit.getName());
                        node.put("subject", commit.getShortMessage());
                        String body = body(commit.getFullMessage());
                        if (body != null) node.put("body", body);
                        node.put(
                                "authored_at",
                                commit.getAuthorIdent().getWhenAsInstant().toString());
                        node.put(
                                "committed_at",
                                commit.getCommitterIdent().getWhenAsInstant().toString());
                        node.put("parent_count", commit.getParentCount());
                        // A merge lists no files, as merge diffs are off.
                        if (commit.getParentCount() == 1) node.put("changed_files", changedFiles(repo, walk, commit));
                    }
                    document.put("truncated", false);
                    mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), document);
                }
                return null;
            });
            return new CommitCapture(path);
        } catch (RuntimeException exception) {
            FileUtils.deleteQuietly(path.toFile());
            throw new JobPreparationException("Could not capture review commits", exception);
        }
    }

    /** {@code %b}: what follows the subject paragraph. */
    private static @Nullable String body(String fullMessage) {
        int paragraph = fullMessage.indexOf("\n\n");
        if (paragraph < 0) return null;
        String body = fullMessage.substring(paragraph + 2).strip();
        return body.isEmpty() ? null : body;
    }

    private static int changedFiles(Repository repo, RevWalk walk, RevCommit commit) throws IOException {
        try (ObjectReader reader = repo.newObjectReader();
                DiffFormatter formatter = formatter(repo, OutputStream.nullOutputStream())) {
            return formatter
                    .scan(tree(reader, walk, commit.getParent(0)), tree(reader, walk, commit))
                    .size();
        }
    }

    /** {@code git -c diff.algorithm=histogram diff -M50%}. */
    private static DiffFormatter formatter(Repository repo, OutputStream out) {
        DiffFormatter formatter = new DiffFormatter(out);
        formatter.setRepository(repo);
        formatter.setDiffAlgorithm(DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM));
        formatter.setDiffComparator(RawTextComparator.DEFAULT);
        formatter.setDetectRenames(true);
        formatter.getRenameDetector().setRenameScore(RENAME_SCORE);
        return formatter;
    }

    private static CanonicalTreeParser tree(ObjectReader reader, RevWalk walk, @Nullable ObjectId commit)
            throws IOException {
        if (commit == null) throw new IOException("Unknown commit");
        var parser = new CanonicalTreeParser();
        parser.reset(reader, walk.parseCommit(commit).getTree());
        return parser;
    }

    /** {@code git diff --stat}: one line per file with its change count, then the totals. */
    private static void writeStat(Path file, DiffFormatter formatter, List<DiffEntry> entries) throws IOException {
        int additions = 0;
        int deletions = 0;
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (DiffEntry entry : entries) {
                FileHeader header = formatter.toFileHeader(entry);
                String column;
                if (header.getPatchType() == FileHeader.PatchType.BINARY) {
                    column = "Bin";
                } else {
                    int added = 0;
                    int deleted = 0;
                    for (Edit edit : header.toEditList()) {
                        deleted += edit.getEndA() - edit.getBeginA();
                        added += edit.getEndB() - edit.getBeginB();
                    }
                    additions += added;
                    deletions += deleted;
                    column = (added + deleted) + " " + "+".repeat(added) + "-".repeat(deleted);
                }
                String path =
                        switch (entry.getChangeType()) {
                            case DELETE -> entry.getOldPath();
                            case RENAME, COPY -> entry.getOldPath() + " => " + entry.getNewPath();
                            default -> entry.getNewPath();
                        };
                out.write(" " + path + " | " + column + "\n");
            }
            if (!entries.isEmpty()) {
                out.write(" " + entries.size() + " file" + (entries.size() == 1 ? "" : "s") + " changed, " + additions
                        + " insertion" + (additions == 1 ? "" : "s") + "(+), " + deletions + " deletion"
                        + (deletions == 1 ? "" : "s") + "(-)\n");
            }
        }
    }

    /** {@code git diff --name-only -z}: a deleted file by its old path, a renamed one by its new path. */
    private static void writePaths(Path file, List<DiffEntry> entries) throws IOException {
        try (Writer out = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            for (DiffEntry entry : entries) {
                out.write(
                        entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath());
                out.write('\0');
            }
        }
    }

    /**
     * Prefixes every hunk line with the source line it shows, {@code [L<n>] }: the new side for
     * additions and context, the old side for deletions. Headers and {@code \} markers pass through.
     */
    static final class LineAnnotatingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        private int oldLine = -1;
        private int newLine = -1;

        LineAnnotatingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int value) throws IOException {
            line.write(value);
            if (value == '\n') flushLine();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            for (int index = offset; index < offset + length; index++) write(bytes[index]);
        }

        private void flushLine() throws IOException {
            byte[] bytes = line.toByteArray();
            line.reset();
            String head = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.UTF_8);
            if (head.startsWith("diff --git ")) {
                oldLine = -1;
                newLine = -1;
            } else {
                Matcher hunk = HUNK_HEADER.matcher(head);
                if (hunk.find()) {
                    oldLine = Integer.parseInt(hunk.group(1));
                    newLine = Integer.parseInt(hunk.group(2));
                    delegate.write(bytes);
                    return;
                }
            }
            if (newLine >= 0 && bytes.length > 1 && bytes[0] != '\\') {
                int number;
                switch (bytes[0]) {
                    case '+' -> number = newLine++;
                    case '-' -> number = oldLine++;
                    case ' ' -> {
                        number = newLine++;
                        oldLine++;
                    }
                    default -> throw new IOException("Invalid diff hunk content");
                }
                delegate.write(("[L" + number + "] ").getBytes(StandardCharsets.UTF_8));
            }
            delegate.write(bytes);
        }

        @Override
        public void flush() throws IOException {
            if (line.size() > 0) flushLine();
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
            delegate.close();
        }
    }

    public record CommitCapture(Path path) implements Closeable {
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    public record DiffCapture(Path directory, Map<String, Path> files) implements Closeable {
        public boolean isEmpty() throws IOException {
            return Files.size(directory.resolve("diff.patch")) == 0;
        }

        @Override
        public void close() throws IOException {
            FileUtils.deleteDirectory(directory.toFile());
        }
    }
}

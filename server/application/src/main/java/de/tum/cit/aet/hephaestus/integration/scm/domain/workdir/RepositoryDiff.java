package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Reads the review base, changed paths and diff from Git for planning and citation verification.
 * Rename detection matches {@code git diff -M50%} used by the container.
 */
public final class RepositoryDiff {

    /** {@code git diff -M50%}. */
    private static final int RENAME_SCORE = 50;

    private RepositoryDiff() {}

    /**
     * The base of a review of {@code head} against {@code target}: a merge reachable from the target
     * whose second parent is the head names its first parent (a squash or a force-push after merging),
     * otherwise the merge base. Null when the head is already part of the target or the histories are
     * disjoint.
     */
    public static @Nullable ObjectId reviewBase(Repository repo, ObjectId target, ObjectId head) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRetainBody(false);
            walk.markStart(walk.parseCommit(target));
            walk.markUninteresting(walk.parseCommit(head));
            for (RevCommit commit : walk) {
                RevCommit[] parents = commit.getParents();
                if (parents.length >= 2 && parents[1].getId().equals(head)) {
                    return parents[0].getId();
                }
            }
        }
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(target));
            walk.markStart(walk.parseCommit(head));
            RevCommit base = walk.next();
            return base == null || base.getId().equals(head) ? null : base.getId();
        }
    }

    /** Every path the change touches, under its old name and its new one. */
    public static Set<String> changedPaths(Repository repo, ObjectId base, ObjectId head) throws IOException {
        Set<String> paths = new TreeSet<>();
        try (DiffFormatter formatter = formatter(repo, DisabledOutputStream.INSTANCE)) {
            for (DiffEntry entry : entries(repo, formatter, base, head)) {
                if (entry.getChangeType() != DiffEntry.ChangeType.ADD) paths.add(entry.getOldPath());
                if (entry.getChangeType() != DiffEntry.ChangeType.DELETE) paths.add(entry.getNewPath());
            }
        }
        return Set.copyOf(paths);
    }

    /** The change as {@code git diff -M50%} prints it, for a literal search; never shown to a review. */
    public static String unifiedDiff(Repository repo, ObjectId base, ObjectId head) throws IOException {
        var out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = formatter(repo, out)) {
            formatter.format(entries(repo, formatter, base, head));
            formatter.flush();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static List<DiffEntry> entries(Repository repo, DiffFormatter formatter, ObjectId base, ObjectId head)
            throws IOException {
        try (ObjectReader reader = repo.newObjectReader();
                RevWalk walk = new RevWalk(repo)) {
            return formatter.scan(tree(reader, walk, base), tree(reader, walk, head));
        }
    }

    private static DiffFormatter formatter(Repository repo, java.io.OutputStream out) {
        DiffFormatter formatter = new DiffFormatter(out);
        formatter.setRepository(repo);
        formatter.setDiffAlgorithm(DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM));
        formatter.setDiffComparator(RawTextComparator.DEFAULT);
        formatter.setDetectRenames(true);
        formatter.getRenameDetector().setRenameScore(RENAME_SCORE);
        return formatter;
    }

    private static CanonicalTreeParser tree(ObjectReader reader, RevWalk walk, ObjectId commit) throws IOException {
        var parser = new CanonicalTreeParser();
        parser.reset(reader, walk.parseCommit(commit).getTree());
        return parser;
    }
}

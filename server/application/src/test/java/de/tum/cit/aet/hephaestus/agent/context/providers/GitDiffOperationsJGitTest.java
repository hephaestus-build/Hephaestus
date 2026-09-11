package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitDiffOperationsJGitTest extends BaseUnitTest {

    private final GitDiffOperations ops = new GitDiffOperations();

    @TempDir
    Path repoDir;

    private Git git;
    private Repository repo;
    private String baseSha;
    private String headSha;

    /**
     * Topology after setUp:
     *   main:    baseSha
     *   feature: baseSha → headSha
     *   origin/main → refs/heads/main (= baseSha)
     *   origin/feature → refs/heads/feature (= headSha)
     */
    @BeforeEach
    void setUp() throws GitAPIException, IOException {
        git = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("main").call();
        repo = git.getRepository();
        GitTestFixtures.disableSigning(repo);

        write("a.txt", "line one\nline two\nline three\n");
        baseSha = commit("initial");

        git.branchCreate().setName("feature").setStartPoint(baseSha).call();
        git.checkout().setName("feature").call();
        write("a.txt", "line one\nline two changed\nline three\nline four added\n");
        write("b.txt", "brand new\n");
        headSha = commit("change a, add b");

        repo.updateRef("refs/remotes/origin/main").link("refs/heads/main");
        repo.updateRef("refs/remotes/origin/feature").link("refs/heads/feature");
    }

    @Test
    void diffProducesUnifiedDiff() {
        String diff = ops.diff(repoDir, baseSha, headSha);
        assertThat(diff).isNotNull().contains("diff --git", "--- a/a.txt", "+++ b/a.txt", "@@ ");
        assertThat(diff).contains("+line two changed", "-line two", "+line four added", "+brand new");
    }

    @Test
    void diffStatEmitsPerFileLines() {
        String stat = ops.diffStat(repoDir, baseSha, headSha);
        assertThat(stat).isNotNull();
        // a.txt: 1 line replaced + 1 line added = 3 (1 add, 1 delete, 1 add); b.txt: 1 add.
        assertThat(stat).contains(" a.txt | 3", " b.txt | 1");
    }

    @Test
    void diffStatEncodesRenames() throws GitAPIException, IOException {
        // Start a third commit that introduces a renameable file, then commit a pure rename.
        write("renamed-from.txt", "stable\n");
        String addSha = commit("add file");

        Files.delete(repoDir.resolve("renamed-from.txt"));
        write("renamed-to.txt", "stable\n");
        git.rm().addFilepattern("renamed-from.txt").call();
        git.add().addFilepattern("renamed-to.txt").call();
        String renameSha = git.commit()
                .setMessage("rename")
                .setAuthor("t", "t@e")
                .setCommitter("t", "t@e")
                .call()
                .getName();

        String stat = ops.diffStat(repoDir, addSha, renameSha);
        assertThat(stat).isNotNull();
        assertThat(stat).contains("renamed-from.txt => renamed-to.txt");
    }

    @Test
    void diffNameOnlyReturnsPaths() {
        String names = ops.diffNameOnly(repoDir, baseSha, headSha);
        assertThat(names).isNotNull();
        assertThat(names.trim().split("\n")).containsExactlyInAnyOrder("a.txt", "b.txt");
    }

    @Test
    void diffNameOnlyReturnsNewPathForRenames() throws GitAPIException, IOException {
        write("renamed-from.txt", "stable\n");
        String addSha = commit("add file");

        Files.delete(repoDir.resolve("renamed-from.txt"));
        write("renamed-to.txt", "stable\n");
        git.rm().addFilepattern("renamed-from.txt").call();
        git.add().addFilepattern("renamed-to.txt").call();
        String renameSha = git.commit()
                .setMessage("rename")
                .setAuthor("t", "t@e")
                .setCommitter("t", "t@e")
                .call()
                .getName();

        String names = ops.diffNameOnly(repoDir, addSha, renameSha);
        assertThat(names).isNotNull();
        assertThat(names.trim().split("\n")).containsExactly("renamed-to.txt");
    }

    @Test
    void shouldListTheRangeOldestFirstWithSubjectBodyAndFileCountWhenReadingTheCommitLog()
            throws GitAPIException, IOException {
        write("c.txt", "c\n");
        String laterSha = commit("add c\n\nNeeded before the shadow pass lands.\n");

        GitDiffOperations.CommitLog log = ops.commitLog(repoDir, baseSha, laterSha, 10);

        assertThat(log).isNotNull();
        assertThat(log.truncated()).isFalse();
        assertThat(log.commits())
                .extracting(GitDiffOperations.CommitLogEntry::sha)
                .containsExactly(headSha, laterSha);
        GitDiffOperations.CommitLogEntry first = log.commits().get(0);
        assertThat(first.subject()).isEqualTo("change a, add b");
        assertThat(first.body()).isNull();
        assertThat(first.parentCount()).isEqualTo(1);
        assertThat(first.changedFiles()).isEqualTo(2);
        GitDiffOperations.CommitLogEntry second = log.commits().get(1);
        assertThat(second.subject()).isEqualTo("add c");
        assertThat(second.body()).isEqualTo("Needed before the shadow pass lands.");
        assertThat(second.changedFiles()).isEqualTo(1);
    }

    @Test
    void shouldFoldTheSubjectAndSplitTheBodyWhenTheMessageHasSeveralParagraphs() throws GitAPIException, IOException {
        write("c.txt", "c\n");
        String sha = commit(
                "Cache detection results\nacross files\n\nAvoids re-running the detector.\n\nCo-authored-by: Ada <ada@example.com>");

        GitDiffOperations.CommitLog log = ops.commitLog(repoDir, headSha, sha, 10);

        assertThat(log).isNotNull();
        GitDiffOperations.CommitLogEntry entry = log.commits().get(0);
        assertThat(entry.subject()).isEqualTo("Cache detection results across files");
        assertThat(entry.body()).isEqualTo("Avoids re-running the detector.\n\nCo-authored-by: Ada <ada@example.com>");
    }

    @Test
    void shouldKeepAMergedBranchTogetherAndLeaveTheMergeWithoutAFileCountWhenHistoryHasTwoLines()
            throws GitAPIException, IOException {
        // Commit times interleave the two lines (feature 10:00, main 10:30, feature 11:00); topological
        // order must not.
        write("c.txt", "c\n");
        String featureFirst = commitAt("add c", Instant.parse("2026-06-01T10:00:00Z"));
        write("d.txt", "d\n");
        String featureSecond = commitAt("add d", Instant.parse("2026-06-01T11:00:00Z"));
        git.checkout().setName("main").call();
        write("main-only.txt", "main\n");
        String mainOnly = commitAt("main only", Instant.parse("2026-06-01T10:30:00Z"));
        git.checkout().setName("feature").call();
        MergeResult merge = git.merge()
                .include(repo.resolve(mainOnly))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setCommit(true)
                .setMessage("Merge branch 'main' into feature")
                .call();
        assertThat(merge.getMergeStatus().isSuccessful()).isTrue();
        String mergeSha = merge.getNewHead().getName();

        GitDiffOperations.CommitLog log = ops.commitLog(repoDir, baseSha, mergeSha, 10);

        assertThat(log).isNotNull();
        assertThat(log.commits())
                .extracting(GitDiffOperations.CommitLogEntry::sha)
                .startsWith(headSha)
                .endsWith(mergeSha)
                .containsSubsequence(featureFirst, featureSecond)
                .doesNotContainSubsequence(featureFirst, mainOnly, featureSecond);
        GitDiffOperations.CommitLogEntry mergeEntry = log.commits().getLast();
        assertThat(mergeEntry.parentCount()).isEqualTo(2);
        assertThat(mergeEntry.changedFiles()).isNull();
    }

    @Test
    void shouldKeepTheOldestCommitsAndReportTruncationOnlyWhenTheRangeExceedsTheLimit()
            throws GitAPIException, IOException {
        write("c.txt", "c\n");
        String laterSha = commit("add c");

        GitDiffOperations.CommitLog exactFit = ops.commitLog(repoDir, baseSha, laterSha, 2);
        GitDiffOperations.CommitLog cut = ops.commitLog(repoDir, baseSha, laterSha, 1);

        assertThat(exactFit).isNotNull();
        assertThat(exactFit.truncated()).isFalse();
        assertThat(cut).isNotNull();
        assertThat(cut.truncated()).isTrue();
        assertThat(cut.commits())
                .extracting(GitDiffOperations.CommitLogEntry::sha)
                .containsExactly(headSha);
    }

    @Test
    void resolveDiffRangeStrategyOne() {
        String[] range = ops.resolveDiffRange(repoDir, "main", "feature", headSha);
        assertThat(range).isNotNull().containsExactly(baseSha, headSha);
    }

    @Test
    void resolveDiffRangeUsesMergeBaseWhenTargetAdvancedPastForkPoint() throws GitAPIException, IOException {
        // The feature diverged at baseSha; then the TARGET branch advanced with its own change.
        // A 2-dot range [origin/main tip, head] would surface main's later change as a
        // phantom diff the developer never made. The range base MUST be the merge-base (baseSha), so the
        // diff is exactly what THIS branch added (3-dot), never what the target branch changed afterwards.
        git.checkout().setName("main").call();
        write("target-only.txt", "added on the target branch after the fork\n");
        String advancedMain = commit("target advances past the fork point");
        git.checkout().setName("feature").call();

        String[] range = ops.resolveDiffRange(repoDir, "main", "feature", headSha);

        assertThat(range).isNotNull().containsExactly(baseSha, headSha);
        assertThat(range[0])
                .as("base must be the merge-base, not the advanced target tip")
                .isNotEqualTo(advancedMain);
        // And the resulting diff must NOT contain the target-only file.
        assertThat(ops.diffNameOnly(repoDir, range[0], range[1]))
                .doesNotContain("target-only.txt")
                .contains("a.txt");
    }

    @Test
    void resolveDiffRangeStrategyTwoMergeCommit() throws GitAPIException, IOException {
        // Diverge main with a commit, then merge feature into main (no-ff) so a real merge commit
        // exists. Use an unresolvable source-branch name in the call so Strategy 1 is skipped and
        // Strategy 2 has to find the merge.
        git.checkout().setName("main").call();
        write("main-only.txt", "main\n");
        commit("main only");
        MergeResult merge = git.merge()
                .include(repo.resolve(headSha))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setCommit(true)
                .setMessage("merge feature")
                .call();
        assertThat(merge.getMergeStatus().isSuccessful()).isTrue();

        String[] range = ops.resolveDiffRange(repoDir, "main", "nonexistent", headSha);
        assertThat(range).isNotNull();
        assertThat(range[1]).isEqualTo(headSha);
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit mergeCommit = walk.parseCommit(merge.getNewHead());
            assertThat(range[0]).isEqualTo(mergeCommit.getParent(0).getName());
        }
    }

    @Test
    void resolveDiffRangeStrategyThreeMergeBase() throws GitAPIException, IOException {
        // Diverge main without merging — only the merge-base remains as a candidate.
        git.checkout().setName("main").call();
        write("main-only.txt", "main only\n");
        commit("main only");

        // Pass an unresolvable source-branch name so Strategy 1 is skipped; no merge commit exists,
        // so Strategy 2 finds nothing; Strategy 3 returns merge-base = baseSha.
        String[] range = ops.resolveDiffRange(repoDir, "main", "nonexistent", headSha);
        assertThat(range).isNotNull().containsExactly(baseSha, headSha);
    }

    @Test
    void diffNullForUnknownRef() {
        ObjectId zero = ObjectId.fromString("0000000000000000000000000000000000000000");
        assertThat(ops.diff(repoDir, zero.getName(), headSha)).isNull();
        assertThat(ops.diff(repoDir, baseSha, zero.getName())).isNull();
    }

    @Test
    void resolveDiffRangeNullForBlankHead() {
        assertThat(ops.resolveDiffRange(repoDir, "main", "feature", "")).isNull();
        assertThat(ops.resolveDiffRange(repoDir, "main", "feature", null)).isNull();
    }

    @Test
    void resolveDiffRangeNullWhenSourceAlreadyMergedIntoTarget() throws GitAPIException, IOException {
        // Fast-forward main up to feature's head so feature is an ancestor of main: the merge-base equals
        // head, so a 3-dot diff is legitimately empty and the range must be null (not a phantom 2-dot range).
        git.checkout().setName("main").call();
        git.merge().include(repo.resolve("feature")).call();
        repo.updateRef("refs/remotes/origin/main").link("refs/heads/main");

        assertThat(ops.resolveDiffRange(repoDir, "main", "feature", headSha)).isNull();
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(repoDir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private String commit(String message) throws GitAPIException {
        git.add().addFilepattern(".").call();
        return git.commit()
                .setMessage(message)
                .setAuthor("t", "t@e")
                .setCommitter("t", "t@e")
                .call()
                .getName();
    }

    private String commitAt(String message, Instant when) throws GitAPIException {
        PersonIdent ident = new PersonIdent("t", "t@e", when, ZoneOffset.UTC);
        git.add().addFilepattern(".").call();
        return git.commit()
                .setMessage(message)
                .setAuthor(ident)
                .setCommitter(ident)
                .call()
                .getName();
    }
}

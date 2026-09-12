package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.NativeGitTestExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitDiffOperationsNativeTest extends BaseUnitTest {

    private GitDiffOperations ops;
    private NativeGitTestExecutor nativeGit;
    private static final RepositoryKey KEY = new RepositoryKey(1, 2);

    @TempDir
    Path nativeDirectory;

    @TempDir
    Path repoDir;

    private Git git;
    private Repository repo;
    private String baseSha;
    private String headSha;

    @BeforeEach
    void setUp() throws GitAPIException, IOException {
        nativeGit = new NativeGitTestExecutor(nativeDirectory);
        ops = new GitDiffOperations(nativeGit);
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
    }

    @Test
    void shouldProduceAUnifiedDiffWhenCapturingARange() throws Exception {
        String diff = diff(baseSha, headSha);
        assertThat(diff).isNotNull().contains("diff --git", "--- a/a.txt", "+++ b/a.txt", "@@ ");
        assertThat(diff).contains("+line two changed", "-line two", "+line four added", "+brand new");
    }

    @Test
    void shouldEmitOneStatLinePerFileWhenCapturingARange() throws Exception {
        String stat = stat(baseSha, headSha);
        assertThat(stat).isNotNull();
        assertThat(stat).containsPattern("a\\.txt\\s+\\|\\s+3").containsPattern("b\\.txt\\s+\\|\\s+1");
    }

    @Test
    void shouldEncodeARenameInTheStatWhenAFileMoves() throws GitAPIException, IOException {
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

        String stat = stat(addSha, renameSha);
        assertThat(stat).isNotNull();
        assertThat(stat).contains("renamed-from.txt => renamed-to.txt");
    }

    @Test
    void shouldResolvePinnedHeadAgainstTarget() throws Exception {
        String[] range = resolveRange("main", headSha);
        assertThat(range).isNotNull().containsExactly(baseSha, headSha);
    }

    @Test
    void shouldResolveTheRangeFromTheMergeBaseWhenTheTargetAdvancedPastTheForkPoint()
            throws GitAPIException, IOException {
        git.checkout().setName("main").call();
        write("target-only.txt", "added on the target branch after the fork\n");
        String advancedMain = commit("target advances past the fork point");
        git.checkout().setName("feature").call();

        String[] range = resolveRange("main", headSha);

        assertThat(range).isNotNull().containsExactly(baseSha, headSha);
        assertThat(range[0])
                .as("base must be the merge-base, not the advanced target tip")
                .isNotEqualTo(advancedMain);
        assertThat(diff(range[0], range[1])).doesNotContain("target-only.txt").contains("a.txt");
    }

    @Test
    void shouldResolveMergedHeadAgainstFirstParent() throws GitAPIException, IOException {
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

        String[] range = resolveRange("main", headSha);
        assertThat(range).isNotNull();
        assertThat(range[1]).isEqualTo(headSha);
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit mergeCommit = walk.parseCommit(merge.getNewHead());
            assertThat(range[0]).isEqualTo(mergeCommit.getParent(0).getName());
        }
    }

    @Test
    void shouldPreserveQuotedPathsDeletionAndBinaryMetadata() throws Exception {
        write("space and\ttab.txt", "before\n");
        Files.write(repoDir.resolve("image.bin"), new byte[] {0, 1, 2});
        String before = commit("add unusual paths");
        git.rm().addFilepattern("space and\ttab.txt").call();
        Files.write(repoDir.resolve("image.bin"), new byte[] {0, 3, 4});
        String after = commit("delete text and update binary");
        String patch = diff(before, after);
        assertThat(patch).contains("space and\\ttab.txt", "deleted file mode", "[L1] -before", "Binary files");
    }

    @Test
    void shouldFailPreparationWhenARevisionIsUnavailable() throws Exception {
        ObjectId zero = ObjectId.fromString("0000000000000000000000000000000000000000");
        assertThatThrownBy(() -> diff(zero.getName(), headSha)).isInstanceOf(JobPreparationException.class);
        assertThatThrownBy(() -> diff(baseSha, zero.getName())).isInstanceOf(JobPreparationException.class);
    }

    @Test
    void shouldResolveNoRangeWhenTheHeadIsAlreadyMergedIntoTheTarget() throws GitAPIException, IOException {
        git.checkout().setName("main").call();
        git.merge().include(repo.resolve("feature")).call();

        assertThat(resolveRange("main", headSha)).isNull();
    }

    @Test
    void shouldCapturePathsForBinaryModeAndRenameChangesWithoutQuoting() throws Exception {
        Files.write(repoDir.resolve("binary.dat"), new byte[] {0, 1});
        write("mode.sh", "echo hello\n");
        write("old-name.txt", "unchanged rename content\n");
        String base = commit("Add path fixtures");
        Files.write(repoDir.resolve("binary.dat"), new byte[] {0, 2});
        Files.setPosixFilePermissions(
                repoDir.resolve("mode.sh"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.move(repoDir.resolve("old-name.txt"), repoDir.resolve("new\nname.txt"));
        git.rm().addFilepattern("old-name.txt").call();
        String head = commit("Change binary mode and name");
        assertThat(captured(base, head, "diff_paths.nul").split("\0"))
                .containsExactlyInAnyOrder("binary.dat", "mode.sh", "new\nname.txt");
    }

    @Test
    void shouldCaptureCompleteCommitMetadataInHistoryOrder() throws Exception {
        write("c.txt", "c\n");
        String later = commit(
                "Cache detection results\nacross files\n\nAvoids repeated work.\n\nCo-authored-by: Ada <ada@example.com>");
        var log = commits(baseSha, later);
        assertThat(log.path("truncated").asBoolean()).isFalse();
        var entries = log.path("commits");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).path("sha").asString()).isEqualTo(headSha);
        assertThat(entries.get(0).path("subject").asString()).isEqualTo("change a, add b");
        assertThat(entries.get(0).has("body")).isFalse();
        assertThat(entries.get(0).path("changed_files").asInt()).isEqualTo(2);
        var entry = entries.get(1);
        assertThat(entry.path("sha").asString()).isEqualTo(later);
        assertThat(entry.path("subject").asString()).isEqualTo("Cache detection results across files");
        assertThat(entry.path("body").asString())
                .isEqualTo("Avoids repeated work.\n\nCo-authored-by: Ada <ada@example.com>");
        assertThat(entry.path("parent_count").asInt()).isEqualTo(1);
        assertThat(entry.path("changed_files").asInt()).isEqualTo(1);
        assertThat(java.time.OffsetDateTime.parse(entry.path("authored_at").asString()))
                .isNotNull();
        assertThat(java.time.OffsetDateTime.parse(entry.path("committed_at").asString()))
                .isNotNull();
    }

    @Test
    void shouldCountRenamesOnceAndOmitFileCountForMerges() throws Exception {
        Files.move(repoDir.resolve("b.txt"), repoDir.resolve("renamed.txt"));
        git.rm().addFilepattern("b.txt").call();
        String renamed = commit("Rename file");
        assertThat(commits(headSha, renamed)
                        .path("commits")
                        .get(0)
                        .path("changed_files")
                        .asInt())
                .isEqualTo(1);
        git.checkout().setName("main").call();
        write("main-only.txt", "main\n");
        String mainCommit = commit("Main advances");
        git.checkout().setName("feature").call();
        var merge = git.merge()
                .include(repo.resolve(mainCommit))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setCommit(true)
                .setMessage("Merge main")
                .call();
        assertThat(merge.getMergeStatus().isSuccessful()).isTrue();
        var entries = commits(baseSha, merge.getNewHead().getName()).path("commits");
        var last = entries.get(entries.size() - 1);
        assertThat(last.path("sha").asString()).isEqualTo(merge.getNewHead().getName());
        assertThat(last.path("parent_count").asInt()).isEqualTo(2);
        assertThat(last.has("changed_files")).isFalse();
    }

    @Test
    void shouldKeepThePinnedRangeWhenTheTargetBranchMoves() throws Exception {
        git.checkout().setName("main").call();
        git.merge().include(repo.resolve(headSha)).call();
        nativeGit.seedMirror(KEY, repoDir);
        assertThat(ops.resolveDiffRange(KEY, baseSha, headSha)).containsExactly(baseSha, headSha);
    }

    @Test
    void shouldKeepMergedHistoryTogetherDespiteInterleavedCommitTimes() throws Exception {
        write("c.txt", "c\n");
        String first = commitAt("Feature first", "2026-06-01T10:00:00Z");
        write("d.txt", "d\n");
        String second = commitAt("Feature second", "2026-06-01T11:00:00Z");
        git.checkout().setName("main").call();
        write("main-only.txt", "main\n");
        String main = commitAt("Main branch", "2026-06-01T10:30:00Z");
        git.checkout().setName("feature").call();
        var merge = git.merge()
                .include(repo.resolve(main))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setCommit(true)
                .setMessage("Merge main")
                .call();
        assertThat(merge.getMergeStatus().isSuccessful()).isTrue();
        var entries = commits(baseSha, merge.getNewHead().getName()).path("commits");
        java.util.List<String> shas = new java.util.ArrayList<>();
        entries.forEach(entry -> shas.add(entry.path("sha").asString()));
        assertThat(shas)
                .startsWith(headSha)
                .endsWith(merge.getNewHead().getName())
                .containsSubsequence(first, second)
                .doesNotContainSubsequence(first, main, second);
    }

    private String commitAt(String message, String when) throws GitAPIException {
        var author = new org.eclipse.jgit.lib.PersonIdent(
                "t", "t@e", java.time.Instant.parse(when), java.time.ZoneOffset.UTC);
        git.add().addFilepattern(".").call();
        return git.commit()
                .setMessage(message)
                .setAuthor(author)
                .setCommitter(author)
                .call()
                .getName();
    }

    private tools.jackson.databind.JsonNode commits(String base, String head) throws Exception {
        nativeGit.seedMirror(KEY, repoDir);
        var capture = ops.captureCommits(KEY, base, head);
        try (capture) {
            return new tools.jackson.databind.ObjectMapper().readTree(Files.readString(capture.path()));
        } finally {
            assertThat(capture.path()).doesNotExist();
        }
    }

    @org.junit.jupiter.api.AfterEach
    void closeRepository() {
        git.close();
    }

    private String @org.jspecify.annotations.Nullable [] resolveRange(String target, String head) {
        nativeGit.seedMirror(KEY, repoDir);
        try {
            return ops.resolveDiffRange(
                    KEY, java.util.Objects.requireNonNull(repo.resolve(target)).getName(), head);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private String diff(String base, String head) throws IOException {
        return captured(base, head, "diff.patch");
    }

    private String stat(String base, String head) throws IOException {
        return captured(base, head, "diff_stat.txt");
    }

    private String captured(String base, String head, String filename) throws IOException {
        nativeGit.seedMirror(KEY, repoDir);
        try (var capture = ops.capture(KEY, base, head)) {
            return Files.readString(capture.directory().resolve(filename));
        }
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
}

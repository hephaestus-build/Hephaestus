package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange.ChangeType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.NativeGitTestExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryManagerTest extends BaseUnitTest {
    private static final String IMAGE = "ghcr.io/hephaestus-build/git-preparation:test";
    private static final long LIMIT = 8L * 1024 * 1024 * 1024;
    private static final RepositoryKey KEY = new RepositoryKey(100L, 1L);

    @TempDir
    private Path temporary;

    private NativeGitTestExecutor executor;
    private GitRepositoryManager manager;
    private Path source;

    @BeforeEach
    void setUp() throws Exception {
        source = Files.createDirectory(temporary.resolve("source"));
        executor = new NativeGitTestExecutor(Files.createDirectory(temporary.resolve("native")));
        manager = new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, IMAGE, LIMIT),
                java.util.Optional.of(executor),
                new FabricLayout(temporary.resolve("outputs").toString()));
    }

    @Test
    void shouldNotReportWorkerFailureAsMissingRepositoryOrCommit() {
        var failed = mock(NativeGitExecutor.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("Worker disconnected"))
                .when(failed)
                .execute(
                        org.mockito.ArgumentMatchers.eq(KEY),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        var unavailable = new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, IMAGE, LIMIT),
                java.util.Optional.of(failed),
                new FabricLayout(temporary.toString()));
        assertThatThrownBy(() -> unavailable.isRepositoryCloned(KEY))
                .isInstanceOf(GitRepositoryManager.GitOperationException.class);
        assertThatThrownBy(() -> unavailable.commitExists(KEY, "a".repeat(40)))
                .isInstanceOf(GitRepositoryManager.GitOperationException.class);
        assertThatThrownBy(() -> unavailable.resolveBranchHead(KEY, "main"))
                .isInstanceOf(GitRepositoryManager.GitOperationException.class);
    }

    @Test
    void shouldRejectOversizedCommitDetailsBeforeMaterializingFileChanges() {
        var nativeGit = mock(NativeGitExecutor.class);
        String sha = "a".repeat(40);
        org.mockito.Mockito.doAnswer(invocation -> {
                    NativeGitExecutor.Request request = invocation.getArgument(1);
                    java.io.OutputStream output = invocation.getArgument(3);
                    if (request.operation() == NativeGitExecutor.Operation.COMMIT_IDS) {
                        output.write((sha + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        var frames = new java.io.DataOutputStream(output);
                        frames.writeLong(1);
                        frames.writeByte('x');
                        frames.writeLong(16 * 1024 * 1024);
                    }
                    return null;
                })
                .when(nativeGit)
                .execute(
                        org.mockito.ArgumentMatchers.eq(KEY),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        var bounded = new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, IMAGE, LIMIT),
                java.util.Optional.of(nativeGit),
                new FabricLayout(temporary.toString()));
        List<String> captured = new ArrayList<>();
        assertThatThrownBy(() ->
                        bounded.forEachMissingCommit(KEY, page -> Set.of(), details -> captured.add(details.sha())))
                .isInstanceOf(GitRepositoryManager.GitOperationException.class)
                .hasRootCauseMessage("Git commit details exceed the ingestion frame budget");
        assertThat(captured).isEmpty();
    }

    private Git repository() throws Exception {
        Git git = Git.init()
                .setInitialBranch("main")
                .setDirectory(source.toFile())
                .call();
        GitTestFixtures.disableSigning(git.getRepository());
        Files.writeString(source.resolve("README.md"), "Repository\n");
        commit(git, "Initial commit");
        return git;
    }

    private String commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit()
                .setSign(false)
                .setAllowEmpty(true)
                .setMessage(message)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call()
                .name();
    }

    private void prepare() {
        manager.ensureRepository(KEY, source.toUri().toString(), null);
    }

    @Test
    void shouldNotReadRepositoryWhenDisabled() {
        var disabled = new GitRepositoryManager(
                new GitRepositoryProperties(false, 2, IMAGE, LIMIT),
                java.util.Optional.of(mock(NativeGitExecutor.class)),
                new FabricLayout(temporary.toString()));
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.isRepositoryCloned(KEY)).isFalse();
        List<CommitDetails> commits = new ArrayList<>();
        disabled.forEachCommitInRange(KEY, null, "a".repeat(40), shas -> Set.of(), commits::add);
        assertThat(commits).isEmpty();
    }

    @Test
    void shouldKeepSourceBinaryAndHistoryWithoutSnapshotSizeCutoffs() throws Exception {
        try (Git git = repository()) {
            Files.writeString(source.resolve("large.txt"), "source\n".repeat(6_000_000));
            Files.write(source.resolve("image.bin"), new byte[64 * 1024]);
            String sha = commit(git, "Add full source");
            prepare();
            Path staging;
            try (var snapshot = manager.readTreeSnapshot(KEY, sha)) {
                staging = snapshot.stagingDir();
                assertThat(snapshot.complete()).isTrue();
                assertThat(snapshot.commitSha()).isEqualTo(sha);
                assertThat(snapshot.totalBytes()).isGreaterThan(32L * 1024 * 1024);
                assertThat(staging.resolve(".git/HEAD")).isRegularFile();
                assertThat(staging.resolve(".git/hephaestus-captured-refs")).isRegularFile();
                assertThat(staging.resolve(".git/index")).isRegularFile();
                assertThat(Files.mismatch(source.resolve("large.txt"), staging.resolve("large.txt")))
                        .isEqualTo(-1);
                assertThat(Files.mismatch(source.resolve("image.bin"), staging.resolve("image.bin")))
                        .isEqualTo(-1);
                assertThat(Files.readString(staging.resolve(".git/config")))
                        .doesNotContain("remote", "credential", "http.extraHeader");
                assertThat(staging.resolve(".git/objects/info/alternates")).doesNotExist();
                Process log =
                        new ProcessBuilder("git", "-C", staging.toString(), "rev-list", "--count", "HEAD").start();
                assertThat(new String(log.getInputStream().readAllBytes()).trim())
                        .isEqualTo("2");
                assertThat(log.waitFor()).isZero();
            }
            assertThat(staging).doesNotExist();
        }
    }

    @Test
    void shouldRepresentGitSymlinksAsTextWithoutReadingTheirTargets() throws Exception {
        try (Git git = repository()) {
            Files.writeString(temporary.resolve("secret"), "outside");
            Files.createSymbolicLink(source.resolve("link"), Path.of("../../secret"));
            String sha = commit(git, "Add link");
            prepare();
            try (var snapshot = manager.readTreeSnapshot(KEY, sha)) {
                assertThat(snapshot.stagingDir().resolve("link")).isRegularFile();
                assertThat(Files.isSymbolicLink(snapshot.stagingDir().resolve("link")))
                        .isFalse();
                assertThat(Files.readString(snapshot.stagingDir().resolve("link")))
                        .isEqualTo("../../secret");
                assertThat(snapshot.limitations()).isEmpty();
            }
        }
    }

    @Test
    void shouldStopRangeConsumptionWhenCommitPersistenceFails() throws Exception {
        try (Git git = repository()) {
            commit(git, "Second");
            String head = commit(git, "Third");
            prepare();
            List<String> received = new ArrayList<>();
            assertThatThrownBy(() -> manager.forEachCommitInRange(KEY, null, head, shas -> Set.of(), details -> {
                        received.add(details.sha());
                        throw new IllegalStateException("Persistence failed");
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Persistence failed");
            assertThat(received).hasSize(1);
            received.clear();
            manager.forEachCommitInRange(KEY, null, head, shas -> Set.of(), details -> received.add(details.sha()));
            assertThat(received).hasSize(3).contains(head);
        }
    }

    @Test
    void shouldCaptureNativeRenameStatisticsAndParentIdentity() throws Exception {
        try (Git git = repository()) {
            var parentId = git.getRepository().resolve("HEAD");
            assertThat(parentId).isNotNull();
            String parent = parentId.name();
            Files.move(source.resolve("README.md"), source.resolve("renamed.md"));
            git.add().addFilepattern(".").setUpdate(true).call();
            String head = commit(git, "Rename\n\nBody");
            prepare();
            List<CommitDetails> commits = new ArrayList<>();
            manager.forEachCommitInRange(KEY, parent, head, shas -> Set.of(), commits::add);
            assertThat(commits).hasSize(1);
            var captured = commits.getFirst();
            assertThat(captured.sha()).isEqualTo(head);
            assertThat(captured.parentShas()).containsExactly(parent);
            assertThat(captured.message()).isEqualTo("Rename");
            assertThat(captured.messageBody()).isEqualTo("Body");
            assertThat(captured.fileChanges()).singleElement().satisfies(change -> {
                assertThat(change.changeType()).isEqualTo(ChangeType.RENAMED);
                assertThat(change.filename()).isEqualTo("renamed.md");
                assertThat(change.previousFilename()).isEqualTo("README.md");
                assertThat(change.changes()).isZero();
            });
        }
    }

    @Test
    void shouldResumeMissingAncestorsAndFeatureBranchesAcrossMoreThanFiveThousandCommits() throws Exception {
        try (Git git = repository()) {
            ObjectId tip = git.getRepository().resolve("HEAD");
            try (var inserter = git.getRepository().newObjectInserter();
                    var walk = new RevWalk(git.getRepository())) {
                ObjectId tree = walk.parseCommit(tip).getTree().getId();
                PersonIdent author = new PersonIdent("Test", "test@example.com");
                for (int i = 0; i < 5001; i++) {
                    CommitBuilder commit = new CommitBuilder();
                    commit.setTreeId(tree);
                    commit.setParentId(tip);
                    commit.setAuthor(author);
                    commit.setCommitter(author);
                    commit.setMessage("Commit " + i);
                    tip = inserter.insert(commit);
                }
                inserter.flush();
            }
            var update = git.getRepository().updateRef("HEAD");
            update.setNewObjectId(tip);
            update.update();
            assertThat(tip).isNotNull();
            String mainTip = tip.name();
            git.checkout().setCreateBranch(true).setName("feature").call();
            String featureTip = commit(git, "Feature");
            prepare();
            Set<String> persisted = new HashSet<>(Set.of(mainTip));
            List<Integer> pageSizes = new ArrayList<>();
            Function<List<String>, Set<String>> existing = shas -> {
                pageSizes.add(shas.size());
                Set<String> found = new HashSet<>(shas);
                found.retainAll(persisted);
                return found;
            };
            assertThatThrownBy(() -> manager.forEachMissingCommit(KEY, existing, info -> {
                        if (persisted.size() == 5) throw new IllegalStateException("Persistence unavailable");
                        persisted.add(info.sha());
                    }))
                    .isInstanceOf(IllegalStateException.class);
            Set<String> resumed = new HashSet<>();
            manager.forEachMissingCommit(KEY, existing, info -> {
                assertThat(persisted.add(info.sha())).isTrue();
                resumed.add(info.sha());
            });
            assertThat(persisted).hasSize(5003).contains(mainTip, featureTip);
            assertThat(resumed).hasSize(4998).doesNotContain(mainTip);
            assertThat(pageSizes).contains(256).allMatch(size -> size <= 256);
            manager.forEachMissingCommit(KEY, existing, info -> {
                throw new AssertionError("Reprocessed captured commit");
            });
        }
    }

    @Test
    void shouldResumeAfterThreadInterruption() throws Exception {
        try (Git git = repository()) {
            commit(git, "Second");
            prepare();
            Set<String> persisted = new HashSet<>();
            try {
                assertThatThrownBy(() -> manager.forEachMissingCommit(KEY, shas -> Set.copyOf(persisted), info -> {
                            persisted.add(info.sha());
                            Thread.currentThread().interrupt();
                        }))
                        .isInstanceOf(GitRepositoryManager.GitOperationException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            assertThat(persisted).hasSize(1);
            manager.forEachMissingCommit(
                    KEY,
                    shas -> Set.copyOf(persisted),
                    info -> assertThat(persisted.add(info.sha())).isTrue());
            assertThat(persisted).hasSize(2);
        }
    }

    @Test
    void shouldNotShareWorkspaceMirrors() throws Exception {
        try (Git git = repository()) {
            assertThat(git.getRepository().isBare()).isFalse();
            prepare();
            assertThat(manager.isRepositoryCloned(KEY)).isTrue();
            assertThat(manager.isRepositoryCloned(new RepositoryKey(200L, 1L))).isFalse();
            manager.deleteOrphanedRepository(1L);
            assertThat(manager.isRepositoryCloned(KEY)).isFalse();
        }
    }
}

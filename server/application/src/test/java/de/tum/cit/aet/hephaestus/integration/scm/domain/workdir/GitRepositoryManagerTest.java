package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange.ChangeType;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryManagerTest extends BaseUnitTest {
    private static final long LIMIT = 8L * 1024 * 1024 * 1024;
    private static final RepositoryKey KEY = new RepositoryKey(100L, 1L);

    @TempDir
    private Path temporary;

    private GitRepositoryManager manager;
    private Path source;
    private Path fabric;

    @BeforeEach
    void setUp() throws Exception {
        source = Files.createDirectory(temporary.resolve("source"));
        fabric = temporary.resolve("fabric");
        manager = manager(true, LIMIT);
    }

    private GitRepositoryManager manager(boolean enabled, long maxSnapshotBytes) {
        return new GitRepositoryManager(
                new GitRepositoryProperties(enabled, 2, maxSnapshotBytes),
                new GitRepositoryLockManager(),
                new FabricLayout(fabric.toString()));
    }

    @Test
    void shouldNotReadRepositoryWhenDisabled() {
        var disabled = manager(false, LIMIT);
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.isRepositoryCloned(KEY)).isFalse();
        assertThat(disabled.commitExists(KEY, "a".repeat(40))).isFalse();
        assertThat(disabled.resolveBranchHead(KEY, "main")).isNull();
        List<CommitDetails> commits = new ArrayList<>();
        disabled.forEachCommitInRange(KEY, null, "a".repeat(40), shas -> Set.of(), commits::add);
        assertThat(commits).isEmpty();
        assertThatThrownBy(() -> disabled.ensureRepository(KEY, source.toUri().toString(), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldMirrorBranchesAndTagsAndFollowTheUpstream() throws Exception {
        try (Git git = repository()) {
            prepare();
            String main = head(git);
            assertThat(manager.isRepositoryCloned(KEY)).isTrue();
            assertThat(manager.resolveBranchHead(KEY, "main")).isEqualTo(main);
            assertThat(manager.commitExists(KEY, main)).isTrue();
            assertThat(manager.commitExists(KEY, "b".repeat(40))).isFalse();

            git.checkout().setCreateBranch(true).setName("feature").call();
            String feature = commit(git, "Feature");
            git.tag().setName("v1").setSigned(false).call();
            git.checkout().setName("main").call();
            git.branchDelete().setBranchNames("feature").setForce(true).call();
            prepare();
            assertThat(manager.resolveBranchHead(KEY, "feature")).isNull();
            List<String> subjects = new ArrayList<>();
            manager.forEachCommitSubject(KEY, main, feature, subjects::add);
            assertThat(subjects).containsExactly("Feature");
        }
    }

    @Test
    void shouldRebuildTheMirrorWhenTheUpstreamChanges() throws Exception {
        try (Git git = repository()) {
            prepare();
            String first = head(git);
            Path other = Files.createDirectory(temporary.resolve("other"));
            String second;
            try (Git otherGit = Git.init()
                    .setInitialBranch("main")
                    .setDirectory(other.toFile())
                    .call()) {
                GitTestFixtures.disableSigning(otherGit.getRepository());
                Files.writeString(other.resolve("other.txt"), "other\n");
                otherGit.add().addFilepattern(".").call();
                second = otherGit.commit()
                        .setSign(false)
                        .setMessage("Other")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()
                        .name();
            }
            manager.ensureRepository(KEY, other.toUri().toString(), null);
            assertThat(manager.commitExists(KEY, second)).isTrue();
            assertThat(manager.commitExists(KEY, first)).isFalse();
        }
    }

    @Test
    void shouldFetchAPinnedCommitFromItsRefAndRefuseARefThatDoesNotCarryIt() throws Exception {
        try (Git git = repository()) {
            prepare();
            git.checkout().setCreateBranch(true).setName("feature").call();
            Files.writeString(source.resolve("feature.txt"), "feature\n");
            String head = commit(git, "Feature");

            assertThat(manager.commitExists(KEY, head)).isFalse();
            assertThat(manager.fetchRemoteCommit(KEY, source.toUri().toString(), "refs/heads/feature", head, null))
                    .isTrue();
            assertThat(manager.commitExists(KEY, head)).isTrue();
            assertThat(manager.fetchRemoteCommit(
                            KEY, source.toUri().toString(), "refs/heads/main", "b".repeat(40), null))
                    .isFalse();
            assertThatThrownBy(() ->
                            manager.fetchRemoteCommit(KEY, source.toUri().toString(), "refs/heads/missing", head, null))
                    .isInstanceOf(GitRepositoryManager.GitOperationException.class);
        }
    }

    @Test
    void shouldStageFullSourceHistoryAndBinariesWhenReadingASnapshot() throws Exception {
        try (Git git = repository()) {
            Files.writeString(source.resolve("large.txt"), "source\n".repeat(300_000));
            Files.write(source.resolve("image.bin"), new byte[64 * 1024]);
            Files.writeString(source.resolve("run.sh"), "#!/bin/sh\n");
            Files.setPosixFilePermissions(
                    source.resolve("run.sh"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            String sha = commit(git, "Add full source");
            git.tag().setName("v1").setSigned(false).call();
            prepare();
            Path staging;
            try (var snapshot = manager.readTreeSnapshot(KEY, sha)) {
                staging = snapshot.stagingDir();
                assertThat(staging).startsWith(fabric);
                assertThat(staging.getFileName().toString()).startsWith(GitRepositoryManager.GIT_SNAPSHOT_PREFIX);
                assertThat(GitRepositoryManager.isCurrentProcessSpool(staging)).isTrue();
                assertThat(snapshot.complete()).isTrue();
                assertThat(snapshot.commitSha()).isEqualTo(sha);
                assertThat(snapshot.visitedFiles()).isEqualTo(4);
                assertThat(snapshot.totalBytes())
                        .isGreaterThanOrEqualTo(
                                Files.size(source.resolve("large.txt")) + Files.size(source.resolve("image.bin")));
                assertThat(staging.resolve(".git/HEAD")).isRegularFile();
                assertThat(Files.readString(staging.resolve(".git/HEAD")).strip())
                        .isEqualTo(sha);
                assertThat(Files.readString(staging.resolve(".git/hephaestus-captured-refs")))
                        .contains("commit " + sha + " refs/remotes/origin/main")
                        .contains("commit " + sha + " refs/tags/v1");
                assertThat(Files.mismatch(source.resolve("large.txt"), staging.resolve("large.txt")))
                        .isEqualTo(-1);
                assertThat(Files.mismatch(source.resolve("image.bin"), staging.resolve("image.bin")))
                        .isEqualTo(-1);
                assertThat(Files.isExecutable(staging.resolve("run.sh"))).isTrue();
                assertThat(Files.readString(staging.resolve(".git/config")))
                        .doesNotContain("[remote", "credential", "extraHeader", "url");
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
    void shouldSnapshotAReviewHeadNoBranchReachesAnyMore() throws Exception {
        try (Git git = repository()) {
            prepare();
            git.checkout().setCreateBranch(true).setName("feature").call();
            Files.writeString(source.resolve("feature.txt"), "feature\n");
            String head = commit(git, "Feature");
            manager.fetchRemoteCommit(KEY, source.toUri().toString(), "refs/heads/feature", head, null);
            git.checkout().setName("main").call();
            git.branchDelete().setBranchNames("feature").setForce(true).call();
            prepare();
            try (var snapshot = manager.readTreeSnapshot(KEY, head)) {
                assertThat(snapshot.stagingDir().resolve("feature.txt")).isRegularFile();
                assertThat(snapshot.stagingDir().resolve(".git/refs/remotes/origin/feature"))
                        .doesNotExist();
            }
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
    void shouldNameSubmodulesAndBackslashPathsAsLimitations() throws Exception {
        try (Git git = repository()) {
            Files.writeString(source.resolve("back\\slash.txt"), "windows\n");
            String sha = commit(git, "Add a path Windows cannot hold");
            ObjectId withSubmodule;
            try (var inserter = git.getRepository().newObjectInserter();
                    var walk = new RevWalk(git.getRepository())) {
                var tree = new org.eclipse.jgit.lib.TreeFormatter();
                tree.append("vendor", org.eclipse.jgit.lib.FileMode.GITLINK, ObjectId.fromString("c".repeat(40)));
                tree.append(
                        "README.md",
                        org.eclipse.jgit.lib.FileMode.REGULAR_FILE,
                        inserter.insert(org.eclipse.jgit.lib.Constants.OBJ_BLOB, "Repository\n".getBytes()));
                CommitBuilder commit = new CommitBuilder();
                commit.setTreeId(inserter.insert(tree));
                commit.setParentId(walk.parseCommit(ObjectId.fromString(sha)));
                PersonIdent ident = new PersonIdent("Test", "test@example.com");
                commit.setAuthor(ident);
                commit.setCommitter(ident);
                commit.setMessage("Add submodule");
                withSubmodule = inserter.insert(commit);
                inserter.flush();
            }
            var update = git.getRepository().updateRef("refs/heads/main");
            update.setNewObjectId(withSubmodule);
            update.forceUpdate();
            prepare();
            try (var snapshot = manager.readTreeSnapshot(KEY, sha)) {
                assertThat(snapshot.limitations()).containsExactly(GitRepositoryManager.TREE_LIMITATION_UNSAFE_PATH);
                assertThat(snapshot.complete()).isFalse();
                assertThat(snapshot.stagingDir().resolve("back\\slash.txt")).doesNotExist();
                assertThat(snapshot.stagingDir().resolve("README.md")).isRegularFile();
            }
            try (var snapshot = manager.readTreeSnapshot(KEY, withSubmodule.name())) {
                assertThat(snapshot.limitations()).containsExactly(GitRepositoryManager.TREE_LIMITATION_SUBMODULE);
                assertThat(snapshot.stagingDir().resolve("README.md")).isRegularFile();
            }
        }
    }

    @Test
    void shouldRefuseTheSnapshotWhenItExceedsMaxSnapshotBytes() throws Exception {
        try (Git git = repository()) {
            String sha = commit(git, "Second");
            prepare();
            var guarded = manager(true, 63);
            assertThatThrownBy(() -> guarded.readTreeSnapshot(KEY, sha))
                    .isInstanceOf(GitRepositoryManager.GitOperationException.class)
                    .hasMessage("Repository snapshot exceeds hephaestus.git.max-snapshot-bytes");
            assertThat(stagingDirectories()).isEmpty();
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
    void shouldCaptureRenameStatisticsAndParentIdentity() throws Exception {
        try (Git git = repository()) {
            String parent = head(git);
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
            assertThat(captured.authorEmail()).isEqualTo("test@example.com");
            assertThat(captured.fileChanges()).singleElement().satisfies(change -> {
                assertThat(change.changeType()).isEqualTo(ChangeType.RENAMED);
                assertThat(change.filename()).isEqualTo("renamed.md");
                assertThat(change.previousFilename()).isEqualTo("README.md");
                assertThat(change.changes()).isZero();
            });
        }
    }

    @Test
    void shouldResumeFromCapturedShasAfterAFailedWalk() throws Exception {
        try (Git git = repository()) {
            ObjectId tip = chain(git, ObjectId.fromString(head(git)), 601);
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
            assertThat(persisted).hasSize(603).contains(mainTip, featureTip);
            assertThat(resumed).hasSize(598).doesNotContain(mainTip);
            assertThat(pageSizes).contains(256).allMatch(size -> size <= 256);
            manager.forEachMissingCommit(KEY, existing, info -> {
                throw new AssertionError("Reprocessed captured commit");
            });
        }
    }

    @Test
    void shouldPauseTheWalkAtItsPageBudgetWhenMoreCommitsAreMissing() throws Exception {
        try (Git git = repository()) {
            // GitRepositoryManager.PAGE_SIZE is 256 and MAX_DETAIL_PAGES_PER_WALK is 32.
            chain(git, ObjectId.fromString(head(git)), 33 * 256);
            prepare();
            List<String> seen = new ArrayList<>();
            manager.forEachMissingCommit(KEY, page -> Set.of(), details -> seen.add(details.sha()));
            assertThat(seen).hasSize(32 * 256);
            Set<String> captured = new HashSet<>(seen);
            manager.forEachMissingCommit(
                    KEY,
                    page -> {
                        Set<String> found = new HashSet<>(page);
                        found.retainAll(captured);
                        return found;
                    },
                    details -> seen.add(details.sha()));
            assertThat(seen).hasSize(33 * 256 + 1);
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
        repository().close();
        prepare();
        assertThat(manager.isRepositoryCloned(KEY)).isTrue();
        assertThat(manager.isRepositoryCloned(new RepositoryKey(200L, 1L))).isFalse();
        manager.deleteOrphanedRepository(1L);
        assertThat(manager.isRepositoryCloned(KEY)).isFalse();
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

    private static String head(Git git) throws IOException {
        ObjectId head = git.getRepository().resolve("HEAD");
        assertThat(head).isNotNull();
        return head.name();
    }

    /** {@code count} empty commits on top of {@code tip}, moved onto HEAD's branch. */
    private static ObjectId chain(Git git, ObjectId tip, int count) throws Exception {
        try (var inserter = git.getRepository().newObjectInserter();
                var walk = new RevWalk(git.getRepository())) {
            ObjectId tree = walk.parseCommit(tip).getTree().getId();
            PersonIdent author = new PersonIdent("Test", "test@example.com");
            for (int i = 0; i < count; i++) {
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
        update.forceUpdate();
        return tip;
    }

    private void prepare() {
        manager.ensureRepository(KEY, source.toUri().toString(), null);
    }

    private List<Path> stagingDirectories() throws IOException {
        if (!Files.isDirectory(fabric)) return List.of();
        try (Stream<Path> entries = Files.list(fabric)) {
            return entries.filter(
                            path -> path.getFileName().toString().startsWith(GitRepositoryManager.GIT_SNAPSHOT_PREFIX))
                    .toList();
        }
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.util.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class GitRepositoryManagerTest extends BaseUnitTest {

    @TempDir
    private Path tempDir;

    private Path storagePath;
    private Path sourceRepoPath;

    private GitRepositoryManager manager = mock(GitRepositoryManager.class);
    private GitRepositoryLockManager lockManager = mock(GitRepositoryLockManager.class);

    @BeforeEach
    void setUp() throws Exception {
        storagePath = tempDir.resolve("storage");
        sourceRepoPath = tempDir.resolve("source-repo");

        lockManager = new GitRepositoryLockManager();

        Files.createDirectories(sourceRepoPath);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(storagePath)) {
            Files.walk(storagePath).sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private GitRepositoryManager createManager(boolean enabled) {
        return createManager(enabled, 20_000, DataSize.ofMegabytes(32), DataSize.ofMegabytes(10));
    }

    private GitRepositoryManager createManager(
            boolean enabled, int maxFiles, DataSize maxTotalSize, DataSize maxFileSize) {
        GitRepositoryProperties properties = new GitRepositoryProperties(enabled, maxFiles, maxTotalSize, maxFileSize);
        return new GitRepositoryManager(properties, lockManager, new FabricLayout(storagePath.toString()));
    }

    private String commit(Git git, String message) throws GitAPIException {
        return git.commit()
                .setSign(false)
                .setMessage(message)
                .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                .call()
                .getName();
    }

    private Git createSourceRepo() throws GitAPIException, IOException {
        Git git = Git.init().setDirectory(sourceRepoPath.toFile()).call();
        GitTestFixtures.disableSigning(git.getRepository());
        Path file = sourceRepoPath.resolve("README.md");
        Files.writeString(file, "# Test Repository\n");
        git.add().addFilepattern("README.md").call();
        git.commit()
                .setSign(false)
                .setMessage("Initial commit")
                .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                .call();
        return git;
    }

    @Nested
    class IsEnabled {

        @Test
        void shouldReturnTrueWhenEnabled() {
            manager = createManager(true);
            assertThat(manager.isEnabled()).isTrue();
        }

        @Test
        void shouldReturnFalseWhenDisabled() {
            manager = createManager(false);
            assertThat(manager.isEnabled()).isFalse();
        }
    }

    @Nested
    class GetRepositoryPath {

        @Test
        void shouldReturnPathWithRepositoryId() {
            manager = createManager(false);
            Path path = manager.getRepositoryPath(42L);

            assertThat(path)
                    .isEqualTo(storagePath.resolve("sources").resolve("scm").resolve("42"));
        }
    }

    @Nested
    class IsRepositoryCloned {

        @Test
        void shouldReturnFalseForNonExistentRepository() {
            manager = createManager(false);
            assertThat(manager.isRepositoryCloned(999L)).isFalse();
        }

        // Closing the scope is the operation; its binding is intentionally unread.
        @SuppressWarnings("try")
        @Test
        void shouldReturnTrueForClonedRepository() throws Exception {
            manager = createManager(true);

            try (Git sourceGit = createSourceRepo()) {
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);
                assertThat(manager.isRepositoryCloned(1L)).isTrue();
            }
        }
    }

    @Nested
    class EnsureRepository {

        @Test
        void shouldThrowWhenNotEnabled() {
            manager = createManager(false);

            assertThatThrownBy(() -> manager.ensureRepository(1L, "https://example.com/repo.git", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not enabled");
        }

        // Closing the scope is the operation; its binding is intentionally unread.
        @SuppressWarnings("try")
        @Test
        void shouldCloneRepositoryOnFirstCall() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                Path result =
                        manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                assertThat(result)
                        .isEqualTo(storagePath.resolve("sources").resolve("scm").resolve("1"));
                assertThat(Files.exists(result.resolve(".git").resolve("HEAD"))).isTrue();
            }
        }

        @Test
        void shouldFetchOnSubsequentCalls() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                Path file = sourceRepoPath.resolve("file2.txt");
                Files.writeString(file, "content");
                sourceGit.add().addFilepattern("file2.txt").call();
                String newSha = sourceGit
                        .commit()
                        .setSign(false)
                        .setMessage("Second commit")
                        .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                        .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                        .call()
                        .getName();

                Path result =
                        manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                assertThat(result)
                        .isEqualTo(storagePath.resolve("sources").resolve("scm").resolve("1"));
                List<GitRepositoryManager.CommitInfo> commits = manager.walkCommits(1L, null, newSha);
                assertThat(commits).hasSize(2);
            }
        }

        @Test
        void shouldRecloneWhenRepositoryIdPointsToDifferentOrigin() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String oldHead = sourceGit.log().call().iterator().next().getName();
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                Path replacementPath = tempDir.resolve("replacement-repo");
                Files.createDirectories(replacementPath);
                String replacementHead;
                try (Git replacement =
                        Git.init().setDirectory(replacementPath.toFile()).call()) {
                    GitTestFixtures.disableSigning(replacement.getRepository());
                    Files.writeString(replacementPath.resolve("replacement.txt"), "replacement\n");
                    replacement.add().addFilepattern("replacement.txt").call();
                    replacementHead = replacement
                            .commit()
                            .setSign(false)
                            .setMessage("Replacement repository")
                            .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                            .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                            .call()
                            .getName();
                }

                Path result =
                        manager.ensureRepository(1L, replacementPath.toUri().toString(), null);

                assertThat(manager.commitExists(1L, replacementHead)).isTrue();
                assertThat(manager.commitExists(1L, oldHead)).isFalse();
                try (Git clone = Git.open(result.toFile())) {
                    assertThat(clone.getRepository().getConfig().getString("remote", "origin", "url"))
                            .isEqualTo(replacementPath.toUri().toString());
                }
            }
        }

        @Test
        void shouldRebuildCheckoutWhenStaleOriginUrlPrecedesCurrentOne() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String cloneUrl = sourceRepoPath.toUri().toString();
                Path repoPath = manager.ensureRepository(1L, cloneUrl, null);

                // JGit reads the last value back as the configured URL but fetches from the first,
                // which is not a repository. Dropping the fetch refspec instead only yields "Nothing
                // to fetch".
                try (Git clone = Git.open(repoPath.toFile())) {
                    var config = clone.getRepository().getConfig();
                    config.setStringList(
                            "remote",
                            "origin",
                            "url",
                            List.of(tempDir.resolve("missing").toUri().toString(), cloneUrl));
                    config.save();
                    assertThatThrownBy(() -> clone.fetch().setRemote("origin").call())
                            .isInstanceOf(InvalidRemoteException.class);
                }

                Path result = manager.ensureRepository(1L, cloneUrl, null);

                assertThat(result).isEqualTo(repoPath);
                try (Git clone = Git.open(result.toFile())) {
                    assertThat(clone.getRepository().getConfig().getStringList("remote", "origin", "url"))
                            .containsExactly(cloneUrl);
                }

                Files.writeString(sourceRepoPath.resolve("file2.txt"), "content");
                sourceGit.add().addFilepattern("file2.txt").call();
                String newSha = commit(sourceGit, "Second commit");

                manager.ensureRepository(1L, cloneUrl, null);

                assertThat(manager.commitExists(1L, newSha)).isTrue();
            }
        }

        @Test
        void shouldRebuildCheckoutWhenRemoteConfigurationDoesNotParse() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String cloneUrl = sourceRepoPath.toUri().toString();
                Path repoPath = manager.ensureRepository(1L, cloneUrl, null);

                // An empty pushurl, as a partially written config leaves behind: JGit parses every URL
                // of the remote before fetching and rejects the empty one.
                try (Git clone = Git.open(repoPath.toFile())) {
                    var config = clone.getRepository().getConfig();
                    config.setString("remote", "origin", "pushurl", "");
                    config.save();
                    assertThatThrownBy(() -> clone.fetch().setRemote("origin").call())
                            .isInstanceOf(InvalidRemoteException.class)
                            .hasRootCauseInstanceOf(URISyntaxException.class);
                }

                Path result = manager.ensureRepository(1L, cloneUrl, null);

                assertThat(result).isEqualTo(repoPath);
                try (Git clone = Git.open(result.toFile())) {
                    assertThat(clone.getRepository().getConfig().getString("remote", "origin", "pushurl"))
                            .isNull();
                }

                Files.writeString(sourceRepoPath.resolve("file2.txt"), "content");
                sourceGit.add().addFilepattern("file2.txt").call();
                String newSha = commit(sourceGit, "Second commit");

                manager.ensureRepository(1L, cloneUrl, null);

                assertThat(manager.commitExists(1L, newSha)).isTrue();
            }
        }

        @Test
        void shouldKeepCheckoutWhenRepositoryIsGoneUpstream() throws Exception {
            manager = createManager(true);
            String cloneUrl = sourceRepoPath.toUri().toString();
            String head;
            try (Git sourceGit = createSourceRepo()) {
                head = sourceGit.log().call().iterator().next().getName();
            }
            manager.ensureRepository(1L, cloneUrl, null);
            FileUtils.delete(sourceRepoPath.toFile(), FileUtils.RECURSIVE);

            assertThatThrownBy(() -> manager.ensureRepository(1L, cloneUrl, null))
                    .isInstanceOf(GitRepositoryManager.GitOperationException.class)
                    .hasMessageContaining("not found upstream")
                    .hasRootCauseInstanceOf(NoRemoteRepositoryException.class);

            assertThat(manager.isRepositoryCloned(1L)).isTrue();
            assertThat(manager.commitExists(1L, head)).isTrue();
        }
    }

    @Nested
    class CommitExists {

        // Closing the scope is the operation; its binding is intentionally unread.
        @SuppressWarnings("try")
        @Test
        void shouldReturnFalseWhenValidObjectIdIsAbsent() throws Exception {
            manager = createManager(true);
            try (Git ignored = createSourceRepo()) {
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                assertThat(manager.commitExists(1L, "0000000000000000000000000000000000000001"))
                        .isFalse();
            }
        }
    }

    @Nested
    class FetchRemoteCommit {

        @Test
        void shouldFetchSyntheticReviewRefAndVerifyPinnedCommit() throws Exception {
            manager = createManager(true);
            try (Git source = createSourceRepo()) {
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                Files.writeString(sourceRepoPath.resolve("review.txt"), "review\n");
                source.add().addFilepattern("review.txt").call();
                String reviewHead = source.commit()
                        .setSign(false)
                        .setMessage("Review head")
                        .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                        .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                        .call()
                        .getName();
                var update = source.getRepository().updateRef("refs/merge-requests/7/head");
                update.setNewObjectId(ObjectId.fromString(reviewHead));
                update.update();

                assertThat(manager.commitExists(1L, reviewHead)).isFalse();

                assertThat(manager.fetchRemoteCommit(1L, "refs/merge-requests/7/head", reviewHead, null))
                        .isTrue();
                assertThat(manager.commitExists(1L, reviewHead)).isTrue();
            }
        }
    }

    @Nested
    class WalkCommits {

        @Test
        void shouldReturnEmptyListWhenNotEnabled() {
            manager = createManager(false);

            List<GitRepositoryManager.CommitInfo> result = manager.walkCommits(1L, null, "abc123");

            assertThat(result).isEmpty();
        }

        @Test
        void shouldWalkAllCommitsWhenFromShaIsNull() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String headSha = sourceGit.log().call().iterator().next().getName();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);
                List<GitRepositoryManager.CommitInfo> commits = manager.walkCommits(1L, null, headSha);

                assertThat(commits).hasSize(1);
                assertThat(commits.get(0).message()).isEqualTo("Initial commit");
            }
        }

        @Test
        void shouldWalkCommitsBetweenShas() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String firstSha = sourceGit.log().call().iterator().next().getName();

                Path file2 = sourceRepoPath.resolve("file2.txt");
                Files.writeString(file2, "content2");
                sourceGit.add().addFilepattern("file2.txt").call();
                sourceGit
                        .commit()
                        .setSign(false)
                        .setMessage("Second commit")
                        .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                        .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                        .call();

                Path file3 = sourceRepoPath.resolve("file3.txt");
                Files.writeString(file3, "content3");
                sourceGit.add().addFilepattern("file3.txt").call();
                String thirdSha = sourceGit
                        .commit()
                        .setSign(false)
                        .setMessage("Third commit")
                        .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                        .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                        .call()
                        .getName();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);
                List<GitRepositoryManager.CommitInfo> commits = manager.walkCommits(1L, firstSha, thirdSha);

                assertThat(commits).hasSize(2);
                assertThat(commits)
                        .extracting(GitRepositoryManager.CommitInfo::message)
                        .containsExactly("Third commit", "Second commit");
            }
        }

        // Closing the scope is the operation; its binding is intentionally unread.
        @SuppressWarnings("try")
        @Test
        void shouldThrowForUnresolvableToSha() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                assertThatThrownBy(() -> manager.walkCommits(1L, null, "0000000000000000000000000000000000000000"))
                        .isInstanceOf(GitRepositoryManager.GitOperationException.class)
                        .hasMessageContaining("Failed to walk commits");
            }
        }

        @Test
        void shouldExtractFileChangesForCommits() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String headSha = sourceGit.log().call().iterator().next().getName();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);
                List<GitRepositoryManager.CommitInfo> commits = manager.walkCommits(1L, null, headSha);

                assertThat(commits).hasSize(1);
                GitRepositoryManager.CommitInfo commit = commits.get(0);

                assertThat(commit.fileChanges()).hasSize(1);
                GitRepositoryManager.FileChange fileChange =
                        commit.fileChanges().get(0);
                assertThat(fileChange.filename()).isEqualTo("README.md");
                assertThat(fileChange.changeType()).isEqualTo(GitRepositoryManager.ChangeType.ADDED);
                assertThat(fileChange.additions()).isPositive();
            }
        }

        @Test
        void shouldExtractAuthorAndCommitterInfo() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String headSha = sourceGit.log().call().iterator().next().getName();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);
                List<GitRepositoryManager.CommitInfo> commits = manager.walkCommits(1L, null, headSha);

                GitRepositoryManager.CommitInfo commit = commits.get(0);
                assertThat(commit.authorName()).isEqualTo("Test Author");
                assertThat(commit.authorEmail()).isEqualTo("author@test.com");
                assertThat(commit.committerName()).isEqualTo("Test Committer");
                assertThat(commit.committerEmail()).isEqualTo("committer@test.com");
                assertThat(commit.authoredAt()).isNotNull();
                assertThat(commit.committedAt()).isNotNull();
            }
        }

        @Test
        void shouldDetectFileModifications() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String firstSha = sourceGit.log().call().iterator().next().getName();

                Path readme = sourceRepoPath.resolve("README.md");
                Files.writeString(readme, "# Updated\nNew content\n");
                sourceGit.add().addFilepattern("README.md").call();
                String secondSha = sourceGit
                        .commit()
                        .setSign(false)
                        .setMessage("Update README")
                        .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                        .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                        .call()
                        .getName();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);
                List<GitRepositoryManager.CommitInfo> commits = manager.walkCommits(1L, firstSha, secondSha);

                assertThat(commits).hasSize(1);
                GitRepositoryManager.FileChange change =
                        commits.get(0).fileChanges().get(0);
                assertThat(change.filename()).isEqualTo("README.md");
                assertThat(change.changeType()).isEqualTo(GitRepositoryManager.ChangeType.MODIFIED);
            }
        }

        @Test
        void shouldDetectFileDeletions() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String firstSha = sourceGit.log().call().iterator().next().getName();

                Files.delete(sourceRepoPath.resolve("README.md"));
                sourceGit.rm().addFilepattern("README.md").call();
                String secondSha = sourceGit
                        .commit()
                        .setSign(false)
                        .setMessage("Remove README")
                        .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                        .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                        .call()
                        .getName();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);
                List<GitRepositoryManager.CommitInfo> commits = manager.walkCommits(1L, firstSha, secondSha);

                assertThat(commits).hasSize(1);
                GitRepositoryManager.FileChange change =
                        commits.get(0).fileChanges().get(0);
                assertThat(change.filename()).isEqualTo("README.md");
                assertThat(change.changeType()).isEqualTo(GitRepositoryManager.ChangeType.REMOVED);
            }
        }

        @Test
        void shouldComputeAdditionsAndDeletionsCorrectly() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String headSha = sourceGit.log().call().iterator().next().getName();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);
                List<GitRepositoryManager.CommitInfo> commits = manager.walkCommits(1L, null, headSha);

                GitRepositoryManager.CommitInfo commit = commits.get(0);
                assertThat(commit.additions()).isPositive();
                assertThat(commit.deletions()).isZero();
                assertThat(commit.changedFiles()).isEqualTo(1);
            }
        }
    }

    @Nested
    class ResolveDefaultBranchHead {

        @Test
        void shouldReturnNullWhenNotEnabled() {
            manager = createManager(false);

            String result = manager.resolveDefaultBranchHead(1L, "main");

            assertThat(result).isNull();
        }

        @Test
        void shouldResolveHeadShaForDefaultBranch() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String expectedSha = sourceGit.log().call().iterator().next().getName();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);
                String result = manager.resolveDefaultBranchHead(1L, "master");

                assertThat(result).isEqualTo(expectedSha);
            }
        }

        @Test
        void shouldResolveHeadAfterFetchUpdates() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                Path file = sourceRepoPath.resolve("file2.txt");
                Files.writeString(file, "content");
                sourceGit.add().addFilepattern("file2.txt").call();
                String newSha = sourceGit
                        .commit()
                        .setSign(false)
                        .setMessage("Second commit")
                        .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                        .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                        .call()
                        .getName();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);
                String result = manager.resolveDefaultBranchHead(1L, "master");

                assertThat(result).isEqualTo(newSha);
            }
        }

        // Closing the scope is the operation; its binding is intentionally unread.
        @SuppressWarnings("try")
        @Test
        void shouldReturnNullForNonExistentBranch() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                String result = manager.resolveDefaultBranchHead(1L, "nonexistent-branch");

                assertThat(result).isNull();
            }
        }

        @Test
        void shouldReturnNullForNonExistentRepository() {
            manager = createManager(true);

            String result = manager.resolveDefaultBranchHead(999L, "main");

            assertThat(result).isNull();
        }
    }

    @Nested
    class ReadTreeSnapshot {

        @Test
        void shouldRefuseToReadATreeWhenCheckoutIsDisabled() {
            manager = createManager(false);

            assertThatThrownBy(() -> manager.readTreeSnapshot(1L, "abc123"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("checkout is disabled");
        }

        @Test
        void shouldReadAllFilesAtCommit() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String headSha = sourceGit.log().call().iterator().next().getName();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);
                try (var snapshot = manager.readTreeSnapshot(1L, headSha)) {
                    Map<String, Path> files = snapshot.files();

                    assertThat(files).containsKey("README.md");
                    assertThat(Files.readString(files.get("README.md"), StandardCharsets.UTF_8))
                            .isEqualTo("# Test Repository\n");
                }
            }
        }

        @Test
        @DisplayName("a screenshot neither spends the budget nor ends the walk the source is waiting in")
        void shouldStageSourceRatherThanBinaries() throws Exception {
            // A budget two thirds the size of the image: staging the image would leave nothing for the
            // text that follows it, so what survives here is exactly what the exclusion buys.
            manager = createManager(true, 20_000, DataSize.ofKilobytes(70), DataSize.ofKilobytes(70));
            try (Git sourceGit = createSourceRepo()) {
                // Sorted before the text, as a documentation image is in a real repository.
                byte[] png = new byte[64 * 1024];
                png[0] = (byte) 0x89;
                png[1] = 'P';
                png[2] = 'N';
                png[3] = 'G';
                png[8] = 0; // the NUL that makes it binary
                Files.write(sourceRepoPath.resolve("a-screenshot.png"), png);
                Files.writeString(sourceRepoPath.resolve("b-filler.txt"), "x".repeat(40 * 1024));
                Files.writeString(sourceRepoPath.resolve("c-service.java"), "class Service {}\n");
                sourceGit.add().addFilepattern(".").call();
                String sha = commit(sourceGit, "Add a documentation image ahead of the source");

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                try (var snapshot = manager.readTreeSnapshot(1L, sha)) {
                    assertThat(snapshot.files())
                            .containsKeys("b-filler.txt", "c-service.java")
                            .doesNotContainKey("a-screenshot.png");
                    // The manifest still says what the review could not have been shown, and the walk
                    // reached the end of the tree rather than stopping at a file it never staged.
                    assertThat(snapshot.limitations())
                            .contains(GitRepositoryManager.TREE_LIMITATION_BINARY)
                            .doesNotContain(GitRepositoryManager.TREE_LIMITATION_TOTAL_SIZE);
                }
            }
        }

        @Test
        void shouldRecordTheResolvedCommitIdentity() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String headSha = sourceGit.log().call().iterator().next().getName();
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                try (var snapshot = manager.readTreeSnapshot(1L, headSha.substring(0, 8))) {
                    assertThat(snapshot.commitSha()).isEqualTo(headSha);
                }
            }
        }

        @Test
        void shouldReadFromSpecificCommit() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String firstSha = sourceGit.log().call().iterator().next().getName();

                Path file2 = sourceRepoPath.resolve("file2.txt");
                Files.writeString(file2, "second file content");
                sourceGit.add().addFilepattern("file2.txt").call();
                sourceGit
                        .commit()
                        .setSign(false)
                        .setMessage("Add file2")
                        .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                        .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                        .call();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                try (var snapshot = manager.readTreeSnapshot(1L, firstSha)) {
                    assertThat(snapshot.files()).containsKey("README.md");
                    assertThat(snapshot.files()).doesNotContainKey("file2.txt");
                }
            }
        }

        @Test
        @DisplayName("excludes a symlink instead of following it out of the tree")
        void shouldExcludeSymlinksAndSaySo() throws Exception {
            manager = createManager(true);
            Path escapingLink = sourceRepoPath.resolve("escape.txt");
            try (Git sourceGit = createSourceRepo()) {
                Files.createSymbolicLink(escapingLink, Path.of("../../../etc/passwd"));
                sourceGit.add().addFilepattern("escape.txt").call();
                String sha = sourceGit
                        .commit()
                        .setSign(false)
                        .setMessage("Add a symlink pointing out of the repository")
                        .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                        .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                        .call()
                        .getName();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                try (var snapshot = manager.readTreeSnapshot(1L, sha)) {
                    assertThat(snapshot.files()).doesNotContainKey("escape.txt");
                    assertThat(snapshot.stagingDir().resolve("escape.txt")).doesNotExist();
                    assertThat(snapshot.limitations()).contains("SYMLINK_EXCLUDED");
                    assertThat(snapshot.complete()).isFalse();
                }
            } finally {
                // Unlink the fixture without following its target during temporary-directory cleanup.
                Files.deleteIfExists(escapingLink);
            }
        }

        @Test
        @DisplayName("deletes its staging directory when closed")
        void shouldReleaseStagingOnClose() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String headSha = sourceGit.log().call().iterator().next().getName();
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                Path stagingDir;
                try (var snapshot = manager.readTreeSnapshot(1L, headSha)) {
                    stagingDir = snapshot.stagingDir();
                    assertThat(stagingDir).exists();
                }
                assertThat(stagingDir).doesNotExist();
            }
        }

        @Test
        @DisplayName("skips one oversized blob, keeps the rest of the tree, and says which bound it hit")
        void shouldSkipABlobOverThePerFileBoundWithoutLosingTheTree() throws Exception {
            manager = createManager(true, 20_000, DataSize.ofMegabytes(32), DataSize.ofKilobytes(4));
            try (Git sourceGit = createSourceRepo()) {
                Files.write(sourceRepoPath.resolve("asset.bin"), new byte[16 * 1024]);
                Files.writeString(sourceRepoPath.resolve("src.java"), "class A {}\n");
                sourceGit.add().addFilepattern(".").call();
                String sha = commit(sourceGit, "Add an oversized asset beside a source file");

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                try (var snapshot = manager.readTreeSnapshot(1L, sha)) {
                    assertThat(snapshot.files()).doesNotContainKey("asset.bin");
                    assertThat(snapshot.files()).containsKeys("src.java", "README.md");
                    assertThat(snapshot.limitations()).contains(GitRepositoryManager.TREE_LIMITATION_FILE_TOO_LARGE);
                    assertThat(snapshot.complete()).isFalse();
                }
            }
        }

        @Test
        @DisplayName("stops at the file-count bound and reports itself incomplete")
        void shouldStopAtTheFileCountBound() throws Exception {
            manager = createManager(true, 3, DataSize.ofMegabytes(32), DataSize.ofMegabytes(10));
            try (Git sourceGit = createSourceRepo()) {
                for (int i = 0; i < 10; i++) {
                    Files.writeString(sourceRepoPath.resolve("file" + i + ".txt"), "content " + i + "\n");
                }
                sourceGit.add().addFilepattern(".").call();
                String sha = commit(sourceGit, "Add more files than the bound admits");

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                try (var snapshot = manager.readTreeSnapshot(1L, sha)) {
                    assertThat(snapshot.files()).hasSize(3);
                    assertThat(snapshot.limitations()).contains(GitRepositoryManager.TREE_LIMITATION_FILE_COUNT);
                    assertThat(snapshot.complete()).isFalse();
                }
            }
        }

        @Test
        @DisplayName("stops at the total-size bound and reports itself incomplete")
        void shouldStopAtTheTotalSizeBound() throws Exception {
            manager = createManager(true, 20_000, DataSize.ofKilobytes(6), DataSize.ofKilobytes(4));
            try (Git sourceGit = createSourceRepo()) {
                for (int i = 0; i < 8; i++) {
                    // Text, not zeroes: a blob of NUL bytes is binary, and the bound under test here is
                    // the size one.
                    Files.writeString(sourceRepoPath.resolve("file" + i + ".txt"), "x".repeat(2 * 1024));
                }
                sourceGit.add().addFilepattern(".").call();
                String sha = commit(sourceGit, "Add more bytes than the bound admits");

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                try (var snapshot = manager.readTreeSnapshot(1L, sha)) {
                    assertThat(snapshot.totalBytes()).isLessThanOrEqualTo(6 * 1024);
                    assertThat(snapshot.files()).hasSizeLessThan(9);
                    assertThat(snapshot.limitations()).contains(GitRepositoryManager.TREE_LIMITATION_TOTAL_SIZE);
                    assertThat(snapshot.complete()).isFalse();
                }
            }
        }

        @Test
        @DisplayName("never writes a blob it is going to reject")
        void shouldNotStageAnOversizedBlobBeforeRejectingIt() throws Exception {
            manager = createManager(true, 20_000, DataSize.ofMegabytes(32), DataSize.ofKilobytes(4));
            try (Git sourceGit = createSourceRepo()) {
                Files.write(sourceRepoPath.resolve("asset.bin"), new byte[64 * 1024]);
                sourceGit.add().addFilepattern(".").call();
                String sha = commit(sourceGit, "Add an oversized asset");

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                try (var snapshot = manager.readTreeSnapshot(1L, sha)) {
                    // The bound has to protect the disk too: measuring the blob after writing it would
                    // let a repository full of huge files fill the staging volume before being rejected.
                    assertThat(snapshot.stagingDir().resolve("asset.bin")).doesNotExist();
                    assertThat(snapshot.totalBytes()).isLessThan(64 * 1024);
                }
            }
        }

        // Closing the scope is the operation; its binding is intentionally unread.
        @SuppressWarnings("try")
        @Test
        void shouldThrowForUnresolvableCommit() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                assertThatThrownBy(() -> manager.readTreeSnapshot(1L, "0000000000000000000000000000000000000000"))
                        .isInstanceOf(GitRepositoryManager.GitOperationException.class)
                        .hasMessageContaining("Failed to read files at commit");
            }
        }
    }

    @Nested
    class GenerateUnifiedDiff {

        @Test
        void shouldReturnEmptyStringWhenNotEnabled() {
            manager = createManager(false);

            String result = manager.generateUnifiedDiff(1L, "main", "feature");

            assertThat(result).isEmpty();
        }

        @Test
        void shouldGenerateUnifiedDiff() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String baseSha = sourceGit.log().call().iterator().next().getName();

                sourceGit.branchCreate().setName("feature").call();
                sourceGit.checkout().setName("feature").call();

                Path file = sourceRepoPath.resolve("new-file.java");
                Files.writeString(file, "public class NewFile {}\n");
                sourceGit.add().addFilepattern("new-file.java").call();
                sourceGit
                        .commit()
                        .setSign(false)
                        .setMessage("Add new file on feature branch")
                        .setAuthor(new PersonIdent("Test Author", "author@test.com"))
                        .setCommitter(new PersonIdent("Test Committer", "committer@test.com"))
                        .call();

                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                String featureSha = sourceGit.log().call().iterator().next().getName();
                String diff = manager.generateUnifiedDiff(1L, baseSha, featureSha);

                assertThat(diff).contains("new-file.java");
                assertThat(diff).contains("public class NewFile {}");
            }
        }

        @Test
        void shouldReturnEmptyForUnresolvableBaseRef() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String headSha = sourceGit.log().call().iterator().next().getName();
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                String diff = manager.generateUnifiedDiff(1L, "nonexistent-ref-xyz", headSha);

                assertThat(diff).isEmpty();
            }
        }

        @Test
        void shouldReturnEmptyForUnresolvableHeadRef() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String headSha = sourceGit.log().call().iterator().next().getName();
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                String diff = manager.generateUnifiedDiff(1L, headSha, "nonexistent-ref-xyz");

                assertThat(diff).isEmpty();
            }
        }

        @Test
        void shouldReturnEmptyDiffWhenSameRef() throws Exception {
            manager = createManager(true);
            try (Git sourceGit = createSourceRepo()) {
                String headSha = sourceGit.log().call().iterator().next().getName();
                manager.ensureRepository(1L, sourceRepoPath.toUri().toString(), null);

                String diff = manager.generateUnifiedDiff(1L, headSha, headSha);

                assertThat(diff).isEmpty();
            }
        }
    }
}

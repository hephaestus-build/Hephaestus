package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class GitHubCommitBackfillServiceTest extends BaseUnitTest {

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    @Mock
    private GitHubAppTokenService tokenService;

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private CommitAuthorResolver authorResolver;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    private GitHubCommitBackfillService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        // Execute TransactionTemplate callbacks directly, with no real transaction.
        lenient()
                .when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(invocation -> {
                    TransactionCallback<Object> callback = invocation.getArgument(0);
                    return callback.doInTransaction(mock(TransactionStatus.class));
                });

        service = new GitHubCommitBackfillService(
                gitRepositoryManager,
                tokenService,
                commitRepository,
                authorResolver,
                eventPublisher,
                transactionTemplate);
    }

    private void stubCommits(List<CommitDetails> commits) {
        doAnswer(invocation -> {
                    Consumer<CommitDetails> consumer = invocation.getArgument(2);
                    commits.forEach(consumer);
                    return null;
                })
                .when(gitRepositoryManager)
                .forEachMissingCommit(eq(new RepositoryKey(100L, 1L)), any(), any());
    }

    private static SyncTarget createSyncTarget(AuthMode authMode) {
        var builder = SyncTargetTestBuilder.syncTarget()
                .id(1L)
                .scopeId(100L)
                .authMode(authMode)
                .repositoryNameWithOwner("owner/repo");
        if (authMode == AuthMode.INSTALLATION_APP) builder.installationId(42L);
        if (authMode == AuthMode.PERSONAL_ACCESS_TOKEN) builder.personalAccessToken("ghp_test_token");
        return builder.build();
    }

    private static Repository createMockRepository(Long id, String nameWithOwner, @Nullable String defaultBranch) {
        Repository repo = TestEntities.repository(id, nameWithOwner, "main");
        repo.setDefaultBranch(defaultBranch);
        repo.setProvider(TestEntities.gitProvider(1L, IdentityProviderType.GITHUB));
        return repo;
    }

    private static CommitDetails createCommitInfo(String sha, String message) {
        return new CommitDetails(
                sha,
                message,
                null,
                "Author",
                "author@test.com",
                Instant.parse("2024-01-15T10:00:00Z"),
                "Committer",
                "committer@test.com",
                Instant.parse("2024-01-15T10:00:00Z"),
                10,
                5,
                1,
                List.of(new CommitDetails.FileChange(
                        "src/Main.java", CommitFileChange.ChangeType.MODIFIED, 10, 5, 15, null)),
                List.of());
    }

    private static Commit createMockCommit(String sha, Long repoId) {
        Commit commit = TestEntities.commit(1L, sha);
        commit.setMessage("test");
        commit.setAuthoredAt(Instant.parse("2024-01-15T10:00:00Z"));
        commit.setAdditions(0);
        commit.setDeletions(0);
        commit.setChangedFiles(0);
        commit.setRepository(TestEntities.repository(repoId, "owner/repository"));
        return commit;
    }

    @Nested
    class SkipConditions {

        @Test
        @DisplayName("should return -1 when git checkout is disabled")
        void shouldReturnNegativeOneWhenDisabled() {
            when(gitRepositoryManager.isEnabled()).thenReturn(false);
            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
            verify(gitRepositoryManager, never()).ensureRepository(any(RepositoryKey.class), anyString(), any());
        }

        @Test
        void shouldReturnNegativeOneWhenDefaultBranchNull() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            Repository repo = createMockRepository(1L, "owner/repo", null);
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
            verify(gitRepositoryManager, never()).ensureRepository(any(RepositoryKey.class), anyString(), any());
        }

        @Test
        void shouldReturnNegativeOneWhenDefaultBranchBlank() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            Repository repo = createMockRepository(1L, "owner/repo", "  ");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        @DisplayName("should return -1 when HEAD cannot be resolved")
        void shouldReturnNegativeOneWhenHeadUnresolvable() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn(null);
            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        @DisplayName("should scan branches even when no new commits are returned")
        void shouldReturnZeroWhenAlreadyUpToDate() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("abc123");

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(0);
            verify(gitRepositoryManager).forEachMissingCommit(eq(new RepositoryKey(100L, 1L)), any(), any());
        }
    }

    @Test
    void shouldPersistMoreThanFiveThousandCommitsInOneSuccessfulTraversal() {
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                .thenReturn("head");
        when(commitRepository.findByShaAndRepositoryId(anyString(), eq(1L)))
                .thenAnswer(invocation -> Optional.of(createMockCommit(invocation.getArgument(0), 1L)));
        stubCommits(java.util.stream.IntStream.range(0, 5001)
                .mapToObj(i -> createCommitInfo("sha" + i, "Commit"))
                .toList());
        int result = service.backfillCommits(
                createSyncTarget(AuthMode.PERSONAL_ACCESS_TOKEN), createMockRepository(1L, "owner/repo", "main"), 100L);
        assertThat(result).isEqualTo(5001);
    }

    @Test
    void shouldCompleteAnExistingWebhookStubWithoutPublishingAnotherCreatedEvent() {
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                .thenReturn("head");
        when(commitRepository.existsByShaAndRepositoryId("stub", 1L)).thenReturn(true);
        when(commitRepository.findByShaAndRepositoryId("stub", 1L))
                .thenReturn(Optional.of(createMockCommit("stub", 1L)));
        stubCommits(List.of(createCommitInfo("stub", "Commit")));

        assertThat(service.backfillCommits(
                        createSyncTarget(AuthMode.PERSONAL_ACCESS_TOKEN),
                        createMockRepository(1L, "owner/repo", "main"),
                        100L))
                .isEqualTo(1);
        verify(commitRepository).markGitDetailsCaptured(eq(1L), eq("stub"), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Nested
    class FullBackfill {

        @Test
        void shouldWalkAllCommitsWhenNoPreviousCommits() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("head123");

            CommitDetails commitInfo = createCommitInfo("commit1", "First commit");
            stubCommits(List.of(commitInfo));

            when(commitRepository.existsByShaAndRepositoryId("commit1", 1L)).thenReturn(false);

            // Lookup after upsert, for event publishing.
            Commit mockCommit = createMockCommit("commit1", 1L);
            when(commitRepository.findByShaAndRepositoryId("commit1", 1L)).thenReturn(Optional.of(mockCommit));

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(1);
            verify(gitRepositoryManager).forEachMissingCommit(eq(new RepositoryKey(100L, 1L)), any(), any());
            verify(commitRepository)
                    .upsertCommit(
                            eq("commit1"),
                            eq("First commit"),
                            any(),
                            eq("https://github.com/owner/repo/commit/commit1"),
                            any(),
                            any(),
                            eq(10),
                            eq(5),
                            eq(1),
                            any(),
                            eq(1L),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldPublishCommitCreatedEvent() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("head123");

            CommitDetails commitInfo = createCommitInfo("commit1", "First commit");
            stubCommits(List.of(commitInfo));
            when(commitRepository.existsByShaAndRepositoryId("commit1", 1L)).thenReturn(false);

            Commit mockCommit = createMockCommit("commit1", 1L);
            when(commitRepository.findByShaAndRepositoryId("commit1", 1L)).thenReturn(Optional.of(mockCommit));

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            service.backfillCommits(target, repo, 100L);

            ArgumentCaptor<ScmDomainEvent.CommitCreated> eventCaptor =
                    ArgumentCaptor.forClass(ScmDomainEvent.CommitCreated.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());

            ScmDomainEvent.CommitCreated event = eventCaptor.getValue();
            assertThat(event.context().scopeId()).isEqualTo(100L);
        }
    }

    @Nested
    class IncrementalBackfill {

        @Test
        void shouldWalkAllBranchesWhenNewCommitsExist() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("head456");

            CommitDetails newCommit = createCommitInfo("head456", "New commit");
            stubCommits(List.of(newCommit));

            when(commitRepository.existsByShaAndRepositoryId("head456", 1L)).thenReturn(false);
            Commit mockCommit = createMockCommit("head456", 1L);
            when(commitRepository.findByShaAndRepositoryId("head456", 1L)).thenReturn(Optional.of(mockCommit));

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(1);
            verify(gitRepositoryManager).forEachMissingCommit(eq(new RepositoryKey(100L, 1L)), any(), any());
        }
    }

    @Nested
    class DuplicateHandling {

        @Test
        void shouldSkipExistingCommits() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("head123");

            CommitDetails existingCommit = createCommitInfo("existing", "Old commit");
            CommitDetails newCommit = createCommitInfo("newone", "New commit");
            stubCommits(List.of(existingCommit, newCommit));

            when(commitRepository.existsByShaAndRepositoryIdAndGitDetailsCapturedAtIsNotNull("existing", 1L))
                    .thenReturn(true);
            when(commitRepository.existsByShaAndRepositoryId("newone", 1L)).thenReturn(false);

            Commit mockCommit = createMockCommit("newone", 1L);
            when(commitRepository.findByShaAndRepositoryId("newone", 1L)).thenReturn(Optional.of(mockCommit));

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(1);
            verify(commitRepository, times(1))
                    .upsertCommit(
                            eq("newone"),
                            anyString(),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            any(Integer.class),
                            any(Integer.class),
                            any(Integer.class),
                            any(),
                            anyLong(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldReturnZeroWhenWalkReturnsEmpty() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("head123");
            stubCommits(List.of());

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(0);
            verify(commitRepository, never())
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            any(Integer.class),
                            any(Integer.class),
                            any(Integer.class),
                            any(),
                            anyLong(),
                            any(),
                            any(),
                            any(),
                            any());
        }
    }

    @Nested
    class Authentication {

        @Test
        void shouldUseInstallationTokenForGitHubApp() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(tokenService.isConfigured()).thenReturn(true);
            when(tokenService.getInstallationToken(42L)).thenReturn("ghs_install_token");
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("head123");
            stubCommits(List.of());

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            service.backfillCommits(target, repo, 100L);

            verify(gitRepositoryManager)
                    .ensureRepository(
                            new RepositoryKey(100L, 1L), "https://github.com/owner/repo.git", "ghs_install_token");
        }

        @Test
        void shouldUsePATForPersonalAccessTokenAuth() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("head123");
            stubCommits(List.of());

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.PERSONAL_ACCESS_TOKEN);

            service.backfillCommits(target, repo, 100L);

            verify(gitRepositoryManager)
                    .ensureRepository(
                            new RepositoryKey(100L, 1L), "https://github.com/owner/repo.git", "ghp_test_token");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void shouldReturnNegativeOneOnGitOperationException() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            doThrow(new GitRepositoryManager.GitOperationException("Clone failed", new RuntimeException()))
                    .when(gitRepositoryManager)
                    .ensureRepository(any(RepositoryKey.class), anyString(), any());

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        void shouldReturnNegativeOneOnUnexpectedException() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            doThrow(new RuntimeException("Unexpected error"))
                    .when(gitRepositoryManager)
                    .ensureRepository(any(RepositoryKey.class), anyString(), any());

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            assertThat(result).isEqualTo(-1);
        }

        @Test
        void shouldReturnNegativeOneWhenTokenServiceFails() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(tokenService.isConfigured()).thenReturn(true);
            when(tokenService.getInstallationToken(42L)).thenThrow(new RuntimeException("Token error"));
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("head123");
            stubCommits(List.of());

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            int result = service.backfillCommits(target, repo, 100L);

            // A token failure is not fatal: the backfill proceeds with a null token.
            assertThat(result).isEqualTo(0);
            verify(gitRepositoryManager)
                    .ensureRepository(new RepositoryKey(100L, 1L), "https://github.com/owner/repo.git", null);
        }
    }

    @Nested
    class UserResolution {

        @Test
        void shouldResolveUserIdsByEmail() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("head123");

            CommitDetails commitInfo = createCommitInfo("commit1", "Test commit");
            stubCommits(List.of(commitInfo));
            when(commitRepository.existsByShaAndRepositoryId("commit1", 1L)).thenReturn(false);

            when(authorResolver.resolveByEmail(eq("author@test.com"), any())).thenReturn(10L);
            when(authorResolver.resolveByEmail(eq("committer@test.com"), any())).thenReturn(20L);

            Commit mockCommit = createMockCommit("commit1", 1L);
            when(commitRepository.findByShaAndRepositoryId("commit1", 1L)).thenReturn(Optional.of(mockCommit));

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            service.backfillCommits(target, repo, 100L);

            verify(commitRepository)
                    .upsertCommit(
                            eq("commit1"),
                            anyString(),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            any(Integer.class),
                            any(Integer.class),
                            any(Integer.class),
                            any(),
                            eq(1L),
                            eq(10L),
                            eq(20L),
                            any(),
                            any());
        }

        @Test
        void shouldPassNullIdsWhenUsersNotFound() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("head123");

            CommitDetails commitInfo = createCommitInfo("commit1", "Test commit");
            stubCommits(List.of(commitInfo));
            when(commitRepository.existsByShaAndRepositoryId("commit1", 1L)).thenReturn(false);

            when(authorResolver.resolveByEmail(eq("author@test.com"), any())).thenReturn(null);
            when(authorResolver.resolveByEmail(eq("committer@test.com"), any())).thenReturn(null);

            Commit mockCommit = createMockCommit("commit1", 1L);
            when(commitRepository.findByShaAndRepositoryId("commit1", 1L)).thenReturn(Optional.of(mockCommit));

            Repository repo = createMockRepository(1L, "owner/repo", "main");
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            service.backfillCommits(target, repo, 100L);

            verify(commitRepository)
                    .upsertCommit(
                            eq("commit1"),
                            anyString(),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            any(Integer.class),
                            any(Integer.class),
                            any(Integer.class),
                            any(),
                            eq(1L),
                            eq(null),
                            eq(null),
                            any(),
                            any());
        }
    }

    @Nested
    class FileChanges {

        @Test
        void shouldAttachFileChangesToPersistedCommit() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.resolveBranchHead(new RepositoryKey(100L, 1L), "main"))
                    .thenReturn("head123");

            CommitDetails commitInfo = createCommitInfo("commit1", "Test commit");
            stubCommits(List.of(commitInfo));
            when(commitRepository.existsByShaAndRepositoryId("commit1", 1L)).thenReturn(false);

            Repository repo = createMockRepository(1L, "owner/repo", "main");

            Commit persistedCommit = TestEntities.commit(1L, "commit1");
            persistedCommit.setRepository(repo);
            // findByShaAndRepositoryId called twice: once for file changes, once for event
            when(commitRepository.findByShaAndRepositoryId("commit1", 1L)).thenReturn(Optional.of(persistedCommit));
            SyncTarget target = createSyncTarget(AuthMode.INSTALLATION_APP);

            service.backfillCommits(target, repo, 100L);

            // File change attached via the real bidirectional entity wiring.
            assertThat(persistedCommit.getFileChanges()).isNotEmpty();
            verify(commitRepository).save(persistedCommit);
        }
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

class GitHubPushMessageHandlerTest extends BaseUnitTest {

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    @Mock
    private GitHubAppTokenService tokenService;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private CommitAuthorResolver authorResolver;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ScopeIdResolver scopeIdResolver;

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private NatsMessageDeserializer deserializer;

    @Mock
    private TransactionTemplate transactionTemplate;

    private GitHubPushMessageHandler handler;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .when(transactionTemplate.execute(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation
                        .<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0)
                        .doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus()));
        org.mockito.Mockito.lenient()
                .doAnswer(invocation -> {
                    invocation
                            .<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>
                                    getArgument(0)
                            .accept(new org.springframework.transaction.support.SimpleTransactionStatus());
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(org.mockito.ArgumentMatchers.any());

        handler = new GitHubPushMessageHandler(
                gitRepositoryManager,
                tokenService,
                repositoryRepository,
                commitRepository,
                authorResolver,
                eventPublisher,
                scopeIdResolver,
                syncTargetProvider,
                deserializer,
                transactionTemplate);
    }

    /**
     * Invokes the protected handleEvent method via reflection.
     */
    private void invokeHandleEvent(GitHubPushEventDTO event) throws Exception {
        Method handleEvent = GitHubPushMessageHandler.class.getDeclaredMethod("handleEvent", GitHubPushEventDTO.class);
        handleEvent.setAccessible(true);
        handleEvent.invoke(handler, event);
    }

    // Test Data Builders

    private static GitHubRepositoryRefDTO createRepoRef(@Nullable Long id, String fullName) {
        return new GitHubRepositoryRefDTO(
                id, "node_" + id, fullName.split("/")[1], fullName, false, "https://github.com/" + fullName, null);
    }

    private static GitHubPushEventDTO.PushCommit createPushCommit(
            String sha, String message, List<String> added, List<String> modified, List<String> removed) {
        return new GitHubPushEventDTO.PushCommit(
                sha,
                "tree123",
                message,
                Instant.parse("2024-01-15T10:30:00Z"),
                "https://github.com/owner/repo/commit/" + sha,
                new GitHubPushEventDTO.CommitUser("Author", "author@test.com", "authoruser"),
                new GitHubPushEventDTO.CommitUser("Committer", "committer@test.com", "committeruser"),
                added,
                removed,
                modified,
                true);
    }

    private static GitHubPushEventDTO createBasicPushEvent(
            String ref, boolean deleted, List<GitHubPushEventDTO.PushCommit> commits) {
        return new GitHubPushEventDTO(
                ref,
                "abc123",
                "def456",
                false,
                deleted,
                false,
                "https://github.com/owner/repo/compare/abc123...def456",
                commits,
                commits != null && !commits.isEmpty() ? commits.get(commits.size() - 1) : null,
                createRepoRef(100L, "owner/repo"),
                new GitHubPushEventDTO.Pusher("pusher", "pusher@test.com"),
                new GitHubPushEventDTO.Sender(1L, "pusheruser"),
                new GitHubPushEventDTO.InstallationRef(42L, "node123"));
    }

    private Repository createMockRepository(Long id, String nameWithOwner, String defaultBranch) {
        Repository repo = TestEntities.repository(id, nameWithOwner, defaultBranch);
        repo.setOrganization(null);
        repo.setProvider(TestEntities.gitProvider(1L, IdentityProviderType.GITHUB));
        return repo;
    }

    /**
     * Sets up scope resolution mocks so that the handler considers the scope active.
     * Required for tests that exercise the local git processing path.
     */
    private void mockActiveScopeForRepo(String nameWithOwner) {
        when(scopeIdResolver.findScopeIdByRepositoryName(nameWithOwner)).thenReturn(Optional.of(1L));
        when(syncTargetProvider.isScopeActiveForSync(1L)).thenReturn(true);
    }

    @Nested
    class HandlerKey {

        @Test
        @DisplayName("should bind to repository.push under the unified registry")
        void shouldReturnPushEventType() {
            assertThat(handler.key().eventType()).isEqualTo("repository.push");
            assertThat(handler.key().kind()).isEqualTo(IntegrationKind.GITHUB);
        }
    }

    @Nested
    class SkipConditions {

        @Test
        void shouldSkipBranchDeletionEvents() throws Exception {
            var event = createBasicPushEvent("refs/heads/feature", true, List.of());

            invokeHandleEvent(event);

            verify(repositoryRepository, never()).findByIdWithOrganization(anyLong());
            verify(commitRepository, never())
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
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
        void shouldSkipEventsWithNullCommits() throws Exception {
            var event = new GitHubPushEventDTO(
                    "refs/heads/main",
                    "abc123",
                    "def456",
                    false,
                    false,
                    false,
                    "https://github.com/owner/repo/compare/abc123...def456",
                    null,
                    null,
                    createRepoRef(100L, "owner/repo"),
                    new GitHubPushEventDTO.Pusher("pusher", "pusher@test.com"),
                    null,
                    null);

            invokeHandleEvent(event);

            verify(repositoryRepository, never()).findByIdWithOrganization(anyLong());
        }

        @Test
        void shouldSkipEventsWithEmptyCommitsList() throws Exception {
            var event = createBasicPushEvent("refs/heads/main", false, List.of());

            invokeHandleEvent(event);

            verify(repositoryRepository, never()).findByIdWithOrganization(anyLong());
        }

        @Test
        void shouldSkipWhenRepositoryNotFoundInDatabase() throws Exception {
            var commit = createPushCommit("sha1", "message", List.of("file.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.empty());

            invokeHandleEvent(event);

            verify(commitRepository, never())
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
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
        void shouldSkipWhenRepositoryRefHasNullId() throws Exception {
            var commit = createPushCommit("sha1", "message", List.of("file.txt"), List.of(), List.of());
            var event = new GitHubPushEventDTO(
                    "refs/heads/main",
                    "abc123",
                    "def456",
                    false,
                    false,
                    false,
                    null,
                    List.of(commit),
                    commit,
                    createRepoRef(null, "owner/repo"),
                    new GitHubPushEventDTO.Pusher("pusher", "pusher@test.com"),
                    null,
                    null);

            invokeHandleEvent(event);

            verify(repositoryRepository, never()).findByIdWithOrganization(anyLong());
        }

        @Test
        void shouldSkipWhenPushIsNotToDefaultBranch() throws Exception {
            var commit = createPushCommit("sha1", "message", List.of("file.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/feature-branch", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));

            invokeHandleEvent(event);

            verify(commitRepository, never())
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
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
    class WebhookProcessing {

        @Test
        void shouldProcessCommitsViaWebhookWhenGitIsDisabled() throws Exception {
            var commit = createPushCommit(
                    "abc123def456789012345678901234567890abcd",
                    "feat: add feature",
                    List.of("newfile.txt"),
                    List.of("existing.txt"),
                    List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            invokeHandleEvent(event);

            verify(commitRepository)
                    .upsertCommit(
                            eq("abc123def456789012345678901234567890abcd"),
                            eq("feat: add feature"),
                            any(), // messageBody
                            eq("https://github.com/owner/repo/commit/abc123def456789012345678901234567890abcd"),
                            any(Instant.class), // authoredAt
                            any(Instant.class), // committedAt
                            eq(0), // additions (not available from webhook)
                            eq(0), // deletions (not available from webhook)
                            eq(2), // changedFiles = 1 added + 1 modified
                            any(Instant.class), // lastSyncAt
                            eq(100L),
                            any(), // authorId
                            any(), // committerId
                            any(), // authorEmail
                            any() // committerEmail
                            );
        }

        @Test
        void shouldProcessMultipleCommits() throws Exception {
            var commit1 = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "first", List.of("f1.txt"), List.of(), List.of());
            var commit2 = createPushCommit(
                    "sha2aabbccdd112233445566778899aabbccddeeff", "second", List.of(), List.of("f1.txt"), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit1, commit2));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            invokeHandleEvent(event);

            verify(commitRepository, times(2))
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(Integer.class),
                            any(Integer.class),
                            any(Integer.class),
                            any(),
                            eq(100L),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldResolveAuthorByUsername() throws Exception {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of(), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            when(authorResolver.resolveByLogin(eq("authoruser"), any())).thenReturn(42L);
            when(authorResolver.resolveByLogin(eq("committeruser"), any())).thenReturn(43L);

            invokeHandleEvent(event);

            verify(commitRepository)
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(Integer.class),
                            any(Integer.class),
                            any(Integer.class),
                            any(),
                            eq(100L),
                            eq(42L),
                            eq(43L),
                            any(),
                            any());
        }

        @Test
        void shouldHandleCommitsWithNullAuthorUsername() throws Exception {
            var commit = new GitHubPushEventDTO.PushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff",
                    "tree123",
                    "message",
                    Instant.parse("2024-01-15T10:30:00Z"),
                    "https://github.com/owner/repo/commit/sha1",
                    new GitHubPushEventDTO.CommitUser("Author", "author@test.com", null),
                    null, // null committer
                    List.of("file.txt"),
                    List.of(),
                    List.of(),
                    true);
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);
            when(authorResolver.resolveByLogin(eq(null), any())).thenReturn(null);

            invokeHandleEvent(event);

            verify(commitRepository)
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(Integer.class),
                            any(Integer.class),
                            any(Integer.class),
                            any(),
                            eq(100L),
                            eq(null),
                            eq(null),
                            any(),
                            any());
        }

        @Test
        void shouldCountChangedFilesCorrectly() throws Exception {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff",
                    "changes",
                    List.of("new1.txt", "new2.txt"), // 2 added
                    List.of("mod1.txt"), // 1 modified
                    List.of("del1.txt", "del2.txt", "del3.txt") // 3 removed
                    );
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            invokeHandleEvent(event);

            verify(commitRepository)
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(0),
                            eq(0),
                            eq(6), // 2 + 1 + 3
                            any(),
                            eq(100L),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldExtractMessageHeadlineAndBodyCorrectly() throws Exception {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff",
                    "feat: add feature\n\nThis is the body.\nWith multiple lines.",
                    List.of("file.txt"),
                    List.of(),
                    List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            invokeHandleEvent(event);

            verify(commitRepository)
                    .upsertCommit(
                            anyString(),
                            eq("feat: add feature"),
                            eq("This is the body.\nWith multiple lines."),
                            any(),
                            any(),
                            any(),
                            any(Integer.class),
                            any(Integer.class),
                            any(Integer.class),
                            any(),
                            eq(100L),
                            any(),
                            any(),
                            any(),
                            any());
        }
    }

    @Nested
    class LocalGitProcessing {

        @Test
        void shouldUseLocalGitWhenEnabled() throws Exception {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of(), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            mockActiveScopeForRepo("owner/repo");
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(tokenService.isConfigured()).thenReturn(true);
            when(tokenService.getInstallationToken(42L)).thenReturn("test-token");

            invokeHandleEvent(event);

            var key = new de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey(
                    1, 100);
            verify(gitRepositoryManager).ensureRepository(key, "https://github.com/owner/repo.git", "test-token");
            verify(gitRepositoryManager).forEachCommitInRange(eq(key), eq("abc123"), eq("def456"), any());
        }

        @Test
        void shouldFallBackToWebhookOnGitFailure() throws Exception {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of("file.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            mockActiveScopeForRepo("owner/repo");
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(tokenService.isConfigured()).thenReturn(false);
            org.mockito.Mockito.doThrow(new RuntimeException("Git clone failed"))
                    .when(gitRepositoryManager)
                    .ensureRepository(
                            any(
                                    de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor
                                            .RepositoryKey.class),
                            any(),
                            any());

            invokeHandleEvent(event);

            // Should fall back to webhook processing with null stats (preserves existing data)
            verify(commitRepository)
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(null), // additions: null on fallback to preserve richer data
                            eq(null), // deletions: null on fallback to preserve richer data
                            eq(null), // changedFiles: null on fallback to preserve richer data
                            any(),
                            eq(100L),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldProcessCommitInfoFromLocalGitWithFileChanges() throws Exception {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of(), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            mockActiveScopeForRepo("owner/repo");
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(tokenService.isConfigured()).thenReturn(false);

            var fileChange =
                    new CommitDetails.FileChange("src/main.java", CommitFileChange.ChangeType.ADDED, 10, 0, 10, null);
            var commitInfo = new CommitDetails(
                    "sha1aabbccdd112233445566778899aabbccddeeff",
                    "msg",
                    null,
                    "Author",
                    "author@test.com",
                    Instant.parse("2024-01-15T10:30:00Z"),
                    "Committer",
                    "committer@test.com",
                    Instant.parse("2024-01-15T10:30:00Z"),
                    10,
                    0,
                    1,
                    List.of(fileChange),
                    List.of());
            doAnswer(invocation -> {
                        java.util.function.Consumer<CommitDetails> consumer = invocation.getArgument(3);
                        consumer.accept(commitInfo);
                        return null;
                    })
                    .when(gitRepositoryManager)
                    .forEachCommitInRange(any(), any(), any(), any());
            when(commitRepository.existsByShaAndRepositoryIdAndGitDetailsCapturedAtIsNotNull(
                            "sha1aabbccdd112233445566778899aabbccddeeff", 100L))
                    .thenReturn(false);

            // After upsertCommit, findByShaAndRepositoryId must return a Commit entity for file changes
            // and for publishCommitCreated (which calls CommitData.from(commit))
            var persistedCommit = TestEntities.commit(1L, "sha1aabbccdd112233445566778899aabbccddeeff");
            persistedCommit.setMessage("msg");
            persistedCommit.setAuthoredAt(Instant.parse("2024-01-15T10:30:00Z"));
            persistedCommit.setRepository(repo);
            when(commitRepository.findByShaAndRepositoryId("sha1aabbccdd112233445566778899aabbccddeeff", 100L))
                    .thenReturn(Optional.of(persistedCommit));

            invokeHandleEvent(event);

            // Should upsert the commit via native SQL
            verify(commitRepository)
                    .upsertCommit(
                            eq("sha1aabbccdd112233445566778899aabbccddeeff"),
                            eq("msg"),
                            any(),
                            anyString(),
                            any(),
                            any(),
                            eq(10),
                            eq(0),
                            eq(1),
                            any(),
                            eq(100L),
                            any(),
                            any(),
                            any(),
                            any());
            // Should fetch the persisted commit: once for file changes, once for publishCommitCreated
            verify(commitRepository, times(2))
                    .findByShaAndRepositoryId("sha1aabbccdd112233445566778899aabbccddeeff", 100L);
            verify(commitRepository).save(persistedCommit);
        }

        @Test
        void shouldSkipExistingCommitsInLocalGitMode() throws Exception {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of(), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            mockActiveScopeForRepo("owner/repo");
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(tokenService.isConfigured()).thenReturn(false);

            var commitInfo = new CommitDetails(
                    "sha1aabbccdd112233445566778899aabbccddeeff",
                    "msg",
                    null,
                    "Author",
                    "author@test.com",
                    Instant.now(),
                    "Committer",
                    "committer@test.com",
                    Instant.now(),
                    0,
                    0,
                    0,
                    List.of(),
                    List.of());
            doAnswer(invocation -> {
                        java.util.function.Consumer<CommitDetails> consumer = invocation.getArgument(3);
                        consumer.accept(commitInfo);
                        return null;
                    })
                    .when(gitRepositoryManager)
                    .forEachCommitInRange(any(), any(), any(), any());
            when(commitRepository.existsByShaAndRepositoryIdAndGitDetailsCapturedAtIsNotNull(
                            "sha1aabbccdd112233445566778899aabbccddeeff", 100L))
                    .thenReturn(true);

            invokeHandleEvent(event);

            verify(commitRepository, never())
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            anyLong(),
                            any(),
                            any(),
                            any(),
                            any());
            verify(commitRepository, never()).save(any());
        }

        @Test
        void shouldFallBackToWebhookWhenScopeNotActive() throws Exception {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of("file.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            // Scope not active: scopeIdResolver returns a scope, but it's inactive
            when(scopeIdResolver.findScopeIdByRepositoryName("owner/repo")).thenReturn(Optional.of(99L));
            when(syncTargetProvider.isScopeActiveForSync(99L)).thenReturn(false);

            invokeHandleEvent(event);

            // Should NOT use local git
            verify(gitRepositoryManager, never())
                    .ensureRepository(
                            any(
                                    de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor
                                            .RepositoryKey.class),
                            anyString(),
                            any());
            verify(gitRepositoryManager, never())
                    .forEachCommitInRange(
                            any(
                                    de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor
                                            .RepositoryKey.class),
                            any(),
                            any(),
                            any());

            // Should process via webhook instead (non-fallback: additions=0, not null)
            verify(commitRepository)
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(0),
                            eq(0),
                            eq(1), // 1 added file
                            any(),
                            eq(100L),
                            any(),
                            any(),
                            any(),
                            any());
        }
    }

    @Nested
    class BranchHandling {

        @Test
        void shouldProcessPushesToDefaultBranch() throws Exception {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of("f.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/develop", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "develop");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            invokeHandleEvent(event);

            verify(commitRepository)
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(Integer.class),
                            any(Integer.class),
                            any(Integer.class),
                            any(),
                            eq(100L),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldHandleRefsWithoutPrefix() throws Exception {
            // Edge case: ref doesn't start with "refs/heads/"
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of("f.txt"), List.of(), List.of());
            var event = createBasicPushEvent("main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            invokeHandleEvent(event);

            verify(commitRepository)
                    .upsertCommit(
                            anyString(),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(Integer.class),
                            any(Integer.class),
                            any(Integer.class),
                            any(),
                            eq(100L),
                            any(),
                            any(),
                            any(),
                            any());
        }
    }

    @Nested
    class DtoContract {

        @Test
        void actionShouldReturnPushed() {
            var event = createBasicPushEvent("refs/heads/main", false, List.of());
            assertThat(event.action()).isEqualTo("pushed");
        }

        @Test
        void actionTypeShouldReturnPushed() {
            var event = createBasicPushEvent("refs/heads/main", false, List.of());
            assertThat(event.actionType()).isEqualTo(GitHubEventAction.Push.PUSHED);
        }
    }

    @Nested
    class EventPublishing {

        @Test
        void shouldPublishCommitCreatedEventAfterWebhookProcessing() throws Exception {
            var commit = createPushCommit(
                    "abc123def456789012345678901234567890abcd",
                    "feat: publish test",
                    List.of("file.txt"),
                    List.of(),
                    List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            // Mock the persisted commit lookup for publishCommitCreated
            var persistedCommit = TestEntities.commit(1L, "abc123def456789012345678901234567890abcd");
            persistedCommit.setMessage("feat: publish test");
            persistedCommit.setAuthoredAt(Instant.parse("2024-01-15T10:30:00Z"));
            persistedCommit.setRepository(repo);
            when(commitRepository.findByShaAndRepositoryId("abc123def456789012345678901234567890abcd", 100L))
                    .thenReturn(Optional.of(persistedCommit));

            invokeHandleEvent(event);

            // Verify event was published
            ArgumentCaptor<ScmDomainEvent.CommitCreated> captor =
                    ArgumentCaptor.forClass(ScmDomainEvent.CommitCreated.class);
            verify(eventPublisher).publishEvent(captor.capture());

            ScmDomainEvent.CommitCreated published = captor.getValue();
            assertThat(published.commit().sha()).isEqualTo("abc123def456789012345678901234567890abcd");
            assertThat(published.commit().message()).isEqualTo("feat: publish test");
            assertThat(published.commit().repositoryId()).isEqualTo(100L);
        }

        @Test
        void shouldNotPublishEventWhenCommitNotFoundAfterUpsert() throws Exception {
            var commit = createPushCommit(
                    "abc123def456789012345678901234567890abcd", "msg", List.of("file.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            // findByShaAndRepositoryId returns empty — commit not found after upsert
            when(commitRepository.findByShaAndRepositoryId("abc123def456789012345678901234567890abcd", 100L))
                    .thenReturn(Optional.empty());

            invokeHandleEvent(event);

            // Verify event was NOT published
            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.CommitCreated.class));
        }
    }
}

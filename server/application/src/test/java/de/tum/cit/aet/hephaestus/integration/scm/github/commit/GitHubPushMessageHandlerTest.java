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
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister.Outcome;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.PassThroughTransactionTemplate;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

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
    private CommitDetailsPersister persister;

    private GitHubPushMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GitHubPushMessageHandler(
                gitRepositoryManager,
                tokenService,
                repositoryRepository,
                commitRepository,
                persister,
                authorResolver,
                eventPublisher,
                scopeIdResolver,
                syncTargetProvider,
                deserializer,
                new PassThroughTransactionTemplate());
    }

    /** Feeds the walk the way native Git does: ask which shas are captured, then hand over each commit. */
    private void stubCommitRange(List<CommitDetails> commits) {
        doAnswer(invocation -> {
                    Function<List<String>, Set<String>> captured = invocation.getArgument(3);
                    captured.apply(commits.stream().map(CommitDetails::sha).toList());
                    Consumer<CommitDetails> consumer = invocation.getArgument(4);
                    commits.forEach(consumer);
                    return null;
                })
                .when(gitRepositoryManager)
                .forEachCommitInRange(any(), any(), any(), any(), any());
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

    private static CommitDetails createCommitInfo(String sha) {
        return new CommitDetails(
                sha,
                "msg",
                null,
                "Author",
                "author@test.com",
                Instant.parse("2024-01-15T10:30:00Z"),
                "Committer",
                "committer@test.com",
                Instant.parse("2024-01-15T10:30:00Z"),
                0,
                0,
                0,
                List.of(),
                List.of());
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
        void shouldSkipBranchDeletionEvents() {
            var event = createBasicPushEvent("refs/heads/feature", true, List.of());

            handler.handleEvent(event);

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
                            any(),
                            any());
        }

        @Test
        void shouldSkipEventsWithNullCommits() {
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

            handler.handleEvent(event);

            verify(repositoryRepository, never()).findByIdWithOrganization(anyLong());
        }

        @Test
        void shouldSkipEventsWithEmptyCommitsList() {
            var event = createBasicPushEvent("refs/heads/main", false, List.of());

            handler.handleEvent(event);

            verify(repositoryRepository, never()).findByIdWithOrganization(anyLong());
        }

        @Test
        void shouldSkipWhenRepositoryNotFoundInDatabase() {
            var commit = createPushCommit("sha1", "message", List.of("file.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.empty());

            handler.handleEvent(event);

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
                            any(),
                            any());
        }

        @Test
        void shouldSkipWhenRepositoryRefHasNullId() {
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

            handler.handleEvent(event);

            verify(repositoryRepository, never()).findByIdWithOrganization(anyLong());
        }

        @Test
        void shouldSkipWhenPushIsNotToDefaultBranch() {
            var commit = createPushCommit("sha1", "message", List.of("file.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/feature-branch", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));

            handler.handleEvent(event);

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
                            any(),
                            any());
        }
    }

    @Nested
    class WebhookProcessing {

        @Test
        void shouldProcessCommitsViaWebhookWhenGitIsDisabled() {
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

            handler.handleEvent(event);

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
                            ,
                            any());
        }

        @Test
        void shouldProcessMultipleCommits() {
            var commit1 = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "first", List.of("f1.txt"), List.of(), List.of());
            var commit2 = createPushCommit(
                    "sha2aabbccdd112233445566778899aabbccddeeff", "second", List.of(), List.of("f1.txt"), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit1, commit2));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            handler.handleEvent(event);

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
                            any(),
                            any());
        }

        @Test
        void shouldResolveAuthorByUsername() {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of(), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            when(authorResolver.resolveByLogin(eq("authoruser"), any())).thenReturn(42L);
            when(authorResolver.resolveByLogin(eq("committeruser"), any())).thenReturn(43L);

            handler.handleEvent(event);

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
                            any(),
                            any());
        }

        @Test
        void shouldHandleCommitsWithNullAuthorUsername() {
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

            handler.handleEvent(event);

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
                            any(),
                            any());
        }

        @Test
        void shouldCountChangedFilesCorrectly() {
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

            handler.handleEvent(event);

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
                            any(),
                            any());
        }

        @Test
        void shouldExtractMessageHeadlineAndBodyCorrectly() {
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

            handler.handleEvent(event);

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
                            any(),
                            any());
        }
    }

    @Nested
    class LocalGitProcessing {

        @Test
        void shouldUseLocalGitWhenEnabled() {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of(), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            mockActiveScopeForRepo("owner/repo");
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(tokenService.isConfigured()).thenReturn(true);
            when(tokenService.getInstallationToken(42L)).thenReturn("test-token");

            handler.handleEvent(event);

            var key = new de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey(
                    1, 100);
            verify(gitRepositoryManager).ensureRepository(key, "https://github.com/owner/repo.git", "test-token");
            verify(gitRepositoryManager).forEachCommitInRange(eq(key), eq("abc123"), eq("def456"), any(), any());
        }

        @Test
        void shouldFallBackToWebhookOnGitFailure() {
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

            handler.handleEvent(event);

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
                            any(),
                            any());
        }

        @Test
        void shouldHandEachCapturedCommitToThePersisterWithoutTouchingTheWebhookPath() {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of(), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            mockActiveScopeForRepo("owner/repo");
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(tokenService.isConfigured()).thenReturn(false);
            when(persister.persist(any(), any(), any())).thenReturn(Outcome.CAPTURED);
            CommitDetails commitInfo = createCommitInfo("sha1aabbccdd112233445566778899aabbccddeeff");
            stubCommitRange(List.of(commitInfo));

            handler.handleEvent(event);

            verify(persister).persist(eq(commitInfo), eq(repo), any());
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
                            any(),
                            any());
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        void shouldAskTheRepositoryWhichShasAreCapturedWhenWalking() {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of(), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            mockActiveScopeForRepo("owner/repo");
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(tokenService.isConfigured()).thenReturn(false);
            when(persister.persist(any(), any(), any())).thenReturn(Outcome.CAPTURED);
            stubCommitRange(List.of(createCommitInfo("first"), createCommitInfo("second")));

            handler.handleEvent(event);

            verify(commitRepository).findGitDetailsCapturedShas(100L, List.of("first", "second"));
        }

        @Test
        void shouldHandAWebhookOriginToThePersisterWhenWalking() {
            when(persister.persist(any(), any(), any())).thenReturn(Outcome.CAPTURED);
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of(), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            mockActiveScopeForRepo("owner/repo");
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(tokenService.isConfigured()).thenReturn(false);
            when(authorResolver.resolveByEmail("author@test.com", 1L)).thenReturn(42L);
            CommitDetails commitInfo = createCommitInfo("sha1aabbccdd112233445566778899aabbccddeeff");
            stubCommitRange(List.of(commitInfo));

            handler.handleEvent(event);

            ArgumentCaptor<CommitDetailsPersister.Origin> origin =
                    ArgumentCaptor.forClass(CommitDetailsPersister.Origin.class);
            verify(persister).persist(eq(commitInfo), eq(repo), origin.capture());
            assertThat(origin.getValue().scopeId()).isEqualTo(1L);
            assertThat(origin.getValue().dataSource()).isEqualTo(DataSource.WEBHOOK);
            assertThat(origin.getValue().provider()).isEqualTo(IdentityProviderType.GITHUB);
            assertThat(origin.getValue().commitUrl().apply("abc"))
                    .isEqualTo("https://github.com/owner/repo/commit/abc");
            assertThat(origin.getValue().userIdByEmail().apply("author@test.com"))
                    .isEqualTo(42L);
        }

        @Test
        void shouldFallBackToWebhookWithNullStatsWhenOneCommitCaptureFails() {
            when(persister.persist(any(), any(), any()))
                    .thenAnswer(invocation -> "broken"
                                    .equals(invocation
                                            .<CommitDetails>getArgument(0)
                                            .sha())
                            ? Outcome.FAILED
                            : Outcome.CAPTURED);
            var commit = createPushCommit("broken", "msg", List.of("file.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            mockActiveScopeForRepo("owner/repo");
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(tokenService.isConfigured()).thenReturn(false);
            stubCommitRange(List.of(createCommitInfo("fine"), createCommitInfo("broken")));

            handler.handleEvent(event);

            verify(commitRepository)
                    .upsertCommit(
                            eq("broken"),
                            anyString(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(null),
                            eq(null),
                            eq(null),
                            any(),
                            eq(100L),
                            any(),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldFallBackToWebhookWhenScopeNotActive() {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of("file.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            // Scope not active: scopeIdResolver returns a scope, but it's inactive
            when(scopeIdResolver.findScopeIdByRepositoryName("owner/repo")).thenReturn(Optional.of(99L));
            when(syncTargetProvider.isScopeActiveForSync(99L)).thenReturn(false);

            handler.handleEvent(event);

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
                            any(),
                            any());
        }
    }

    @Nested
    class BranchHandling {

        @Test
        void shouldProcessPushesToDefaultBranch() {
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of("f.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/develop", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "develop");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            handler.handleEvent(event);

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
                            any(),
                            any());
        }

        @Test
        void shouldHandleRefsWithoutPrefix() {
            // Edge case: ref doesn't start with "refs/heads/"
            var commit = createPushCommit(
                    "sha1aabbccdd112233445566778899aabbccddeeff", "msg", List.of("f.txt"), List.of(), List.of());
            var event = createBasicPushEvent("main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            handler.handleEvent(event);

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
        void shouldPublishCommitCreatedEventAfterWebhookProcessing() {
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

            handler.handleEvent(event);

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
        void shouldNotPublishEventWhenCommitNotFoundAfterUpsert() {
            var commit = createPushCommit(
                    "abc123def456789012345678901234567890abcd", "msg", List.of("file.txt"), List.of(), List.of());
            var event = createBasicPushEvent("refs/heads/main", false, List.of(commit));

            Repository repo = createMockRepository(100L, "owner/repo", "main");
            when(repositoryRepository.findByIdWithOrganization(100L)).thenReturn(Optional.of(repo));
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            // findByShaAndRepositoryId returns empty — commit not found after upsert
            when(commitRepository.findByShaAndRepositoryId("abc123def456789012345678901234567890abcd", 100L))
                    .thenReturn(Optional.empty());

            handler.handleEvent(event);

            // Verify event was NOT published
            verify(eventPublisher, never()).publishEvent(any(ScmDomainEvent.CommitCreated.class));
        }
    }
}

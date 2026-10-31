package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister.Outcome;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit.GitLabCommitMergeRequestLinker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabAdmissionTestFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.PassThroughTransactionTemplate;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import io.nats.client.Message;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

@Tag("unit")
class GitLabPushMessageHandlerTest extends BaseUnitTest {

    private final GitLabProjectAdmission projectAdmission = mock(GitLabProjectAdmission.class);
    private final GitLabRouteAdmission routeAdmission = mock(GitLabRouteAdmission.class);

    private static final Long PROVIDER_ID = 2L;
    private static final String DEFAULT_SERVER_URL = "https://gitlab.lrz.de";
    private static final GitLabRouteAdmission.AdmittedRoute ROUTE =
            new GitLabRouteAdmission.AdmittedRoute(1L, 7L, PROVIDER_ID, 42L, "org");

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private CommitDetailsPersister persister;

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    @Mock
    private GitLabTokenService tokenService;

    @Mock
    private CommitAuthorResolver authorResolver;

    @Mock
    private SyncTargetProvider syncTargetProvider;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private GitLabCommitMergeRequestLinker commitMergeRequestLinker;

    @Mock
    private NatsMessageDeserializer deserializer;

    private GitLabPushMessageHandler handler;
    private IdentityProvider gitLabProvider;

    @BeforeEach
    void setUp() {
        gitLabProvider = new IdentityProvider();
        gitLabProvider.setId(PROVIDER_ID);
        gitLabProvider.setType(IdentityProviderType.GITLAB);
        gitLabProvider.setServerUrl(DEFAULT_SERVER_URL);

        handler = new GitLabPushMessageHandler(
                repositoryRepository,
                commitRepository,
                persister,
                gitRepositoryManager,
                tokenService,
                authorResolver,
                syncTargetProvider,
                eventPublisher,
                commitMergeRequestLinker,
                routeAdmission,
                deserializer,
                new PassThroughTransactionTemplate(),
                projectAdmission);
    }

    @Test
    void key_returnsPush() {
        assertThat(handler.key().eventType()).isEqualTo("push");
    }

    @Test
    void shouldWriteNothingWithoutAnAdmittedRoute() throws IOException {
        var projectInfo = createProjectInfo(42L, "org/proj");
        Repository repo = activeLocalGitRepository(projectInfo);

        handler.onMessage(mockMessage("gitlab.?connection.1.push", createPushEvent(projectInfo)));

        verify(repositoryRepository, never()).findByNameWithOwnerAndProviderId(anyString(), any());
        verify(commitMergeRequestLinker, never()).linkCommits(any(), eq(repo), any(), any());
    }

    @Test
    void shouldSkipAProjectOutsideTheConnectedGroup() throws IOException {
        var projectInfo = createProjectInfo(42L, "other/proj");

        onRoute(mockMessage("gitlab.?connection.1.push", createPushEvent(projectInfo)));

        verify(repositoryRepository, never()).findByNameWithOwnerAndProviderId(anyString(), any());
    }

    @Test
    void shouldSkipAProjectAdmissionDoesNotAdmit() throws IOException {
        var projectInfo = createProjectInfo(42L, "org/proj");
        Repository repo = activeLocalGitRepository(projectInfo);
        when(routeAdmission.admitRepository(ROUTE, repo)).thenReturn(false);

        onRoute(mockMessage("gitlab.?connection.1.push", createPushEvent(projectInfo)));

        verify(commitMergeRequestLinker, never()).linkCommits(any(), eq(repo), any(), any());
    }

    @Test
    void branchDeletion_skipsProcessing() throws IOException {
        var projectInfo = new GitLabPushEventDTO.ProjectInfo(
                1L, "proj", null, "https://gitlab.com/org/proj", null, "org/proj", "main", 0);
        var pushEvent = new GitLabPushEventDTO(
                "push",
                "refs/heads/feature-branch",
                "abc123",
                "0000000000000000000000000000000000000000", // branch deletion
                null,
                1L,
                projectInfo,
                0,
                null);

        onRoute(mockMessage("gitlab.?connection.1.push", pushEvent));

        verify(repositoryRepository, never()).findByNameWithOwnerAndProviderId(anyString(), any());
    }

    @Test
    void nullProject_skipsProcessing() throws IOException {
        var pushEvent = new GitLabPushEventDTO(
                "push",
                "refs/heads/main",
                "before",
                "after",
                "after",
                null,
                null, // null project
                0,
                null);

        onRoute(mockMessage("gitlab.?connection.1.push", pushEvent));

        verify(repositoryRepository, never()).findByNameWithOwnerAndProviderId(anyString(), any());
    }

    @Test
    void batchedLinker_runsOncePerPush() throws IOException {
        var projectInfo = createProjectInfo(42L, "org/proj");
        Repository repo = new Repository();
        repo.setId(42L);
        repo.setNameWithOwner("org/proj");
        var originalRead = permitPush(projectInfo, repo);

        var pushEvent = new GitLabPushEventDTO(
                "push",
                "refs/heads/main",
                "before",
                "after",
                "after",
                42L,
                projectInfo,
                3, // three commits in push
                null);

        onRoute(mockMessage("gitlab.?connection.1.push", pushEvent));

        // ONE batched GraphQL call, not one per commit.
        verify(commitMergeRequestLinker, times(1)).linkCommits(eq(7L), eq(repo), any(), eq(originalRead));
    }

    @Test
    void shouldFallBackToWebhookWithNullStatsWhenOneCommitCaptureFails() throws IOException {
        var projectInfo = createProjectInfo(42L, "org/proj");
        Repository repo = activeLocalGitRepository(projectInfo);
        when(tokenService.resolveServerUrl(7L)).thenReturn(DEFAULT_SERVER_URL);
        when(tokenService.getAccessToken(7L)).thenReturn("glpat-token");
        doAnswer(invocation -> {
                    Consumer<CommitDetails> consumer = invocation.getArgument(4);
                    consumer.accept(capturedCommit("fine"));
                    consumer.accept(capturedCommit("broken"));
                    return null;
                })
                .when(gitRepositoryManager)
                .forEachCommitInRange(eq(new RepositoryKey(7L, 42L)), eq("before"), eq("after"), any(), any());
        when(persister.persist(any(), eq(repo), any()))
                .thenAnswer(invocation ->
                        "broken".equals(invocation.<CommitDetails>getArgument(0).sha())
                                ? Outcome.FAILED
                                : Outcome.CAPTURED);
        var pushEvent = new GitLabPushEventDTO(
                "push",
                "refs/heads/main",
                "before",
                "after",
                "after",
                42L,
                projectInfo,
                1,
                List.of(webhookCommit("broken")));

        onRoute(mockMessage("gitlab.?connection.1.push", pushEvent));

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
                        eq(42L),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void shouldPersistWebhookCommitsWhenLocalGitHasNoAfterSha() throws IOException {
        var projectInfo = createProjectInfo(42L, "org/proj");
        activeLocalGitRepository(projectInfo);
        var pushEvent = new GitLabPushEventDTO(
                "push", "refs/heads/main", "before", null, null, 42L, projectInfo, 1, List.of(webhookCommit("sha1")));

        onRoute(mockMessage("gitlab.?connection.1.push", pushEvent));

        verify(gitRepositoryManager, never()).forEachCommitInRange(any(), any(), any(), any(), any());
        verify(commitRepository)
                .upsertCommit(
                        eq("sha1"),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(null),
                        eq(null),
                        eq(null),
                        any(),
                        eq(42L),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void nonPushSubject_rejected() throws IOException {
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn("gitlab.org.proj.merge_request");

        handler.onMessage(msg);

        verify(deserializer, never()).deserialize(any(), any());
    }

    // Helpers

    private GitLabPushEventDTO.ProjectInfo createProjectInfo(Long id, String pathWithNamespace) {
        return new GitLabPushEventDTO.ProjectInfo(
                id, "proj", null, "https://gitlab.com/" + pathWithNamespace, null, pathWithNamespace, "main", 0);
    }

    private GitLabPushEventDTO createPushEvent(GitLabPushEventDTO.ProjectInfo projectInfo) {
        return new GitLabPushEventDTO(
                "push", "refs/heads/main", "before", "after", "after", projectInfo.id(), projectInfo, 1, null);
    }

    private GitLabProjectAdmission.ProjectRead permitPush(
            GitLabPushEventDTO.ProjectInfo projectInfo, Repository repository) {
        String path = Objects.requireNonNull(projectInfo.pathWithNamespace());
        repository.setNameWithOwner(path);
        repository.setNativeId(Objects.requireNonNull(projectInfo.id()));
        repository.setProvider(gitLabProvider);
        lenient()
                .when(repositoryRepository.findByNameWithOwnerAndProviderId(path, PROVIDER_ID))
                .thenReturn(Optional.of(repository));
        lenient()
                .when(repositoryRepository.findByIdWithOrganization(repository.getId()))
                .thenReturn(Optional.of(repository));
        lenient().when(routeAdmission.admitRepository(ROUTE, repository)).thenReturn(true);
        lenient().when(routeAdmission.holdActive(ROUTE)).thenReturn(true);
        return GitLabAdmissionTestFixtures.permit(projectAdmission, 7L, repository, 7L);
    }

    /** A repository in an active scope with local git enabled: the handler walks default-branch pushes. */
    private Repository activeLocalGitRepository(GitLabPushEventDTO.ProjectInfo projectInfo) {
        Repository repo = TestEntities.repository(42L, "org/proj", "main");
        permitPush(projectInfo, repo);
        lenient().when(syncTargetProvider.isScopeActiveForSync(7L)).thenReturn(true);
        lenient().when(gitRepositoryManager.isEnabled()).thenReturn(true);
        return repo;
    }

    /** Delivers {@code msg} as admission would, on the connection route of the group {@code org}. */
    private void onRoute(Message msg) {
        GitLabRouteTestFixtures.handle(ROUTE, Map.of(), null, () -> handler.onMessage(msg));
    }

    private static CommitDetails capturedCommit(String sha) {
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
                List.of(),
                "msg");
    }

    private static GitLabPushEventDTO.CommitInfo webhookCommit(String sha) {
        return new GitLabPushEventDTO.CommitInfo(
                sha,
                "msg",
                "msg",
                "2024-01-15T10:30:00Z",
                "https://gitlab.lrz.de/org/proj/-/commit/" + sha,
                new GitLabPushEventDTO.AuthorInfo("Author", "author@test.com"),
                List.of("file.txt"),
                List.of(),
                List.of());
    }

    private Message mockMessage(String subject, GitLabPushEventDTO event) throws IOException {
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn(subject);
        when(deserializer.deserialize(msg, GitLabPushEventDTO.class)).thenReturn(event);
        return msg;
    }
}

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
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister.Outcome;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit.GitLabCommitMergeRequestLinker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.PassThroughTransactionTemplate;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import io.nats.client.Message;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

@Tag("unit")
class GitLabPushMessageHandlerTest extends BaseUnitTest {

    private static final Long PROVIDER_ID = 2L;
    private static final String DEFAULT_SERVER_URL = "https://gitlab.lrz.de";

    @Mock
    private GitLabProjectProcessor projectProcessor;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private CommitRepository commitRepository;

    @Mock
    private CommitDetailsPersister persister;

    @Mock
    private IdentityProviderRepository gitProviderRepository;

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    @Mock
    private GitLabTokenService tokenService;

    @Mock
    private CommitAuthorResolver authorResolver;

    @Mock
    private ScopeIdResolver scopeIdResolver;

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
        GitLabProperties properties = new GitLabProperties(
                DEFAULT_SERVER_URL,
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofMillis(200),
                Duration.ofMinutes(5));

        gitLabProvider = new IdentityProvider();
        gitLabProvider.setId(PROVIDER_ID);
        gitLabProvider.setType(IdentityProviderType.GITLAB);
        gitLabProvider.setServerUrl(DEFAULT_SERVER_URL);

        // Default: provider lookup succeeds
        lenient()
                .when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITLAB, DEFAULT_SERVER_URL))
                .thenReturn(Optional.of(gitLabProvider));

        handler = new GitLabPushMessageHandler(
                projectProcessor,
                organizationRepository,
                repositoryRepository,
                commitRepository,
                persister,
                gitProviderRepository,
                properties,
                gitRepositoryManager,
                tokenService,
                authorResolver,
                scopeIdResolver,
                syncTargetProvider,
                eventPublisher,
                commitMergeRequestLinker,
                deserializer,
                new PassThroughTransactionTemplate());
    }

    @Test
    void key_returnsPush() {
        assertThat(handler.key().eventType()).isEqualTo("push");
    }

    @Test
    void validPushEvent_upsertsProject() throws IOException {
        var projectInfo = new GitLabPushEventDTO.ProjectInfo(
                246765L,
                "demo-repository",
                "Demo repo",
                "https://gitlab.lrz.de/hephaestustest/demo-repository",
                "HephaestusTest",
                "hephaestustest/demo-repository",
                "main",
                0);
        var pushEvent = new GitLabPushEventDTO(
                "push",
                "refs/heads/main",
                "9c5dedd52046bb5213189afc25f75e608a98d462",
                "a4bf10d93a2d136f1db911b6f1c03d26d835a44f",
                "a4bf10d93a2d136f1db911b6f1c03d26d835a44f",
                246765L,
                projectInfo,
                3,
                null);

        Repository repo = new Repository();
        repo.setId(246765L);
        when(projectProcessor.processPushEvent(projectInfo, gitLabProvider)).thenReturn(repo);
        when(repositoryRepository.findByIdWithOrganization(repo.getId())).thenReturn(Optional.of(repo));

        // Org lookup — simulate existing org in DB
        Organization org = new Organization();
        org.setId(1L);
        org.setLogin("hephaestustest");
        when(organizationRepository.findByLoginIgnoreCaseAndProviderId("hephaestustest", PROVIDER_ID))
                .thenReturn(Optional.of(org));

        Message msg = mockMessage("gitlab.hephaestustest.demo-repository.push", pushEvent);
        handler.onMessage(msg);

        verify(projectProcessor).processPushEvent(projectInfo, gitLabProvider);
        verify(repositoryRepository).save(repo);
        assertThat(repo.getOrganization()).isSameAs(org);
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

        Message msg = mockMessage("gitlab.org.proj.push", pushEvent);
        handler.onMessage(msg);

        verify(projectProcessor, never()).processPushEvent(any(), any());
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

        Message msg = mockMessage("gitlab.org.proj.push", pushEvent);
        handler.onMessage(msg);

        verify(projectProcessor, never()).processPushEvent(any(), any());
    }

    @Test
    void processorReturnsNull_logsWarning() throws IOException {
        var projectInfo = new GitLabPushEventDTO.ProjectInfo(
                1L, "proj", null, "https://gitlab.com/org/proj", null, "org/proj", "main", 0);
        var pushEvent =
                new GitLabPushEventDTO("push", "refs/heads/main", "before", "after", "after", 1L, projectInfo, 1, null);

        when(projectProcessor.processPushEvent(projectInfo, gitLabProvider)).thenReturn(null);

        Message msg = mockMessage("gitlab.org.proj.push", pushEvent);
        handler.onMessage(msg);

        verify(projectProcessor).processPushEvent(projectInfo, gitLabProvider);
        // Processor returned null — no org linking attempted
        verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(anyString(), any());
    }

    @Test
    void batchedLinker_runsOncePerPush() throws IOException {
        var projectInfo = createProjectInfo(42L, "org/proj");
        Repository repo = new Repository();
        repo.setId(42L);
        repo.setNameWithOwner("org/proj");
        when(projectProcessor.processPushEvent(projectInfo, gitLabProvider)).thenReturn(repo);
        when(repositoryRepository.findByIdWithOrganization(repo.getId())).thenReturn(Optional.of(repo));
        when(scopeIdResolver.findScopeIdByRepositoryName("org/proj")).thenReturn(Optional.of(7L));

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

        Message msg = mockMessage("gitlab.org.proj.push", pushEvent);
        handler.onMessage(msg);

        // ONE batched GraphQL call, not one per commit.
        verify(commitMergeRequestLinker, times(1)).linkCommits(eq(7L), eq(repo), any());
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

        handler.onMessage(mockMessage("gitlab.org.proj.push", pushEvent));

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

        handler.onMessage(mockMessage("gitlab.org.proj.push", pushEvent));

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
        verify(projectProcessor, never()).processPushEvent(any(), any());
    }

    // Organization Linking Tests

    @Nested
    class OrganizationLinking {

        @Test
        void skipsWhenOrgAlreadySet() throws IOException {
            Organization existingOrg = new Organization();
            existingOrg.setId(1L);

            Repository repo = new Repository();
            repo.setId(1L);
            repo.setOrganization(existingOrg); // already linked

            var projectInfo = createProjectInfo(1L, "org/proj");
            when(projectProcessor.processPushEvent(projectInfo, gitLabProvider)).thenReturn(repo);
            when(repositoryRepository.findByIdWithOrganization(repo.getId())).thenReturn(Optional.of(repo));

            Message msg = mockMessage("gitlab.org.proj.push", createPushEvent(projectInfo));
            handler.onMessage(msg);

            // Should NOT look up org since it's already linked
            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(anyString(), any());
        }

        @Test
        void linksOrgFromDbLookup() throws IOException {
            Repository repo = new Repository();
            repo.setId(1L);

            var projectInfo = createProjectInfo(1L, "org/proj");
            when(projectProcessor.processPushEvent(projectInfo, gitLabProvider)).thenReturn(repo);
            when(repositoryRepository.findByIdWithOrganization(repo.getId())).thenReturn(Optional.of(repo));

            Organization org = new Organization();
            org.setId(42L);
            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("org", PROVIDER_ID))
                    .thenReturn(Optional.of(org));

            Message msg = mockMessage("gitlab.org.proj.push", createPushEvent(projectInfo));
            handler.onMessage(msg);

            assertThat(repo.getOrganization()).isSameAs(org);
            verify(repositoryRepository).save(repo);
        }

        @Test
        void skipsLinkingWhenOrgNotInDb() throws IOException {
            Repository repo = new Repository();
            repo.setId(1L);

            var projectInfo = createProjectInfo(1L, "org/proj");
            when(projectProcessor.processPushEvent(projectInfo, gitLabProvider)).thenReturn(repo);
            when(repositoryRepository.findByIdWithOrganization(repo.getId())).thenReturn(Optional.of(repo));
            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("org", PROVIDER_ID))
                    .thenReturn(Optional.empty());

            Message msg = mockMessage("gitlab.org.proj.push", createPushEvent(projectInfo));
            handler.onMessage(msg);

            // Org not found in DB — repo stays unlinked, will be resolved on next full sync
            assertThat(repo.getOrganization()).isNull();
            verify(repositoryRepository, never()).save(any());
        }

        @Test
        void handlesNestedGroupPaths() throws IOException {
            Repository repo = new Repository();
            repo.setId(1L);

            // Project in nested group: org/team/subteam/project
            var projectInfo = createProjectInfo(1L, "org/team/subteam/project");
            when(projectProcessor.processPushEvent(projectInfo, gitLabProvider)).thenReturn(repo);
            when(repositoryRepository.findByIdWithOrganization(repo.getId())).thenReturn(Optional.of(repo));

            Organization org = new Organization();
            org.setId(42L);
            // Should look up "org/team/subteam" (immediate parent)
            when(organizationRepository.findByLoginIgnoreCaseAndProviderId("org/team/subteam", PROVIDER_ID))
                    .thenReturn(Optional.of(org));

            Message msg = mockMessage("gitlab.org.team.subteam.project.push", createPushEvent(projectInfo));
            handler.onMessage(msg);

            assertThat(repo.getOrganization()).isSameAs(org);
        }

        @Test
        void skipsForUserOwnedProject() throws IOException {
            Repository repo = new Repository();
            repo.setId(1L);

            // User-owned project has no slash in path
            var projectInfo = createProjectInfo(1L, "myproject");
            when(projectProcessor.processPushEvent(projectInfo, gitLabProvider)).thenReturn(repo);
            when(repositoryRepository.findByIdWithOrganization(repo.getId())).thenReturn(Optional.of(repo));

            Message msg = mockMessage("gitlab.myproject.push", createPushEvent(projectInfo));
            handler.onMessage(msg);

            verify(organizationRepository, never()).findByLoginIgnoreCaseAndProviderId(anyString(), any());
        }
    }

    // extractGroupPath Tests

    @Nested
    class ExtractGroupPath {

        @Test
        void simpleOrgProject() {
            assertThat(GitLabPushMessageHandler.extractGroupPath("org/project")).isEqualTo("org");
        }

        @Test
        @DisplayName("nested org/team/project")
        void nestedPath() {
            assertThat(GitLabPushMessageHandler.extractGroupPath("org/team/project"))
                    .isEqualTo("org/team");
        }

        @Test
        void deeplyNested() {
            assertThat(GitLabPushMessageHandler.extractGroupPath("a/b/c/d/project"))
                    .isEqualTo("a/b/c/d");
        }

        @Test
        void noSlash() {
            assertThat(GitLabPushMessageHandler.extractGroupPath("project")).isNull();
        }

        @Test
        void nullInput() {
            assertThat(GitLabPushMessageHandler.extractGroupPath(null)).isNull();
        }

        @Test
        void blankInput() {
            assertThat(GitLabPushMessageHandler.extractGroupPath("  ")).isNull();
        }

        @Test
        void leadingSlash() {
            assertThat(GitLabPushMessageHandler.extractGroupPath("/project")).isNull();
        }
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

    /** A repository in an active scope with local git enabled: the handler walks default-branch pushes. */
    private Repository activeLocalGitRepository(GitLabPushEventDTO.ProjectInfo projectInfo) {
        Repository repo = TestEntities.repository(42L, "org/proj", "main");
        repo.setProvider(gitLabProvider);
        when(projectProcessor.processPushEvent(projectInfo, gitLabProvider)).thenReturn(repo);
        when(repositoryRepository.findByIdWithOrganization(42L)).thenReturn(Optional.of(repo));
        when(scopeIdResolver.findScopeIdByRepositoryName("org/proj")).thenReturn(Optional.of(7L));
        when(syncTargetProvider.isScopeActiveForSync(7L)).thenReturn(true);
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        return repo;
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
                List.of());
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

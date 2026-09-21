package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewthread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewthread.dto.GitHubPullRequestReviewThreadEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for GitHubPullRequestReviewThreadMessageHandler.
 * <p>
 * Tests use JSON fixtures parsed directly into DTOs using JSON fixtures for complete isolation.
 */
class GitHubPullRequestReviewThreadMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubPullRequestReviewThreadMessageHandler handler;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private PullRequestReviewThreadRepository threadRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private IdentityProvider gitProvider;
    private Repository testRepository;
    private PullRequest testPullRequest = new PullRequest();

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setupTestData();
    }

    private void setupTestData() {
        // Create GitHub provider
        gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create organization - use ID from fixture
        Organization org = new Organization();
        org.setNativeId(215361191L);
        org.setLogin("HephaestusTest");
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
        org.setProvider(gitProvider);
        org = organizationRepository.save(org);

        // Create repository matching the fixture's repository
        testRepository = new Repository();
        testRepository.setNativeId(1087937297L); // ID from fixture
        testRepository.setName("payload-fixture-repo-renamed");
        testRepository.setNameWithOwner("HephaestusTest/payload-fixture-repo-renamed");
        testRepository.setHtmlUrl("https://github.com/HephaestusTest/payload-fixture-repo-renamed");
        testRepository.setVisibility(Repository.Visibility.PUBLIC);
        testRepository.setDefaultBranch("main");
        testRepository.setCreatedAt(Instant.now());
        testRepository.setUpdatedAt(Instant.now());
        testRepository.setPushedAt(Instant.now());
        testRepository.setOrganization(org);
        testRepository.setProvider(gitProvider);
        testRepository = repositoryRepository.save(testRepository);

        // Create workspace
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test");
        workspace.setDisplayName("Hephaestus Test");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setIsPubliclyViewable(true);
        workspace.setOrganization(org);
        workspace.setAccountLogin("HephaestusTest");
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    private void createTestPullRequest(Long prId, int number) {
        testPullRequest = new PullRequest();
        testPullRequest.setNativeId(prId);
        testPullRequest.setNumber(number);
        testPullRequest.setTitle("Test Pull Request");
        testPullRequest.setState(PullRequest.State.OPEN);
        testPullRequest.setRepository(testRepository);
        testPullRequest.setCreatedAt(Instant.now());
        testPullRequest.setUpdatedAt(Instant.now());
        testPullRequest.setProvider(gitProvider);
        testPullRequest = pullRequestRepository.save(testPullRequest);
    }

    @Test
    void shouldReturnCorrectEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("repository.pull_request_review_thread");
    }

    @Test
    void shouldHandleResolvedEvent() throws Exception {
        GitHubPullRequestReviewThreadEventDTO event = loadPayload("pull_request_review_thread.resolved");

        // Create the PR that the thread belongs to
        createTestPullRequest(
                required(event.pullRequest().getDatabaseId()),
                event.pullRequest().number());

        // The thread ID is derived from the first comment's ID in the webhook payload.
        // This matches how threads are stored during sync (first comment's databaseId = thread ID).
        Long threadId = required(event.thread().getFirstCommentId());
        assertThat(threadId).isEqualTo(2494208170L); // First comment ID from fixture

        // Create the thread in UNRESOLVED state first
        PullRequestReviewThread thread = new PullRequestReviewThread();
        thread.setNativeId(threadId);
        thread.setNodeId(event.thread().nodeId());
        thread.setPullRequest(testPullRequest);
        thread.setState(PullRequestReviewThread.State.UNRESOLVED);
        thread.setPath(event.thread().path());
        thread.setLine(event.thread().line());
        thread.setCreatedAt(Instant.now());
        thread.setUpdatedAt(Instant.now());
        thread.setProvider(gitProvider);
        thread = threadRepository.save(thread);

        // Verify initial state
        assertThat(threadRepository.findById(thread.getId()))
                .isPresent()
                .get()
                .satisfies(t -> assertThat(t.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED));

        handler.handleEvent(event);

        // Then - thread should be resolved, dated by the event: the only source of that moment on GitHub.
        assertThat(threadRepository.findById(thread.getId())).isPresent().get().satisfies(t -> {
            assertThat(t.getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED);
            assertThat(t.getResolvedAt()).isEqualTo(Instant.parse("2025-11-05T12:15:24Z"));
        });
    }

    @Test
    void shouldHandleUnresolvedEvent() throws Exception {
        GitHubPullRequestReviewThreadEventDTO event = loadPayload("pull_request_review_thread.unresolved");

        // Create the PR that the thread belongs to
        createTestPullRequest(
                required(event.pullRequest().getDatabaseId()),
                event.pullRequest().number());

        // The thread ID is derived from the first comment's ID in the webhook payload.
        Long threadId = required(event.thread().getFirstCommentId());
        assertThat(threadId).isEqualTo(2494208170L); // First comment ID from fixture

        // Create the thread in RESOLVED state first
        PullRequestReviewThread thread = new PullRequestReviewThread();
        thread.setNativeId(threadId);
        thread.setNodeId(event.thread().nodeId());
        thread.setPullRequest(testPullRequest);
        thread.setState(PullRequestReviewThread.State.RESOLVED);
        thread.setResolvedAt(Instant.parse("2025-11-05T12:15:24Z"));
        thread.setPath(event.thread().path());
        thread.setLine(event.thread().line());
        thread.setCreatedAt(Instant.now());
        thread.setUpdatedAt(Instant.now());
        thread.setProvider(gitProvider);
        thread = threadRepository.save(thread);

        // Verify initial state
        assertThat(threadRepository.findById(thread.getId()))
                .isPresent()
                .get()
                .satisfies(t -> assertThat(t.getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED));

        handler.handleEvent(event);

        // Then - thread should be unresolved, and no longer dated as resolved
        assertThat(threadRepository.findById(thread.getId())).isPresent().get().satisfies(t -> {
            assertThat(t.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            assertThat(t.getResolvedAt()).isNull();
        });
    }

    @Test
    void shouldResolveThreadWhenIdIsKnown() throws Exception {
        // Given - create a thread directly
        createTestPullRequest(12345L, 1);
        Long threadNativeId = 100L;

        PullRequestReviewThread thread = new PullRequestReviewThread();
        thread.setNativeId(threadNativeId);
        thread.setPullRequest(testPullRequest);
        thread.setState(PullRequestReviewThread.State.UNRESOLVED);
        thread.setPath("README.md");
        thread.setCreatedAt(Instant.now());
        thread.setUpdatedAt(Instant.now());
        thread.setProvider(gitProvider);
        thread = threadRepository.save(thread);

        // Verify initial state
        assertThat(threadRepository.findById(thread.getId()))
                .isPresent()
                .get()
                .satisfies(t -> assertThat(t.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED));

        // When - directly call repository method (simulating proper thread ID resolution)
        thread.setState(PullRequestReviewThread.State.RESOLVED);
        threadRepository.save(thread);

        // Then - thread should be resolved
        // Note: GitHub only provides isResolved (boolean), not a timestamp.
        // The state enum (RESOLVED/UNRESOLVED) is sufficient.
        assertThat(threadRepository.findById(thread.getId()))
                .isPresent()
                .get()
                .satisfies(t -> assertThat(t.getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED));
    }

    private GitHubPullRequestReviewThreadEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubPullRequestReviewThreadEventDTO.class);
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}

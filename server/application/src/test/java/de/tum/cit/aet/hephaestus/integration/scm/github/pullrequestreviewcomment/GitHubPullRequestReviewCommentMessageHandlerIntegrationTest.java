package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.dto.GitHubPullRequestReviewCommentEventDTO;
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
 * Integration tests for GitHubPullRequestReviewCommentMessageHandler.
 * <p>
 * Tests use JSON fixtures parsed directly into DTOs using JSON fixtures for complete isolation.
 */
class GitHubPullRequestReviewCommentMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubPullRequestReviewCommentMessageHandler handler;

    @Autowired
    private PullRequestReviewCommentRepository commentRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

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

        // Create organization
        Organization org = new Organization();
        org.setNativeId(215361191L);
        org.setLogin("HephaestusTest");
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
        org.setProvider(gitProvider);
        org = organizationRepository.save(org);

        // Create repository
        testRepository = new Repository();
        testRepository.setNativeId(1000663383L);
        testRepository.setName("TestRepository");
        testRepository.setNameWithOwner("HephaestusTest/TestRepository");
        testRepository.setHtmlUrl("https://github.com/HephaestusTest/TestRepository");
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
        assertThat(handler.key().eventType()).isEqualTo("repository.pull_request_review_comment");
    }

    @Test
    void shouldCreateReviewCommentOnCreatedEvent() throws Exception {
        GitHubPullRequestReviewCommentEventDTO event = loadPayload("pull_request_review_comment.created");

        // Create the PR that the comment belongs to
        createTestPullRequest(
                required(event.pullRequest().getDatabaseId()),
                event.pullRequest().number());

        // Verify comment doesn't exist initially
        assertThat(commentRepository.findByNativeIdAndProviderId(event.comment().id(), gitProviderId()))
                .isEmpty();

        handler.handleEvent(event);

        // Then - verify comment is created with required fields
        assertThat(commentRepository.findByNativeIdAndProviderId(event.comment().id(), gitProviderId()))
                .isPresent()
                .get()
                .satisfies(comment -> {
                    assertThat(comment.getNativeId()).isEqualTo(event.comment().id());
                    assertThat(comment.getBody()).isEqualTo(event.comment().body());
                    assertThat(comment.getPath()).isEqualTo(event.comment().path());
                    // Verify thread is created (required FK)
                    assertThat(comment.getThread()).isNotNull();
                    assertThat(comment.getThread().getId()).isNotNull();
                    // Verify required fields are populated
                    assertThat(comment.getCommitId()).isNotEmpty();
                    assertThat(comment.getSide()).isEqualTo(PullRequestReviewComment.Side.RIGHT);
                });
    }

    @Test
    void shouldUpdateReviewCommentOnEditedEvent() throws Exception {
        // Given - first create the comment
        GitHubPullRequestReviewCommentEventDTO createEvent = loadPayload("pull_request_review_comment.created");
        createTestPullRequest(
                required(createEvent.pullRequest().getDatabaseId()),
                createEvent.pullRequest().number());
        handler.handleEvent(createEvent);

        // Load edited event
        GitHubPullRequestReviewCommentEventDTO editEvent = loadPayload("pull_request_review_comment.edited");

        handler.handleEvent(editEvent);

        assertThat(commentRepository.findByNativeIdAndProviderId(
                        editEvent.comment().id(), gitProviderId()))
                .isPresent()
                .get()
                .satisfies(comment -> {
                    assertThat(comment.getBody()).isEqualTo(editEvent.comment().body());
                });
    }

    @Test
    void shouldDeleteReviewCommentOnDeletedEvent() throws Exception {
        // Given - first create the comment
        GitHubPullRequestReviewCommentEventDTO createEvent = loadPayload("pull_request_review_comment.created");
        createTestPullRequest(
                required(createEvent.pullRequest().getDatabaseId()),
                createEvent.pullRequest().number());
        handler.handleEvent(createEvent);

        // Verify it exists
        assertThat(commentRepository.findByNativeIdAndProviderId(
                        createEvent.comment().id(), gitProviderId()))
                .isPresent();

        // Load deleted event
        GitHubPullRequestReviewCommentEventDTO deleteEvent = loadPayload("pull_request_review_comment.deleted");

        handler.handleEvent(deleteEvent);

        assertThat(commentRepository.findByNativeIdAndProviderId(
                        deleteEvent.comment().id(), gitProviderId()))
                .isEmpty();
    }

    private GitHubPullRequestReviewCommentEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubPullRequestReviewCommentEventDTO.class);
    }

    private Long gitProviderId() {
        Long id = gitProvider.getId();
        assertNotNull(id);
        return id;
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO;
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
 * Integration tests for GitHubIssueCommentMessageHandler.
 * <p>
 * Tests use JSON fixtures parsed directly into DTOs using JSON fixtures for complete isolation.
 */
class GitHubIssueCommentMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubIssueCommentMessageHandler handler;

    @Autowired
    private IssueCommentRepository commentRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Repository testRepository;
    private Issue testIssue = new Issue();
    private IdentityProvider gitProvider;

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
        org.setHtmlUrl("https://github.com/HephaestusTest");
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
        workspace.setPublicActivityEnabled(true);
        workspace.setOrganization(org);
        workspace.setAccountLogin("HephaestusTest");
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    private void createTestIssue(Long issueId, int number) {
        testIssue = new Issue();
        testIssue.setNativeId(issueId);
        testIssue.setNumber(number);
        testIssue.setTitle("Test Issue");
        testIssue.setState(Issue.State.OPEN);
        testIssue.setRepository(testRepository);
        testIssue.setCreatedAt(Instant.now());
        testIssue.setUpdatedAt(Instant.now());
        testIssue.setProvider(gitProvider);
        testIssue = issueRepository.save(testIssue);
    }

    @Test
    void shouldReturnCorrectEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("repository.issue_comment");
    }

    @Test
    void shouldCreateCommentOnCreatedEvent() throws Exception {
        GitHubIssueCommentEventDTO event = loadPayload("issue_comment.created");

        // Create the issue that the comment belongs to
        createTestIssue(required(event.issue().getDatabaseId()), event.issue().number());

        // Verify comment doesn't exist initially
        assertThat(commentRepository.findByNativeIdAndProviderId(
                        required(event.comment().id()), gitProviderId()))
                .isEmpty();

        handler.handleEvent(event);

        assertThat(commentRepository.findByNativeIdAndProviderId(
                        required(event.comment().id()), gitProviderId()))
                .isPresent()
                .get()
                .satisfies(comment -> {
                    assertThat(comment.getNativeId())
                            .isEqualTo(required(event.comment().id()));
                    assertThat(comment.getBody()).isEqualTo(event.comment().body());
                    assertThat(comment.getHtmlUrl()).isEqualTo(event.comment().htmlUrl());
                });
    }

    @Test
    void shouldUpdateCommentOnEditedEvent() throws Exception {
        // Given - first create the comment
        GitHubIssueCommentEventDTO createEvent = loadPayload("issue_comment.created");
        createTestIssue(
                required(createEvent.issue().getDatabaseId()),
                createEvent.issue().number());
        handler.handleEvent(createEvent);

        // Load edited event
        GitHubIssueCommentEventDTO editEvent = loadPayload("issue_comment.edited");

        handler.handleEvent(editEvent);

        assertThat(commentRepository.findByNativeIdAndProviderId(
                        required(editEvent.comment().id()), gitProviderId()))
                .isPresent()
                .get()
                .satisfies(comment -> {
                    assertThat(comment.getBody()).isEqualTo(editEvent.comment().body());
                });
    }

    @Test
    void shouldDeleteCommentOnDeletedEvent() throws Exception {
        // Given - first create the comment
        GitHubIssueCommentEventDTO createEvent = loadPayload("issue_comment.created");
        createTestIssue(
                required(createEvent.issue().getDatabaseId()),
                createEvent.issue().number());
        handler.handleEvent(createEvent);

        // Verify it exists
        assertThat(commentRepository.findByNativeIdAndProviderId(
                        required(createEvent.comment().id()), gitProviderId()))
                .isPresent();

        // Load deleted event
        GitHubIssueCommentEventDTO deleteEvent = loadPayload("issue_comment.deleted");

        handler.handleEvent(deleteEvent);

        assertThat(commentRepository.findByNativeIdAndProviderId(
                        required(deleteEvent.comment().id()), gitProviderId()))
                .isEmpty();
    }

    private GitHubIssueCommentEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubIssueCommentEventDTO.class);
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

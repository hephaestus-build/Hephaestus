package de.tum.cit.aet.hephaestus.integration.scm.github.discussioncomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussion.DiscussionRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.discussioncomment.DiscussionCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.discussioncomment.dto.GitHubDiscussionCommentEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for GitHubDiscussionCommentMessageHandler.
 * <p>
 * Tests the full webhook handling flow using JSON fixtures parsed directly
 * into DTOs for complete isolation. The comment handler processes the parent
 * discussion first (via GitHubDiscussionProcessor.process()) before processing
 * the comment itself, so no manual Discussion setup is needed.
 * <p>
 * Note: This test class does NOT use @Transactional because the processing
 * chain calls GitHubUserProcessor.findOrCreate() which uses REQUIRES_NEW propagation.
 * Having @Transactional here would cause connection pool deadlocks.
 */
class GitHubDiscussionCommentMessageHandlerIntegrationTest extends BaseIntegrationTest {

    private static final Long FIXTURE_ORG_ID = 215361191L;
    private static final Long FIXTURE_REPO_ID = 998279771L;
    private static final String FIXTURE_ORG_LOGIN = "HephaestusTest";
    private static final String FIXTURE_REPO_FULL_NAME = "HephaestusTest/TestRepository";

    // Comment fixture values from discussion_comment.created.json
    private static final Long FIXTURE_COMMENT_ID = 14848457L;
    private static final String FIXTURE_COMMENT_BODY = "Here is a helpful answer";
    private static final String FIXTURE_COMMENT_HTML_URL =
            "https://github.com/HephaestusTest/TestRepository/discussions/27#discussioncomment-14848457";
    private static final String FIXTURE_EDITED_COMMENT_BODY = "Edited answer content";

    // Discussion ID embedded in the comment fixtures
    private static final Long FIXTURE_DISCUSSION_ID = 9096662L;

    @Autowired
    private GitHubDiscussionCommentMessageHandler handler;

    @Autowired
    private DiscussionCommentRepository commentRepository;

    @Autowired
    private DiscussionRepository discussionRepository;

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

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RecordingScmEventListener eventListener;

    private static final int FIXTURE_DISCUSSION_NUMBER = 27;

    private Repository testRepository;
    private IdentityProvider gitProvider;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
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
        org.setNativeId(FIXTURE_ORG_ID);
        org.setLogin(FIXTURE_ORG_LOGIN);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/" + FIXTURE_ORG_ID);
        org.setHtmlUrl("https://github.com/" + FIXTURE_ORG_LOGIN);
        org.setProvider(gitProvider);
        org = organizationRepository.save(org);

        // Create repository
        Repository repo = new Repository();
        repo.setNativeId(FIXTURE_REPO_ID);
        repo.setName("TestRepository");
        repo.setNameWithOwner(FIXTURE_REPO_FULL_NAME);
        repo.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME);
        repo.setVisibility(Repository.Visibility.PUBLIC);
        repo.setDefaultBranch("main");
        repo.setCreatedAt(Instant.now());
        repo.setUpdatedAt(Instant.now());
        repo.setPushedAt(Instant.now());
        repo.setOrganization(org);
        repo.setProvider(gitProvider);
        testRepository = repositoryRepository.save(repo);

        // Create workspace
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test");
        workspace.setDisplayName("Hephaestus Test");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setPublicActivityEnabled(true);
        workspace.setOrganization(org);
        workspace.setAccountLogin(FIXTURE_ORG_LOGIN);
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    @Test
    void shouldReturnCorrectEventType() {
        assertThat(handler.key().eventType()).isEqualTo("repository.discussion_comment");
    }

    @Test
    void shouldCreateCommentOnCreatedEvent() throws Exception {
        GitHubDiscussionCommentEventDTO event = loadPayload("discussion_comment.created");

        // Verify comment doesn't exist initially
        assertThat(commentRepository.findByNativeIdAndProviderId(FIXTURE_COMMENT_ID, gitProviderId()))
                .isEmpty();

        handler.handleEvent(event);

        // Then - comment should be persisted with correct fields
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(commentRepository.findByNativeIdAndProviderId(FIXTURE_COMMENT_ID, gitProviderId()))
                    .isPresent()
                    .get()
                    .satisfies(comment -> {
                        assertThat(comment.getNativeId()).isEqualTo(FIXTURE_COMMENT_ID);
                        assertThat(comment.getBody()).isEqualTo(FIXTURE_COMMENT_BODY);
                        assertThat(comment.getHtmlUrl()).isEqualTo(FIXTURE_COMMENT_HTML_URL);
                        assertThat(comment.getDiscussion()).isNotNull();
                        assertThat(comment.getDiscussion().getNativeId()).isEqualTo(FIXTURE_DISCUSSION_ID);
                        assertThat(comment.getAuthor()).isNotNull();
                    });
        });

        // Parent discussion should also have been created by the handler
        assertThat(discussionRepository.findByRepositoryIdAndNumber(testRepository.getId(), FIXTURE_DISCUSSION_NUMBER))
                .isPresent();

        // Domain event published
        assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentCreated.class))
                .hasSize(1);
    }

    @Test
    void shouldUpdateCommentOnEditedEvent() throws Exception {
        // Given - first create the comment
        GitHubDiscussionCommentEventDTO createEvent = loadPayload("discussion_comment.created");
        handler.handleEvent(createEvent);
        eventListener.clear();

        // Load edited event
        GitHubDiscussionCommentEventDTO editEvent = loadPayload("discussion_comment.edited");

        handler.handleEvent(editEvent);

        assertThat(commentRepository.findByNativeIdAndProviderId(FIXTURE_COMMENT_ID, gitProviderId()))
                .isPresent()
                .get()
                .satisfies(comment -> {
                    assertThat(comment.getBody()).isEqualTo(FIXTURE_EDITED_COMMENT_BODY);
                });
    }

    @Test
    void shouldDeleteCommentOnDeletedEvent() throws Exception {
        // Given - first create the comment
        GitHubDiscussionCommentEventDTO createEvent = loadPayload("discussion_comment.created");
        handler.handleEvent(createEvent);

        // Verify it exists
        assertThat(commentRepository.findByNativeIdAndProviderId(FIXTURE_COMMENT_ID, gitProviderId()))
                .isPresent();
        eventListener.clear();

        // Load deleted event
        GitHubDiscussionCommentEventDTO deleteEvent = loadPayload("discussion_comment.deleted");

        handler.handleEvent(deleteEvent);

        assertThat(commentRepository.findByNativeIdAndProviderId(FIXTURE_COMMENT_ID, gitProviderId()))
                .isEmpty();

        // Domain event published
        assertThat(eventListener.ofType(ScmDomainEvent.DiscussionCommentDeleted.class))
                .hasSize(1);
    }

    private GitHubDiscussionCommentEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubDiscussionCommentEventDTO.class);
    }

    private Long gitProviderId() {
        Long id = gitProvider.getId();
        assertNotNull(id);
        return id;
    }
}

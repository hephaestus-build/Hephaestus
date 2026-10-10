package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.events.GitHubProjectEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectEventDTO;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for GitHubProjectMessageHandler.
 * <p>
 * Tests use JSON fixtures parsed directly into DTOs using JSON fixtures for complete isolation.
 * Verifies persistence, field-level correctness, and domain event publishing.
 */
class GitHubProjectMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // Fixture values from projects_v2.created.json
    private static final Long FIXTURE_PROJECT_ID = 18615912L;
    private static final String FIXTURE_PROJECT_NODE_ID = "PVT_kwDODNYmp84BHA5o";
    private static final String FIXTURE_PROJECT_TITLE = "Payload Fixture Project 2025-11-01";
    private static final int FIXTURE_PROJECT_NUMBER = 1;
    private static final Instant FIXTURE_CREATED_AT = Instant.parse("2025-11-01T23:53:46Z");

    @Autowired
    private GitHubProjectMessageHandler handler;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecordingScmEventListener eventListener;

    private Organization testOrganization;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    private void setupTestData() {
        // Create GitHub provider
        IdentityProvider gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create organization matching the fixture data
        testOrganization = new Organization();
        testOrganization.setNativeId(215361191L);
        testOrganization.setLogin("HephaestusTest");
        testOrganization.setCreatedAt(Instant.now());
        testOrganization.setUpdatedAt(Instant.now());
        testOrganization.setName("Hephaestus Test");
        testOrganization.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
        testOrganization.setProvider(gitProvider);
        testOrganization = organizationRepository.save(testOrganization);

        // Create workspace for scope resolution
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test");
        workspace.setDisplayName("Hephaestus Test");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setPublicActivityEnabled(true);
        workspace.setOrganization(testOrganization);
        workspace.setAccountLogin("HephaestusTest");
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    @Test
    void shouldReturnCorrectEventType() {
        assertThat(handler.key().eventType()).isEqualTo("organization.projects_v2");
    }

    @Test
    void shouldHandleProjectCreatedEvent() throws Exception {
        GitHubProjectEventDTO event = loadPayload("projects_v2.created");

        handler.handleEvent(event);

        // Then - project should be created with correct field values
        assertThat(projectRepository.findByOwnerTypeAndOwnerIdAndNumber(
                        Project.OwnerType.ORGANIZATION,
                        testOrganization.getNativeId(),
                        event.project().number()))
                .isPresent()
                .get()
                .satisfies(project -> {
                    assertThat(project.getNativeId()).isEqualTo(FIXTURE_PROJECT_ID);
                    assertThat(project.getNodeId()).isEqualTo(FIXTURE_PROJECT_NODE_ID);
                    assertThat(project.getTitle()).isEqualTo(FIXTURE_PROJECT_TITLE);
                    assertThat(project.getNumber()).isEqualTo(FIXTURE_PROJECT_NUMBER);
                    assertThat(project.getOwnerType()).isEqualTo(Project.OwnerType.ORGANIZATION);
                    assertThat(project.getOwnerId()).isEqualTo(testOrganization.getNativeId());
                    assertThat(project.isClosed()).isFalse();
                    assertThat(project.isPublic()).isFalse();
                    assertThat(project.getShortDescription()).isNull();
                    assertThat(project.getCreatedAt()).isEqualTo(FIXTURE_CREATED_AT);
                });

        // Verify domain event (project().id() is the synthetic PK, not the native ID)
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectCreated.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectCreated.class)
                        .getFirst()
                        .project()
                        .id())
                .isNotNull();
    }

    @Test
    void shouldHandleProjectEditedEvent() throws Exception {
        // Given - first create a project
        GitHubProjectEventDTO createEvent = loadPayload("projects_v2.created");
        handler.handleEvent(createEvent);
        eventListener.clear();

        // Load and process edited event
        GitHubProjectEventDTO editEvent = loadPayload("projects_v2.edited");

        handler.handleEvent(editEvent);

        // Then - project should be updated with the edited short description
        assertThat(projectRepository.findByOwnerTypeAndOwnerIdAndNumber(
                        Project.OwnerType.ORGANIZATION,
                        testOrganization.getNativeId(),
                        editEvent.project().number()))
                .isPresent()
                .get()
                .satisfies(project -> {
                    assertThat(project.getShortDescription()).isEqualTo("Fixtures for webhook payloads");
                    assertThat(project.getUpdatedAt()).isEqualTo(Instant.parse("2025-11-01T23:54:01Z"));
                });

        // Verify ProjectUpdated domain event (not ProjectCreated, since project already existed)
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectCreated.class))
                .isEmpty();
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectUpdated.class))
                .hasSize(1);
    }

    @Test
    void shouldHandleProjectClosedEvent() throws Exception {
        // Given - first create a project
        GitHubProjectEventDTO createEvent = loadPayload("projects_v2.created");
        handler.handleEvent(createEvent);
        eventListener.clear();

        // Load and process closed event
        GitHubProjectEventDTO closedEvent = loadPayload("projects_v2.closed");

        handler.handleEvent(closedEvent);

        // Then - project should be marked as closed
        assertThat(projectRepository.findByOwnerTypeAndOwnerIdAndNumber(
                        Project.OwnerType.ORGANIZATION,
                        testOrganization.getNativeId(),
                        closedEvent.project().number()))
                .isPresent()
                .get()
                .satisfies(project -> assertThat(project.isClosed()).isTrue());

        // Verify ProjectClosed domain event (project().id() is the synthetic PK)
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectClosed.class)).hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectClosed.class)
                        .getFirst()
                        .project()
                        .id())
                .isNotNull();
    }

    @Test
    void shouldHandleProjectReopenedEvent() throws Exception {
        // Given - first create and close a project
        GitHubProjectEventDTO createEvent = loadPayload("projects_v2.created");
        handler.handleEvent(createEvent);

        GitHubProjectEventDTO closedEvent = loadPayload("projects_v2.closed");
        handler.handleEvent(closedEvent);
        eventListener.clear();

        // Load and process reopened event
        GitHubProjectEventDTO reopenedEvent = loadPayload("projects_v2.reopened");

        handler.handleEvent(reopenedEvent);

        // Then - project should be reopened
        assertThat(projectRepository.findByOwnerTypeAndOwnerIdAndNumber(
                        Project.OwnerType.ORGANIZATION,
                        testOrganization.getNativeId(),
                        reopenedEvent.project().number()))
                .isPresent()
                .get()
                .satisfies(project -> assertThat(project.isClosed()).isFalse());

        // Verify ProjectReopened domain event (project().id() is the synthetic PK)
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectReopened.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectReopened.class)
                        .getFirst()
                        .project()
                        .id())
                .isNotNull();
    }

    @Test
    void shouldHandleProjectDeletedEvent() throws Exception {
        // Given - first create a project
        GitHubProjectEventDTO createEvent = loadPayload("projects_v2.created");
        handler.handleEvent(createEvent);
        eventListener.clear();

        // Verify project exists (use nodeId since findById expects synthetic PK)
        assertThat(projectRepository.findByNodeId(FIXTURE_PROJECT_NODE_ID)).isPresent();

        // Load and process deleted event
        GitHubProjectEventDTO deletedEvent = loadPayload("projects_v2.deleted");

        handler.handleEvent(deletedEvent);

        // Then - project should be deleted
        assertThat(projectRepository.findByNodeId(FIXTURE_PROJECT_NODE_ID)).isEmpty();

        // Verify ProjectDeleted domain event (projectId is the synthetic PK)
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectDeleted.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectDeleted.class)
                        .getFirst()
                        .projectId())
                .isNotNull();
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectDeleted.class)
                        .getFirst()
                        .projectTitle())
                .isEqualTo(FIXTURE_PROJECT_TITLE);
    }

    @Test
    void shouldSkipEventWhenOrganizationNotFound() throws Exception {
        // Given - clean database and don't set up test data
        databaseTestUtils.cleanDatabase();
        eventListener.clear();

        GitHubProjectEventDTO event = loadPayload("projects_v2.created");

        handler.handleEvent(event);

        // Then - no project should be created and no events published
        assertThat(projectRepository.count()).isZero();
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectCreated.class))
                .isEmpty();
    }

    @Test
    void shouldHandleDuplicateCreatedEventsIdempotently() throws Exception {
        GitHubProjectEventDTO event = loadPayload("projects_v2.created");

        // When — process the same event twice
        handler.handleEvent(event);
        handler.handleEvent(event);

        // Then — only one project should exist (upsert is idempotent)
        assertThat(projectRepository.findByOwnerTypeAndOwnerIdAndNumber(
                        Project.OwnerType.ORGANIZATION,
                        testOrganization.getNativeId(),
                        event.project().number()))
                .isPresent();
        assertThat(projectRepository.count()).isOne();

        // First call publishes ProjectCreated, second publishes ProjectUpdated
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectCreated.class))
                .hasSize(1);
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectUpdated.class))
                .hasSize(1);
    }

    private GitHubProjectEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubProjectEventDTO.class);
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.events.GitHubProjectEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectStatusUpdateEventDTO;
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
 * Integration tests for GitHubProjectStatusUpdateMessageHandler.
 * <p>
 * Verifies persistence, field-level correctness, domain event publishing,
 * and edge case handling for status update webhook events.
 */
class GitHubProjectStatusUpdateMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // Fixture values from projects_v2_status_update.created.json
    private static final Long FIXTURE_STATUS_UPDATE_ID = 160033L;
    private static final String FIXTURE_STATUS_UPDATE_NODE_ID = "PVTSU_lADODNYmp84BHA5ozgACcSE";
    private static final String FIXTURE_CREATED_BODY = "Initial status update for payload fixtures";
    private static final String FIXTURE_EDITED_BODY = "Updated status highlighting risks";

    @Autowired
    private GitHubProjectStatusUpdateMessageHandler handler;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectStatusUpdateRepository statusUpdateRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecordingScmEventListener eventListener;

    private Organization testOrganization;
    private Project testProject;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    @Test
    void shouldReturnCorrectEventType() {
        assertThat(handler.key().eventType()).isEqualTo("organization.projects_v2_status_update");
    }

    @Test
    void shouldHandleStatusUpdateCreatedEvent() throws Exception {
        GitHubProjectStatusUpdateEventDTO createdEvent = loadPayload("projects_v2_status_update.created");

        handler.handleEvent(createdEvent);

        assertThat(statusUpdateRepository.findByNodeId(
                        createdEvent.statusUpdate().nodeId()))
                .isPresent()
                .get()
                .satisfies(update -> {
                    assertThat(update.getNativeId()).isEqualTo(FIXTURE_STATUS_UPDATE_ID);
                    assertThat(update.getNodeId()).isEqualTo(FIXTURE_STATUS_UPDATE_NODE_ID);
                    assertNotNull(update.getProject());
                    assertThat(update.getProject().getId()).isEqualTo(testProject.getId());
                    assertThat(update.getBody()).isEqualTo(FIXTURE_CREATED_BODY);
                    assertThat(update.getStatus()).isEqualTo(ProjectStatusUpdate.Status.ON_TRACK);
                    assertThat(update.getStartDate()).isNull();
                    assertThat(update.getTargetDate()).isNull();
                });

        // Verify domain event
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectStatusUpdateCreated.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectStatusUpdateCreated.class)
                        .getFirst()
                        .projectId())
                .isEqualTo(testProject.getId());
    }

    @Test
    void shouldHandleStatusUpdateEditedEvent() throws Exception {
        GitHubProjectStatusUpdateEventDTO createdEvent = loadPayload("projects_v2_status_update.created");
        handler.handleEvent(createdEvent);
        eventListener.clear();

        GitHubProjectStatusUpdateEventDTO editedEvent = loadPayload("projects_v2_status_update.edited");
        handler.handleEvent(editedEvent);

        assertThat(statusUpdateRepository.findByNodeId(
                        editedEvent.statusUpdate().nodeId()))
                .isPresent()
                .get()
                .satisfies(update -> {
                    assertThat(update.getBody()).isEqualTo(FIXTURE_EDITED_BODY);
                    assertThat(update.getStatus()).isEqualTo(ProjectStatusUpdate.Status.AT_RISK);
                    assertThat(update.getUpdatedAt()).isEqualTo(Instant.parse("2025-11-02T00:05:24Z"));
                });

        // Verify ProjectStatusUpdateUpdated event (not Created)
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectStatusUpdateCreated.class))
                .isEmpty();
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectStatusUpdateUpdated.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectStatusUpdateUpdated.class)
                        .getFirst()
                        .projectId())
                .isEqualTo(testProject.getId());
    }

    @Test
    void shouldHandleStatusUpdateDeletedEvent() throws Exception {
        GitHubProjectStatusUpdateEventDTO createdEvent = loadPayload("projects_v2_status_update.created");
        handler.handleEvent(createdEvent);
        eventListener.clear();

        GitHubProjectStatusUpdateEventDTO deletedEvent = loadPayload("projects_v2_status_update.deleted");
        handler.handleEvent(deletedEvent);

        assertThat(statusUpdateRepository.findByNodeId(
                        deletedEvent.statusUpdate().nodeId()))
                .isEmpty();

        // Verify domain event (statusUpdateId is the synthetic PK, not native ID)
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectStatusUpdateDeleted.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectStatusUpdateDeleted.class)
                        .getFirst()
                        .projectId())
                .isEqualTo(testProject.getId());
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectStatusUpdateDeleted.class)
                        .getFirst()
                        .statusUpdateId())
                .isNotNull();
    }

    @Test
    void shouldSkipEventWhenOrganizationNotFound() throws Exception {
        // Given - clean database so no org exists
        databaseTestUtils.cleanDatabase();
        eventListener.clear();

        GitHubProjectStatusUpdateEventDTO event = loadPayload("projects_v2_status_update.created");

        handler.handleEvent(event);

        // Then — no status update should be created
        assertThat(statusUpdateRepository.count()).isZero();
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectStatusUpdateCreated.class))
                .isEmpty();
    }

    @Test
    void shouldSkipEventWhenProjectNotFound() throws Exception {
        // Given — org/workspace exist but project does not
        statusUpdateRepository.deleteAll();
        projectRepository.deleteAll();
        eventListener.clear();

        GitHubProjectStatusUpdateEventDTO event = loadPayload("projects_v2_status_update.created");

        handler.handleEvent(event);

        // Then — no status update should be created
        assertThat(statusUpdateRepository.count()).isZero();
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectStatusUpdateCreated.class))
                .isEmpty();
    }

    @Test
    void shouldHandleDuplicateCreatedEventsIdempotently() throws Exception {
        GitHubProjectStatusUpdateEventDTO event = loadPayload("projects_v2_status_update.created");

        // When — process the same event twice
        handler.handleEvent(event);
        handler.handleEvent(event);

        // Then — only one status update should exist
        assertThat(statusUpdateRepository.findByNodeId(event.statusUpdate().nodeId()))
                .isPresent();

        // First call publishes Created, second publishes Updated
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectStatusUpdateCreated.class))
                .hasSize(1);
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectStatusUpdateUpdated.class))
                .hasSize(1);
    }

    private void setupTestData() {
        // Create GitHub provider
        IdentityProvider gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        testOrganization = new Organization();
        testOrganization.setNativeId(215361191L);
        testOrganization.setLogin("HephaestusTest");
        testOrganization.setCreatedAt(Instant.now());
        testOrganization.setUpdatedAt(Instant.now());
        testOrganization.setName("Hephaestus Test");
        testOrganization.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
        testOrganization.setHtmlUrl("https://github.com/HephaestusTest");
        testOrganization.setProvider(gitProvider);
        testOrganization = organizationRepository.save(testOrganization);

        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test");
        workspace.setDisplayName("Hephaestus Test");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setIsPubliclyViewable(true);
        workspace.setOrganization(testOrganization);
        workspace.setAccountLogin("HephaestusTest");
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);

        testProject = new Project();
        testProject.setNativeId(18615912L);
        testProject.setNodeId("PVT_kwDODNYmp84BHA5o");
        testProject.setOwnerType(Project.OwnerType.ORGANIZATION);
        testProject.setOwnerId(testOrganization.getId());
        testProject.setNumber(1);
        testProject.setTitle("Payload Fixture Project 2025-11-01");
        testProject.setClosed(false);
        testProject.setPublic(false);
        testProject.setCreatedAt(Instant.now());
        testProject.setUpdatedAt(Instant.now());
        testProject.setProvider(gitProvider);
        testProject = projectRepository.save(testProject);
    }

    private GitHubProjectStatusUpdateEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubProjectStatusUpdateEventDTO.class);
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.events.GitHubProjectEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectItemEventDTO;
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
 * Integration tests for GitHubProjectItemMessageHandler.
 * <p>
 * Tests verify that the handler correctly processes project item events
 * (create, edit, archive, restore, convert, reorder, delete) using JSON fixtures
 * parsed directly into DTOs for complete isolation.
 */
class GitHubProjectItemMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // Fixture values from projects_v2_item.created.json
    private static final Long FIXTURE_ITEM_ID = 136779200L;
    private static final String FIXTURE_ITEM_NODE_ID = "PVTI_lADODNYmp84BHA5ozggnFcA";
    private static final Instant FIXTURE_ITEM_CREATED_AT = Instant.parse("2025-11-01T23:54:51Z");
    private static final Instant FIXTURE_ITEM_UPDATED_AT = Instant.parse("2025-11-01T23:54:51Z");

    // Fixture values from projects_v2_item.archived/restored/reordered/deleted.json (second item)
    private static final Long FIXTURE_ITEM2_ID = 136779223L;
    private static final String FIXTURE_ITEM2_NODE_ID = "PVTI_lADODNYmp84BHA5ozggnFdc";

    @Autowired
    private GitHubProjectItemMessageHandler handler;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectItemRepository projectItemRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecordingScmEventListener eventListener;

    private IdentityProvider testGitProvider;
    private Organization testOrganization;
    private Project testProject;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    private void setupTestData() {
        // Create IdentityProvider for GitHub
        testGitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create organization matching the fixture data
        testOrganization = new Organization();
        testOrganization.setNativeId(215361191L);
        testOrganization.setProvider(testGitProvider);
        testOrganization.setLogin("HephaestusTest");
        testOrganization.setCreatedAt(Instant.now());
        testOrganization.setUpdatedAt(Instant.now());
        testOrganization.setName("Hephaestus Test");
        testOrganization.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
        testOrganization.setHtmlUrl("https://github.com/HephaestusTest");
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

        // Create test project matching the fixture data
        testProject = new Project();
        testProject.setNativeId(18615912L);
        testProject.setProvider(testGitProvider);
        testProject.setNodeId("PVT_kwDODNYmp84BHA5o");
        testProject.setOwnerType(Project.OwnerType.ORGANIZATION);
        testProject.setOwnerId(testOrganization.getId());
        testProject.setNumber(1);
        testProject.setTitle("Payload Fixture Project 2025-11-01");
        testProject.setClosed(false);
        testProject.setPublic(false);
        testProject.setCreatedAt(Instant.now());
        testProject.setUpdatedAt(Instant.now());
        testProject = projectRepository.save(testProject);
    }

    // Helper methods

    private ProjectItem createAndSaveTestItem(
            Long id, String nodeId, ProjectItem.ContentType contentType, boolean archived) {
        ProjectItem item = new ProjectItem();
        item.setNativeId(id);
        item.setProvider(testGitProvider);
        item.setNodeId(nodeId);
        item.setProject(testProject);
        item.setContentType(contentType);
        item.setArchived(archived);
        item.setCreatedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        return projectItemRepository.saveAndFlush(item);
    }

    // Basic handler tests

    @Test
    void shouldReturnCorrectEventType() {
        assertThat(handler.key().eventType()).isEqualTo("organization.projects_v2_item");
    }

    @Test
    void shouldHandleItemDeletedEvent() throws Exception {
        ProjectItem item = createAndSaveTestItem(
                FIXTURE_ITEM2_ID, FIXTURE_ITEM2_NODE_ID, ProjectItem.ContentType.DRAFT_ISSUE, false);
        assertThat(projectItemRepository.findById(item.getId())).isPresent();
        eventListener.clear();

        GitHubProjectItemEventDTO deletedEvent = loadPayload("projects_v2_item.deleted");

        handler.handleEvent(deletedEvent);

        assertThat(projectItemRepository.findById(item.getId())).isEmpty();

        // Verify ProjectItemDeleted domain event
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemDeleted.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectItemDeleted.class)
                        .getFirst()
                        .itemId())
                .isEqualTo(item.getId());
    }

    @Test
    void shouldHandleItemArchivedEvent() throws Exception {
        createAndSaveTestItem(FIXTURE_ITEM2_ID, FIXTURE_ITEM2_NODE_ID, ProjectItem.ContentType.DRAFT_ISSUE, false);
        eventListener.clear();

        GitHubProjectItemEventDTO archivedEvent = loadPayload("projects_v2_item.archived");

        handler.handleEvent(archivedEvent);

        // Then — processArchived() forces archived=true on the DTO before upserting,
        // compensating for GitHub's webhook payload using "archived_at" instead of "archived".
        var result = projectItemRepository.findByProjectIdAndNodeId(testProject.getId(), FIXTURE_ITEM2_NODE_ID);
        assertThat(result).isPresent().get().satisfies(i -> {
            assertThat(i.getNativeId()).isEqualTo(FIXTURE_ITEM2_ID);
            assertThat(i.getContentType()).isEqualTo(ProjectItem.ContentType.DRAFT_ISSUE);
            assertNotNull(i.getProject());
            assertThat(i.getProject().getId()).isEqualTo(testProject.getId());
            assertThat(i.isArchived()).isTrue();
        });

        // Verify domain events: processArchived() calls process() first (Updated since item exists),
        // then publishes ProjectItemArchived
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemUpdated.class))
                .hasSize(1);
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemArchived.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectItemArchived.class)
                        .getFirst()
                        .projectId())
                .isEqualTo(testProject.getId());
    }

    // Edge case / guard-clause tests

    @Test
    void shouldSkipEventWhenOrganizationNotFound() throws Exception {
        // Given - clean database so no org exists
        databaseTestUtils.cleanDatabase();
        eventListener.clear();

        GitHubProjectItemEventDTO event = loadPayload("projects_v2_item.created");

        handler.handleEvent(event);

        // Then - no item should be created and no events published
        assertThat(projectItemRepository.count()).isZero();
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemCreated.class))
                .isEmpty();
    }

    @Test
    void shouldSkipEventWhenProjectNotFound() throws Exception {
        // Given — org/workspace exist but project does not
        projectItemRepository.deleteAll();
        projectRepository.deleteAll();
        eventListener.clear();

        GitHubProjectItemEventDTO event = loadPayload("projects_v2_item.created");

        handler.handleEvent(event);

        // Then — no item should be created and no events published
        assertThat(projectItemRepository.count()).isZero();
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemCreated.class))
                .isEmpty();
    }

    // Create / Edit / Convert / Restore / Reorder tests

    @Test
    void shouldHandleItemCreatedEvent() throws Exception {
        GitHubProjectItemEventDTO event = loadPayload("projects_v2_item.created");

        handler.handleEvent(event);

        var item = projectItemRepository.findByProjectIdAndNodeId(testProject.getId(), FIXTURE_ITEM_NODE_ID);
        assertThat(item).isPresent().get().satisfies(i -> {
            assertThat(i.getNativeId()).isEqualTo(FIXTURE_ITEM_ID);
            assertThat(i.getNodeId()).isEqualTo(FIXTURE_ITEM_NODE_ID);
            assertThat(i.getContentType()).isEqualTo(ProjectItem.ContentType.DRAFT_ISSUE);
            assertNotNull(i.getProject());
            assertThat(i.getProject().getId()).isEqualTo(testProject.getId());
            assertThat(i.isArchived()).isFalse();
            assertThat(i.getCreatedAt()).isEqualTo(FIXTURE_ITEM_CREATED_AT);
            assertThat(i.getUpdatedAt()).isEqualTo(FIXTURE_ITEM_UPDATED_AT);
        });

        // Verify ProjectItemCreated domain event
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemCreated.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectItemCreated.class)
                        .getFirst()
                        .projectId())
                .isEqualTo(testProject.getId());
    }

    @Test
    void shouldHandleDuplicateCreatedEventsIdempotently() throws Exception {
        GitHubProjectItemEventDTO event = loadPayload("projects_v2_item.created");

        // When — process the same event twice
        handler.handleEvent(event);
        handler.handleEvent(event);

        // Then — only one item should exist (upsert is idempotent)
        assertThat(projectItemRepository.findAllByProjectId(testProject.getId()))
                .hasSize(1);

        // First call publishes ProjectItemCreated, second publishes ProjectItemUpdated
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemCreated.class))
                .hasSize(1);
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemUpdated.class))
                .hasSize(1);
    }

    @Test
    void shouldHandleItemEditedEvent() throws Exception {
        // Given — pre-create the item
        createAndSaveTestItem(FIXTURE_ITEM_ID, FIXTURE_ITEM_NODE_ID, ProjectItem.ContentType.DRAFT_ISSUE, false);
        eventListener.clear();

        GitHubProjectItemEventDTO event = loadPayload("projects_v2_item.edited");

        handler.handleEvent(event);

        // Then — updated_at should reflect the edited fixture timestamp;
        // created_at is NOT overwritten on conflict (upsert only updates updated_at).
        var item = projectItemRepository.findByProjectIdAndNodeId(testProject.getId(), FIXTURE_ITEM_NODE_ID);
        assertThat(item).isPresent().get().satisfies(i -> {
            assertThat(i.getNativeId()).isEqualTo(FIXTURE_ITEM_ID);
            assertThat(i.getNodeId()).isEqualTo(FIXTURE_ITEM_NODE_ID);
            assertThat(i.getContentType()).isEqualTo(ProjectItem.ContentType.DRAFT_ISSUE);
            assertNotNull(i.getProject());
            assertThat(i.getProject().getId()).isEqualTo(testProject.getId());
            assertThat(i.isArchived()).isFalse();
            assertThat(i.getUpdatedAt()).isEqualTo(Instant.parse("2025-11-01T23:55:09Z"));
        });

        // Verify ProjectItemUpdated domain event (not Created, since item already existed)
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemCreated.class))
                .isEmpty();
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemUpdated.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectItemUpdated.class)
                        .getFirst()
                        .projectId())
                .isEqualTo(testProject.getId());
    }

    @Test
    void shouldHandleItemRestoredEvent() throws Exception {
        // Given — pre-create item as archived
        createAndSaveTestItem(FIXTURE_ITEM2_ID, FIXTURE_ITEM2_NODE_ID, ProjectItem.ContentType.DRAFT_ISSUE, true);
        eventListener.clear();

        GitHubProjectItemEventDTO event = loadPayload("projects_v2_item.restored");

        handler.handleEvent(event);

        var result = projectItemRepository.findByProjectIdAndNodeId(testProject.getId(), FIXTURE_ITEM2_NODE_ID);
        assertThat(result).isPresent().get().satisfies(i -> {
            assertThat(i.getNativeId()).isEqualTo(FIXTURE_ITEM2_ID);
            assertThat(i.getContentType()).isEqualTo(ProjectItem.ContentType.DRAFT_ISSUE);
            assertNotNull(i.getProject());
            assertThat(i.getProject().getId()).isEqualTo(testProject.getId());
            assertThat(i.isArchived()).isFalse();
        });

        // Verify domain events: processRestored() calls process() first (Updated since item exists),
        // then publishes ProjectItemRestored
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemUpdated.class))
                .hasSize(1);
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemRestored.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectItemRestored.class)
                        .getFirst()
                        .projectId())
                .isEqualTo(testProject.getId());
    }

    @Test
    void shouldHandleItemConvertedEvent() throws Exception {
        // Given — pre-create item as DRAFT_ISSUE
        createAndSaveTestItem(FIXTURE_ITEM_ID, FIXTURE_ITEM_NODE_ID, ProjectItem.ContentType.DRAFT_ISSUE, false);
        eventListener.clear();

        GitHubProjectItemEventDTO event = loadPayload("projects_v2_item.converted");

        handler.handleEvent(event);

        // Then — content type should be updated to ISSUE
        var result = projectItemRepository.findByProjectIdAndNodeId(testProject.getId(), FIXTURE_ITEM_NODE_ID);
        assertThat(result).isPresent().get().satisfies(i -> {
            assertThat(i.getNativeId()).isEqualTo(FIXTURE_ITEM_ID);
            assertThat(i.getContentType()).isEqualTo(ProjectItem.ContentType.ISSUE);
            assertNotNull(i.getProject());
            assertThat(i.getProject().getId()).isEqualTo(testProject.getId());
            assertThat(i.isArchived()).isFalse();
        });

        // Verify domain events: processConverted() calls process() first (Updated since item exists),
        // then publishes ProjectItemConverted
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemUpdated.class))
                .hasSize(1);
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemConverted.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectItemConverted.class)
                        .getFirst()
                        .projectId())
                .isEqualTo(testProject.getId());
    }

    @Test
    void shouldHandleItemReorderedEvent() throws Exception {
        // Given — pre-create the item
        createAndSaveTestItem(FIXTURE_ITEM2_ID, FIXTURE_ITEM2_NODE_ID, ProjectItem.ContentType.DRAFT_ISSUE, false);
        eventListener.clear();

        GitHubProjectItemEventDTO event = loadPayload("projects_v2_item.reordered");

        handler.handleEvent(event);

        var result = projectItemRepository.findByProjectIdAndNodeId(testProject.getId(), FIXTURE_ITEM2_NODE_ID);
        assertThat(result).isPresent().get().satisfies(i -> {
            assertThat(i.getNativeId()).isEqualTo(FIXTURE_ITEM2_ID);
            assertThat(i.getContentType()).isEqualTo(ProjectItem.ContentType.DRAFT_ISSUE);
            assertNotNull(i.getProject());
            assertThat(i.getProject().getId()).isEqualTo(testProject.getId());
            assertThat(i.isArchived()).isFalse();
        });

        // Verify domain events: processReordered() calls process() first (Updated since item exists),
        // then publishes ProjectItemReordered
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemUpdated.class))
                .hasSize(1);
        assertThat(eventListener.ofType(GitHubProjectEvent.ProjectItemReordered.class))
                .hasSize(1);
        assertThat(eventListener
                        .ofType(GitHubProjectEvent.ProjectItemReordered.class)
                        .getFirst()
                        .projectId())
                .isEqualTo(testProject.getId());
    }

    private GitHubProjectItemEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubProjectItemEventDTO.class);
    }
}

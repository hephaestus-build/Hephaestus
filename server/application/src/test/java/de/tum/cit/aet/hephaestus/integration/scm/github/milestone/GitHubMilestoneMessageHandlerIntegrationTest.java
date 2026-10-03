package de.tum.cit.aet.hephaestus.integration.scm.github.milestone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto.GitHubMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto.GitHubMilestoneEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Note: This test class uses @Transactional because it directly calls handler methods
 * and needs to access lazy-loaded relationships. This is safe because there are no
 * parallel HTTP handler threads that would compete for database connections.
 */
@Transactional
class GitHubMilestoneMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // IDs from the actual GitHub webhook fixtures
    private static final Long FIXTURE_ORG_ID = 215361191L;
    private static final Long FIXTURE_REPO_ID = 998279771L;
    private static final Long FIXTURE_MILESTONE_ID = 14028854L;
    private static final String FIXTURE_ORG_LOGIN = "HephaestusTest";
    private static final String FIXTURE_REPO_NAME = "TestRepository";
    private static final String FIXTURE_REPO_FULL_NAME = "HephaestusTest/TestRepository";

    @Autowired
    private GitHubMilestoneMessageHandler handler;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Repository testRepository;
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

        // Create organization matching fixture data
        Organization org = new Organization();
        org.setNativeId(FIXTURE_ORG_ID);
        org.setLogin(FIXTURE_ORG_LOGIN);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/" + FIXTURE_ORG_ID + "?v=4");
        org.setHtmlUrl("https://github.com/" + FIXTURE_ORG_LOGIN);
        org.setProvider(gitProvider);
        org = organizationRepository.save(org);

        // Create repository matching fixture data
        testRepository = new Repository();
        testRepository.setNativeId(FIXTURE_REPO_ID);
        testRepository.setName(FIXTURE_REPO_NAME);
        testRepository.setNameWithOwner(FIXTURE_REPO_FULL_NAME);
        testRepository.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME);
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
        workspace.setAccountLogin(FIXTURE_ORG_LOGIN);
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    @Test
    void shouldReturnCorrectEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("repository.milestone");
    }

    @Test
    @DisplayName("Should persist milestones on creation events")
    void shouldPersistMilestoneOnCreatedEvent() throws Exception {
        GitHubMilestoneEventDTO event = loadPayload("milestone.created");

        // Verify milestone doesn't exist initially
        assertThat(milestoneRepository.findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, providerId()))
                .isEmpty();

        handler.handleEvent(event);

        assertThat(milestoneRepository.findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, providerId()))
                .isPresent()
                .get()
                .satisfies(milestone -> {
                    assertThat(milestone.getNativeId()).isEqualTo(FIXTURE_MILESTONE_ID);
                    assertNotNull(event.milestone());
                    assertThat(milestone.getTitle()).isEqualTo(event.milestone().title());
                    assertThat(milestone.getDescription())
                            .isEqualTo(event.milestone().description());
                    assertThat(milestone.getState()).isEqualTo(Milestone.State.OPEN);
                    assertThat(milestone.getNumber()).isEqualTo(3);
                });
    }

    @Test
    void shouldUpdateMilestoneOnEditedEvent() throws Exception {
        GitHubMilestoneEventDTO createEvent = loadPayload("milestone.created");
        handler.handleEvent(createEvent);

        // Load edited event
        GitHubMilestoneEventDTO editEvent = loadPayload("milestone.edited");

        handler.handleEvent(editEvent);

        assertThat(milestoneRepository.findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, providerId()))
                .isPresent()
                .get()
                .satisfies(milestone -> {
                    assertNotNull(editEvent.milestone());
                    assertThat(milestone.getTitle())
                            .isEqualTo(editEvent.milestone().title());
                    assertThat(milestone.getDescription())
                            .isEqualTo(editEvent.milestone().description());
                });
    }

    @Test
    void shouldCloseMilestoneOnClosedEvent() throws Exception {
        GitHubMilestoneEventDTO createEvent = loadPayload("milestone.created");
        handler.handleEvent(createEvent);

        // Load closed event
        GitHubMilestoneEventDTO closedEvent = loadPayload("milestone.closed");

        handler.handleEvent(closedEvent);

        assertThat(milestoneRepository.findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, providerId()))
                .isPresent()
                .get()
                .satisfies(milestone -> {
                    assertThat(milestone.getState()).isEqualTo(Milestone.State.CLOSED);
                });
    }

    @Test
    void shouldReopenMilestoneOnOpenedEvent() throws Exception {
        Milestone closedMilestone = new Milestone();
        closedMilestone.setNativeId(FIXTURE_MILESTONE_ID);
        closedMilestone.setNumber(3);
        closedMilestone.setTitle("Fixture Milestone");
        closedMilestone.setState(Milestone.State.CLOSED);
        closedMilestone.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/3");
        closedMilestone.setRepository(testRepository);
        closedMilestone.setProvider(gitProvider);
        milestoneRepository.save(closedMilestone);

        // Load opened event
        GitHubMilestoneEventDTO openedEvent = loadPayload("milestone.opened");

        handler.handleEvent(openedEvent);

        assertThat(milestoneRepository.findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, providerId()))
                .isPresent()
                .get()
                .satisfies(milestone -> {
                    assertThat(milestone.getState()).isEqualTo(Milestone.State.OPEN);
                });
    }

    @Test
    void shouldDeleteMilestoneOnDeletedEvent() throws Exception {
        GitHubMilestoneEventDTO createEvent = loadPayload("milestone.created");
        handler.handleEvent(createEvent);

        // Verify it exists
        assertThat(milestoneRepository.findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, providerId()))
                .isPresent();

        // Load deleted event
        GitHubMilestoneEventDTO deleteEvent = loadPayload("milestone.deleted");

        handler.handleEvent(deleteEvent);

        assertThat(milestoneRepository.findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, providerId()))
                .isEmpty();
    }

    private GitHubMilestoneEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubMilestoneEventDTO.class);
    }

    /**
     * Creates a test repository reference DTO matching the fixture data.
     */
    private GitHubRepositoryRefDTO createTestRepoRef() {
        return new GitHubRepositoryRefDTO(
                FIXTURE_REPO_ID,
                "R_kgDOO4CKWw",
                FIXTURE_REPO_NAME,
                FIXTURE_REPO_FULL_NAME,
                false,
                "https://github.com/" + FIXTURE_REPO_FULL_NAME,
                null);
    }

    // Edge Case Tests

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleNullMilestoneGracefully() {
            GitHubMilestoneEventDTO event = new GitHubMilestoneEventDTO("created", null, createTestRepoRef(), null);

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();

            // No milestones should be created
            assertThat(milestoneRepository.count()).isZero();
        }

        @Test
        void shouldHandleMissingRepositoryContext() {
            GitHubMilestoneDTO milestoneDto = new GitHubMilestoneDTO(
                    999999L,
                    1,
                    "orphan-milestone",
                    "Test description",
                    "open",
                    null,
                    "https://example.com",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );
            GitHubMilestoneEventDTO event = new GitHubMilestoneEventDTO("created", milestoneDto, null, null);

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
        }

        @Test
        void shouldHandleMilestoneWithNullDescription() {
            Long milestoneId = 123456789L;
            GitHubMilestoneDTO milestoneDto = new GitHubMilestoneDTO(
                    milestoneId,
                    1,
                    "no-description-milestone",
                    null, // null description
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/1",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );
            GitHubMilestoneEventDTO event =
                    new GitHubMilestoneEventDTO("created", milestoneDto, createTestRepoRef(), null);

            handler.handleEvent(event);

            assertThat(milestoneRepository.findByNativeIdAndProviderId(milestoneId, providerId()))
                    .isPresent()
                    .get()
                    .satisfies(milestone -> {
                        assertThat(milestone.getTitle()).isEqualTo("no-description-milestone");
                        assertThat(milestone.getDescription()).isNull();
                    });
        }

        @Test
        void shouldHandleMilestoneWithNullDueOn() {
            Long milestoneId = 234567890L;
            GitHubMilestoneDTO milestoneDto = new GitHubMilestoneDTO(
                    milestoneId,
                    2,
                    "no-due-date-milestone",
                    "Description",
                    "open",
                    null, // null dueOn
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/2",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );
            GitHubMilestoneEventDTO event =
                    new GitHubMilestoneEventDTO("created", milestoneDto, createTestRepoRef(), null);

            handler.handleEvent(event);

            assertThat(milestoneRepository.findByNativeIdAndProviderId(milestoneId, providerId()))
                    .isPresent()
                    .get()
                    .satisfies(milestone -> {
                        assertThat(milestone.getDueOn()).isNull();
                    });
        }

        @Test
        void shouldUpdateDescriptionToNull() {
            Long milestoneId = 987654321L;
            Milestone existingMilestone = new Milestone();
            existingMilestone.setNativeId(milestoneId);
            existingMilestone.setNumber(10);
            existingMilestone.setTitle("has-description");
            existingMilestone.setState(Milestone.State.OPEN);
            existingMilestone.setDescription("original description");
            existingMilestone.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/10");
            existingMilestone.setRepository(testRepository);
            existingMilestone.setProvider(gitProvider);
            milestoneRepository.save(existingMilestone);

            // The handler sets only a non-null description.
            GitHubMilestoneDTO milestoneDto = new GitHubMilestoneDTO(
                    milestoneId,
                    10,
                    "has-description",
                    null, // setting to null
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/10",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );
            GitHubMilestoneEventDTO event =
                    new GitHubMilestoneEventDTO("edited", milestoneDto, createTestRepoRef(), null);
            handler.handleEvent(event);

            assertThat(milestoneRepository.findByNativeIdAndProviderId(milestoneId, providerId()))
                    .isPresent()
                    .get()
                    .extracting(Milestone::getDescription)
                    .isEqualTo("original description");
        }

        @Test
        void shouldHandleIdempotentCreation() throws Exception {
            GitHubMilestoneEventDTO event = loadPayload("milestone.created");

            handler.handleEvent(event);
            handler.handleEvent(event);

            assertThat(milestoneRepository.findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, providerId()))
                    .isPresent();
            assertThat(milestoneRepository.count()).isEqualTo(1);
        }

        @Test
        void shouldHandleDeletionOfNonExistentMilestone() throws Exception {
            GitHubMilestoneEventDTO event = loadPayload("milestone.deleted");
            assertThat(milestoneRepository.findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, providerId()))
                    .isEmpty();

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
        }

        @Test
        void shouldHandleUnknownAction() {
            GitHubMilestoneDTO milestoneDto = new GitHubMilestoneDTO(
                    111222333L,
                    1,
                    "unknown-action-milestone",
                    "desc",
                    "open",
                    null,
                    "https://example.com",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );
            GitHubMilestoneEventDTO event = new GitHubMilestoneEventDTO(
                    "unknown_action", // not created/edited/closed/opened/deleted
                    milestoneDto,
                    createTestRepoRef(),
                    null);

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
        }
    }

    // Repository Association Tests

    @Nested
    class RepositoryAssociation {

        @Test
        void shouldAssociateMilestoneWithRepository() throws Exception {
            GitHubMilestoneEventDTO event = loadPayload("milestone.created");

            handler.handleEvent(event);

            assertThat(milestoneRepository.findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, providerId()))
                    .isPresent()
                    .get()
                    .satisfies(milestone -> {
                        assertThat(milestone.getRepository()).isNotNull();
                        assertThat(milestone.getRepository().getNativeId()).isEqualTo(FIXTURE_REPO_ID);
                        assertThat(milestone.getRepository().getNameWithOwner()).isEqualTo(FIXTURE_REPO_FULL_NAME);
                    });
        }

        @Test
        void shouldFindMilestoneByRepositoryAndNumber() throws Exception {
            GitHubMilestoneEventDTO event = loadPayload("milestone.created");
            handler.handleEvent(event);

            assertNotNull(event.milestone());
            var foundMilestone = milestoneRepository.findByNumberAndRepositoryId(
                    event.milestone().number(), testRepository.getId());

            assertThat(foundMilestone).isPresent().get().satisfies(milestone -> {
                assertThat(milestone.getNativeId()).isEqualTo(FIXTURE_MILESTONE_ID);
                assertNotNull(event.milestone());
                assertThat(milestone.getTitle()).isEqualTo(event.milestone().title());
            });
        }
    }

    // Milestone-Issue Relationship Tests

    @Nested
    class MilestoneIssueRelationship {

        @Test
        void shouldPreserveMilestoneIssueRelationshipsAfterEdit() throws Exception {
            GitHubMilestoneEventDTO createEvent = loadPayload("milestone.created");
            handler.handleEvent(createEvent);

            Milestone milestone = milestoneRepository
                    .findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, providerId())
                    .orElseThrow();

            Issue issue = new Issue();
            issue.setNativeId(12345L);
            issue.setNumber(1);
            issue.setTitle("Test Issue");
            issue.setState(Issue.State.OPEN);
            issue.setRepository(testRepository);
            issue.setCreatedAt(Instant.now());
            issue.setUpdatedAt(Instant.now());
            issue.setMilestone(milestone);
            issue.setProvider(gitProvider);
            issueRepository.save(issue);

            GitHubMilestoneDTO editedDto = new GitHubMilestoneDTO(
                    FIXTURE_MILESTONE_ID,
                    3,
                    "Renamed Fixture Milestone",
                    "Updated description",
                    "open",
                    Instant.parse("2026-01-14T08:00:00Z"),
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/3",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );
            GitHubMilestoneEventDTO editEvent =
                    new GitHubMilestoneEventDTO("edited", editedDto, createTestRepoRef(), null);
            handler.handleEvent(editEvent);

            Issue updatedIssue = issueRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), 1)
                    .orElseThrow();
            assertThat(updatedIssue.getMilestone()).isNotNull().satisfies(m -> {
                assertThat(m.getTitle()).isEqualTo("Renamed Fixture Milestone");
                assertThat(m.getDescription()).isEqualTo("Updated description");
            });
        }
    }

    private Long providerId() {
        Long id = gitProvider.getId();
        assertNotNull(id);
        return id;
    }
}

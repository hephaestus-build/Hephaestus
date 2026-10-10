package de.tum.cit.aet.hephaestus.integration.scm.github.milestone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.milestone.dto.GitHubMilestoneDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GitHubMilestoneProcessorIntegrationTest extends BaseIntegrationTest {

    // IDs from the actual GitHub webhook fixtures
    private static final Long FIXTURE_ORG_ID = 215361191L;
    private static final Long FIXTURE_REPO_ID = 998279771L;
    private static final Long FIXTURE_CREATOR_ID = 5898705L;
    private static final String FIXTURE_ORG_LOGIN = "HephaestusTest";
    private static final String FIXTURE_REPO_FULL_NAME = "HephaestusTest/TestRepository";
    private static final String FIXTURE_CREATOR_LOGIN = "FelixTJDietrich";

    @Autowired
    private GitHubMilestoneProcessor processor;

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
    private UserRepository userRepository;

    @Autowired
    private RecordingScmEventListener eventListener;

    private Repository testRepository;
    private Workspace testWorkspace;
    private IdentityProvider githubProvider;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    private void setupTestData() {
        // Create GitHub provider
        githubProvider = gitProviderRepository
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
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/" + FIXTURE_ORG_ID);
        org.setHtmlUrl("https://github.com/" + FIXTURE_ORG_LOGIN);
        org.setProvider(githubProvider);
        org = organizationRepository.save(org);

        // Create repository matching fixture data
        testRepository = new Repository();
        testRepository.setNativeId(FIXTURE_REPO_ID);
        testRepository.setName("TestRepository");
        testRepository.setNameWithOwner(FIXTURE_REPO_FULL_NAME);
        testRepository.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME);
        testRepository.setVisibility(Repository.Visibility.PUBLIC);
        testRepository.setDefaultBranch("main");
        testRepository.setCreatedAt(Instant.now());
        testRepository.setUpdatedAt(Instant.now());
        testRepository.setPushedAt(Instant.now());
        testRepository.setOrganization(org);
        testRepository.setProvider(githubProvider);
        testRepository = repositoryRepository.save(testRepository);

        // Create workspace
        testWorkspace = new Workspace();
        testWorkspace.setWorkspaceSlug("hephaestus-test");
        testWorkspace.setDisplayName("Hephaestus Test");
        testWorkspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        testWorkspace.setPublicActivityEnabled(true);
        testWorkspace.setOrganization(org);
        testWorkspace.setAccountLogin(FIXTURE_ORG_LOGIN);
        testWorkspace.setAccountType(AccountType.ORG);
        testWorkspace = workspaceRepository.save(testWorkspace);
    }

    private ProcessingContext createContext() {
        return ProcessingContext.forSync(testWorkspace.getId(), testRepository);
    }

    private GitHubUserDTO createCreatorDto() {
        return new GitHubUserDTO(
                FIXTURE_CREATOR_ID,
                FIXTURE_CREATOR_ID,
                FIXTURE_CREATOR_LOGIN,
                "https://avatars.githubusercontent.com/u/" + FIXTURE_CREATOR_ID,
                "https://github.com/" + FIXTURE_CREATOR_LOGIN,
                null,
                null);
    }

    // Process (Create/Update) Tests

    @Nested
    class ProcessMethod {

        @Test
        void shouldCreateNewMilestoneAndPublishEvent() {
            Long milestoneId = 14028854L;
            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    milestoneId,
                    3,
                    "New Milestone",
                    "A new milestone for testing",
                    "open",
                    Instant.parse("2025-11-30T08:00:00Z"),
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/3",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );

            Milestone result = processor.process(dto, testRepository, createCreatorDto(), createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(milestoneId);
            assertThat(result.getNumber()).isEqualTo(3);
            assertThat(result.getTitle()).isEqualTo("New Milestone");
            assertThat(result.getDescription()).isEqualTo("A new milestone for testing");
            assertThat(result.getState()).isEqualTo(Milestone.State.OPEN);
            assertThat(result.getDueOn()).isNotNull();
            assertThat(result.getRepository().getNativeId()).isEqualTo(FIXTURE_REPO_ID);

            // Verify persisted
            assertThat(milestoneRepository.findByNativeIdAndProviderId(milestoneId, providerId()))
                    .isPresent();

            // Verify MilestoneCreated event published
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneCreated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.milestone().id()).isEqualTo(result.getId());
                        assertThat(event.context().scopeId()).isEqualTo(testWorkspace.getId());
                        assertNotNull(event.context().repository());
                        assertThat(event.context().repository().id()).isEqualTo(testRepository.getId());
                    });
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneUpdated.class))
                    .isEmpty();
        }

        @Test
        void shouldUpdateExistingMilestoneAndPublishEvent() {
            Long milestoneId = 14028854L;
            Milestone existing = new Milestone();
            existing.setNativeId(milestoneId);
            existing.setProvider(githubProvider);
            existing.setNumber(3);
            existing.setTitle("Old Title");
            existing.setDescription("Old description");
            existing.setState(Milestone.State.OPEN);
            existing.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/3");
            existing.setRepository(testRepository);
            milestoneRepository.save(existing);

            eventListener.clear();

            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    milestoneId,
                    3,
                    "Updated Title",
                    "Updated description",
                    "closed",
                    Instant.parse("2026-01-14T08:00:00Z"),
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/3",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );

            Milestone result = processor.process(dto, testRepository, createCreatorDto(), createContext());
            assertNotNull(result);

            assertThat(result.getTitle()).isEqualTo("Updated Title");
            assertThat(result.getDescription()).isEqualTo("Updated description");
            assertThat(result.getState()).isEqualTo(Milestone.State.CLOSED);

            // Verify MilestoneUpdated event published
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneUpdated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.milestone().title()).isEqualTo("Updated Title");
                    });
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneCreated.class))
                    .isEmpty();
        }

        @Test
        void shouldReturnNullForNullDto() {
            Milestone result = processor.process(null, testRepository, createCreatorDto(), createContext());

            assertThat(result).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneCreated.class))
                    .isEmpty();
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneUpdated.class))
                    .isEmpty();
        }

        @Test
        void shouldCreateMilestoneWithGeneratedIdWhenDtoHasNullId() {
            // A GraphQL sync DTO carries no ID.
            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    null, // null ID - simulates GraphQL response
                    1,
                    "GraphQL Synced Milestone",
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

            Milestone result = processor.process(dto, testRepository, null, createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isNotNull();
            assertThat(result.getNativeId()).isNegative(); // Generated IDs are negative to avoid collision
            assertThat(result.getNumber()).isEqualTo(1);
            assertThat(result.getTitle()).isEqualTo("GraphQL Synced Milestone");
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneCreated.class))
                    .hasSize(1);
        }

        @Test
        void shouldUpdateExistingMilestoneByNumberWhenDtoHasNullId() {
            Long existingId = 999888777L;
            Milestone existingMilestone = new Milestone();
            existingMilestone.setNativeId(existingId);
            existingMilestone.setProvider(githubProvider);
            existingMilestone.setNumber(42);
            existingMilestone.setTitle("Existing Milestone");
            existingMilestone.setState(Milestone.State.OPEN);
            existingMilestone.setDescription("old description");
            existingMilestone.setRepository(testRepository);
            milestoneRepository.save(existingMilestone);

            // DTO without ID (like GraphQL sync) but matching number
            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    null, // null ID
                    42, // same number
                    "Updated Title",
                    "new description",
                    "open",
                    null,
                    "https://example.com",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );

            Milestone result = processor.process(dto, testRepository, null, createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(existingId); // keeps original nativeId
            assertThat(result.getTitle()).isEqualTo("Updated Title");
            assertThat(result.getDescription()).isEqualTo("new description");
            assertThat(milestoneRepository.count()).isEqualTo(1);
        }

        @Test
        void shouldHandleMilestoneWithNullDescription() {
            Long milestoneId = 777888999L;
            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    milestoneId,
                    5,
                    "No Description Milestone",
                    null, // null description
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/5",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );

            Milestone result = processor.process(dto, testRepository, null, createContext());
            assertNotNull(result);

            assertThat(result.getDescription()).isNull();
            assertThat(milestoneRepository
                            .findByNativeIdAndProviderId(milestoneId, providerId())
                            .get()
                            .getDescription())
                    .isNull();
        }

        @Test
        void shouldHandleMilestoneWithNullDueOn() {
            Long milestoneId = 888999000L;
            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    milestoneId,
                    6,
                    "No Due Date Milestone",
                    "Has description but no due date",
                    "open",
                    null, // null dueOn
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/6",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );

            Milestone result = processor.process(dto, testRepository, null, createContext());
            assertNotNull(result);

            assertThat(result.getDueOn()).isNull();
            assertThat(milestoneRepository
                            .findByNativeIdAndProviderId(milestoneId, providerId())
                            .get()
                            .getDueOn())
                    .isNull();
        }

        @Test
        @DisplayName("Should be idempotent - processing same DTO twice")
        void shouldBeIdempotent() {
            Long milestoneId = 123123123L;
            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    milestoneId,
                    7,
                    "Idempotent Milestone",
                    "Same every time",
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/7",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );

            processor.process(dto, testRepository, null, createContext());
            eventListener.clear();
            processor.process(dto, testRepository, null, createContext());

            assertThat(milestoneRepository.count()).isEqualTo(1);
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneUpdated.class))
                    .hasSize(1);
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneCreated.class))
                    .isEmpty();
        }

        @Test
        void shouldSetCreatorWhenProvided() {
            Long milestoneId = 444555666L;
            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    milestoneId,
                    8,
                    "Milestone With Creator",
                    "Description",
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/8",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );

            Milestone result = processor.process(dto, testRepository, createCreatorDto(), createContext());
            assertNotNull(result);

            assertThat(result.getCreator()).isNotNull();
            assertThat(result.getCreator().getNativeId()).isEqualTo(FIXTURE_CREATOR_ID);
            assertThat(result.getCreator().getLogin()).isEqualTo(FIXTURE_CREATOR_LOGIN);
        }

        @Test
        void shouldCreateUserIfNotExists() {
            Long milestoneId = 555666777L;
            Long newUserId = 999888777L;
            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    milestoneId,
                    9,
                    "Milestone With New Creator",
                    "Description",
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/9",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );
            GitHubUserDTO newCreator = new GitHubUserDTO(
                    newUserId,
                    newUserId,
                    "new-creator",
                    "https://avatars.example.com/new",
                    "https://github.com/new-creator",
                    null,
                    null);

            // Verify user doesn't exist
            assertThat(userRepository.findByNativeIdAndProviderId(newUserId, providerId()))
                    .isEmpty();

            Milestone result = processor.process(dto, testRepository, newCreator, createContext());
            assertNotNull(result);

            assertThat(result.getCreator()).isNotNull();
            assertThat(userRepository.findByNativeIdAndProviderId(newUserId, providerId()))
                    .isPresent();
        }

        @Test
        void shouldHandleNullCreator() {
            Long milestoneId = 666777888L;
            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    milestoneId,
                    10,
                    "Milestone Without Creator",
                    "Description",
                    "open",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/10",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );

            Milestone result = processor.process(dto, testRepository, null, createContext());

            assertNotNull(result);
            assertThat(result.getCreator()).isNull();
        }

        @Test
        void shouldParseClosedState() {
            Long milestoneId = 777888999L;
            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    milestoneId,
                    11,
                    "Closed Milestone",
                    "Description",
                    "closed",
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/11",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );

            Milestone result = processor.process(dto, testRepository, null, createContext());
            assertNotNull(result);

            assertThat(result.getState()).isEqualTo(Milestone.State.CLOSED);
        }

        @Test
        void shouldDefaultToOpenStateForNullState() {
            Long milestoneId = 888999111L;
            GitHubMilestoneDTO dto = new GitHubMilestoneDTO(
                    milestoneId,
                    12,
                    "Unknown State Milestone",
                    "Description",
                    null, // null state
                    null,
                    "https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/12",
                    0,
                    0,
                    Instant.now(),
                    Instant.now(),
                    null // closedAt
                    );

            Milestone result = processor.process(dto, testRepository, null, createContext());
            assertNotNull(result);

            assertThat(result.getState()).isEqualTo(Milestone.State.OPEN);
        }
    }

    // Delete Tests

    @Nested
    class DeleteMethod {

        @Test
        void shouldDeleteMilestoneAndPublishEvent() {
            Long milestoneId = 14028854L;
            Milestone milestone = new Milestone();
            milestone.setNativeId(milestoneId);
            milestone.setProvider(githubProvider);
            milestone.setNumber(3);
            milestone.setTitle("To Delete Milestone");
            milestone.setState(Milestone.State.OPEN);
            milestone.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME + "/milestone/3");
            milestone.setRepository(testRepository);
            Milestone savedMilestone = milestoneRepository.save(milestone);

            assertThat(milestoneRepository.findByNativeIdAndProviderId(milestoneId, providerId()))
                    .isPresent();

            processor.delete(milestoneId, createContext());

            assertThat(milestoneRepository.findByNativeIdAndProviderId(milestoneId, providerId()))
                    .isEmpty();

            // Verify event published
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneDeleted.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.milestoneId()).isEqualTo(savedMilestone.getId());
                        assertThat(event.title()).isEqualTo("To Delete Milestone");
                        assertThat(event.context().scopeId()).isEqualTo(testWorkspace.getId());
                        assertNotNull(event.context().repository());
                        assertThat(event.context().repository().id()).isEqualTo(testRepository.getId());
                    });
        }

        @Test
        void shouldHandleDeletionOfNonExistentMilestone() {
            Long nonExistentId = 999999999L;
            assertThat(milestoneRepository.findByNativeIdAndProviderId(nonExistentId, providerId()))
                    .isEmpty();

            assertThatCode(() -> processor.delete(nonExistentId, createContext()))
                    .doesNotThrowAnyException();

            // No event published
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneDeleted.class))
                    .isEmpty();
        }

        @Test
        void shouldHandleNullMilestoneId() {
            assertThatCode(() -> processor.delete(null, createContext())).doesNotThrowAnyException();

            // No event published
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneDeleted.class))
                    .isEmpty();
        }
    }

    private Long providerId() {
        Long id = githubProvider.getId();
        assertNotNull(id);
        return id;
    }
}

package de.tum.cit.aet.hephaestus.activity;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for ActivityEventService.
 *
 * <p>Tests verify database-level behavior including:
 * <ul>
 *   <li>Idempotency via unique constraint on event_key</li>
 *   <li>Relationship to workspace, user, and repository entities</li>
 *   <li>Cache eviction behavior</li>
 * </ul>
 */
class ActivityEventServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ActivityEventService activityEventService;

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    private Workspace testWorkspace;
    private IdentityProvider gitProvider;
    private User testUser;
    private Repository testRepository;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setupTestData();
    }

    private void setupTestData() {
        // Create workspace
        testWorkspace = new Workspace();
        testWorkspace.setWorkspaceSlug("test-workspace");
        testWorkspace.setDisplayName("Test Workspace");
        testWorkspace.setAccountLogin("test-org");
        testWorkspace.setAccountType(AccountType.ORG);
        testWorkspace = workspaceRepository.save(testWorkspace);

        // Create git provider
        gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create user
        testUser = new User();
        testUser.setNativeId(12345L);
        testUser.setLogin("test-user");
        testUser.setName("Test User");
        testUser.setAvatarUrl("https://example.com/avatar.png");
        testUser.setHtmlUrl("https://github.com/test-user");
        testUser.setType(User.Type.USER);
        testUser.setCreatedAt(Instant.now());
        testUser.setUpdatedAt(Instant.now());
        testUser.setProvider(gitProvider);
        testUser = userRepository.save(testUser);

        // Create repository
        testRepository = new Repository();
        testRepository.setNativeId(67890L);
        testRepository.setName("test-repo");
        testRepository.setNameWithOwner("test-org/test-repo");
        testRepository.setHtmlUrl("https://github.com/test-org/test-repo");
        testRepository.setVisibility(Repository.Visibility.PUBLIC);
        testRepository.setDefaultBranch("main");
        testRepository.setPushedAt(Instant.now());
        testRepository.setCreatedAt(Instant.now());
        testRepository.setUpdatedAt(Instant.now());
        testRepository.setProvider(gitProvider);
        testRepository = repositoryRepository.save(testRepository);
    }

    @Nested
    class EventRecording {

        @Test
        void persistsEventWithAllFields() {
            Instant occurredAt = Instant.parse("2024-01-15T10:30:00Z");

            boolean result = activityEventService.record(
                    testWorkspace.getId(),
                    ActivityEventType.PULL_REQUEST_OPENED,
                    occurredAt,
                    testUser,
                    testRepository,
                    ActivityTargetType.PULL_REQUEST,
                    100L);

            assertThat(result).isTrue();

            List<ActivityEvent> events = activityEventRepository.findAll();
            assertThat(events).hasSize(1);

            ActivityEvent event = events.get(0);
            assertThat(event.getEventType()).isEqualTo(ActivityEventType.PULL_REQUEST_OPENED);
            assertThat(event.getOccurredAt()).isEqualTo(occurredAt);
            org.junit.jupiter.api.Assertions.assertNotNull(event.getActor());
            assertThat(event.getActor().getId()).isEqualTo(testUser.getId());
            assertThat(event.getRepository().getId()).isEqualTo(testRepository.getId());
            assertThat(event.getWorkspace().getId()).isEqualTo(testWorkspace.getId());
            assertThat(event.getTargetType()).isEqualTo(ActivityTargetType.PULL_REQUEST.getValue());
            assertThat(event.getTargetId()).isEqualTo(100L);
        }

        @Test
        void persistsEventWithoutOptionalFields() {
            boolean result = activityEventService.record(
                    testWorkspace.getId(),
                    ActivityEventType.ISSUE_CREATED,
                    Instant.now(),
                    null, // No actor (system event)
                    null, // No repository
                    ActivityTargetType.ISSUE,
                    200L);

            assertThat(result).isTrue();

            List<ActivityEvent> events = activityEventRepository.findAll();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getActor()).isNull();
            assertThat(events.get(0).getRepository()).isNull();
        }
    }

    @Nested
    class Idempotency {

        @Test
        void rejectsDuplicateEvents() {
            Instant occurredAt = Instant.now();

            // First record - should succeed
            boolean first = activityEventService.record(
                    testWorkspace.getId(),
                    ActivityEventType.PULL_REQUEST_OPENED,
                    occurredAt,
                    testUser,
                    testRepository,
                    ActivityTargetType.PULL_REQUEST,
                    100L);

            // Second record with same key - should be rejected
            boolean second = activityEventService.record(
                    testWorkspace.getId(),
                    ActivityEventType.PULL_REQUEST_OPENED,
                    occurredAt,
                    testUser,
                    testRepository,
                    ActivityTargetType.PULL_REQUEST,
                    100L);

            assertThat(first).isTrue();
            assertThat(second).isFalse();
            assertThat(activityEventRepository.findAll()).hasSize(1);
        }

        @Test
        void allowsSameEventTypeWithDifferentTimestamps() {
            Instant time1 = Instant.parse("2024-01-15T10:00:00Z");
            Instant time2 = Instant.parse("2024-01-15T11:00:00Z");

            boolean first = activityEventService.record(
                    testWorkspace.getId(),
                    ActivityEventType.REVIEW_APPROVED,
                    time1,
                    testUser,
                    testRepository,
                    ActivityTargetType.REVIEW,
                    100L);

            boolean second = activityEventService.record(
                    testWorkspace.getId(),
                    ActivityEventType.REVIEW_APPROVED,
                    time2,
                    testUser,
                    testRepository,
                    ActivityTargetType.REVIEW,
                    100L);

            assertThat(first).isTrue();
            assertThat(second).isTrue();
            assertThat(activityEventRepository.findAll()).hasSize(2);
        }
    }

    @Nested
    class WorkspaceValidation {

        @Test
        void returnsFalseForNonExistentWorkspace() {
            boolean result = activityEventService.record(
                    999999L, // Non-existent workspace ID
                    ActivityEventType.PULL_REQUEST_OPENED,
                    Instant.now(),
                    testUser,
                    testRepository,
                    ActivityTargetType.PULL_REQUEST,
                    100L);

            assertThat(result).isFalse();
            assertThat(activityEventRepository.findAll()).isEmpty();
        }
    }

    @Nested
    class EventKeyGeneration {

        @Test
        void generatesCorrectEventKey() {
            Instant occurredAt = Instant.parse("2024-01-15T10:30:00.000Z");

            activityEventService.record(
                    testWorkspace.getId(),
                    ActivityEventType.PULL_REQUEST_OPENED,
                    occurredAt,
                    testUser,
                    testRepository,
                    ActivityTargetType.PULL_REQUEST,
                    42L);

            List<ActivityEvent> events = activityEventRepository.findAll();
            assertThat(events).hasSize(1);

            String expectedKey = String.format("pull_request.opened:42:%d", occurredAt.toEpochMilli());
            assertThat(events.get(0).getEventKey()).isEqualTo(expectedKey);
        }
    }
}

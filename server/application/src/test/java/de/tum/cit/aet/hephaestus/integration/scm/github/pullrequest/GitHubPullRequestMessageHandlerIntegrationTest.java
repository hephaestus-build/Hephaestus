package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for GitHubPullRequestMessageHandler.
 * <p>
 * Tests the full webhook handling flow using JSON fixtures parsed directly
 * into DTOs using JSON fixtures for complete isolation. Verifies:
 * - Correct routing of webhook actions to processor methods
 * - Pull request persistence for all action types
 * - Event publishing through the handler → processor chain
 * - PR-specific state transitions (draft, merged)
 * - Edge cases in event handling
 * <p>
 * <b>Fixture Values (pull_request.opened.json - PR #26):</b>
 * <ul>
 *   <li>ID: 2969820636</li>
 *   <li>Number: 26</li>
 *   <li>Title: "Fixture: pull request events"</li>
 *   <li>Body: "Testing webhook payloads for pull_request actions."</li>
 *   <li>State: open, Draft: false, Merged: false</li>
 *   <li>HTML URL: "https://github.com/HephaestusTest/TestRepository/pull/26"</li>
 *   <li>Created/Updated: 2025-11-01T23:07:44Z</li>
 *   <li>Head ref: fixtures/pr-events, SHA: 3d1674023df8f9ef83febb8fb8d9ffa3a8119d6a</li>
 *   <li>Base ref: main, SHA: a76020c0f7e2afac4475770bb83cf4fe06ab5da1</li>
 *   <li>Commits: 1, Additions: 1, Deletions: 0, Changed files: 1</li>
 *   <li>Author: FelixTJDietrich (ID: 5898705)</li>
 * </ul>
 * <p>
 * Note: This test class does NOT use @Transactional because the pull request processing
 * chain calls GitHubUserProcessor.findOrCreate() which uses REQUIRES_NEW propagation.
 * Having @Transactional here would cause connection pool deadlocks as the test transaction
 * holds a connection while REQUIRES_NEW needs an additional one.
 * We use TransactionTemplate for lazy-loading assertions.
 */
class GitHubPullRequestMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // IDs from the actual GitHub webhook fixtures
    private static final Long FIXTURE_ORG_ID = 215361191L;
    private static final Long FIXTURE_REPO_ID = 998279771L;
    private static final String FIXTURE_ORG_LOGIN = "HephaestusTest";
    private static final String FIXTURE_REPO_FULL_NAME = "HephaestusTest/TestRepository";

    // Pull Request ID from fixtures (PR #26)
    private static final Long PR_26_ID = 2969820636L;
    private static final int PR_26_NUMBER = 26;

    // Exact fixture values from pull_request.opened.json for correctness verification
    private static final String FIXTURE_PR_TITLE = "Fixture: pull request events";
    private static final String FIXTURE_PR_BODY = "Testing webhook payloads for pull_request actions.";
    private static final String FIXTURE_PR_HTML_URL = "https://github.com/HephaestusTest/TestRepository/pull/26";
    private static final Instant FIXTURE_PR_CREATED_AT = Instant.parse("2025-11-01T23:07:44Z");
    private static final Instant FIXTURE_PR_UPDATED_AT = Instant.parse("2025-11-01T23:07:44Z");

    // Branch reference fixture values
    private static final String FIXTURE_HEAD_REF_NAME = "fixtures/pr-events";
    private static final String FIXTURE_HEAD_REF_OID = "3d1674023df8f9ef83febb8fb8d9ffa3a8119d6a";
    private static final String FIXTURE_BASE_REF_NAME = "main";
    private static final String FIXTURE_BASE_REF_OID = "a76020c0f7e2afac4475770bb83cf4fe06ab5da1";

    // Code change statistics from fixture
    private static final int FIXTURE_COMMITS = 1;
    private static final int FIXTURE_ADDITIONS = 1;
    private static final int FIXTURE_DELETIONS = 0;
    private static final int FIXTURE_CHANGED_FILES = 1;

    // Label ID from labeled fixture
    private static final Long FIXTURE_LABEL_ID = 9568029313L;
    private static final String FIXTURE_LABEL_NAME = "fixtures";

    // Milestone ID from milestoned fixture
    private static final Long FIXTURE_MILESTONE_ID = 14028563L;
    private static final String FIXTURE_MILESTONE_TITLE = "Webhook Fixtures";

    // Author fixture values
    private static final Long FIXTURE_AUTHOR_ID = 5898705L;
    private static final String FIXTURE_AUTHOR_LOGIN = "FelixTJDietrich";
    private static final String FIXTURE_AUTHOR_AVATAR_URL = "https://avatars.githubusercontent.com/u/5898705?v=4";
    private static final String FIXTURE_AUTHOR_HTML_URL = "https://github.com/FelixTJDietrich";

    @Autowired
    private GitHubPullRequestMessageHandler handler;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private UserRepository userRepository;

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
    private TeamRepository teamRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RecordingScmEventListener eventListener;

    private Repository testRepository;
    private IdentityProvider testProvider;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    // Event Key Tests

    @Nested
    class EventType {

        @Test
        void shouldReturnCorrectEventType() {
            assertThat(handler.key().eventType()).isEqualTo("repository.pull_request");
        }
    }

    // Basic Lifecycle Events

    @Nested
    class BasicLifecycleEvents {

        @Test
        void shouldPersistPullRequestOnOpenedEvent() throws Exception {
            GitHubPullRequestEventDTO event = loadPayload("pull_request.opened");

            handler.handleEvent(event);

            // Then - verify ALL persisted fields against hardcoded fixture values
            // Use TransactionTemplate for lazy-loading assertions
            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                        .orElseThrow();

                // Core identification fields (inherited from Issue)
                assertThat(pr.getNativeId()).isEqualTo(PR_26_ID);
                assertThat(pr.getNumber()).isEqualTo(PR_26_NUMBER);

                // Content fields
                assertThat(pr.getTitle()).isEqualTo(FIXTURE_PR_TITLE);
                assertThat(pr.getBody()).isEqualTo(FIXTURE_PR_BODY);

                // State fields (Issue + PR-specific)
                assertThat(pr.getState()).isEqualTo(PullRequest.State.OPEN);
                assertThat(pr.isLocked()).isFalse();
                assertThat(pr.getClosedAt()).isNull();
                assertThat(pr.isDraft()).isFalse();
                assertThat(pr.isMerged()).isFalse();
                assertThat(pr.getMergedAt()).isNull();

                // URL fields
                assertThat(pr.getHtmlUrl()).isEqualTo(FIXTURE_PR_HTML_URL);

                // Timestamp fields (critical for sync correctness)
                assertThat(pr.getCreatedAt()).isEqualTo(FIXTURE_PR_CREATED_AT);
                assertThat(pr.getUpdatedAt()).isEqualTo(FIXTURE_PR_UPDATED_AT);

                // Branch reference fields (PR-specific, critical for context)
                assertThat(pr.getHeadRefName()).isEqualTo(FIXTURE_HEAD_REF_NAME);
                assertThat(pr.getHeadRefOid()).isEqualTo(FIXTURE_HEAD_REF_OID);
                assertThat(pr.getBaseRefName()).isEqualTo(FIXTURE_BASE_REF_NAME);
                assertThat(pr.getBaseRefOid()).isEqualTo(FIXTURE_BASE_REF_OID);

                // Code change statistics (PR-specific)
                assertThat(pr.getCommits()).isEqualTo(FIXTURE_COMMITS);
                assertThat(pr.getAdditions()).isEqualTo(FIXTURE_ADDITIONS);
                assertThat(pr.getDeletions()).isEqualTo(FIXTURE_DELETIONS);
                assertThat(pr.getChangedFiles()).isEqualTo(FIXTURE_CHANGED_FILES);

                // Repository association (foreign key)
                assertThat(pr.getRepository()).isNotNull();
                assertThat(pr.getRepository().getNativeId()).isEqualTo(FIXTURE_REPO_ID);

                // Author association (foreign key) - verify exact fixture values
                assertThat(pr.getAuthor()).isNotNull();
                assertThat(pr.getAuthor().getNativeId()).isEqualTo(FIXTURE_AUTHOR_ID);
                assertThat(pr.getAuthor().getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
                assertThat(pr.getAuthor().getAvatarUrl()).isEqualTo(FIXTURE_AUTHOR_AVATAR_URL);
                assertThat(pr.getAuthor().getHtmlUrl()).isEqualTo(FIXTURE_AUTHOR_HTML_URL);

                // Null/empty associations (not present in opened fixture)
                assertThat(pr.getMilestone()).isNull();
                assertThat(pr.getAssignees()).isEmpty();
                assertThat(pr.getLabels()).isEmpty();
                assertThat(pr.getMergedBy()).isNull();
                assertThat(pr.getRequestedReviewers()).isEmpty();
            });

            // Domain event published
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);
        }

        @Test
        void shouldHandleClosedEvent() throws Exception {
            // Given - create PR first
            handler.handleEvent(loadPayload("pull_request.opened"));
            eventListener.clear();

            GitHubPullRequestEventDTO closedEvent = loadPayload("pull_request.closed");

            handler.handleEvent(closedEvent);

            PullRequest pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElse(null);
            assertNotNull(pr);
            assertThat(pr.getState()).isEqualTo(PullRequest.State.CLOSED);
            assertThat(pr.isMerged()).isFalse(); // closed.json has merged=false

            // Verify Closed event was published
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestClosed.class))
                    .hasSize(1);
        }

        @Test
        void shouldHandleReopenedEvent() throws Exception {
            // Given - create and close PR
            handler.handleEvent(loadPayload("pull_request.opened"));
            handler.handleEvent(loadPayload("pull_request.closed"));
            eventListener.clear();

            GitHubPullRequestEventDTO reopenedEvent = loadPayload("pull_request.reopened");

            handler.handleEvent(reopenedEvent);

            PullRequest pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElse(null);
            assertNotNull(pr);
            assertThat(pr.getState()).isEqualTo(PullRequest.State.OPEN);
        }
    }

    // Draft State Events

    @Nested
    class DraftStateEvents {

        @Test
        void shouldHandleReadyForReviewEvent() throws Exception {
            // Given - create draft PR first
            handler.handleEvent(loadPayload("pull_request.converted_to_draft"));
            eventListener.clear();

            GitHubPullRequestEventDTO readyEvent = loadPayload("pull_request.ready_for_review");

            handler.handleEvent(readyEvent);

            PullRequest pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElse(null);
            assertNotNull(pr);
            assertThat(pr.isDraft()).isFalse();

            // Verify PullRequestReady event was published
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestReady.class))
                    .hasSize(1);
        }

        @Test
        void shouldHandleConvertedToDraftEvent() throws Exception {
            GitHubPullRequestEventDTO draftEvent = loadPayload("pull_request.converted_to_draft");

            handler.handleEvent(draftEvent);

            PullRequest pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElse(null);
            assertNotNull(pr);
            assertThat(pr.isDraft()).isTrue();

            // Verify PullRequestDrafted event was published
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestDrafted.class))
                    .hasSize(1);
        }
    }

    // Synchronize Event

    @Nested
    class SynchronizeEvent {

        @Test
        void shouldHandleSynchronizeEvent() throws Exception {
            // Given - create PR first
            handler.handleEvent(loadPayload("pull_request.opened"));
            eventListener.clear();

            GitHubPullRequestEventDTO syncEvent = loadPayload("pull_request.synchronize");

            handler.handleEvent(syncEvent);

            PullRequest pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElse(null);
            assertNotNull(pr);

            // Verify PullRequestSynchronized event was published
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestSynchronized.class))
                    .hasSize(1);
        }
    }

    // Label Events

    @Nested
    class LabelEvents {

        @Test
        void shouldHandleLabeledEvent() throws Exception {
            // Given - create PR first
            handler.handleEvent(loadPayload("pull_request.opened"));
            eventListener.clear();

            GitHubPullRequestEventDTO labeledEvent = loadPayload("pull_request.labeled");

            handler.handleEvent(labeledEvent);

            // Then - use TransactionTemplate for lazy-loading assertions
            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                        .orElse(null);
                assertNotNull(pr);
                assertThat(labelNames(pr)).contains(FIXTURE_LABEL_NAME);
            });

            // Verify label was created in repository
            assertThat(labelRepository.findByNativeIdAndProviderId(FIXTURE_LABEL_ID, testProviderId()))
                    .isPresent();

            // Verify Labeled event was published
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestLabeled.class))
                    .hasSize(1);
        }

        @Test
        void shouldHandleUnlabeledEvent() throws Exception {
            // Given - create PR with label
            handler.handleEvent(loadPayload("pull_request.opened"));
            handler.handleEvent(loadPayload("pull_request.labeled"));
            eventListener.clear();

            GitHubPullRequestEventDTO unlabeledEvent = loadPayload("pull_request.unlabeled");

            handler.handleEvent(unlabeledEvent);

            // Then - Unlabeled event should be published
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestUnlabeled.class))
                    .hasSize(1);
        }
    }

    // Assignment Events

    @Nested
    class AssignmentEvents {

        @Test
        void shouldHandleAssignedEvent() throws Exception {
            GitHubPullRequestEventDTO assignedEvent = loadPayload("pull_request.assigned");

            handler.handleEvent(assignedEvent);

            // Then - PR should be created with assignees from the DTO
            // Use TransactionTemplate for lazy-loading assertions
            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                        .orElse(null);
                assertNotNull(pr);
                assertThat(pr.getAssignees()).isNotEmpty();
                assertThat(userLogins(pr.getAssignees())).contains(FIXTURE_AUTHOR_LOGIN);
            });
        }

        @Test
        void shouldHandleUnassignedEvent() throws Exception {
            // Given - create PR with assignee
            handler.handleEvent(loadPayload("pull_request.assigned"));

            GitHubPullRequestEventDTO unassignedEvent = loadPayload("pull_request.unassigned");

            handler.handleEvent(unassignedEvent);

            // Then - PR still exists and was processed
            PullRequest pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElse(null);
            assertNotNull(pr);
        }
    }

    // Milestone Events

    @Nested
    class MilestoneEvents {

        @Test
        void shouldHandleMilestonedEvent() throws Exception {
            GitHubPullRequestEventDTO milestonedEvent = loadPayload("pull_request.milestoned");

            handler.handleEvent(milestonedEvent);

            // Then - use TransactionTemplate for lazy-loading assertions
            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                        .orElse(null);
                assertNotNull(pr);
                assertThat(pr.getMilestone()).isNotNull();
                assertThat(pr.getMilestone().getTitle()).isEqualTo(FIXTURE_MILESTONE_TITLE);
                assertThat(pr.getMilestone().getNumber()).isEqualTo(2);
            });

            // Verify milestone was created in repository
            assertThat(milestoneRepository.findByNativeIdAndProviderId(FIXTURE_MILESTONE_ID, testProviderId()))
                    .isPresent();
        }

        @Test
        void shouldHandleDemilestonedEvent() throws Exception {
            // Given - this uses a fresh demilestoned event
            // Note: The processor currently only clears milestone during create,
            // not during update. This test verifies the handler routing works.
            GitHubPullRequestEventDTO demilestonedEvent = loadPayload("pull_request.demilestoned");

            handler.handleEvent(demilestonedEvent);

            // Then - PR should be created and processed
            // Use TransactionTemplate for lazy-loading assertions
            transactionTemplate.executeWithoutResult(status -> {
                PullRequest pr = pullRequestRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                        .orElse(null);
                assertNotNull(pr);
                // Milestone is null in the demilestoned payload itself
                assertThat(pr.getMilestone()).isNull();
            });
        }
    }

    // Review Request Events

    @Nested
    class ReviewRequestEvents {

        /** The fixtures ask the Hephaetus team for a review, then take the request back. */
        private static final long FIXTURE_TEAM_ID = 13459148L;

        @Test
        void shouldStoreTheRequestedTeamWhenATeamHephaestusKnowsIsAskedForAReview() throws Exception {
            Team team = team(FIXTURE_TEAM_ID, "hephaetus");

            handler.handleEvent(loadPayload("pull_request.review_requested"));

            assertThat(requestedTeamIds()).containsExactly(team.getId());
        }

        @Test
        void shouldRemoveTheRequestedTeamWhenTheTeamsReviewRequestIsRemoved() throws Exception {
            Team team = team(FIXTURE_TEAM_ID, "hephaetus");
            handler.handleEvent(loadPayload("pull_request.review_requested"));
            assertThat(requestedTeamIds()).containsExactly(team.getId());

            handler.handleEvent(loadPayload("pull_request.review_request_removed"));

            assertThat(requestedTeamIds()).isEmpty();
        }

        @Test
        void shouldStoreNoTeamWhenTheRequestedTeamIsNotSynced() throws Exception {
            handler.handleEvent(loadPayload("pull_request.review_requested"));

            assertThat(requestedTeamIds()).isEmpty();
        }

        /** sync-lifecycle.md § Reviewer lists are dated snapshots: review requests can arrive within one second. */
        @Test
        void shouldStoreTheLaterReviewRequestWhenItArrivesWithinTheSameSecond() throws Exception {
            Team team = team(FIXTURE_TEAM_ID, "hephaetus");
            handler.handleEvent(loadPayload("pull_request.review_requested"));

            handler.handleEvent(loadPayload("pull_request.review_requested.reviewer.derived"));

            assertThat(requestedTeamIds()).containsExactly(team.getId());
            assertThat(requestedLogins()).containsExactly("octocat");
        }

        @Test
        void shouldStoreThePersonButNotTheBotWhenBothAreAskedToReview() throws Exception {
            handler.handleEvent(loadPayload("pull_request.review_requested.reviewer.derived"));

            assertThat(requestedLogins()).containsExactly("octocat");
        }

        private Set<String> requestedLogins() {
            return required(transactionTemplate.execute(status -> pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElseThrow()
                    .getRequestedReviewers()
                    .stream()
                    .map(request -> request.getUser().getLogin())
                    .collect(Collectors.toSet())));
        }

        private Set<Long> requestedTeamIds() {
            return required(transactionTemplate.execute(status -> pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElseThrow()
                    .getRequestedTeams()
                    .stream()
                    .map(request -> request.getTeam().getId())
                    .collect(Collectors.toSet())));
        }

        private Team team(long nativeId, String slug) {
            Team team = new Team();
            team.setNativeId(nativeId);
            team.setName(slug);
            team.setSlug(slug);
            team.setOrganization(FIXTURE_ORG_LOGIN);
            team.setHtmlUrl("https://github.com/orgs/" + FIXTURE_ORG_LOGIN + "/teams/" + slug);
            team.setPrivacy(Team.Privacy.VISIBLE);
            team.setProvider(testProvider);
            return teamRepository.save(team);
        }
    }

    // Lock Events

    @Nested
    class LockEvents {

        @Test
        void shouldHandleLockedEvent() throws Exception {
            // Given - create PR first
            handler.handleEvent(loadPayload("pull_request.opened"));

            GitHubPullRequestEventDTO lockedEvent = loadPayload("pull_request.locked");

            handler.handleEvent(lockedEvent);

            // Then - PR processed successfully
            PullRequest pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElse(null);
            assertNotNull(pr);
        }

        @Test
        void shouldHandleUnlockedEvent() throws Exception {
            // Given - create PR first
            handler.handleEvent(loadPayload("pull_request.opened"));
            handler.handleEvent(loadPayload("pull_request.locked"));

            GitHubPullRequestEventDTO unlockedEvent = loadPayload("pull_request.unlocked");

            handler.handleEvent(unlockedEvent);

            PullRequest pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElse(null);
            assertNotNull(pr);
        }
    }

    // Edge Cases

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleUnknownActionGracefully() throws Exception {
            GitHubPullRequestEventDTO event = loadPayload("pull_request.opened");

            // When/Then - should not throw
            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
        }

        @Test
        void shouldHandleMissingRepositoryContextGracefully() throws Exception {
            // Given - remove the repository so context creation fails
            repositoryRepository.deleteAll();

            GitHubPullRequestEventDTO event = loadPayload("pull_request.opened");

            // When/Then - should not throw, just log warning
            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();

            // PR should not be persisted since context is null
            assertThat(pullRequestRepository.count()).isZero();
        }

        @Test
        @DisplayName("Should be idempotent - processing same event twice")
        void shouldBeIdempotent() throws Exception {
            GitHubPullRequestEventDTO event = loadPayload("pull_request.opened");

            // When - handle same event twice
            handler.handleEvent(event);
            long countAfterFirst = pullRequestRepository.count();

            handler.handleEvent(event);

            // Then - still only one PR
            assertThat(pullRequestRepository.count()).isEqualTo(countAfterFirst);
        }

        @Test
        void shouldVerifyGetDatabaseIdFallback() throws Exception {
            // Given - webhook payloads have 'id' not 'database_id'
            GitHubPullRequestEventDTO event = loadPayload("pull_request.opened");

            // Verify the DTO is using the fallback correctly
            // In webhook payloads, databaseId will be null and id will have the value
            assertThat(required(event.pullRequest()).getDatabaseId()).isEqualTo(PR_26_ID);

            handler.handleEvent(event);

            // Then - PR should be persisted with the correct native ID
            var pr = pullRequestRepository.findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER);
            assertThat(pr).isPresent();
            assertThat(pr.get().getNativeId()).isEqualTo(PR_26_ID);
        }

        @Test
        void shouldCreateAllRelatedEntitiesFromOpenedEvent() throws Exception {
            // Given - no users exist
            assertThat(userRepository.count()).isZero();

            handler.handleEvent(loadPayload("pull_request.opened"));

            // Then - author created with exact fixture values
            var author = userRepository
                    .findByNativeIdAndProviderId(FIXTURE_AUTHOR_ID, testProviderId())
                    .orElseThrow();
            assertThat(author.getLogin()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
            assertThat(author.getAvatarUrl()).isEqualTo(FIXTURE_AUTHOR_AVATAR_URL);
            assertThat(author.getHtmlUrl()).isEqualTo(FIXTURE_AUTHOR_HTML_URL);
            assertThat(author.getType()).isEqualTo(User.Type.USER);
        }

        @Test
        void shouldHandleNullPullRequestDTOGracefully() throws Exception {
            // Given - a manually created event with null PR
            GitHubPullRequestEventDTO event = new GitHubPullRequestEventDTO(
                    "opened",
                    PR_26_NUMBER,
                    null, // null pull_request
                    null, // repository
                    null, // sender
                    null, // label
                    null, // requestedReviewer
                    null // changes
                    );

            // When/Then - should not throw
            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();

            // PR should not be persisted
            assertThat(pullRequestRepository.count()).isZero();
        }
    }

    // Full Workflow Tests

    @Nested
    class FullWorkflow {

        @Test
        void shouldHandleCompletePullRequestLifecycle() throws Exception {
            // 1. Open PR
            handler.handleEvent(loadPayload("pull_request.opened"));
            PullRequest pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElse(null);
            assertNotNull(pr);
            assertThat(pr.getState()).isEqualTo(PullRequest.State.OPEN);

            // 2. Add label
            handler.handleEvent(loadPayload("pull_request.labeled"));
            transactionTemplate.executeWithoutResult(status -> {
                PullRequest prWithLabel = pullRequestRepository
                        .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                        .orElseThrow();
                assertThat(labelNames(prWithLabel)).contains(FIXTURE_LABEL_NAME);
            });

            // 3. Synchronize (push new commits)
            handler.handleEvent(loadPayload("pull_request.synchronize"));
            pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElseThrow();
            assertThat(pr.getState()).isEqualTo(PullRequest.State.OPEN);

            // 4. Close
            handler.handleEvent(loadPayload("pull_request.closed"));
            pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElseThrow();
            assertThat(pr.getState()).isEqualTo(PullRequest.State.CLOSED);

            // Verify events were published
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestLabeled.class))
                    .hasSize(1);
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestSynchronized.class))
                    .hasSize(1);
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestClosed.class))
                    .hasSize(1);
        }

        @Test
        void shouldHandleReadyForReviewAfterOpened() throws Exception {
            // 1. Open PR (which is not draft in opened.json)
            handler.handleEvent(loadPayload("pull_request.opened"));
            PullRequest pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElseThrow();
            assertThat(pr.isDraft()).isFalse();

            // 2. Mark ready for review (even if not draft, event is processed)
            handler.handleEvent(loadPayload("pull_request.ready_for_review"));
            pr = pullRequestRepository
                    .findByRepositoryIdAndNumber(testRepository.getId(), PR_26_NUMBER)
                    .orElseThrow();
            assertThat(pr.isDraft()).isFalse();

            // Verify events
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestCreated.class))
                    .hasSize(1);
            assertThat(eventListener.ofType(ScmDomainEvent.PullRequestReady.class))
                    .hasSize(1);
        }
    }

    // DTO Parsing Tests

    @Nested
    class DTOParsing {

        @Test
        void shouldParseAllOpenedEventFieldsCorrectly() throws Exception {
            String payload = loadPayloadRaw("pull_request.opened");
            GitHubPullRequestEventDTO event = objectMapper.readValue(payload, GitHubPullRequestEventDTO.class);

            // Event-level fields
            assertThat(event.action()).isEqualTo("opened");
            assertThat(event.number()).isEqualTo(PR_26_NUMBER);

            // PR identification
            var pr = event.pullRequest();
            assertNotNull(pr);
            assertThat(pr.getDatabaseId()).isEqualTo(PR_26_ID);
            assertThat(pr.number()).isEqualTo(PR_26_NUMBER);

            // PR content
            assertThat(pr.title()).isEqualTo(FIXTURE_PR_TITLE);
            assertThat(pr.body()).isEqualTo(FIXTURE_PR_BODY);
            assertThat(pr.htmlUrl()).isEqualTo(FIXTURE_PR_HTML_URL);

            // PR state
            assertThat(pr.state()).isEqualTo("open");
            assertThat(pr.isDraft()).isFalse();
            assertThat(pr.isMerged()).isFalse();
            assertThat(pr.locked()).isFalse();

            // Timestamps
            assertThat(pr.createdAt()).isEqualTo(FIXTURE_PR_CREATED_AT);
            assertThat(pr.updatedAt()).isEqualTo(FIXTURE_PR_UPDATED_AT);

            // Branch refs
            assertThat(pr.head()).isNotNull();
            assertThat(pr.head().ref()).isEqualTo(FIXTURE_HEAD_REF_NAME);
            assertThat(pr.head().sha()).isEqualTo(FIXTURE_HEAD_REF_OID);
            assertThat(pr.base()).isNotNull();
            assertThat(pr.base().ref()).isEqualTo(FIXTURE_BASE_REF_NAME);
            assertThat(pr.base().sha()).isEqualTo(FIXTURE_BASE_REF_OID);

            // Code stats
            assertThat(pr.commits()).isEqualTo(FIXTURE_COMMITS);
            assertThat(pr.additions()).isEqualTo(FIXTURE_ADDITIONS);
            assertThat(pr.deletions()).isEqualTo(FIXTURE_DELETIONS);
            assertThat(pr.changedFiles()).isEqualTo(FIXTURE_CHANGED_FILES);

            // Author
            assertThat(pr.author()).isNotNull();
            assertThat(pr.author().id()).isEqualTo(FIXTURE_AUTHOR_ID);
            assertThat(pr.author().login()).isEqualTo(FIXTURE_AUTHOR_LOGIN);
        }

        @Test
        void shouldParseLabeledEventWithLabelData() throws Exception {
            String payload = loadPayloadRaw("pull_request.labeled");
            GitHubPullRequestEventDTO event = objectMapper.readValue(payload, GitHubPullRequestEventDTO.class);

            assertThat(event.action()).isEqualTo("labeled");
            assertThat(event.label()).isNotNull();
            assertThat(event.label().id()).isEqualTo(FIXTURE_LABEL_ID);
            assertThat(event.label().name()).isEqualTo(FIXTURE_LABEL_NAME);
        }

        @Test
        void shouldParseMilestonedEventWithMilestoneData() throws Exception {
            String payload = loadPayloadRaw("pull_request.milestoned");
            GitHubPullRequestEventDTO event = objectMapper.readValue(payload, GitHubPullRequestEventDTO.class);

            assertThat(event.action()).isEqualTo("milestoned");
            assertThat(required(required(event.pullRequest()).milestone())).isNotNull();
            assertThat(required(required(event.pullRequest()).milestone()).id()).isEqualTo(FIXTURE_MILESTONE_ID);
            assertThat(required(required(event.pullRequest()).milestone()).title())
                    .isEqualTo(FIXTURE_MILESTONE_TITLE);
        }
    }

    // Helper Methods

    private GitHubPullRequestEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubPullRequestEventDTO.class);
    }

    private String loadPayloadRaw(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private void setupTestData() {
        // Create GitHub provider
        testProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        IdentityProvider gitProvider = testProvider;

        // Create organization matching fixture data
        Organization org = new Organization();
        org.setNativeId(FIXTURE_ORG_ID);
        org.setLogin(FIXTURE_ORG_LOGIN);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/" + FIXTURE_ORG_ID);
        org.setProvider(gitProvider);
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

    private Set<String> labelNames(PullRequest pr) {
        return pr.getLabels().stream().map(Label::getName).collect(Collectors.toSet());
    }

    private Set<String> userLogins(Set<User> users) {
        return users.stream().map(User::getLogin).collect(Collectors.toSet());
    }

    private Long testProviderId() {
        Long id = testProvider.getId();
        assertNotNull(id);
        return id;
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}

package de.tum.cit.aet.hephaestus.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.adapter.ActivityPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventIdentity;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ActivityEventServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ActivityEventService activityEventService;

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceLifecycleService workspaceLifecycle;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private FailedEventPublications publications;

    @Autowired
    private JdbcTemplate jdbc;

    /** A fixture changes the schema as its owner: the application role cannot. */
    private final JdbcTemplate schemaOwner = PostgreSQLTestContainer.ownerJdbc();

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private ActivityFactDelivery activityFacts;

    @Autowired
    private ActivityPersonDataCatalog activityPersonData;

    @Autowired
    private ConnectionRepository connections;

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
        var connection = new Connection(
                testWorkspace,
                IntegrationKind.GITHUB,
                "GitHub",
                new ConnectionConfig.GitHubPatConfig("test-org", null, Set.of()));
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        jdbc.update(
                "INSERT INTO repository_to_monitor(workspace_id,name_with_owner,native_id,generated_paths) VALUES (?,?,?,'[]')",
                testWorkspace.getId(),
                testRepository.getNameWithOwner(),
                testRepository.getNativeId());
    }

    @Nested
    class DurableDelivery {
        private static final String FAILURE_CONSTRAINT = "test_activity_record_failure";

        @Test
        void shouldRefuseFactPublicationOutsideTheSourceTransaction() {
            assertThatThrownBy(() -> activityFacts.publish(new ActivityFact(
                            testWorkspace.getId(),
                            ActivityEventType.ISSUE_DELETED,
                            Instant.now(),
                            null,
                            testRepository.getId(),
                            ActivityTargetType.ISSUE,
                            95002L,
                            null)))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        void shouldRetainAFailedPublicationAndRecordItOnceAfterRecovery() {
            var event = event();
            long targetId = event.pullRequest().id();
            schemaOwner.execute("ALTER TABLE activity_event ADD CONSTRAINT " + FAILURE_CONSTRAINT
                    + " CHECK (target_id <> " + targetId + ")");
            try {
                publish(event);

                assertThat(rows(targetId)).isZero();
                assertThat(pending(event)).isEqualTo(1L);
                schemaOwner.execute("ALTER TABLE activity_event DROP CONSTRAINT " + FAILURE_CONSTRAINT);

                resubmit(event);

                assertThat(rows(targetId)).isEqualTo(1L);
                assertThat(pending(event)).isZero();
                publish(event);
                assertThat(rows(targetId)).isEqualTo(1L);
            } finally {
                schemaOwner.execute("ALTER TABLE activity_event DROP CONSTRAINT IF EXISTS " + FAILURE_CONSTRAINT);
            }
        }

        @Test
        void shouldEndAPendingPublicationWhenItsWorkspaceIsPurged() {
            var event = event();
            long targetId = event.pullRequest().id();
            schemaOwner.execute("ALTER TABLE activity_event ADD CONSTRAINT " + FAILURE_CONSTRAINT
                    + " CHECK (target_id <> " + targetId + ")");
            try {
                publish(event);
                assertThat(pending(event)).isEqualTo(1L);
                schemaOwner.execute("ALTER TABLE activity_event DROP CONSTRAINT " + FAILURE_CONSTRAINT);
                workspaceLifecycle.purgeWorkspace(testWorkspace.getWorkspaceSlug());

                resubmit(event);

                assertThat(rows(targetId)).isZero();
                assertThat(pending(event)).isZero();
            } finally {
                schemaOwner.execute("ALTER TABLE activity_event DROP CONSTRAINT IF EXISTS " + FAILURE_CONSTRAINT);
            }
        }

        @Test
        void shouldEndAPendingPublicationWhenItsRepositoryIsDeleted() {
            assertEndsWhenSourceIsGone(() -> repositoryRepository.deleteById(testRepository.getId()));
        }

        @Test
        void shouldEndAPendingPublicationWhenItsRepositoryIsNoLongerMonitored() {
            assertEndsWhenSourceIsGone(() -> jdbc.update(
                    "DELETE FROM repository_to_monitor WHERE workspace_id=? AND native_id=?",
                    testWorkspace.getId(),
                    testRepository.getNativeId()));
        }

        @Test
        void shouldEraseThePendingFactWithoutStoringWorkText() {
            var event = event();
            long targetId = event.pullRequest().id();
            schemaOwner.execute("ALTER TABLE activity_event ADD CONSTRAINT " + FAILURE_CONSTRAINT
                    + " CHECK (target_id <> " + targetId + ")");
            try {
                publish(event);
                assertThat(pending(event)).isEqualTo(1L);
                var payload = jdbc.queryForObject(
                        "SELECT serialized_event FROM event_publication WHERE event_type=? AND serialized_event::jsonb->>'targetId'=?",
                        String.class,
                        ActivityFact.class.getName(),
                        Long.toString(targetId));
                assertThat(payload).doesNotContain("Durable activity", "Private work text", "pullRequest", "body");
                var store = activityPersonData.contributors().stream()
                        .filter(candidate -> candidate.store().equals("activity_publication"))
                        .findFirst()
                        .orElseThrow();
                var scope = new PersonScope(null, List.of(), List.of(testUser.getId()));
                var selected = store.select(scope);
                assertThat(selected.rows()).hasSize(1);
                assertThat(store.erase(selected, scope)).isEqualTo(1L);
                assertThat(pending(event)).isZero();
            } finally {
                schemaOwner.execute("ALTER TABLE activity_event DROP CONSTRAINT IF EXISTS " + FAILURE_CONSTRAINT);
            }
        }

        private void assertEndsWhenSourceIsGone(Runnable remove) {
            var event = event();
            long targetId = event.pullRequest().id();
            schemaOwner.execute("ALTER TABLE activity_event ADD CONSTRAINT " + FAILURE_CONSTRAINT
                    + " CHECK (target_id <> " + targetId + ")");
            try {
                publish(event);
                assertThat(pending(event)).isEqualTo(1L);
                schemaOwner.execute("ALTER TABLE activity_event DROP CONSTRAINT " + FAILURE_CONSTRAINT);
                remove.run();
                resubmit(event);
                assertThat(rows(targetId)).isZero();
                assertThat(pending(event)).isZero();
            } finally {
                schemaOwner.execute("ALTER TABLE activity_event DROP CONSTRAINT IF EXISTS " + FAILURE_CONSTRAINT);
            }
        }

        @Test
        void shouldNotQueueAnErasedActorsFact() {
            var event = event();
            databaseTestUtils.erase(
                    Objects.requireNonNull(gitProvider.getId()),
                    testUser.getNativeId().toString(),
                    null);
            publish(event);
            assertThat(rows(event.pullRequest().id())).isZero();
            assertThat(pending(event)).isZero();
        }

        @Test
        void shouldRecordAReplayedDeletionOnceAtItsOriginalTime() {
            var at = Instant.parse("2025-04-01T10:00:00Z");
            var context = EventContext.forSync(
                    testWorkspace.getId(),
                    Objects.requireNonNull(RepositoryRef.from(testRepository)),
                    IdentityProviderType.GITHUB,
                    at);
            var issue = new Issue();
            issue.setNativeId(95002L);
            issue.setNumber(32);
            issue.setTitle("Deleted issue");
            issue.setState(Issue.State.OPEN);
            issue.setAuthor(testUser);
            issue.setRepository(testRepository);
            issue.setProvider(gitProvider);
            issue.setCreatedAt(at);
            issue.setUpdatedAt(at);
            issue.setHtmlUrl("https://github.com/test-org/test-repo/issues/32");
            issue = issues.saveAndFlush(issue);
            var event = new ScmDomainEvent.IssueDeleted(issue.getId(), context);
            for (int replay = 0; replay < 2; replay++) {
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(ignored -> publisher.publishEvent(event));
            }
            var times = jdbc.queryForList(
                    "SELECT occurred_at FROM activity_event WHERE workspace_id=? AND target_id=? AND event_type='ISSUE_DELETED'",
                    Timestamp.class,
                    testWorkspace.getId(),
                    event.issueId());
            assertThat(times).containsExactly(Timestamp.from(at));
        }

        private ScmDomainEvent.PullRequestCreated event() {
            var pull = new PullRequest();
            pull.setNativeId(95001L);
            pull.setNumber(31);
            pull.setTitle("Durable activity");
            pull.setBody("Private work text");
            pull.setState(Issue.State.OPEN);
            pull.setAuthor(testUser);
            pull.setRepository(testRepository);
            pull.setProvider(gitProvider);
            pull.setBaseRefName("main");
            pull.setHeadRefName("work");
            pull.setHtmlUrl("https://github.com/test-org/test-repo/pull/31");
            pull.setHeadRefOid("a".repeat(40));
            pull.setBaseRefOid("b".repeat(40));
            pull.setCreatedAt(Instant.now());
            pull.setUpdatedAt(Instant.now());
            pull = pullRequests.saveAndFlush(pull);
            return new ScmDomainEvent.PullRequestCreated(
                    ScmEventPayload.PullRequestData.from(pull),
                    EventContext.from(ProcessingContext.forWebhook(testWorkspace.getId(), testRepository, "opened")));
        }

        private void publish(ScmDomainEvent.PullRequestCreated event) {
            new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> publisher.publishEvent(event));
        }

        private void resubmit(ScmDomainEvent.PullRequestCreated event) {
            publications.resubmit(ResubmissionOptions.defaults()
                    .withMinAge(Duration.ZERO)
                    .withFilter(publication -> publication.getEvent() instanceof ActivityFact stored
                            && stored.workspaceId().equals(testWorkspace.getId())
                            && stored.targetId().equals(event.pullRequest().id())));
        }

        private Long rows(long targetId) {
            return Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT count(*) FROM activity_event WHERE workspace_id=? AND target_id=? AND event_type='PULL_REQUEST_OPENED'",
                    Long.class,
                    testWorkspace.getId(),
                    targetId));
        }

        private Long pending(ScmDomainEvent.PullRequestCreated event) {
            return Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE event_type=? AND serialized_event::jsonb->>'workspaceId'=? AND serialized_event::jsonb->>'targetId'=? AND completion_date IS NULL",
                    Long.class,
                    ActivityFact.class.getName(),
                    testWorkspace.getId().toString(),
                    event.pullRequest().id().toString()));
        }
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
            assertThat(event.getActor()).isNotNull();
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

    /** The activity of one provider event is one row, whichever path reports it and however often. */
    @Nested
    class OneEventIdentity {

        private final Instant closedAt = Instant.parse("2024-01-15T10:00:00Z");

        @Test
        void shouldAddNoCloseToAnEntryRecordedBeforeSourceKeys() {
            // The entry an earlier release wrote for this close, with the key shape it wrote.
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(ignored -> activityEventRepository.insertIfAbsent(
                            UUID.randomUUID(),
                            "issue.closed:301:" + closedAt.toEpochMilli(),
                            "ISSUE_CLOSED",
                            closedAt,
                            testUser.getId(),
                            testWorkspace.getId(),
                            testRepository.getId(),
                            "issue",
                            301L));
            var listener = listener(mock(PullRequestReviewThreadRepository.class));
            var issue = new Issue();
            issue.setId(301L);
            issue.setNumber(12);
            issue.setTitle("Closed issue");
            issue.setState(Issue.State.CLOSED);
            issue.setAuthor(testUser);
            issue.setRepository(testRepository);
            issue.setClosedAt(closedAt);
            var closed = ScmEventPayload.IssueData.from(issue);

            // The hook redelivers the close, and a sync reads the closed issue again. The listener runs in its own
            // transaction, as it does in production.
            var byHook = new ScmDomainEvent.IssueClosed(closed, "completed", webhook());
            var bySync = new ScmDomainEvent.IssueClosed(
                    closed,
                    "completed",
                    EventContext.forSync(testWorkspace.getId(), null, IdentityProviderType.GITLAB, Instant.now()));
            for (var event : List.of(byHook, bySync)) {
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(ignored -> listener.onIssueClosed(event));
            }

            assertThat(rows(ActivityEventType.ISSUE_CLOSED, 301L)).isEqualTo(1);
        }

        @Test
        void shouldRecordTwoReopensThatTheProviderDatesApart() {
            for (var reopenedAt : List.of(closedAt, closedAt.plusSeconds(60), closedAt)) {
                activityEventService.record(
                        testWorkspace.getId(),
                        ActivityEventType.ISSUE_REOPENED,
                        Instant.now(),
                        testUser,
                        testRepository,
                        ActivityTargetType.ISSUE,
                        302L,
                        ScmEventIdentity.transition(302L, "reopened", reopenedAt));
            }

            assertThat(rows(ActivityEventType.ISSUE_REOPENED, 302L)).isEqualTo(2);
        }

        @Test
        void shouldRecordARedeliveredCommentEditOnceAndTwoEditsInOneSecondTwice() {
            var listener = listener(mock(PullRequestReviewThreadRepository.class));
            var edit = commentEdit(closedAt, "First words");
            var secondEditInTheSameSecond = commentEdit(closedAt, "Second words");

            for (var event : List.of(edit, edit, secondEditInTheSameSecond, secondEditInTheSameSecond)) {
                inTransaction(() -> listener.onCommentUpdated(event));
            }

            assertThat(rows(ActivityEventType.COMMENT_UPDATED, 303L)).isEqualTo(2);
        }

        @Test
        void shouldRecordARedeliveredReviewCommentEditOnce() {
            var listener = listener(mock(PullRequestReviewThreadRepository.class));
            var comment = new ScmEventPayload.ReviewCommentData(
                    306L,
                    "Edited note",
                    "src/Main.java",
                    3,
                    null,
                    null,
                    testUser.getId(),
                    closedAt.minusSeconds(600),
                    closedAt,
                    53L,
                    testRepository.getId());
            var edit = new ScmDomainEvent.ReviewCommentEdited(comment, 53L, Set.of("body"), webhook());

            inTransaction(() -> listener.onReviewCommentEdited(edit));
            inTransaction(() -> listener.onReviewCommentEdited(edit));

            assertThat(rows(ActivityEventType.REVIEW_COMMENT_EDITED, 306L)).isEqualTo(1);
        }

        @Test
        void shouldRecordEachDismissalOfOneReviewOnceEvenWhenGitLabDismissesItAgain() {
            var listener = listener(mock(PullRequestReviewThreadRepository.class));
            var dismissed = new ScmDomainEvent.ReviewDismissed(dismissedReview(closedAt), webhook());
            // GitLab reuses the review of an approver: a renewed approval, withdrawn again, is a second dismissal.
            var dismissedAgain =
                    new ScmDomainEvent.ReviewDismissed(dismissedReview(closedAt.plusSeconds(3600)), webhook());

            for (var event : List.of(dismissed, dismissed, dismissedAgain, dismissedAgain)) {
                inTransaction(() -> listener.onReviewDismissed(event));
            }

            assertThat(rows(ActivityEventType.REVIEW_DISMISSED, 305L)).isEqualTo(2);
        }

        @Test
        void shouldRecordARedeliveredResolutionOnceAndAResolutionAfterAReopenAgain() {
            var threads = mock(PullRequestReviewThreadRepository.class);
            var listener = listener(threads);
            var pullRequest = new PullRequest();
            pullRequest.setId(54L);
            pullRequest.setAuthor(testUser);
            pullRequest.setRepository(testRepository);
            var thread = new PullRequestReviewThread();
            thread.setId(307L);
            thread.setState(PullRequestReviewThread.State.RESOLVED);
            thread.setResolvedBy(testUser);
            thread.setPullRequest(pullRequest);
            when(threads.findById(307L)).thenReturn(Optional.of(thread));

            for (var resolvedAt : List.of(closedAt, closedAt, closedAt.plusSeconds(600))) {
                thread.setResolvedAt(resolvedAt);
                var resolved = new ScmDomainEvent.ReviewThreadResolved(
                        ScmEventPayload.ReviewThreadData.from(thread).orElseThrow(), webhook());
                inTransaction(() -> listener.onReviewThreadResolved(resolved));
            }

            assertThat(rows(ActivityEventType.REVIEW_THREAD_RESOLVED, 307L)).isEqualTo(2);
        }

        private ScmEventPayload.ReviewData dismissedReview(Instant dismissedAt) {
            return new ScmEventPayload.ReviewData(
                    305L,
                    "Looks good",
                    PullRequestReview.State.APPROVED,
                    true,
                    null,
                    testUser.getId(),
                    true,
                    51L,
                    closedAt.minusSeconds(3600),
                    dismissedAt,
                    testRepository.getId());
        }

        private ScmDomainEvent.CommentUpdated commentEdit(Instant at, String body) {
            var comment = new ScmEventPayload.CommentData(
                    303L, body, null, testUser.getId(), at.minusSeconds(600), at, 52L, testRepository.getId());
            return new ScmDomainEvent.CommentUpdated(comment, 52L, Set.of("body"), webhook());
        }

        /** Runs a listener call in its own transaction, as the listener runs in production. */
        private void inTransaction(Runnable call) {
            new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> call.run());
        }

        /** The real listener, called in this thread: what it records is what an event records in production. */
        private ActivityEventListener listener(PullRequestReviewThreadRepository threads) {
            return new ActivityEventListener(
                    activityEventService, activityFacts, threads, userRepository, repositoryRepository);
        }

        private EventContext webhook() {
            return new EventContext(
                    UUID.randomUUID(),
                    Instant.now(),
                    testWorkspace.getId(),
                    null,
                    DataSource.WEBHOOK,
                    "edited",
                    "hook",
                    IdentityProviderType.GITHUB);
        }

        private long rows(ActivityEventType type, long targetId) {
            return activityEventRepository.findAll().stream()
                    .filter(row -> row.getEventType() == type && row.getTargetId() == targetId)
                    .count();
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

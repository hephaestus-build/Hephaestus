package de.tum.cit.aet.hephaestus.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.config.SpringAsyncConfig;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

class ActivityLedgerReconcilerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ActivityLedgerRepository ledgerRepository;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private PersonDataWriteFence fence;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private ApplicationContext applicationContext;

    private ActivityLedgerReconciler reconciler;
    private WorkspaceActorSelector actorSelector;
    private long workspaceId;
    private long providerId;
    private long repositoryId;
    private long authorId;
    private long monitorId;
    private final Instant at = Instant.parse("2025-01-03T10:11:12.123456Z");

    @BeforeEach
    void setUp() {
        fixture(IdentityProviderType.GITHUB);
    }

    private void fixture(IdentityProviderType type) {
        String unique = UUID.randomUUID().toString();
        var workspace = new Workspace();
        workspace.setWorkspaceSlug("repair-" + unique);
        workspace.setDisplayName("Repair test");
        workspace.setAccountLogin("repair");
        workspace.setAccountType(AccountType.ORG);
        workspaceId = workspaces.saveAndFlush(workspace).getId();
        providerId = Objects.requireNonNull(providers
                .saveAndFlush(new IdentityProvider(type, "https://" + unique + ".example.com"))
                .getId());
        repositoryId = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO repository(native_id,provider_id,name,name_with_owner,has_discussions_enabled,is_private,is_archived,is_disabled,html_url,pushed_at,visibility)
                VALUES (1,?,'repo',?,false,false,false,false,'https://example.com/repo',now(),'PUBLIC') RETURNING id
                """, Long.class, providerId, "repair/" + unique));
        monitorId = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO repository_to_monitor(workspace_id,name_with_owner,native_id,generated_paths)
                VALUES (?,?,1,'[]') RETURNING id
                """, Long.class, workspaceId, "repair/" + unique));
        authorId = author(2, "author");
        actorSelector = mock(WorkspaceActorSelector.class);
        when(actorSelector.connectedProviderId(workspaceId)).thenReturn(Optional.of(providerId));
        reconciler = new ActivityLedgerReconciler(ledgerRepository, fence, transactions, actorSelector);
    }

    private long author(long nativeId, String login) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO \"user\"(native_id,provider_id,login,type) VALUES (?,?,?,'USER') RETURNING id",
                Long.class,
                nativeId,
                providerId,
                login));
    }

    private long issue(String type, int number, @Nullable Long author) {
        return Objects.requireNonNull(jdbc.queryForObject(
                """
                INSERT INTO issue(issue_type,native_id,provider_id,repository_id,number,
                    author_id,created_at,state,comments_count,is_locked,is_merged,title,is_draft,additions,deletions,changed_files,commits)
                VALUES (?,?,?, ?,?,?,?,'OPEN',0,false,false,'Work',false,0,0,0,0) RETURNING id
                """, Long.class, type, number, providerId, repositoryId, number, author, Timestamp.from(at)));
    }

    private long review(long pr, String state) {
        return review(pr, state, pr);
    }

    private long review(long pr, String state, long nativeId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("""
                INSERT INTO pull_request_review(native_id,provider_id,pull_request_id,
                    author_id,submitted_at,state,is_dismissed,body)
                VALUES (?,?,?, ?,?,?,false,'') RETURNING id
                """, Long.class, nativeId, providerId, pr, authorId, Timestamp.from(at), state));
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldRestoreExactKeysOnceForBothProviders(IdentityProviderType type) {
        if (type == IdentityProviderType.GITLAB) fixture(type);
        long pr = issue("PULL_REQUEST", 1, authorId);
        long issue = issue("ISSUE", 2, authorId);
        long review = review(pr, "CHANGES_REQUESTED");
        jdbc.update(
                "UPDATE issue SET is_merged=true,merged_at=?,state='MERGED' WHERE id=?",
                Timestamp.from(at.plusSeconds(5)),
                pr);
        when(actorSelector.connectedProviderId(workspaceId + 10000)).thenReturn(Optional.of(providerId));
        assertThat(reconciler.reconcileRepository(workspaceId + 10000, repositoryId))
                .isZero();
        assertThat(reconciler.reconcileRepository(workspaceId, repositoryId)).isEqualTo(4);
        assertThat(reconciler.reconcileRepository(workspaceId, repositoryId)).isZero();
        assertThat(jdbc.queryForList(
                        "SELECT event_key FROM activity_event WHERE workspace_id=?", String.class, workspaceId))
                .containsExactlyInAnyOrder(
                        ActivityEvent.buildKey(ActivityEventType.PULL_REQUEST_OPENED, pr, at),
                        ActivityEvent.buildKey(ActivityEventType.PULL_REQUEST_MERGED, pr, at.plusSeconds(5)),
                        ActivityEvent.buildKey(ActivityEventType.ISSUE_CREATED, issue, at),
                        ActivityEvent.buildKey(ActivityEventType.REVIEW_CHANGES_REQUESTED, review, at));
    }

    @Test
    void shouldCommitActivityOnTheProducerWhenItsQueueIsFull() throws Exception {
        long prId = issue("PULL_REQUEST", 1, authorId);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setParent(applicationContext);
            context.getEnvironment().setActiveProfiles("default");
            context.register(
                    SpringAsyncConfig.class, ActivityTransactionConfiguration.class, ActivityEventListener.class);
            context.refresh();
            var pool = context.getBean("activityExecutor", ThreadPoolTaskExecutor.class);
            var started = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            Runnable block = () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            try {
                pool.execute(block);
                pool.execute(block);
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 500; i++) pool.execute(() -> {});
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    var pr = pullRequests.findById(prId).orElseThrow();
                    var event = new ScmDomainEvent.PullRequestCreated(
                            ScmEventPayload.PullRequestData.from(pr),
                            EventContext.from(ProcessingContext.forSync(
                                    workspaceId, Objects.requireNonNull(pr.getRepository()))));
                    // Dispatch locally so the parent test context does not record the event a second time.
                    context.getBean("applicationEventMulticaster", ApplicationEventMulticaster.class)
                            .multicastEvent(new PayloadApplicationEvent<>(context, event));
                });
                assertThat(jdbc.queryForList(
                                "SELECT event_key FROM activity_event WHERE workspace_id=?", String.class, workspaceId))
                        .containsExactly(ActivityEvent.buildKey(ActivityEventType.PULL_REQUEST_OPENED, prId, at));
            } finally {
                release.countDown();
            }
        }
    }

    @EnableTransactionManagement
    static class ActivityTransactionConfiguration {}

    private long reviewComment(long pr, long review, long thread, long nativeId, @Nullable Long parent) {
        return Objects.requireNonNull(jdbc.queryForObject(
                """
                INSERT INTO pull_request_review_comment(native_id,provider_id,pull_request_id,review_id,
                    thread_id,author_id,created_at,in_reply_to_id,line,original_line,path,body,html_url)
                VALUES (?,?,?,?,?,?,?, ?,1,1,'file.java','Comment','https://example.com/comment') RETURNING id
                """, Long.class, nativeId, providerId, pr, review, thread, authorId, Timestamp.from(at), parent));
    }

    private static Stream<Arguments> replyReviewCases() {
        return Stream.of(
                Arguments.of(IdentityProviderType.GITHUB, "", false),
                Arguments.of(IdentityProviderType.GITHUB, " \n\t", false),
                Arguments.of(IdentityProviderType.GITHUB, "A review summary", true),
                Arguments.of(IdentityProviderType.GITLAB, "", false),
                Arguments.of(IdentityProviderType.GITLAB, " \n\t", false),
                Arguments.of(IdentityProviderType.GITLAB, "A review summary", true));
    }

    @ParameterizedTest
    @MethodSource("replyReviewCases")
    void shouldKeepRepliesAsCommentsAndPreserveRealReviews(IdentityProviderType type, String body, boolean keepReview) {
        if (type == IdentityProviderType.GITLAB) fixture(type);
        long pr = issue("PULL_REQUEST", 1, author(4, "pr-author"));
        long replyAuthor = author(3, "reply-author");
        long rootReview = review(pr, "COMMENTED", 20);
        long replyReview = review(pr, "COMMENTED", 21);
        jdbc.update("UPDATE pull_request_review SET author_id=?,body=? WHERE id=?", replyAuthor, body, replyReview);
        long thread = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO pull_request_review_thread(native_id,provider_id,pull_request_id)
                VALUES (1,?,?) RETURNING id
                """, Long.class, providerId, pr));
        long root = reviewComment(pr, rootReview, thread, 30, null);
        long reply = reviewComment(pr, replyReview, thread, 31, root);
        jdbc.update("UPDATE pull_request_review_comment SET author_id=? WHERE id=?", replyAuthor, reply);
        jdbc.update(
                """
                INSERT INTO activity_event(id,event_key,event_type,occurred_at,actor_id,workspace_id,
                    repository_id,target_type,target_id,ingested_at)
                VALUES (?,?,'REVIEW_COMMENTED',?,?,?,?,'review',?,now())
                """,
                UUID.randomUUID(),
                ActivityEvent.buildKey(ActivityEventType.REVIEW_COMMENTED, replyReview, at),
                Timestamp.from(at),
                replyAuthor,
                workspaceId,
                repositoryId,
                replyReview);
        assertThat(reconciler.reconcileRepository(workspaceId, repositoryId)).isEqualTo(4);
        assertThat(jdbc.queryForList(
                        "SELECT target_id FROM activity_event WHERE workspace_id=? AND target_type='review'",
                        Long.class,
                        workspaceId))
                .containsExactlyInAnyOrderElementsOf(
                        keepReview ? List.of(rootReview, replyReview) : List.of(rootReview));
        assertThat(jdbc.queryForList(
                        "SELECT target_id FROM activity_event WHERE workspace_id=? AND target_type='review_comment'",
                        Long.class,
                        workspaceId))
                .containsExactlyInAnyOrder(root, reply);
        long comment = Objects.requireNonNull(
                jdbc.queryForObject("""
                INSERT INTO issue_comment(native_id,provider_id,issue_id,author_id,created_at,body,html_url)
                VALUES (1,?,?,?,?,'Discussion','https://example.com/comment') RETURNING id
                """, Long.class, providerId, pr, authorId, Timestamp.from(at)));
        assertThat(reconciler.reconcileRepository(workspaceId, repositoryId)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "SELECT event_key FROM activity_event WHERE workspace_id=? AND target_type='issue_comment'",
                        String.class,
                        workspaceId))
                .containsExactly(ActivityEvent.buildKey(ActivityEventType.COMMENT_CREATED, comment, at));
    }

    @Test
    void shouldNotRestoreAnErasedPersonWhenRepairWaitsForErasure() throws Exception {
        issue("PULL_REQUEST", 1, authorId);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerPid = new AtomicInteger();
        var observedFence = mock(PersonDataWriteFence.class);
        when(observedFence.holdForUserWrites(any())).thenAnswer(invocation -> {
            writerPid.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
            return fence.holdForUserWrites(invocation.getArgument(0));
        });
        reconciler = new ActivityLedgerReconciler(ledgerRepository, observedFence, transactions, actorSelector);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var eraser = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                fence.holdForErasure(List.of(new PersonIdentity(providerId, "2", null)));
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Erasure timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                jdbc.update(
                        "INSERT INTO person_suppression(id,provider_id,subject,team_key) VALUES (?,?,?,?)",
                        UUID.randomUUID(),
                        providerId,
                        "2",
                        "");
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var repair = executor.submit(() -> reconciler.reconcileRepository(workspaceId, repositoryId));
                await().atMost(Duration.ofSeconds(10))
                        .until(() -> writerPid.get() != 0
                                && Boolean.TRUE.equals(jdbc.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event='advisory')",
                                        Boolean.class,
                                        writerPid.get())));
                release.countDown();
                eraser.get(10, TimeUnit.SECONDS);
                assertThat(repair.get(10, TimeUnit.SECONDS)).isZero();
            } finally {
                release.countDown();
            }
        }
        assertThat(jdbc.queryForList("SELECT id FROM activity_event WHERE workspace_id=?", UUID.class, workspaceId))
                .isEmpty();
    }

    @Test
    void shouldNotRestoreSuppressedErasedOrSoftDeletedWork() {
        issue("PULL_REQUEST", 1, authorId);
        issue("ISSUE", 2, null);
        long otherAuthor = author(3, "other");
        long deleted = issue("ISSUE", 3, otherAuthor);
        jdbc.update("UPDATE issue SET deleted_at=now() WHERE id=?", deleted);
        jdbc.update(
                "INSERT INTO person_suppression(id,provider_id,subject,team_key) VALUES (?,?,?,?)",
                UUID.randomUUID(),
                providerId,
                "2",
                "");
        assertThat(reconciler.reconcileRepository(workspaceId, repositoryId)).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM activity_event WHERE workspace_id=?", UUID.class, workspaceId))
                .isEmpty();
    }

    @Test
    void shouldRepairAcrossChunkBoundariesWithoutDuplicatingChangedTimestamps() {
        jdbc.update("""
                INSERT INTO issue(issue_type,native_id,provider_id,repository_id,number,
                    author_id,created_at,state,comments_count,is_locked,is_merged,title,is_draft,additions,deletions,changed_files,commits)
                SELECT 'PULL_REQUEST',n,?,?,n,?,?,'OPEN',0,false,false,'Work',false,0,0,0,0
                FROM generate_series(1,1001) n
                """, providerId, repositoryId, authorId, Timestamp.from(at));
        assertThat(reconciler.reconcileRepository(workspaceId, repositoryId)).isEqualTo(1001);
        jdbc.update("UPDATE issue SET created_at=created_at + interval '1 second' WHERE repository_id=?", repositoryId);
        assertThat(reconciler.reconcileRepository(workspaceId, repositoryId)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM activity_event WHERE workspace_id=?", Long.class, workspaceId))
                .isEqualTo(1001);
    }

    @Test
    void shouldCountDismissedWorkOnceWithoutInventingItsFormerVerdict() {
        long pr = issue("PULL_REQUEST", 1, authorId);
        long review = review(pr, "DISMISSED");
        assertThat(reconciler.reconcileRepository(workspaceId, repositoryId)).isEqualTo(2);
        assertThat(jdbc.queryForList(
                        "SELECT event_type FROM activity_event WHERE workspace_id=? AND target_type='review'",
                        String.class,
                        workspaceId))
                .containsExactly("REVIEW_COMMENTED");
        jdbc.update(
                "UPDATE activity_event SET event_type='REVIEW_APPROVED',event_key=? WHERE workspace_id=? AND target_id=? AND target_type='review'",
                ActivityEvent.buildKey(ActivityEventType.REVIEW_APPROVED, review, at),
                workspaceId,
                review);
        assertThat(reconciler.reconcileRepository(workspaceId, repositoryId)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM activity_event WHERE workspace_id=? AND target_type='review'",
                        Integer.class,
                        workspaceId))
                .isEqualTo(1);
    }

    @Test
    void shouldNotRestartTheSameCoverageGapAfterAnotherCompletedScan() {
        jdbc.update(
                "UPDATE repository_to_monitor SET pull_request_backfill_high_water_mark=950,pull_request_backfill_checkpoint=0 WHERE id=?",
                monitorId);
        var tx = new TransactionTemplate(transactions);
        assertThat(tx.<Integer>execute(status -> monitors.restartCompletedBackfill(workspaceId, monitorId, 100, 0)))
                .isEqualTo(1);
        jdbc.update(
                "UPDATE repository_to_monitor SET pull_request_backfill_high_water_mark=950,pull_request_backfill_checkpoint=0 WHERE id=?",
                monitorId);
        assertThat(tx.<Integer>execute(status -> monitors.restartCompletedBackfill(workspaceId, monitorId, 100, 0)))
                .isZero();
        assertThat(tx.<Integer>execute(status -> monitors.restartCompletedBackfill(workspaceId, monitorId, 101, 0)))
                .isZero();
        assertThat(tx.<Integer>execute(status -> monitors.restartCompletedBackfill(workspaceId, monitorId, 120, 0)))
                .isZero();
        assertThat(tx.<Integer>execute(status -> monitors.restartCompletedBackfill(workspaceId, monitorId, 200, 100)))
                .isEqualTo(1);
    }

    @Test
    void shouldKeepHistoryResetWhenAnEarlierRecentSyncSnapshotIsSaved() {
        jdbc.update(
                "UPDATE repository_to_monitor SET pull_request_backfill_high_water_mark=950,pull_request_backfill_checkpoint=0 WHERE id=?",
                monitorId);
        var reset = new TransactionTemplate(transactions);
        reset.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var earlier = monitors.findById(monitorId).orElseThrow();
            assertThat(reset.<Integer>execute(
                            inner -> monitors.restartCompletedBackfill(workspaceId, monitorId, 950, 0)))
                    .isEqualTo(1);
            earlier.setLabelsSyncedAt(at);
            monitors.saveAndFlush(earlier);
        });
        assertThat(jdbc.queryForObject(
                        "SELECT pull_request_backfill_high_water_mark FROM repository_to_monitor WHERE id=?",
                        Integer.class,
                        monitorId))
                .isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT labels_synced_at FROM repository_to_monitor WHERE id=?", Timestamp.class, monitorId))
                .isEqualTo(Timestamp.from(at));
    }

    @Test
    void shouldRestartOnlyTheCompletedMonitorInThisWorkspace() {
        jdbc.update("""
                UPDATE repository_to_monitor SET issue_backfill_high_water_mark=900,issue_backfill_checkpoint=0,
                    pull_request_backfill_high_water_mark=950,pull_request_backfill_checkpoint=0,
                    pull_request_sync_cursor='stale',pull_requests_synced_at=? WHERE id=?
                """, Timestamp.from(at), monitorId);
        var tx = new TransactionTemplate(transactions);
        assertThat(tx.<Integer>execute(
                        status -> monitors.restartCompletedBackfill(workspaceId + 10000, monitorId, 950, 0)))
                .isZero();
        assertThat(tx.<Integer>execute(status -> monitors.restartCompletedBackfill(workspaceId, monitorId, 950, 0)))
                .isEqualTo(1);
        assertThat(tx.<Integer>execute(status -> monitors.restartCompletedBackfill(workspaceId, monitorId, 950, 0)))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT pull_request_backfill_high_water_mark FROM repository_to_monitor WHERE id=?",
                        Integer.class,
                        monitorId))
                .isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT pull_requests_synced_at FROM repository_to_monitor WHERE id=?",
                        Timestamp.class,
                        monitorId))
                .isEqualTo(Timestamp.from(at));
    }
}

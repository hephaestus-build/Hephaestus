package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunNotifications;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RunningMigration;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A sandbox's {@code next} waits for its subject's next run against the run rows and the {@code agent_run}
 * notifications of PostgreSQL. Each {@link AgentNextWaits} here stands for one server replica.
 */
@DisplayName("The wait of a sandbox for its next run Integration")
class AgentNextWaitsIntegrationTest extends BaseIntegrationTest {

    private static final Set<AgentRunKind> REVIEW = Set.of(AgentRunKind.REVIEW);

    @Autowired
    private AgentRuns runs;

    @Autowired
    private AgentRunNotifications notifications;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private final List<AgentNextWaits> replicas = new ArrayList<>();
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private long workspace;

    @BeforeEach
    void listen() throws InterruptedException {
        workspace = workspaces
                .save(TestEntities.activeWorkspace("next-wait-" + UUID.randomUUID()))
                .getId();
        // The listener connects on its own thread: a run queued before it listens wakes no request.
        var heard = new CountDownLatch(1);
        notifications.subscribe(kind -> heard.countDown());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!heard.await(100, TimeUnit.MILLISECONDS)) {
            assertThat(System.nanoTime()).as("the run listener listens").isLessThan(until);
            jdbc.queryForObject("SELECT 1 FROM (SELECT pg_notify('agent_run', 'REVIEW')) sent", Integer.class);
        }
    }

    @AfterEach
    void stopReplicas() {
        replicas.forEach(AgentNextWaits::stop);
        requests.shutdownNow();
    }

    /** A replica that wakes its requests only on a notification: its recheck is longer than any wait here. */
    private AgentNextWaits replica(Duration wait) {
        var waits = new AgentNextWaits(runs, notifications, 16, wait, Duration.ofMinutes(5));
        waits.start();
        replicas.add(waits);
        return waits;
    }

    private static AgentSandboxes.LiveSandbox sandbox(long workspace, String subject) {
        return new AgentSandboxes.LiveSandbox(UUID.randomUUID(), workspace, subject, AgentSandboxKind.REVIEW);
    }

    /** {@code next} on the replica, as the controller asks it: the claim is the claim of every pull. */
    private CompletableFuture<Optional<AgentRunLease>> next(
            AgentNextWaits replica, AgentSandboxes.LiveSandbox sandbox) {
        return next(replica, sandbox, new AtomicInteger());
    }

    /** {@code next}, counting its claims. */
    private CompletableFuture<Optional<AgentRunLease>> next(
            AgentNextWaits replica, AgentSandboxes.LiveSandbox sandbox, AtomicInteger claims) {
        return next(replica, sandbox, REVIEW, claims);
    }

    private CompletableFuture<Optional<AgentRunLease>> next(
            AgentNextWaits replica, AgentSandboxes.LiveSandbox sandbox, Set<AgentRunKind> kinds, AtomicInteger claims) {
        return CompletableFuture.supplyAsync(
                () -> replica.claim(sandbox, kinds, () -> {
                    var lease = Objects.requireNonNull(transactions.execute(
                            status -> runs.claimSubject(sandbox.workspaceId(), sandbox.subjectKey(), kinds)));
                    claims.incrementAndGet();
                    return lease;
                }),
                requests);
    }

    private AgentRunRef queue(String subject) {
        return Objects.requireNonNull(transactions.execute(
                status -> runs.enqueue(workspace, AgentRunKind.REVIEW, AgentRunBand.BATCH, subject, null)));
    }

    private static void waitFor(AgentNextWaits replica, int requests) {
        await().atMost(Duration.ofSeconds(10)).until(() -> replica.waiting() >= requests);
    }

    @Test
    void shouldLeaseTheRunWithinASecondWhenItIsQueuedWhileTheRequestWaits() throws Exception {
        var replica = replica(Duration.ofSeconds(20));
        var sandbox = sandbox(workspace, "review:" + UUID.randomUUID());
        var claims = new AtomicInteger();
        var answer = next(replica, sandbox, claims);
        // Past the request's first claim, so only the notification can end its wait.
        await().atMost(Duration.ofSeconds(10)).untilAtomic(claims, greaterThanOrEqualTo(1));
        assertThat(answer).isNotDone();

        long queued = System.nanoTime();
        var run = queue(sandbox.subjectKey());
        var lease = answer.get(5, TimeUnit.SECONDS).orElseThrow();

        assertThat(Duration.ofNanos(System.nanoTime() - queued)).isLessThan(Duration.ofSeconds(1));
        assertThat(lease.runId()).isEqualTo(run.id());
        assertThat(runs.find(run).orElseThrow().getState()).isEqualTo(AgentRunState.LEASED);
    }

    @Test
    void shouldWakeAnInstanceSandboxOnceItsDeferredDraftIsDue() throws Exception {
        var replica = replica(Duration.ofSeconds(25));
        var subject = "draft:" + UUID.randomUUID() + ":1";
        var kinds = Set.of(AgentRunKind.DRAFT_REPORT);
        var sandbox = new AgentSandboxes.LiveSandbox(
                UUID.randomUUID(), AgentRunRef.INSTANCE_SCOPE, subject, AgentSandboxKind.DRAFT_REPORT);
        var first = Objects.requireNonNull(transactions.execute(status -> {
            runs.enqueueReportDraft(subject, Duration.ofMinutes(10));
            var lease = runs.claimSubject(sandbox.workspaceId(), subject, kinds).orElseThrow();
            assertThat(runs.defer(lease.fence(), AgentRuns.Deferral.MODEL_POOL)).isTrue();
            return lease;
        }));
        try {
            var claims = new AtomicInteger();
            var answer = next(replica, sandbox, kinds, claims);
            await().atMost(Duration.ofSeconds(10)).untilAtomic(claims, greaterThanOrEqualTo(1));
            replica.pass();
            assertThat(answer).isNotDone();

            jdbc.update("UPDATE agent_run SET deferred_until=clock_timestamp() WHERE id=?", first.runId());
            replica.pass();

            assertThat(answer.get(5, TimeUnit.SECONDS).orElseThrow().runId()).isEqualTo(first.runId());
        } finally {
            jdbc.update("DELETE FROM agent_run WHERE id=?", first.runId());
        }
    }

    @Test
    void shouldAnswerNothingWhenNoRunOfItsSubjectIsQueuedBeforeItsWaitEnds() throws Exception {
        var replica = replica(Duration.ofSeconds(1));
        var sandbox = sandbox(workspace, "review:" + UUID.randomUUID());
        long asked = System.nanoTime();
        var answer = next(replica, sandbox);
        waitFor(replica, 1);
        // A run of another subject wakes no request of this one.
        var other = queue("review:" + UUID.randomUUID());

        assertThat(answer.get(10, TimeUnit.SECONDS)).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - asked)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(replica.waiting()).isZero();
        assertThat(runs.find(other).orElseThrow().getState()).isEqualTo(AgentRunState.QUEUED);
    }

    @Test
    void shouldAnswerNothingWhileAMigrationRunsAndLeaseTheRunOnceTheMigrationEnds() throws Exception {
        var replica = replica(Duration.ofSeconds(1));
        var sandbox = sandbox(workspace, "review:" + UUID.randomUUID());
        var run = queue(sandbox.subjectKey());
        var claims = new AtomicInteger();

        try (var _ = RunningMigration.start(dataSource)) {
            var answer = next(replica, sandbox, claims);
            waitFor(replica, 1);
            // The run is due, but a pass under the barrier wakes no request to claim it again.
            replica.pass();

            assertThat(answer.get(10, TimeUnit.SECONDS)).isEmpty();
            assertThat(claims).hasValue(1);
            assertThat(runs.find(run).orElseThrow().getState()).isEqualTo(AgentRunState.QUEUED);
        }

        assertThat(next(replica, sandbox).get(10, TimeUnit.SECONDS))
                .map(AgentRunLease::runId)
                .contains(run.id());
    }

    @Test
    void shouldLeaseEachRunOnceWhenTwoReplicasWaitForTheSameSubject() throws Exception {
        var first = replica(Duration.ofSeconds(3));
        var second = replica(Duration.ofSeconds(3));
        var subjects = new ArrayList<String>();
        var answers = new ArrayList<List<CompletableFuture<Optional<AgentRunLease>>>>();
        for (int i = 0; i < 5; i++) {
            String subject = "review:" + UUID.randomUUID();
            subjects.add(subject);
            // A request that its caller gave up on one replica, and the caller's new request on the other.
            answers.add(List.of(next(first, sandbox(workspace, subject)), next(second, sandbox(workspace, subject))));
        }
        waitFor(first, 5);
        waitFor(second, 5);

        var queued = subjects.stream().map(this::queue).toList();

        for (int i = 0; i < queued.size(); i++) {
            var run = queued.get(i);
            var leases = new ArrayList<AgentRunLease>();
            for (var answer : answers.get(i)) answer.get(10, TimeUnit.SECONDS).ifPresent(leases::add);
            assertThat(leases).singleElement().satisfies(lease -> {
                assertThat(lease.runId()).isEqualTo(run.id());
                assertThat(lease.epoch()).isEqualTo(1);
            });
            assertThat(runs.find(run).orElseThrow().getLeaseEpoch()).isEqualTo(1);
        }
    }
}

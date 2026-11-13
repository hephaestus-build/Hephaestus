package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.SandboxJwt;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.SandboxOwnerFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/** The installation turn cap applies to subject pulls, without waiting on a cap lock under a shared sandbox lock. */
class TurnSubjectCapacityIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ApplicationContext beans;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private AgentRunRepository rows;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    private long workspace;
    private AgentRuns capped;
    private AgentSandboxes sandboxes;

    @BeforeEach
    void prepareOwners() {
        workspace = workspaces
                .save(TestEntities.activeWorkspace("turn-cap-" + UUID.randomUUID()))
                .getId();
        capped = withTurnCap(
                rows.countLeased(AgentRunKind.TURN.name()) + rows.countLeased(AgentRunKind.DIGEST.name()) + 1);
        sandboxes = SandboxOwnerFixtures.owner(
                beans, new AttemptTokenIssuer(AttemptKeyFixtures.keyring(AttemptSigningKey.generate())), capped);
    }

    private AgentRuns withTurnCap(@Nullable Integer cap) {
        AgentCapacityProperties capacity = runs.capacity();
        return new AgentRuns(
                rows,
                new AgentCapacityProperties(
                        capacity.leaseTtl(),
                        capacity.turnLeaseTtl(),
                        cap,
                        capacity.leaseRenewal(),
                        capacity.reviewRetries(),
                        capacity.bandAging(),
                        capacity.permitAging(),
                        capacity.sandboxStart(),
                        capacity.sandboxIdle(),
                        capacity.conversationSandboxIdle()),
                new SimpleMeterRegistry());
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentRunKind.class,
            names = {"TURN", "DIGEST"})
    void shouldLeaveTurnWorkQueuedWhenTheConfiguredCapacityIsZero(AgentRunKind kind) {
        String subject = "conversation:" + UUID.randomUUID();
        AgentRunRef queued = queue(subject, kind);
        AgentRuns stopped = withTurnCap(0);
        assertThat(beans.getBean(Validator.class).validate(stopped.capacity())).isEmpty();
        var claimed = transactions.execute(status -> stopped.claimSubject(workspace, subject, Set.of(kind)));
        assertThat(claimed).isEmpty();
        assertThat(rows.findByRef(queued.id(), workspace).orElseThrow().getState())
                .isEqualTo(AgentRunState.QUEUED);
    }

    private AgentRunRef queue(String subject) {
        return queue(subject, AgentRunKind.TURN);
    }

    private AgentRunRef queue(String subject, AgentRunKind kind) {
        return Objects.requireNonNull(transactions.execute(status -> runs.enqueue(
                workspace,
                kind,
                kind == AgentRunKind.DIGEST ? AgentRunBand.BACKGROUND : AgentRunBand.INTERACTIVE,
                subject,
                null)));
    }

    private AgentSandboxes.LiveSandbox sandbox(String subject) {
        var requested = sandboxes.ensure(workspace, subject, AgentSandboxKind.CONVERSATION);
        return sandboxes
                .authenticate(new SandboxJwt(
                        requested.id(),
                        workspace,
                        subject,
                        "test",
                        Instant.now().plusSeconds(300)))
                .orElseThrow();
    }

    @Test
    void shouldKeepTwoSubjectsWithinTheCapAcrossConcurrentPullsAndAdmitTheNextAfterCompletion() throws Exception {
        keepWithinCap(AgentRunKind.TURN, AgentRunKind.TURN);
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentRunKind.class,
            names = {"TURN", "DIGEST"})
    void shouldShareOneCapBetweenInteractiveAndDigestWork(AgentRunKind firstKind) throws Exception {
        keepWithinCap(firstKind, firstKind == AgentRunKind.TURN ? AgentRunKind.DIGEST : AgentRunKind.TURN);
    }

    @Test
    void shouldLetAQuestionPassItsQueuedDigestAsSoonAsConversationCapacityReturns() {
        String occupied = "conversation:" + UUID.randomUUID();
        queue(occupied);
        var held = transactions.execute(status -> capped.claimSubject(workspace, occupied, Set.of(AgentRunKind.TURN)));
        var lease = Objects.requireNonNull(held).orElseThrow();
        String subject = "conversation:" + UUID.randomUUID();
        var digest = queue(subject, AgentRunKind.DIGEST);
        var question = Objects.requireNonNull(transactions.execute(status ->
                runs.enqueue(workspace, AgentRunKind.TURN, AgentRunBand.INTERACTIVE, subject, Duration.ofMinutes(3))));
        var offered = Set.of(AgentRunKind.TURN, AgentRunKind.DIGEST);
        assertThat(capped.due(offered, Integer.MAX_VALUE))
                .filteredOn(run -> run.run().workspaceId() == workspace)
                .extracting(AgentRuns.DueRun::run)
                .contains(question)
                .doesNotContain(digest);
        var blocked = transactions.execute(status -> capped.claimSubject(workspace, subject, offered));
        assertThat(blocked).isEmpty();
        transactions.executeWithoutResult(status -> runs.end(lease.fence(), AgentRunStopReason.COMPLETED));
        var claimed = transactions.execute(status -> capped.claimSubject(workspace, subject, offered));
        assertThat(claimed).get().extracting(AgentRunLease::runId).isEqualTo(question.id());
        assertThat(rows.findByRef(digest.id(), workspace))
                .get()
                .extracting(AgentRun::getState)
                .isEqualTo(AgentRunState.QUEUED);
    }

    private void keepWithinCap(AgentRunKind firstKind, AgentRunKind secondKind) throws Exception {
        String firstSubject = "conversation:" + UUID.randomUUID();
        String secondSubject = "conversation:" + UUID.randomUUID();
        AgentRunRef first = queue(firstSubject, firstKind);
        AgentRunRef second = queue(secondSubject, secondKind);
        var firstSandbox = sandbox(firstSubject);
        var secondSandbox = sandbox(secondSubject);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRunLease claimed = Objects.requireNonNull(transactions.execute(status -> {
                var lease = sandboxes
                        .lease(firstSandbox, Set.of(firstKind), ignored -> true)
                        .orElseThrow();
                var concurrent =
                        executor.submit(() -> sandboxes.lease(secondSandbox, Set.of(secondKind), ignored -> true));
                try {
                    assertThat(concurrent.get(5, TimeUnit.SECONDS)).isEmpty();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                return lease;
            }));
            assertThat(claimed.runId()).isEqualTo(first.id());
            assertThat(sandboxes.lease(secondSandbox, Set.of(secondKind), ignored -> true))
                    .isEmpty();
            assertThat(rows.findByRef(second.id(), workspace).orElseThrow().getState())
                    .isEqualTo(AgentRunState.QUEUED);
            transactions.executeWithoutResult(status -> runs.end(claimed.fence(), AgentRunStopReason.COMPLETED));
            assertThat(sandboxes.lease(secondSandbox, Set.of(secondKind), ignored -> true))
                    .map(AgentRunLease::runId)
                    .contains(second.id());
        }
        assertThat(rows.findByRef(first.id(), workspace).orElseThrow().getState())
                .isEqualTo(AgentRunState.ENDED);
        assertThat(rows.findByRef(second.id(), workspace).orElseThrow().getState())
                .isEqualTo(AgentRunState.LEASED);
    }

    @Test
    void shouldLeaveAnUncappedQuestionQueuedWhileItsSandboxClaimsTheDigest() throws Exception {
        var owner = SandboxOwnerFixtures.owner(
                beans,
                new AttemptTokenIssuer(AttemptKeyFixtures.keyring(AttemptSigningKey.generate())),
                withTurnCap(null));
        String subject = "conversation:" + UUID.randomUUID();
        var digest = queue(subject, AgentRunKind.DIGEST);
        var sandbox = sandbox(subject);
        var offered = Set.of(AgentRunKind.TURN, AgentRunKind.DIGEST);
        var claimed = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> owner.lease(sandbox, offered, lease -> {
                claimed.countDown();
                try {
                    if (!finish.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("The test did not release the first pull");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            }));
            AgentRunRef question;
            try {
                assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
                question = queue(subject);
                var second = executor.submit(() -> owner.lease(sandbox, offered, ignored -> true));
                assertThat(second.get(5, TimeUnit.SECONDS)).isEmpty();
            } finally {
                finish.countDown();
            }
            var lease = first.get(5, TimeUnit.SECONDS).orElseThrow();
            assertThat(lease.runId()).isEqualTo(digest.id());
            transactions.executeWithoutResult(status -> runs.end(lease.fence(), AgentRunStopReason.COMPLETED));
            assertThat(owner.lease(sandbox, offered, ignored -> true))
                    .get()
                    .extracting(AgentRunLease::runId)
                    .isEqualTo(question.id());
        }
    }

    @Test
    void shouldLetASecondPullOfTheSameSandboxReturnBeforeTheFirstCommits() throws Exception {
        String subject = "conversation:" + UUID.randomUUID();
        AgentRunRef run = queue(subject);
        var sandbox = sandbox(subject);
        var claimed = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> sandboxes.lease(sandbox, Set.of(AgentRunKind.TURN), lease -> {
                claimed.countDown();
                try {
                    if (!finish.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("The test did not release the first pull");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            }));
            try {
                assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
                var second =
                        executor.submit(() -> sandboxes.lease(sandbox, Set.of(AgentRunKind.TURN), ignored -> true));
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(Optional.empty());
            } finally {
                finish.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).map(AgentRunLease::runId).contains(run.id());
        }
        assertThat(rows.findByRef(run.id(), workspace).orElseThrow().getState()).isEqualTo(AgentRunState.LEASED);
    }
}

package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptKeyFixtures;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptSigningKey;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenVerifier;
import de.tum.cit.aet.hephaestus.agent.attempt.SandboxJwt;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunLease;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("Agent sandboxes over PostgreSQL Integration")
class AgentSandboxesIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentSandboxRepository repository;

    @Autowired
    private AgentRuns runs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final FakeSandboxClaims claims = new FakeSandboxClaims();
    private final AttemptSigningKey key = AttemptSigningKey.generate();
    private final AttemptTokenVerifier verifier =
            new AttemptTokenVerifier(AttemptKeyFixtures.keyring(key), new SimpleMeterRegistry());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private AgentSandboxes sandboxes;
    private long workspace;

    @BeforeEach
    void setUp() {
        sandboxes = new AgentSandboxes(
                repository,
                runs,
                claims,
                new AttemptTokenIssuer(AttemptKeyFixtures.keyring(key)),
                transactions,
                "http://hephaestus-sandbox-gateway.hephaestus.svc:8081",
                host -> "10.43.0.12",
                meters);
        workspace = workspaces
                .save(TestEntities.activeWorkspace("sandboxes-" + UUID.randomUUID()))
                .getId();
    }

    /** Each limit of the capacity home at its floor of one second, except the idle limit of a conversation. */
    private AgentSandboxReaper reaper(Duration conversationIdle) {
        return new AgentSandboxReaper(
                repository,
                sandboxes,
                claims,
                new AgentCapacityProperties(
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(60),
                        null,
                        Duration.ofSeconds(25),
                        5,
                        Duration.ofMinutes(10),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        conversationIdle),
                transactions,
                new SimpleMeterRegistry());
    }

    private AgentSandboxes.LiveSandbox ensure(String subject, AgentSandboxKind kind) {
        return sandboxes.ensure(workspace, subject, kind, Instant.now().plus(Duration.ofHours(1)));
    }

    private SandboxJwt token(AgentSandboxes.LiveSandbox sandbox) {
        String token = Objects.requireNonNull(claims.created.get(claimName(sandbox)))
                .env()
                .get(AgentSandboxes.TOKEN_ENV);
        return verifier.verifySandbox(Objects.requireNonNull(token));
    }

    private String claimName(AgentSandboxes.LiveSandbox sandbox) {
        return AgentSandboxes.claimName(sandbox.id());
    }

    private void queueRun(String subject) {
        transactions.executeWithoutResult(
                status -> runs.enqueue(workspace, AgentRunKind.REVIEW, AgentRunBand.BATCH, subject, null));
    }

    private static void waitPast(Duration limit) {
        try {
            Thread.sleep(limit.plusMillis(200).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void shouldRequestOneSandboxWithItsClaimForASubjectAndAnswerItAgainUntilItIsTakenBack() {
        var first = ensure("review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        var again = sandboxes.ensure(
                workspace,
                first.subjectKey(),
                AgentSandboxKind.REVIEW,
                Instant.now().plus(Duration.ofHours(2)));

        assertThat(again).isEqualTo(first);
        assertThat(claims.creates).containsExactly(claimName(first));
        var claim = Objects.requireNonNull(claims.created.get(claimName(first)));
        assertThat(claim.kind()).isEqualTo(AgentSandboxKind.REVIEW);
        assertThat(claim.sandboxId()).isEqualTo(first.id());
        assertThat(claim.env())
                .containsEntry(AgentSandboxes.GATEWAY_ENV, "http://10.43.0.12:8081")
                .containsKey(AgentSandboxes.TOKEN_ENV);
        var token = token(first);
        assertThat(token.sandboxId()).isEqualTo(first.id());
        assertThat(token.workspaceId()).isEqualTo(workspace);
        assertThat(token.subjectKey()).isEqualTo(first.subjectKey());
    }

    @Test
    void shouldRequestOneSandboxWhenTwoReplicasAskForTheSameSubjectAtOnce() throws Exception {
        String subject = "review:" + UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var asked = new ArrayList<Future<AgentSandboxes.LiveSandbox>>();
            for (int i = 0; i < 4; i++) asked.add(pool.submit(() -> ensure(subject, AgentSandboxKind.REVIEW)));
            var answered = new ArrayList<AgentSandboxes.LiveSandbox>();
            for (var future : asked) answered.add(future.get());
            assertThat(answered)
                    .map(AgentSandboxes.LiveSandbox::id)
                    .containsOnly(answered.getFirst().id());
        }
        assertThat(claims.creates).hasSize(1);
    }

    @Test
    void shouldAcceptALiveSandboxsTokenAndMakeItReadyOnItsFirstCall() {
        var sandbox = ensure("conversation:" + UUID.randomUUID(), AgentSandboxKind.CONVERSATION);

        assertThat(sandboxes.authenticate(token(sandbox))).contains(sandbox);
        assertThat(state(sandbox)).isEqualTo("READY");
    }

    @Test
    void shouldRefuseTheTokenOfASandboxThatWasTakenBackAndRequestANewOne() {
        var sandbox = ensure("conversation:" + UUID.randomUUID(), AgentSandboxKind.CONVERSATION);
        var token = token(sandbox);

        sandboxes.release(workspace, sandbox.subjectKey());

        assertThat(sandboxes.authenticate(token)).isEmpty();
        assertThat(claims.created).doesNotContainKey(claimName(sandbox));
        assertThat(repository.findById(sandbox.id())).isEmpty();
        var next = ensure(sandbox.subjectKey(), AgentSandboxKind.CONVERSATION);
        assertThat(next.id()).isNotEqualTo(sandbox.id());
    }

    @Test
    void shouldRevokeASandboxInItsCallersTransactionAndLeaveItsClaimToTheReaper() {
        var sandbox = ensure("review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        var token = token(sandbox);
        assertThat(sandboxes.authenticate(token)).isPresent();

        assertThatThrownBy(() -> sandboxes.revoke(sandbox, AgentSandboxes.Revocation.UNISOLATED))
                .isInstanceOf(IllegalStateException.class);
        transactions.executeWithoutResult(status -> sandboxes.revoke(sandbox, AgentSandboxes.Revocation.UNISOLATED));

        assertThat(sandboxes.authenticate(token)).isEmpty();
        assertThat(claims.created).containsKey(claimName(sandbox));
        assertThat(meters.counter("agent.sandbox.revoked", "reason", "unisolated")
                        .count())
                .isEqualTo(1.0);
        reaper(Duration.ofMinutes(15)).reap();
        assertThat(claims.created).doesNotContainKey(claimName(sandbox));
    }

    @Test
    void shouldRefuseATokenThatNamesAnotherSubjectOrWorkspaceThanItsSandbox() {
        var sandbox = ensure("review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        var token = token(sandbox);

        assertThat(sandboxes.authenticate(new SandboxJwt(
                        token.sandboxId(), token.workspaceId(), "review:other", token.jti(), token.expiresAt())))
                .isEmpty();
        assertThat(sandboxes.authenticate(new SandboxJwt(
                        token.sandboxId(),
                        token.workspaceId() + 1,
                        token.subjectKey(),
                        token.jti(),
                        token.expiresAt())))
                .isEmpty();
    }

    @Test
    void shouldNotCountACallThatLeasesNothingAsUseSoAPollingSandboxStillGoesIdle() {
        var sandbox = ensure("review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        var token = token(sandbox);
        assertThat(sandboxes.authenticate(token)).contains(sandbox);
        waitPast(Duration.ofSeconds(1));
        // A poll of the gateway after the idle limit: the sandbox answers, but it leased nothing.
        assertThat(sandboxes.authenticate(token)).contains(sandbox);
        assertThat(sandboxes.lease(sandbox, Set.of(AgentRunKind.REVIEW), lease -> true))
                .isEmpty();

        reaper(Duration.ofHours(1)).reap();

        assertThat(repository.findById(sandbox.id())).isEmpty();
    }

    @Test
    void shouldCorrelateAClaimAndRestoreItsCallersContextOnSuccessAndFailure() {
        var enclosing = MDC.getCopyOfContextMap();
        try {
            var previous = Map.of(
                    StructuredLogKeys.WORKSPACE_ID,
                    "enclosing-workspace",
                    StructuredLogKeys.REVIEW_ID,
                    "enclosing-review");
            for (boolean fail : List.of(false, true)) {
                String reviewId = UUID.randomUUID().toString();
                String subject = "review:" + reviewId;
                queueRun(subject);
                var sandbox = ensure(subject, AgentSandboxKind.REVIEW);
                sandboxes.authenticate(token(sandbox));
                MDC.setContextMap(previous);
                Predicate<AgentRunLease> claimed = lease -> {
                    assertThat(MDC.getCopyOfContextMap())
                            .containsEntry(StructuredLogKeys.WORKSPACE_ID, Long.toString(workspace))
                            .containsEntry(StructuredLogKeys.SUBJECT_KEY, subject)
                            .containsEntry(
                                    StructuredLogKeys.RUN_ID, lease.runId().toString())
                            .containsEntry(StructuredLogKeys.RUN_EPOCH, Integer.toString(lease.epoch()))
                            .containsEntry(StructuredLogKeys.REVIEW_ID, reviewId);
                    if (fail) throw new IllegalStateException("claim failed");
                    return true;
                };
                if (fail) {
                    assertThatThrownBy(() -> sandboxes.lease(sandbox, Set.of(AgentRunKind.REVIEW), claimed))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("claim failed");
                } else {
                    assertThat(sandboxes.lease(sandbox, Set.of(AgentRunKind.REVIEW), claimed))
                            .isPresent();
                }
                assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
            }
        } finally {
            if (enclosing == null) MDC.clear();
            else MDC.setContextMap(enclosing);
        }
    }

    @Test
    void shouldLeaseARunOnlyOfTheOfferedKindsAndOnlyWhenItsKindTakesIt() {
        String subject = "review:" + UUID.randomUUID();
        queueRun(subject);
        var sandbox = ensure(subject, AgentSandboxKind.REVIEW);
        sandboxes.authenticate(token(sandbox));

        assertThat(sandboxes.lease(sandbox, Set.of(), lease -> true)).isEmpty();
        assertThat(sandboxes.lease(sandbox, Set.of(AgentRunKind.REVIEW_COMPOSE), lease -> true))
                .isEmpty();
        assertThat(runState(subject)).isEqualTo("QUEUED");

        // A kind that finds its work gone ends the run in the transaction of the lease, and the sandbox gets none.
        assertThat(sandboxes.lease(
                        sandbox,
                        Set.of(AgentRunKind.REVIEW),
                        lease -> runs.end(lease.fence()) != AgentRuns.EndOutcome.ENDED))
                .isEmpty();
        assertThat(runState(subject)).isEqualTo("ENDED");

        queueRun(subject);
        assertThat(sandboxes.lease(sandbox, Set.of(AgentRunKind.REVIEW), lease -> true))
                .hasValueSatisfying(lease -> assertThat(lease.kind()).isEqualTo(AgentRunKind.REVIEW));
    }

    private String runState(String subject) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT state FROM agent_run WHERE concurrency_key = ? ORDER BY created_at DESC LIMIT 1",
                String.class,
                subject));
    }

    @Test
    void shouldLeaveANewerSandboxOfTheSubjectAloneWhenAnOlderOnesClaimFails() {
        String subject = "review:" + UUID.randomUUID();
        var older = ensure(subject, AgentSandboxKind.REVIEW);
        // The reaper took the older sandbox back while its claim was being created, and another replica requested a
        // newer one.
        transactions.executeWithoutResult(status -> repository.release(workspace, subject));
        var newer = ensure(subject, AgentSandboxKind.REVIEW);

        transactions.executeWithoutResult(status -> repository.releaseSandbox(older.id(), workspace));

        assertThat(state(newer)).isEqualTo("REQUESTED");
    }

    @Test
    void shouldNotRequestASandboxThatOutlivesTheKeyThatSignsItsToken() {
        assertThatThrownBy(() -> sandboxes.ensure(
                        workspace,
                        "review:" + UUID.randomUUID(),
                        AgentSandboxKind.REVIEW,
                        Instant.now().plus(Duration.ofDays(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldTakeBackItsRowWhenTheClaimCannotBeCreated() {
        claims.failCreates = true;
        String subject = "review:" + UUID.randomUUID();

        assertThatThrownBy(() -> ensure(subject, AgentSandboxKind.REVIEW)).isInstanceOf(IllegalStateException.class);

        assertThat(repository.findLive(workspace, subject)).isEmpty();
    }

    @Test
    void shouldKeepASandboxWhoseSubjectHasAQueuedRunAndTakeItBackAfterItsIdleLimitWithoutOne() {
        String needed = "review:" + UUID.randomUUID();
        String done = "review:" + UUID.randomUUID();
        queueRun(needed);
        var kept = ensure(needed, AgentSandboxKind.REVIEW);
        var idle = ensure(done, AgentSandboxKind.REVIEW);
        sandboxes.authenticate(token(kept));
        sandboxes.authenticate(token(idle));
        waitPast(Duration.ofSeconds(1));

        reaper(Duration.ofHours(1)).reap();

        assertThat(repository.findById(kept.id())).isPresent();
        assertThat(claims.created).containsKey(claimName(kept)).doesNotContainKey(claimName(idle));
        assertThat(repository.findById(idle.id())).isEmpty();
    }

    @Test
    void shouldKeepAConversationsSandboxWarmUntilItsOwnIdleLimit() {
        var conversation = ensure("conversation:" + UUID.randomUUID(), AgentSandboxKind.CONVERSATION);
        sandboxes.authenticate(token(conversation));
        waitPast(Duration.ofSeconds(1));

        reaper(Duration.ofHours(1)).reap();
        assertThat(repository.findById(conversation.id())).isPresent();

        reaper(Duration.ofSeconds(1)).reap();
        assertThat(repository.findById(conversation.id())).isEmpty();
    }

    @Test
    void shouldTakeBackASandboxThatNeverCalledTheGatewayWithinTheStartLimitEvenWhileItsSubjectNeedsIt() {
        String subject = "review:" + UUID.randomUUID();
        queueRun(subject);
        var silent = ensure(subject, AgentSandboxKind.REVIEW);
        waitPast(Duration.ofSeconds(1));

        reaper(Duration.ofHours(1)).reap();

        assertThat(repository.findById(silent.id())).isEmpty();
        assertThat(claims.created).doesNotContainKey(claimName(silent));
    }

    @Test
    void shouldTakeBackASandboxAtItsShutdownWhileItsSubjectStillNeedsIt() {
        String subject = "review:" + UUID.randomUUID();
        queueRun(subject);
        // A token's expiry is a whole second, so the shutdown is far enough ahead that the token still verifies now.
        var sandbox = sandboxes.ensure(
                workspace, subject, AgentSandboxKind.REVIEW, Instant.now().plus(Duration.ofSeconds(3)));
        var token = token(sandbox);
        sandboxes.authenticate(token);
        waitPast(Duration.ofSeconds(3));

        assertThat(sandboxes.authenticate(token)).isEmpty();
        reaper(Duration.ofHours(1)).reap();
        assertThat(repository.findById(sandbox.id())).isEmpty();
    }

    @Test
    void shouldTryAClaimDeleteAgainOnTheNextTickWhenTheApiRefusedIt() {
        var sandbox = ensure("review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        sandboxes.authenticate(token(sandbox));
        waitPast(Duration.ofSeconds(1));
        claims.failDeletes = true;

        reaper(Duration.ofHours(1)).reap();
        assertThat(state(sandbox)).isEqualTo("DELETING");
        assertThat(sandboxes.authenticate(token(sandbox)))
                .as("its token stops at once")
                .isEmpty();

        claims.failDeletes = false;
        reaper(Duration.ofHours(1)).reap();
        assertThat(repository.findById(sandbox.id())).isEmpty();
        assertThat(claims.created).doesNotContainKey(claimName(sandbox));
    }

    @Test
    void shouldRetryExplicitReleaseUntilTheClaimAndItsDeletingRowAreGone() {
        var sandbox = ensure("review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        claims.failDeletes = true;
        assertThatThrownBy(() -> sandboxes.release(workspace, sandbox.id())).isInstanceOf(RuntimeException.class);
        assertThat(state(sandbox)).isEqualTo("DELETING");
        assertThat(claims.created).containsKey(claimName(sandbox));

        var newer = ensure(sandbox.subjectKey(), AgentSandboxKind.REVIEW);
        claims.failDeletes = false;
        sandboxes.release(workspace, sandbox.id());
        assertThat(repository.findById(sandbox.id())).isEmpty();
        assertThat(claims.created).doesNotContainKey(claimName(sandbox));
        assertThat(repository.findById(newer.id())).isPresent();
        assertThat(claims.created).containsKey(claimName(newer));
    }

    @Test
    void shouldDeleteTheReceiptsPhysicalSandboxAfterWorkspacePurgeRemovedItsRow() {
        var sandbox = ensure("review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        transactions.executeWithoutResult(status -> repository.deleteAllByWorkspaceId(workspace));
        assertThat(repository.findById(sandbox.id())).isEmpty();
        assertThat(claims.created).containsKey(claimName(sandbox));

        sandboxes.release(workspace, sandbox.id());
        assertThat(repository.findById(sandbox.id())).isEmpty();
        assertThat(claims.created).doesNotContainKey(claimName(sandbox));
    }

    @Test
    void shouldNeverReleaseAnotherWorkspacesSandboxThroughACopyReceipt() {
        var sandbox = ensure("review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        assertThatThrownBy(() -> sandboxes.release(workspace + 1, sandbox.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findById(sandbox.id())).isPresent();
        assertThat(claims.created).containsKey(claimName(sandbox));
    }

    @Test
    void shouldDeleteAClaimThatNoRowNamesAsAfterItsWorkspaceWasPurged() {
        var sandbox = ensure("review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        queueRun(sandbox.subjectKey());

        transactions.executeWithoutResult(status -> repository.deleteAllByWorkspaceId(workspace));
        reaper(Duration.ofHours(1)).reap();

        assertThat(claims.created).doesNotContainKey(claimName(sandbox));
    }

    @Test
    void shouldRefuseToMakeASandboxThatIsBeingDeletedLiveAgain() {
        var sandbox = ensure("review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        transactions.executeWithoutResult(status -> repository.release(workspace, sandbox.subjectKey()));

        assertThatThrownBy(() -> jdbc.update("UPDATE agent_sandbox SET state = 'READY' WHERE id = ?", sandbox.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("never live again");
    }

    private String state(AgentSandboxes.LiveSandbox sandbox) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT state FROM agent_sandbox WHERE id = ?", String.class, sandbox.id()));
    }
}

package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarts;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCancelledException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ToolsPods;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The installation's tools Pods in {@code conversation_tools_pod}, over a runtime in memory. Each instance stands for
 * one worker; two instances on the same database are two workers.
 */
class ConversationToolsPodsIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final Duration SOON = Duration.ofSeconds(5);

    @Autowired
    private ConversationToolsPodRepository rows;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private ApplicationEventPublisher events;

    private final FakeToolsPods runtime = new FakeToolsPods();
    private final List<ConversationToolsPods> workers = new ArrayList<>();
    private Workspace workspace;
    private long developer;
    private @Nullable MentorTurn last;

    @BeforeEach
    void createTheWorkspace() {
        User user = persistUser("tools-pods");
        workspace = createWorkspace("tools-pods", "Tools Pods", "tools-pods-org", AccountType.ORG, user);
        developer = accountId(user);
    }

    @AfterEach
    void closeTheWorkers() {
        workers.forEach(ConversationToolsPods::close);
    }

    /** A worker's tools Pods, with a pool of {@code poolSize} for the installation. */
    private ConversationToolsPods worker(int poolSize) {
        return worker(poolSize, new SimpleMeterRegistry());
    }

    private ConversationToolsPods worker(int poolSize, MeterRegistry meters) {
        var pods = new ConversationToolsPods(runtime, rows, transactions, poolSize, meters);
        workers.add(pods);
        return pods;
    }

    /**
     * A running attempt that holds its live lease. Each after the first runs in a thread of the one before, so that no
     * attempt waits behind another.
     */
    private TurnAttempt running() {
        MentorTurn before = last;
        MentorConversation conversation = before == null
                ? conversations.findOrCreateMain(workspace.getId(), developer)
                : conversations.openThread(
                        workspace.getId(),
                        turns.question(workspace.getId(), before.getId()).getId());
        MentorTurn turn = MentorTurnStarts.askAndStart(
                turns, metrics, workspace.getId(), conversation.getId(), "Read my repository " + UUID.randomUUID());
        last = turn;
        return new TurnAttempt(
                turn.getId(),
                workspace.getId(),
                developer,
                conversation.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()));
    }

    /** The attempt with a lease it does not hold. */
    private static TurnAttempt withAnotherLease(TurnAttempt turn) {
        return new TurnAttempt(
                turn.turnId(),
                turn.workspaceId(),
                turn.accountId(),
                turn.conversationId(),
                turn.attempt(),
                UUID.randomUUID());
    }

    /** Takes the attempt's lease back as the lease reaper does once it expires. */
    private void expire(TurnAttempt turn) {
        turns.renewLease(turn.workspaceId(), turn.turnId(), turn.attempt(), turn.leaseId(), Duration.ofSeconds(-1));
    }

    private static ToolsPods.Started bind(ConversationToolsPods pods, TurnAttempt turn) {
        return pods.bind(turn, () -> false);
    }

    /** What the workspace's turns may read changes, in a transaction, as on the server; a worker then hears of it. */
    private void changeTheContext(ConversationToolsPods worker) {
        transactions.executeWithoutResult(
                status -> events.publishEvent(new MentorLedgerEvents.ContextChanged(workspace.getId())));
        new TurnContextRevocations(new TurnContextGenerations(), worker)
                .contextChanged(new MentorLedgerEvents.ContextChanged(workspace.getId()));
    }

    private long pooled() {
        return rows.countPooled(ConversationToolsPods.POOL_LIFE.toSeconds());
    }

    /** The pooled Pods that started, oldest first. */
    private List<String> pooledReady() {
        return jdbc.query(
                "SELECT name FROM conversation_tools_pod WHERE turn_id IS NULL AND address IS NOT NULL ORDER BY created_at",
                (row, number) -> Objects.requireNonNull(row.getString("name")));
    }

    private void backdate(String name, Duration age) {
        jdbc.update(
                "UPDATE conversation_tools_pod SET created_at = clock_timestamp() - make_interval(secs => ?) WHERE name = ?",
                (double) age.toSeconds(),
                name);
    }

    @Test
    void shouldBindAReadyPooledPodAndGiveTwoAttemptsDifferentPods() throws Exception {
        ConversationToolsPods first = worker(2);
        ConversationToolsPods second = worker(2);
        first.reap();
        await().atMost(SOON).until(() -> pooledReady().size() == 2);
        List<String> pool = pooledReady();
        TurnAttempt one = running();
        TurnAttempt other = running();

        var bindingOne = CompletableFuture.supplyAsync(() -> bind(first, one));
        var bindingOther = CompletableFuture.supplyAsync(() -> bind(second, other));
        String boundOne = bindingOne.get(10, TimeUnit.SECONDS).id();
        String boundOther = bindingOther.get(10, TimeUnit.SECONDS).id();

        assertThat(boundOne).isNotEqualTo(boundOther);
        assertThat(List.of(boundOne, boundOther)).containsExactlyInAnyOrderElementsOf(pool);
        assertThat(rows.findBound(one.turnId(), one.attempt()).orElseThrow().getWorkspaceId())
                .isEqualTo(workspace.getId());
        assertThat(rows.findBound(other.turnId(), other.attempt()).orElseThrow().getName())
                .isEqualTo(boundOther);
        await().atMost(SOON)
                .untilAsserted(() -> assertThat(pooledReady())
                        .as("each bind fills the pool again")
                        .hasSize(2)
                        .doesNotContain(boundOne, boundOther));
    }

    @Test
    void shouldBindNothingForAnAttemptThatLostItsLease() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt turn = running();

        assertThatThrownBy(() -> bind(pods, withAnotherLease(turn))).isInstanceOf(SandboxException.class);
        expire(turn);
        assertThatThrownBy(() -> bind(pods, turn)).isInstanceOf(SandboxException.class);

        assertThat(rows.findBound(turn.turnId(), turn.attempt())).isEmpty();
        assertThat(runtime.createdCount()).isZero();
    }

    @Test
    void shouldGiveAnAttemptThatHoldsAPodTheSamePodOnEveryWorker() {
        TurnAttempt turn = running();
        ToolsPods.Started bound = bind(worker(0), turn);

        ToolsPods.Started again = bind(worker(0), turn);

        assertThat(again).isEqualTo(bound);
        assertThat(runtime.createdCount()).isOne();
        assertThat(rows.findBound(turn.turnId(), turn.attempt()).orElseThrow().getAddress())
                .isEqualTo(bound.host());
    }

    @Test
    void shouldSayOnceThatTheContextChangedAndThenBindANewPodWhenTheWorkspaceIsReleased() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt turn = running();
        String revoked = bind(pods, turn).id();

        changeTheContext(worker(0));

        assertThat(rows.findBound(turn.turnId(), turn.attempt()).orElseThrow().isRevoked())
                .isTrue();
        assertThatThrownBy(() -> bind(pods, turn)).isInstanceOf(ConversationToolsPods.ContextChanged.class);
        await().atMost(SOON).until(() -> runtime.removed.contains(revoked));
        String replacement = bind(pods, turn).id();
        assertThat(replacement).isNotEqualTo(revoked);
        assertThat(rows.findBound(turn.turnId(), turn.attempt()).orElseThrow().isRevoked())
                .isFalse();
    }

    /** A worker that starts, or that reconnects, may have missed changes; the rows carry them, so it revokes nothing. */
    @Test
    void shouldKeepTheBoundPodOfARunningAttemptWhenAWorkerConnects() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt turn = running();
        ToolsPods.Started bound = bind(pods, turn);

        new TurnContextRevocations(new TurnContextGenerations(), worker(0)).resync();

        assertThat(rows.findBound(turn.turnId(), turn.attempt()).orElseThrow().isRevoked())
                .isFalse();
        assertThat(bind(pods, turn)).isEqualTo(bound);
        assertThat(runtime.removed).doesNotContain(bound.id());
    }

    @Test
    void shouldRevokeNothingWhenTheChangeRollsBack() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt turn = running();
        ToolsPods.Started bound = bind(pods, turn);

        transactions.executeWithoutResult(status -> {
            events.publishEvent(new MentorLedgerEvents.ContextChanged(workspace.getId()));
            status.setRollbackOnly();
        });

        assertThat(rows.findBound(turn.turnId(), turn.attempt()).orElseThrow().isRevoked())
                .isFalse();
        assertThat(bind(pods, turn)).isEqualTo(bound);
    }

    /** The loop discards the Pod that the revocation removed, because its connection broke; the notice stays. */
    @Test
    void shouldStillSayThatTheContextChangedWhenTheLoopDiscardsTheRevokedPod() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt turn = running();
        String revoked = bind(pods, turn).id();
        changeTheContext(pods);

        pods.discard(turn, revoked);

        assertThat(rows.findBound(turn.turnId(), turn.attempt())).isPresent();
        assertThatThrownBy(() -> bind(pods, turn)).isInstanceOf(ConversationToolsPods.ContextChanged.class);
        assertThat(bind(pods, turn).id()).as("the notice comes once").isNotEqualTo(revoked);
    }

    @Test
    void shouldRemoveTheJobOfARevokedPodAgainWhenItReapsUntilABindConsumesTheRow() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt turn = running();
        ToolsPods.Started revoked = bind(pods, turn);
        changeTheContext(pods);
        await().atMost(SOON).until(() -> runtime.removed.contains(revoked.id()));
        // The delete of its Job did not take, as an API failure leaves it.
        runtime.running.put(revoked.id(), revoked.nonce());

        pods.reap();

        assertThat(runtime.running).doesNotContainKey(revoked.id());
        assertThat(runtime.removed).filteredOn(revoked.id()::equals).hasSize(2);
        assertThat(rows.findBound(turn.turnId(), turn.attempt()).orElseThrow().isRevoked())
                .as("the row waits for the bind that says the context changed")
                .isTrue();
        assertThatThrownBy(() -> bind(pods, turn)).isInstanceOf(ConversationToolsPods.ContextChanged.class);
        assertThat(rows.findBound(turn.turnId(), turn.attempt())).isEmpty();
    }

    @Test
    void shouldOfferToolsAgainOnceAStartWorksAfterOneFailed() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt turn = running();
        runtime.failCreate = true;

        assertThatThrownBy(() -> bind(pods, turn)).isInstanceOf(SandboxException.class);
        assertThat(pods.available()).as("a start failed just now").isFalse();
        runtime.failCreate = false;
        bind(pods, turn);

        assertThat(pods.available()).isTrue();
    }

    @Test
    void shouldRemoveOnlyTheAttemptsOwnPodWhenItsLoopDiscardsOne() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt turn = running();
        String broken = bind(pods, turn).id();

        pods.discard(turn, ToolsPods.name(UUID.randomUUID().toString()));

        assertThat(bind(pods, turn).id())
                .as("a late report of another Pod keeps this one")
                .isEqualTo(broken);
        assertThat(runtime.removed).isEmpty();

        pods.discard(turn, broken);

        await().atMost(SOON).until(() -> runtime.removed.contains(broken));
        assertThat(bind(pods, turn).id()).isNotEqualTo(broken);
    }

    @Test
    void shouldRemoveTheAttemptsRowAndJobWhenTheAttemptEnds() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt turn = running();
        String bound = bind(pods, turn).id();

        worker(0).ended(turn.turnId(), turn.attempt());

        assertThat(rows.findBound(turn.turnId(), turn.attempt())).isEmpty();
        await().atMost(SOON).until(() -> runtime.removed.contains(bound));
        assertThat(runtime.running).doesNotContainKey(bound);
    }

    @Test
    void shouldRemoveThePodsThatNoLiveAttemptHoldsAndKeepTheOthersWhenItReaps() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt lost = running();
        TurnAttempt live = running();
        String lostPod = bind(pods, lost).id();
        String livePod = bind(pods, live).id();
        expire(lost);

        pods.reap();

        assertThat(rows.findBound(lost.turnId(), lost.attempt())).isEmpty();
        assertThat(runtime.removed).contains(lostPod).doesNotContain(livePod);
        assertThat(rows.findBound(live.turnId(), live.attempt())).isPresent();
    }

    @Test
    void shouldRemoveAPooledPodPastItsLifeAndAPodThatNeverStartedWhenItReaps() {
        ConversationToolsPods pods = worker(0);
        String old = ToolsPods.name(UUID.randomUUID().toString());
        String young = ToolsPods.name(UUID.randomUUID().toString());
        String neverStarted = ToolsPods.name(UUID.randomUUID().toString());
        transactions.executeWithoutResult(status -> {
            for (String name : List.of(old, young, neverStarted)) {
                rows.insertPooled(name, UUID.randomUUID());
            }
            rows.started(old, "10.0.9.1");
            rows.started(young, "10.0.9.2");
        });
        backdate(old, ConversationToolsPods.POOL_LIFE.plusMinutes(1));
        backdate(neverStarted, ConversationToolsPods.START_GRACE.plusMinutes(1));

        pods.reap();

        assertThat(rows.findAllNames()).contains(young).doesNotContain(old, neverStarted);
        assertThat(runtime.removed).contains(old, neverStarted).doesNotContain(young);
    }

    @Test
    void shouldRemoveAJobThatNoRowNamesOnlyOnceItIsPastTheStartGrace() {
        ConversationToolsPods pods = worker(0);
        String orphan = ToolsPods.name(UUID.randomUUID().toString());
        String starting = ToolsPods.name(UUID.randomUUID().toString());
        runtime.others.add(new ToolsPods.Listed(orphan, ConversationToolsPods.START_GRACE.plusSeconds(1)));
        runtime.others.add(new ToolsPods.Listed(starting, Duration.ofSeconds(10)));

        pods.reap();

        assertThat(runtime.removed)
                .as("a younger Job may belong to a row that is written after the read")
                .contains(orphan)
                .doesNotContain(starting);
    }

    @Test
    void shouldRemoveNoPodWhenAWorkerCloses() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt turn = running();
        ToolsPods.Started bound = bind(pods, turn);

        pods.close();

        assertThat(runtime.removed).isEmpty();
        assertThat(runtime.running).containsKey(bound.id());
        assertThat(rows.findBound(turn.turnId(), turn.attempt())).isPresent();
        assertThatThrownBy(() -> bind(pods, turn)).isInstanceOf(SandboxCancelledException.class);
        assertThat(bind(worker(0), turn).id())
                .as("another worker serves the attempt")
                .isEqualTo(bound.id());
    }

    @Test
    void shouldFillTheInstallationsPoolToItsSizeWhenEveryWorkerFillsIt() throws Exception {
        List<ConversationToolsPods> filling = List.of(worker(2), worker(2), worker(2));

        CompletableFuture.allOf(filling.stream()
                        .map(pods -> CompletableFuture.runAsync(pods::reap))
                        .toArray(CompletableFuture[]::new))
                .get(10, TimeUnit.SECONDS);
        await().atMost(SOON).until(() -> pooledReady().size() == 2);
        filling.forEach(ConversationToolsPods::reap);

        await().during(Duration.ofMillis(500)).atMost(SOON).until(() -> pooled() == 2);
        assertThat(runtime.createdCount()).isEqualTo(2);
    }

    @Test
    void shouldWaitForToolsQuotaWithoutStartingAPodAndBindOnceItHasRoom() throws Exception {
        var meters = new SimpleMeterRegistry();
        ConversationToolsPods pods = worker(0, meters);
        TurnAttempt turn = running();
        runtime.quotaFull = true;

        var waiting = CompletableFuture.supplyAsync(() -> bind(pods, turn));
        await().atMost(SOON).until(() -> runtime.quotaRefusals.get() > 1);
        assertThat(runtime.createdCount()).isZero();
        assertThat(pods.available())
                .as("a full quota is no failure of the worker")
                .isTrue();
        runtime.quotaFull = false;

        assertThat(waiting.get(10, TimeUnit.SECONDS).id()).isNotBlank();
        assertThat(meters.get(AgentMetrics.MENTOR_TURN_TOOLS_WAIT)
                        .tag("outcome", "ready")
                        .timer()
                        .count())
                .isOne();
    }

    @Test
    void shouldStopWaitingForToolsQuotaWhenTheRequestEnds() {
        ConversationToolsPods pods = worker(0);
        TurnAttempt turn = running();
        runtime.quotaFull = true;
        var expired = new AtomicBoolean();

        var waiting = CompletableFuture.supplyAsync(() -> pods.bind(turn, expired::get));
        await().atMost(SOON).until(() -> runtime.quotaRefusals.get() > 1);
        expired.set(true);

        assertThatThrownBy(() -> waiting.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(SandboxCancelledException.class);
        assertThat(runtime.createdCount()).isZero();
        assertThat(rows.findBound(turn.turnId(), turn.attempt())).isEmpty();
    }
}

package de.tum.cit.aet.hephaestus.integration.core.signal;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class DeferredSignalIntegrationTest extends BaseIntegrationTest {
    private static final Instant START = Instant.parse("2026-09-05T00:00:00Z");

    @Autowired
    private ArtifactSignalRepository signals;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("deferred-" + UUID.randomUUID()));
    }

    @Test
    void shouldKeepTheOriginalArrivalTimeWhenADeferredDeliveryIsReplayed() {
        SignalKey key = key(workspace, "same");
        assertThat(insert(key, START)).isEqualTo(1);
        assertThat(signals.isDeferred(key)).isTrue();
        assertThat(insert(key, START.plusSeconds(20))).isZero();
        assertThat(rows(workspace)).singleElement().satisfies(row -> {
            assertThat(row.getStateChangedAt()).isEqualTo(START);
            assertThat(row.getOccurredAt()).isEqualTo(START);
        });
        assertThat(due(START.plusSeconds(30))).contains(workspace.getId());
    }

    @Test
    void shouldPromoteSyncDiscoveryOnlyOnceAndNeverScheduleSyncByItself() {
        SignalKey key = key(workspace, "synced");
        transactions.executeWithoutResult(
                status -> signals.insertIfAbsent(key, UUID.randomUUID(), START, "SYNC", START));
        assertThat(due(START.plusSeconds(60))).doesNotContain(workspace.getId());
        assertThat(signals.isDeferred(key)).isFalse();
        assertThat(insert(key, START.plusSeconds(60))).isEqualTo(1);
        assertThat(insert(key, START.plusSeconds(70))).isZero();
        assertThat(due(START.plusSeconds(89))).doesNotContain(workspace.getId());
        assertThat(due(START.plusSeconds(90))).contains(workspace.getId());
    }

    @Test
    void shouldWaitForTheLastSnapshotButBoundAContinuousBurst() {
        insert(key(workspace, "first"), START);
        insert(key(workspace, "second"), START.plusSeconds(20));
        assertThat(due(START.plusSeconds(30))).doesNotContain(workspace.getId());
        assertThat(due(START.plusSeconds(50))).contains(workspace.getId());
        insert(key(workspace, "latest"), START.plusSeconds(299));
        assertThat(due(START.plusSeconds(299))).doesNotContain(workspace.getId());
        assertThat(due(START.plusSeconds(300))).contains(workspace.getId());
    }

    @Test
    void shouldNotRestartTheQuietPeriodWhenReturningToAPendingSnapshot() {
        insert(key(workspace, "a"), START);
        insert(key(workspace, "b"), START.plusSeconds(20));
        assertThat(insert(key(workspace, "a"), START.plusSeconds(40))).isZero();
        assertThat(due(START.plusSeconds(49))).doesNotContain(workspace.getId());
        assertThat(due(START.plusSeconds(50))).contains(workspace.getId());
    }

    @Test
    void shouldKeepIdenticalArtifactAndRevisionIndependentAcrossWorkspaces() {
        Workspace other = workspaces.save(WorkspaceTestFixtures.activeWorkspace("deferred-other-" + UUID.randomUUID()));
        SignalKey ownKey = key(workspace, "same");
        SignalKey otherKey = key(other, "same");
        insert(ownKey, START);
        insert(otherKey, START);
        transactions.executeWithoutResult(status -> {
            assertThat(signals.lockDeferred(workspace.getId(), 42L, ScmSignals.ISSUE_UPDATED.value()))
                    .singleElement()
                    .satisfies(row -> assertThat(row.key()).isEqualTo(ownKey));
            signals.markRefused(ownKey, "SUPPRESSED", "COALESCED", START.plusSeconds(30));
        });
        assertThat(rows(other))
                .singleElement()
                .satisfies(row -> assertThat(row.getState()).isEqualTo(SignalState.DEFERRED));
        assertThat(insert(ownKey, START.plusSeconds(60))).isEqualTo(1);
        assertThat(rows(workspace)).singleElement().satisfies(row -> {
            assertThat(row.getState()).isEqualTo(SignalState.DEFERRED);
            assertThat(row.getStateReason()).isNull();
            assertThat(row.getStateChangedAt()).isEqualTo(START.plusSeconds(60));
        });
    }

    @Test
    void shouldRefuseToRearmASuppressedRowForAnyReasonOtherThanCoalesced() {
        // The WHERE clause's `state_reason = 'COALESCED'` guard is the only thing stopping a webhook
        // replay from undoing a cooldown, a per-requester quota or an out-of-scope refusal.
        SignalKey key = key(workspace, "quota-limited");
        insert(key, START);
        transactions.executeWithoutResult(
                status -> signals.markRefused(key, "SUPPRESSED", "REQUESTER_QUOTA_EXHAUSTED", START.plusSeconds(30)));
        assertThat(insert(key, START.plusSeconds(60))).isZero();
        assertThat(rows(workspace)).singleElement().satisfies(row -> {
            assertThat(row.getState()).isEqualTo(SignalState.SUPPRESSED);
            assertThat(row.getStateReason()).isEqualTo(SignalStateReason.REQUESTER_QUOTA_EXHAUSTED);
        });
    }

    @Test
    void shouldNeverRearmASnapshotThatAlreadyCreatedAReviewJob() {
        SignalKey key = key(workspace, "reviewed");
        insert(key, START);
        UUID jobId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> signals.markTriggered(key, jobId, START.plusSeconds(30)));
        assertThat(insert(key, START.plusSeconds(60))).isZero();
        assertThat(signals.isDeferred(key)).isFalse();
        assertThat(rows(workspace)).singleElement().satisfies(row -> {
            assertThat(row.getState()).isEqualTo(SignalState.TRIGGERED);
            assertThat(row.getJobId()).isEqualTo(jobId);
        });
    }

    @Test
    void shouldRotateFailedArtifactsWithoutPostponingTheirQuietPeriod() {
        Workspace other = workspaces.save(WorkspaceTestFixtures.activeWorkspace("deferred-fair-" + UUID.randomUUID()));
        insert(key(workspace, "first"), START);
        insert(key(other, "second"), START.plusSeconds(1));
        signals.noteDeferredAttempt(workspace.getId(), 42L, ScmSignals.ISSUE_UPDATED.value(), START.plusSeconds(60));
        assertThat(due(START.plusSeconds(60)).stream()
                        .filter(id -> id.equals(workspace.getId()) || id.equals(other.getId()))
                        .toList())
                .containsExactly(other.getId(), workspace.getId());
        assertThat(rows(workspace))
                .singleElement()
                .satisfies(row -> assertThat(row.getStateChangedAt()).isEqualTo(START));
    }

    @Test
    void shouldLeaveWorkAvailableWhenSettlementRollsBack() {
        SignalKey key = key(workspace, "retry");
        insert(key, START);
        transactions.executeWithoutResult(status -> {
            assertThat(signals.lockDeferred(workspace.getId(), 42L, ScmSignals.ISSUE_UPDATED.value()))
                    .hasSize(1);
            signals.markTriggered(key, UUID.randomUUID(), START.plusSeconds(30));
            status.setRollbackOnly();
        });
        assertThat(rows(workspace)).singleElement().satisfies(row -> {
            assertThat(row.getState()).isEqualTo(SignalState.DEFERRED);
            assertThat(row.getJobId()).isNull();
        });
        assertThat(due(START.plusSeconds(30))).contains(workspace.getId());
    }

    /**
     * Pins the second line of defence behind a scheduler lock: PostgreSQL re-evaluates {@code
     * lockDeferred}'s predicate against each row once a concurrent caller's {@code FOR UPDATE} is
     * granted (EvalPlanQual), so the loser's list comes back empty rather than raising or blocking
     * forever — the {@code containsExactlyInAnyOrder(0, 1)} below is that guarantee.
     */
    @Test
    void shouldAllowOnlyOneConsumerToSettleADeferredGroup() throws Exception {
        SignalKey key = key(workspace, "race");
        insert(key, START);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> consume = () -> {
                ready.countDown();
                if (!go.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Consumer start timed out");
                }
                return transactions.execute(status -> {
                    var locked = signals.lockDeferred(workspace.getId(), 42L, ScmSignals.ISSUE_UPDATED.value());
                    for (var signal : locked) {
                        signals.markTriggered(signal.key(), UUID.randomUUID(), START.plusSeconds(30));
                    }
                    return locked.size();
                });
            };
            var first = executor.submit(consume);
            var second = executor.submit(consume);
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
        }
    }

    private int insert(SignalKey key, Instant now) {
        Integer inserted =
                transactions.execute(status -> signals.insertDeferred(key, UUID.randomUUID(), now, now, null));
        return java.util.Objects.requireNonNull(inserted);
    }

    private List<Long> due(Instant now) {
        return signals
                .findDueDeferred(ScmSignals.ISSUE_UPDATED.value(), now.minusSeconds(30), now.minusSeconds(300), 10000)
                .stream()
                .map(ArtifactSignalRepository.DeferredArtifact::getWorkspaceId)
                .toList();
    }

    private List<ArtifactSignal> rows(Workspace owner) {
        return signals.findForArtifact(owner.getId(), ScmSignals.ISSUE.value(), 42L);
    }

    private SignalKey key(Workspace owner, String revision) {
        return new SignalKey(owner.getId(), 42L, ScmSignals.ISSUE_UPDATED, new SignalRevision("digest~" + revision));
    }
}

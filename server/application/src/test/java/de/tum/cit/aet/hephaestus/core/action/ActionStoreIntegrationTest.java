package de.tum.cit.aet.hephaestus.core.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The outbox lifecycle against PostgreSQL: claims, lanes, fencing, the reaper and the trigger that refuses what
 * {@link ActionState} does not allow. Every test makes its own lane and keys, so rows of other tests never match.
 */
class ActionStoreIntegrationTest extends BaseIntegrationTest {
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration MARGIN = Duration.ofSeconds(60);

    @Autowired
    private ActionStore store;

    @Autowired
    private AgentActions actions;

    @Autowired
    private ActionPersonDataCatalog erasure;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldKeepOneActionPerIdempotencyKey() {
        String key = "test:" + UUID.randomUUID();

        UUID first = actions.enqueue(request(key, key, null, 3));
        UUID second = actions.enqueue(request(key, key, null, 3));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_action WHERE idempotency_key = ?", Long.class, key))
                .isEqualTo(1);
    }

    @Test
    void shouldRunALaneOneActionAtATimeInEnqueueOrder() {
        String lane = "test-lane:" + UUID.randomUUID();
        UUID create = actions.enqueue(request(lane, lane + ":create", null, 3));
        UUID edit = actions.enqueue(request(lane, lane + ":edit", null, 3));

        var first = claimOwn(List.of(create, edit));
        assertThat(first).extracting(ActionStore.Claim::id).containsExactly(create);
        assertThat(claimOwn(List.of(create, edit)))
                .as("the edit waits for the create")
                .isEmpty();

        assertThat(store.settle(create, first.getFirst().epoch(), new ActionResult.Done("ref")))
                .isPresent();

        assertThat(claimOwn(List.of(create, edit)))
                .extracting(ActionStore.Claim::id)
                .containsExactly(edit);
    }

    @Test
    void shouldRefuseEveryWriteOfAnAttemptWhoseLeaseWasTakenAgain() {
        UUID id = actions.enqueue(request("lane:" + UUID.randomUUID(), "key:" + UUID.randomUUID(), null, 3));
        var stale = claimOwn(List.of(id)).getFirst();
        expireLease(id);

        var current = claimOwn(List.of(id)).getFirst();

        assertThat(current.epoch()).isEqualTo(stale.epoch() + 1);
        assertThat(store.beginWrite(id, stale.epoch(), MARGIN)).isFalse();
        assertThat(store.settle(id, stale.epoch(), new ActionResult.Done("stale")))
                .isEmpty();
        assertThat(store.beginWrite(id, current.epoch(), MARGIN)).isTrue();
        assertThat(store.settle(id, current.epoch(), new ActionResult.Done("current")))
                .get()
                .extracting(ActionStore.Settled::state)
                .isEqualTo(ActionState.DONE);
        assertThat(row(id))
                .containsEntry("readback_ref", "current")
                .containsEntry("stop_reason", "COMPLETED")
                .containsEntry("payload", null);
    }

    @Test
    void shouldNeverTakeAnEmailAgainAfterItsWriteStarted() {
        UUID id = actions.enqueue(request("lane:" + UUID.randomUUID(), "key:" + UUID.randomUUID(), null, 3));
        var claim = claimOwn(List.of(id)).getFirst();
        assertThat(store.beginWrite(id, claim.epoch(), MARGIN)).isTrue();
        expireLease(id);

        assertThat(claimOwn(List.of(id))).isEmpty();
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE agent_action SET lease_epoch = lease_epoch + 1, attempts = attempts + 1 WHERE id = ?",
                        id))
                .hasMessageContaining("never taken again");

        store.endStale();

        assertThat(row(id))
                .containsEntry("state", "UNCERTAIN")
                .containsEntry("stop_reason", "LEASE_LOST_AFTER_WRITE")
                .containsEntry("payload", null);
    }

    @Test
    void shouldTakeAnEmailAgainWhenItsLeaseExpiredBeforeAnyWrite() {
        UUID id = actions.enqueue(request("lane:" + UUID.randomUUID(), "key:" + UUID.randomUUID(), null, 3));
        claimOwn(List.of(id));
        expireLease(id);

        assertThat(claimOwn(List.of(id)))
                .singleElement()
                .satisfies(claim -> assertThat(claim.attempts()).isEqualTo(2));
    }

    @Test
    void shouldRetryOnlyUntilTheLastAttemptAndClearTheFenceOfAProvenRetry() {
        UUID id = actions.enqueue(request("lane:" + UUID.randomUUID(), "key:" + UUID.randomUUID(), null, 2));
        var first = claimOwn(List.of(id)).getFirst();
        assertThat(store.beginWrite(id, first.epoch(), MARGIN)).isTrue();

        store.settle(id, first.epoch(), new ActionResult.Retry(Duration.ZERO));
        assertThat(row(id)).containsEntry("state", "ADMITTED").containsEntry("write_started_at", null);

        var second = claimOwn(List.of(id)).getFirst();
        store.settle(id, second.epoch(), new ActionResult.Retry(Duration.ZERO));
        assertThat(row(id))
                .containsEntry("state", "FAILED")
                .containsEntry("stop_reason", "ATTEMPTS_EXHAUSTED")
                .containsEntry("payload", null);
    }

    @Test
    void shouldNotSpendAnAttemptOnADeferral() {
        UUID id = actions.enqueue(request("lane:" + UUID.randomUUID(), "key:" + UUID.randomUUID(), null, 1));
        var claim = claimOwn(List.of(id)).getFirst();

        store.settle(id, claim.epoch(), new ActionResult.Defer(Duration.ZERO));

        assertThat(row(id)).containsEntry("state", "ADMITTED").containsEntry("attempts", 0);
    }

    @Test
    void shouldCancelAnActionThatExpiredBeforeItStarted() {
        String key = "key:" + UUID.randomUUID();
        UUID id = actions.enqueue(new ActionRequest(
                ActionKind.EMAIL,
                null,
                key,
                key,
                JsonNodeFactory.instance.objectNode().put("n", 1),
                null,
                3,
                Instant.now().minusSeconds(1)));

        assertThat(store.endStale())
                .contains(new ActionStore.Settled(ActionKind.EMAIL, ActionState.CANCELLED, ActionStopReason.EXPIRED));
        assertThat(claimOwn(List.of(id))).isEmpty();
        assertThat(row(id)).containsEntry("state", "CANCELLED").containsEntry("payload", null);
    }

    @Test
    void shouldDeleteTheActionsOfAnErasedAccount() {
        Account account = accounts.save(new Account("Outbox " + UUID.randomUUID()));
        long accountId = Objects.requireNonNull(account.getId());
        UUID pending = actions.enqueue(request("lane:" + UUID.randomUUID(), "key:" + UUID.randomUUID(), accountId, 3));
        UUID other = actions.enqueue(request("lane:" + UUID.randomUUID(), "key:" + UUID.randomUUID(), null, 3));

        erasure.eraseAccount(accountId);

        assertThat(jdbc.queryForList("SELECT id FROM agent_action WHERE id = ?", pending))
                .isEmpty();
        assertThat(row(other)).containsEntry("state", "ADMITTED");
    }

    /**
     * Every pair of states, so the trigger and {@link ActionState} cannot drift apart. A plain write never enters
     * EXECUTING: only a claim does, because a claim takes the next epoch.
     */
    @Test
    void shouldRefuseInDatabaseEveryTransitionThatTheStateTableRefuses() {
        for (ActionState from : ActionState.values()) {
            for (ActionState to : ActionState.values()) {
                if (from == to) continue;
                UUID id = inState(from);
                boolean accepted;
                try {
                    accepted = jdbc.update(transition(to), "ref", id) == 1;
                } catch (RuntimeException refused) {
                    accepted = false;
                }
                assertThat(accepted)
                        .as("%s -> %s", from, to)
                        .isEqualTo(from.canTransitionTo(to) && to != ActionState.EXECUTING);
            }
        }
    }

    @Test
    void shouldNeverChangeAPayloadOrTheIdentityOfAnAction() {
        UUID id = actions.enqueue(request("lane:" + UUID.randomUUID(), "key:" + UUID.randomUUID(), null, 3));

        assertThatThrownBy(() -> jdbc.update("UPDATE agent_action SET payload = '{\"n\":2}' WHERE id = ?", id))
                .hasMessageContaining("payload never changes");
        assertThatThrownBy(() -> jdbc.update("UPDATE agent_action SET lane_key = 'other' WHERE id = ?", id))
                .hasMessageContaining("identity is immutable");
    }

    /** An action in the given state, reached the way the server reaches it. */
    private UUID inState(ActionState state) {
        String key = "walk:" + UUID.randomUUID();
        if (state == ActionState.PROPOSED) {
            // No server path proposes yet, so the walk inserts its own proposal.
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO agent_action (id, kind, lane_key, idempotency_key, state, payload, max_attempts,
                      next_attempt_at, created_at, updated_at)
                    VALUES (?, 'EMAIL', ?, ?, 'PROPOSED', '{}', 3, now(), now(), now())
                    """, id, key, key);
            return id;
        }
        UUID id = actions.enqueue(request(key, key, null, 3));
        if (state == ActionState.ADMITTED) return id;
        var claim = claimOwn(List.of(id)).getFirst();
        @Nullable
        ActionResult result = switch (state) {
            case DONE -> new ActionResult.Done(null);
            case UNCERTAIN -> new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED);
            case FAILED -> new ActionResult.Failed(ActionStopReason.REJECTED);
            case CANCELLED -> new ActionResult.Cancelled(ActionStopReason.SILENCED);
            default -> null;
        };
        if (result != null) store.settle(id, claim.epoch(), result);
        return id;
    }

    /** The write that moves an action to the state, with the columns the target state needs. */
    private static String transition(ActionState to) {
        return switch (to) {
            case PROPOSED -> "UPDATE agent_action SET state = 'PROPOSED', readback_ref = ? WHERE id = ?";
            case ADMITTED ->
                "UPDATE agent_action SET state = 'ADMITTED', leased_until = NULL, readback_ref = ? WHERE id = ?";
            case EXECUTING ->
                "UPDATE agent_action SET state = 'EXECUTING', leased_until = now() + interval '1 minute',"
                        + " readback_ref = ? WHERE id = ?";
            case DONE, UNCERTAIN, FAILED, CANCELLED ->
                "UPDATE agent_action SET state = '" + to + "', stop_reason = '"
                        + (to == ActionState.DONE ? "COMPLETED" : "REJECTED")
                        + "', ended_at = now(), payload = NULL, leased_until = NULL, readback_ref = ? WHERE id = ?";
        };
    }

    private List<ActionStore.Claim> claimOwn(List<UUID> ids) {
        // Other tests leave due actions behind; take enough to reach ours, then release the rest at once.
        var claimed = new ArrayList<ActionStore.Claim>();
        for (ActionStore.Claim claim : store.claim(1000, LEASE)) {
            if (ids.contains(claim.id())) claimed.add(claim);
            else store.settle(claim.id(), claim.epoch(), new ActionResult.Cancelled(ActionStopReason.SILENCED));
        }
        return claimed;
    }

    private void expireLease(UUID id) {
        jdbc.update("UPDATE agent_action SET leased_until = now() - interval '1 second' WHERE id = ?", id);
    }

    private Map<String, @Nullable Object> row(UUID id) {
        return jdbc.queryForMap(
                "SELECT state, stop_reason, readback_ref, payload::text AS payload, attempts, write_started_at"
                        + " FROM agent_action WHERE id = ?",
                id);
    }

    private static ActionRequest request(String lane, String key, @Nullable Long subject, int maxAttempts) {
        return new ActionRequest(
                ActionKind.EMAIL,
                null,
                lane,
                key,
                JsonNodeFactory.instance.objectNode().put("n", 1),
                subject,
                maxAttempts,
                null);
    }
}

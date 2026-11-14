package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes back the sandboxes that no subject needs, on the database clock, on every replica that holds the Kubernetes
 * client. One rule decides it ({@link AgentSandboxRepository#retire}). A ready sandbox whose claim is gone, as after a
 * stopped window deleted the claims, is taken back too, so its subject gets a new sandbox. Then every claim that no row
 * names goes, as after a workspace was deleted, and so do the claims of the taken-back sandboxes. No leader: each
 * statement changes a row once, and each claim call is idempotent.
 */
@WorkspaceAgnostic("Takes back sandboxes of every workspace; each row carries its workspace")
public class AgentSandboxReaper {

    private static final Logger log = LoggerFactory.getLogger(AgentSandboxReaper.class);

    /** The most sandboxes one tick takes back or deletes, so a backlog cannot hold the scheduler thread for long. */
    static final int BATCH = 100;

    static final List<String> REASONS = List.of("expired", "start_limit", "idle", "lost", "orphan");

    private final AgentSandboxRepository sandboxes;
    private final AgentSandboxes owner;
    private final SandboxClaims claims;
    private final AgentCapacityProperties capacity;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;

    AgentSandboxReaper(
            AgentSandboxRepository sandboxes,
            AgentSandboxes owner,
            SandboxClaims claims,
            AgentCapacityProperties capacity,
            TransactionTemplate transactions,
            MeterRegistry meters) {
        this.sandboxes = sandboxes;
        this.owner = owner;
        this.claims = claims;
        this.capacity = capacity;
        this.transactions = transactions;
        this.meters = meters;
        // Registered at start, so an alert on a count that is still zero has a series to read.
        meters.counter(AgentMetrics.AGENT_SANDBOX_REAPER_FAILURE);
        for (String reason : REASONS) meters.counter(AgentMetrics.AGENT_SANDBOX_REAPED, "reason", reason);
    }

    public void reap() {
        try {
            retire();
            // Read before the claims: a row that was ready then had its claim, so a claim missing from the later list
            // is gone, not still being created.
            var ready = sandboxes.findReady();
            var names = claims.names();
            takeBackLost(ready, names);
            deleteOrphans(names);
            deleteClaims();
        } catch (RuntimeException e) {
            meters.counter(AgentMetrics.AGENT_SANDBOX_REAPER_FAILURE).increment();
            log.atWarn()
                    .addKeyValue("event.name", "agent.sandbox.reaper.failed")
                    .addKeyValue("error.type", e.getClass().getName())
                    .setCause(e)
                    .log("Sandbox reaper failed; it tries again next tick");
        }
    }

    private void retire() {
        var retired = Objects.requireNonNull(transactions.execute(status -> sandboxes.retire(
                capacity.sandboxStart().toSeconds(),
                capacity.sandboxIdle().toSeconds(),
                capacity.conversationSandboxIdle().toSeconds(),
                BATCH)));
        for (var row : retired) {
            meters.counter(AgentMetrics.AGENT_SANDBOX_REAPED, "reason", row.getReason())
                    .increment();
            SandboxEvents.event(log.atInfo(), "agent.sandbox.taken_back", row)
                    .addKeyValue("sandbox.reason", row.getReason())
                    .log("Sandbox taken back; its claim is deleted next");
        }
    }

    /**
     * Each in a short transaction that takes the row back only while it is still ready with that claim. At most
     * {@link #BATCH} per tick; the rest go on the next one.
     */
    private void takeBackLost(List<AgentSandboxRepository.SandboxRow> ready, List<String> names) {
        var live = new HashSet<>(names);
        int taken = 0;
        for (var row : ready) {
            if (taken == BATCH) return;
            if (live.contains(row.getClaimName())) continue;
            var lost = transactions.execute(
                    status -> sandboxes.takeBackLost(row.getId(), row.getWorkspaceId(), row.getClaimName()));
            if (lost == null || lost.isEmpty()) continue;
            taken++;
            meters.counter(AgentMetrics.AGENT_SANDBOX_REAPED, "reason", "lost").increment();
            SandboxEvents.event(log.atInfo(), "agent.sandbox.taken_back", lost.get())
                    .addKeyValue("sandbox.reason", "lost")
                    .log("Ready sandbox without a claim taken back; its subject can start a new one");
        }
    }

    /** Each claim on its own: one that the API refuses stays for the next tick, and the rest go on. */
    private void deleteClaims() {
        for (var row : sandboxes.findDeleting(BATCH)) {
            try {
                owner.deleteClaim(row);
            } catch (RuntimeException e) {
                meters.counter(AgentMetrics.AGENT_SANDBOX_REAPER_FAILURE).increment();
                SandboxEvents.event(log.atWarn(), "agent.sandbox.claim_delete_failed", row)
                        .addKeyValue("error.type", e.getClass().getName())
                        .log("Could not delete a sandbox's claim; the reaper tries again next tick");
            }
        }
    }

    /**
     * A claim that no row names: its row was deleted with its workspace. A claim is created only after its row
     * commits, so a claim that is being created always has its row. The rows are read after {@code names} was listed.
     */
    private void deleteOrphans(List<String> names) {
        if (names.isEmpty()) return;
        var known = new HashSet<>(sandboxes.findClaimNames(names));
        for (String name : names) {
            if (known.contains(name)) continue;
            try {
                claims.delete(name);
            } catch (RuntimeException e) {
                meters.counter(AgentMetrics.AGENT_SANDBOX_REAPER_FAILURE).increment();
                log.atWarn()
                        .addKeyValue("event.name", "agent.sandbox.claim_delete_failed")
                        .addKeyValue(StructuredLogKeys.SANDBOX_NAME, name)
                        .addKeyValue("error.type", e.getClass().getName())
                        .log("Could not delete a sandbox claim without a row; the reaper tries again next tick");
                continue;
            }
            meters.counter(AgentMetrics.AGENT_SANDBOX_REAPED, "reason", "orphan")
                    .increment();
            log.atInfo()
                    .addKeyValue("event.name", "agent.sandbox.orphan_deleted")
                    .addKeyValue(StructuredLogKeys.SANDBOX_NAME, name)
                    .log("Sandbox claim without a sandbox row deleted");
        }
    }
}

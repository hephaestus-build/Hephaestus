package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes back the sandboxes that no subject needs, on the database clock, on every replica that holds the Kubernetes
 * client. One rule decides it ({@link AgentSandboxRepository#retire}); then the claims of the taken-back sandboxes go,
 * and so does every claim that no row names, as after a workspace was deleted. No leader: each statement changes a
 * row once, and each claim call is idempotent.
 */
@WorkspaceAgnostic("Takes back sandboxes of every workspace; each row carries its workspace")
public class AgentSandboxReaper {

    private static final Logger log = LoggerFactory.getLogger(AgentSandboxReaper.class);

    /** The most sandboxes one tick takes back or deletes, so a backlog cannot hold the scheduler thread for long. */
    static final int BATCH = 100;

    static final List<String> REASONS = List.of("expired", "start_limit", "idle", "orphan");

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
            deleteClaims();
            deleteOrphans();
        } catch (RuntimeException e) {
            meters.counter(AgentMetrics.AGENT_SANDBOX_REAPER_FAILURE).increment();
            log.warn(
                    "Sandbox reaper failed; trying again next tick: error={}",
                    e.getClass().getSimpleName(),
                    e);
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
            log.info(
                    "Sandbox taken back: sandboxId={}, kind={}, reason={}",
                    row.getId(),
                    row.getKind(),
                    row.getReason());
        }
    }

    /** Each claim on its own: one that the API refuses stays for the next tick, and the rest go on. */
    private void deleteClaims() {
        for (var row : sandboxes.findDeleting(BATCH)) {
            try {
                owner.deleteClaim(row);
            } catch (RuntimeException e) {
                meters.counter(AgentMetrics.AGENT_SANDBOX_REAPER_FAILURE).increment();
                log.warn(
                        "Could not delete a sandbox's claim; trying again next tick: sandboxId={}, error={}",
                        row.getId(),
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * A claim that no row names: its row was deleted with its workspace. A claim is created only after its row
     * commits, so a claim that is being created always has its row.
     */
    private void deleteOrphans() {
        var names = claims.names();
        if (names.isEmpty()) return;
        var known = new HashSet<>(sandboxes.findClaimNames(names));
        for (String name : names) {
            if (known.contains(name)) continue;
            try {
                claims.delete(name);
            } catch (RuntimeException e) {
                meters.counter(AgentMetrics.AGENT_SANDBOX_REAPER_FAILURE).increment();
                log.warn(
                        "Could not delete a sandbox claim without a row; trying again next tick: claim={}, error={}",
                        name,
                        e.getClass().getSimpleName());
                continue;
            }
            meters.counter(AgentMetrics.AGENT_SANDBOX_REAPED, "reason", "orphan")
                    .increment();
            log.info("Sandbox claim without a sandbox row deleted: claim={}", name);
        }
    }
}

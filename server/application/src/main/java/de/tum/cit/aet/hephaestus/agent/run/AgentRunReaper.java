package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes back the runs whose lease expired, on the database clock, on every server replica at once. Each run is taken
 * back in its own transaction, together with its domain row, by a write that holds only while the lease is still
 * expired: of two replicas, one takes it back and the other changes nothing. One failing run cannot stop the runs
 * behind it. No leader, no registry: an expired lease is the whole fact.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Takes back expired leases in every workspace; each run carries its workspace")
public class AgentRunReaper {

    private static final Logger log = LoggerFactory.getLogger(AgentRunReaper.class);
    /** The most runs one tick takes back, so a backlog cannot hold a scheduler thread for long. */
    static final int BATCH = 200;

    private final AgentRunRepository runs;
    private final TransactionTemplate transactions;
    private final Map<AgentRunKind, AgentRunHandler> handlers = new EnumMap<>(AgentRunKind.class);
    private final MeterRegistry meters;

    AgentRunReaper(
            AgentRunRepository runs,
            TransactionTemplate transactions,
            List<AgentRunHandler> handlers,
            MeterRegistry meters) {
        this.runs = runs;
        this.transactions = transactions;
        this.meters = meters;
        for (AgentRunHandler handler : handlers) {
            if (this.handlers.put(handler.kind(), handler) != null)
                throw new IllegalStateException("Two handlers for runs of kind " + handler.kind());
        }
        // Registered at start, so an alert on a count that is still zero has a series to read.
        meters.counter(AgentMetrics.AGENT_RUN_REAPER_FAILURE);
        for (AgentRunKind kind : AgentRunKind.values()) {
            count(kind, "requeued", 0);
            count(kind, "continued", 0);
            for (AgentRunStopReason reason : List.of(
                    AgentRunStopReason.ATTEMPTS_EXHAUSTED, AgentRunStopReason.NOT_STARTED, AgentRunStopReason.DEADLINE))
                count(kind, reason.name().toLowerCase(Locale.ROOT), 0);
        }
    }

    @Scheduled(
            fixedDelayString = "${hephaestus.agent.capacity.lease-renewal:25s}",
            initialDelayString = "${hephaestus.agent.capacity.lease-renewal:25s}")
    public void reap() {
        for (AgentRunHandler handler : handlers.values()) {
            String kind = handler.kind().name();
            drain(skipped -> runs.findExpired(kind, skipped), row -> takeBack(handler, row));
            drain(skipped -> runs.findOverdueQueued(kind, skipped), row -> endOverdue(handler, row));
        }
    }

    /** Takes back one run after another; a run that fails is skipped until the next tick, and the rest go on. */
    private void drain(
            Function<Set<UUID>, Optional<AgentRunRepository.ExpiredRow>> next,
            Consumer<AgentRunRepository.ExpiredRow> takeBack) {
        Set<UUID> failed = new HashSet<>();
        for (int i = 0; i < BATCH; i++) {
            Optional<AgentRunRepository.ExpiredRow> row = next.apply(failed);
            if (row.isEmpty()) return;
            try {
                takeBack.accept(row.get());
            } catch (StaleRunException stale) {
                // The conditional fence lost; the transaction rolled back every domain and successor write.
                failed.add(row.get().getId());
            } catch (RuntimeException e) {
                failed.add(row.get().getId());
                meters.counter(AgentMetrics.AGENT_RUN_REAPER_FAILURE).increment();
                event(log.atWarn(), "agent.run.reaper.failed", row.get())
                        .addKeyValue("error.type", e.getClass().getName())
                        .setCause(e)
                        .log("Run reaper failed on one run; it tries the run again next tick");
            }
        }
    }

    private void takeBack(AgentRunHandler handler, AgentRunRepository.ExpiredRow row) {
        AgentRunKind kind = handler.kind();
        transactions.executeWithoutResult(status -> {
            handler.lock(new AgentRunRef(row.getId(), row.getWorkspaceId()));
            AgentRunFence lost = new AgentRunFence(row.getId(), row.getWorkspaceId(), row.getLeaseEpoch());
            AgentRunStopReason reason = row.getPastDeadline()
                    ? AgentRunStopReason.DEADLINE
                    : row.getAttempt() < handler.retries() ? null : AgentRunStopReason.ATTEMPTS_EXHAUSTED;
            // The domain moves first, while the run still reads as its lost holder left it.
            if (reason == null) {
                var outcome = handler.lost(lost);
                if (outcome instanceof AgentRunHandler.Continued continued) {
                    TransactionCallbacks.afterCommit(() -> {
                        count(kind, "continued");
                        event(log.atInfo(), "agent.run.continued", row)
                                .addKeyValue("run.next_id", continued.next().id())
                                .log("Run lease expired; its successor continues the work");
                    });
                    return;
                }
                if (outcome instanceof AgentRunHandler.Requeue) {
                    long backoff = handler.retryAfter(row.getAttempt() + 1).toSeconds();
                    if (!taken(status, runs.requeueExpired(lost.runId(), lost.workspaceId(), lost.epoch(), backoff)))
                        return;
                    settle(lost);
                    runs.notifyQueued(AgentRunNotifications.CHANNEL, kind.name());
                    count(kind, "requeued");
                    event(log.atInfo(), "agent.run.requeued", row).log("Run lease expired; the run is queued again");
                    return;
                }
                reason = AgentRunStopReason.ATTEMPTS_EXHAUSTED;
            } else {
                handler.ended(lost.run(), lost.epoch(), reason);
            }
            if (!taken(status, runs.endExpired(lost.runId(), lost.workspaceId(), lost.epoch(), reason.name()))) return;
            settle(lost);
            count(kind, reason.name().toLowerCase(Locale.ROOT));
            event(log.atInfo(), "agent.run.ended", row)
                    .addKeyValue("run.stop_reason", reason.name())
                    .log("Run lease expired; the run ended");
        });
    }

    private void endOverdue(AgentRunHandler handler, AgentRunRepository.ExpiredRow row) {
        AgentRunKind kind = handler.kind();
        transactions.executeWithoutResult(status -> {
            var run = new AgentRunRef(row.getId(), row.getWorkspaceId());
            handler.lock(run);
            var reason = row.getPastDeadline() ? AgentRunStopReason.DEADLINE : AgentRunStopReason.NOT_STARTED;
            handler.ended(run, row.getLeaseEpoch(), reason);
            if (!taken(status, runs.endExpired(row.getId(), row.getWorkspaceId(), row.getLeaseEpoch(), reason.name())))
                return;
            count(kind, reason.name().toLowerCase(Locale.ROOT));
            event(log.atInfo(), "agent.run.ended", row)
                    .addKeyValue("run.stop_reason", reason.name())
                    .log("Queued run waited past its start window; the run ended");
        });
    }

    /** Settles the model calls that the lost claim left open, in the transaction that takes its run back. */
    private void settle(AgentRunFence lost) {
        int calls = runs.settleOpenCalls(lost.runId(), lost.workspaceId(), lost.epoch());
        if (calls > 0) meters.counter(AgentMetrics.LLM_USAGE_UNVERIFIABLE).increment(calls);
    }

    /** A reaper event names the run as every record of its holder does. */
    private static LoggingEventBuilder event(
            LoggingEventBuilder builder, String name, AgentRunRepository.ExpiredRow row) {
        return builder.addKeyValue("event.name", name)
                .addKeyValue(StructuredLogKeys.RUN_ID, row.getId())
                .addKeyValue(StructuredLogKeys.RUN_EPOCH, row.getLeaseEpoch())
                .addKeyValue(StructuredLogKeys.SUBJECT_KEY, row.getConcurrencyKey())
                .addKeyValue(StructuredLogKeys.WORKSPACE_ID, row.getWorkspaceId())
                .addKeyValue("run.kind", row.getKind());
    }

    /**
     * Whether the run write took the run back. When another replica or a renewal won meanwhile, the domain writes of
     * this transaction are rolled back with it.
     */
    private static boolean taken(TransactionStatus status, int written) {
        if (written == 1) return true;
        status.setRollbackOnly();
        return false;
    }

    private void count(AgentRunKind kind, String outcome) {
        count(kind, outcome, 1);
    }

    private void count(AgentRunKind kind, String outcome, double amount) {
        meters.counter(AgentMetrics.AGENT_RUN_REAPED, "kind", kind.name().toLowerCase(Locale.ROOT), "outcome", outcome)
                .increment(amount);
    }
}

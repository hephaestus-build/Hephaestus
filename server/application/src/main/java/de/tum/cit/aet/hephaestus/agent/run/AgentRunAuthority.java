package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusal;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The authorization recheck of a run (ADR 0134): a boundary compares the run's stamp with the current generations.
 * When authority narrowed since the work was requested, the run ends {@link AgentRunStopReason#AUTHORITY_CHANGED}
 * with its domain row, and the boundary refuses with the reason. A false cancellation is safe; work under authority
 * that ended is not.
 */
@Component
@ConditionalOnServerRole
public class AgentRunAuthority {

    private static final Logger log = LoggerFactory.getLogger(AgentRunAuthority.class);

    private final AgentRuns runs;
    private final AuthorityGenerations generations;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final List<AgentRunHandler> handlers;

    /**
     * @param handlers resolved on the first refusal: the handlers' own collaborators, such as the evidence folders of a
     *     review, recheck through this bean, and only a refusal needs a handler
     */
    AgentRunAuthority(
            AgentRuns runs,
            AuthorityGenerations generations,
            TransactionTemplate transactions,
            MeterRegistry meters,
            @Lazy List<AgentRunHandler> handlers) {
        this.runs = runs;
        this.generations = generations;
        this.transactions = transactions;
        this.meters = meters;
        this.handlers = handlers;
        AuthorityRefusal.register(meters);
    }

    /**
     * Refuses when the run's authority narrowed, without ending the run: for a caller that holds a lease, a lock or a
     * transaction that ending the run would need. The route that it unwinds to ends the run with {@link #require}.
     *
     * @throws AuthorityRefusedException when authority narrowed since the work was requested
     */
    public void check(AgentRunFence fence, AuthorityBoundary boundary) {
        if (fence.workspaceId() == AgentRunRef.INSTANCE_SCOPE) return;
        runs.find(fence.run())
                .flatMap(run -> generations.check(run.getAuthorizationStamp(), fence.workspaceId(), boundary))
                .ifPresent(refusal -> {
                    throw new AuthorityRefusedException(refusal);
                });
    }

    /**
     * Lets the claim go on past {@code boundary} while the run's authority holds. Otherwise it ends the run and its
     * domain row, and throws.
     *
     * @throws AuthorityRefusedException when authority narrowed since the work was requested
     * @throws StaleRunException when the claim no longer holds the run
     */
    public void require(AgentRunFence fence, AuthorityBoundary boundary) {
        AgentRun run = runs.find(fence.run())
                .filter(found -> runs.isCurrent(fence.run(), fence.epoch()))
                .orElseThrow(() -> new StaleRunException(fence));
        // Instance work, such as a report draft, belongs to no workspace and carries no workspace authority.
        if (fence.workspaceId() == AgentRunRef.INSTANCE_SCOPE) return;
        Optional<AuthorityRefusal> refusal =
                generations.check(run.getAuthorizationStamp(), fence.workspaceId(), boundary);
        if (refusal.isEmpty()) return;
        AgentRunHandler handler = handlers.stream()
                .filter(candidate -> candidate.kind() == run.getKind())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No handler for runs of kind " + run.getKind()));
        boolean ended = Boolean.TRUE.equals(transactions.execute(status -> {
            // The domain moves first, as the reaper moves it, while the run still reads as its holder left it.
            handler.lock(fence.run());
            // A parallel call of the same holder, or the reaper, may have ended the run while this one waited for the
            // lock: then this claim is stale, and nothing changes.
            if (!runs.isCurrent(fence.run(), fence.epoch())) {
                status.setRollbackOnly();
                return false;
            }
            handler.ended(fence.run(), fence.epoch(), AgentRunStopReason.AUTHORITY_CHANGED);
            if (runs.end(fence, AgentRunStopReason.AUTHORITY_CHANGED) == AgentRuns.EndOutcome.ENDED) return true;
            status.setRollbackOnly();
            return false;
        }));
        if (!ended) throw new StaleRunException(fence);
        refusal.get().count(meters);
        log.atInfo()
                .addKeyValue("event.name", "agent.run.authority_refused")
                .addKeyValue(StructuredLogKeys.RUN_ID, fence.runId())
                .addKeyValue(StructuredLogKeys.RUN_EPOCH, fence.epoch())
                .addKeyValue(StructuredLogKeys.WORKSPACE_ID, fence.workspaceId())
                .addKeyValue("run.kind", run.getKind().name())
                .addKeyValue("authority.boundary", boundary.name())
                .addKeyValue("authority.reason", refusal.get().reason().name())
                .log("Run refused at a boundary because its authority narrowed; the run ended");
        throw new AuthorityRefusedException(refusal.get());
    }
}

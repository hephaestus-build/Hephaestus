package de.tum.cit.aet.hephaestus.agent.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetHeadroom;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * In-flight budget backstop for the LLM proxy: the submit and claim gates only decide whether a job
 * may <em>start</em>, and the ledger they read gains nothing until the run ENDS. A call is refused as
 * soon as the ledger and everything the workspace's running work has spent reach the cap, so a purse
 * can overshoot by at most the calls already dispatched, never by a whole run or by each run in turn.
 * The bound: {@code docs/decisions/0026-per-purpose-agent-bindings-and-llm-governance.md}.
 */
@Component
@ConditionalOnWorkerRole
class ProxyBudgetGate {

    /** A run that ended within this time has left the running spend, and the cached ledger may not show it yet. */
    private static final Duration TTL = Duration.ofSeconds(5);

    private static final long MAX_CACHED_WORKSPACES = 10_000;

    private final LlmBudgetService budgetService;
    private final ProxyRunningSpend runningSpend;
    private final long directModelTokensPerAttempt;
    private final long directModelSearchUnitsPerAttempt;
    private final Cache<Long, LlmBudgetHeadroom> headroomByWorkspace;

    ProxyBudgetGate(
            LlmBudgetService budgetService, ProxyRunningSpend runningSpend, PracticeReviewProperties properties) {
        this.budgetService = budgetService;
        this.runningSpend = runningSpend;
        this.directModelTokensPerAttempt = properties.directModelTokensPerAttempt();
        this.directModelSearchUnitsPerAttempt = properties.directModelSearchUnitsPerAttempt();
        this.headroomByWorkspace = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_CACHED_WORKSPACES)
                .build();
    }

    /**
     * An unattributable route (no workspace id) fails open. Judged per funding source so an exhausted
     * host budget cannot 429 calls the workspace pays for through its own provider. The running spend is
     * read fresh, and only when a cap applies to the purse that pays this call.
     */
    boolean isBlocked(ProxyRouting routing) {
        Long workspaceId = routing.workspaceId();
        if (workspaceId == null) {
            return false;
        }
        LlmBudgetHeadroom headroom = headroomByWorkspace.get(workspaceId, budgetService::headroom);
        return headroom != null
                && headroom.cappedFor(routing.connectionScope())
                && headroom.decideWith(runningSpend.of(workspaceId)).blocks(routing.connectionScope());
    }

    /**
     * Whether one attempt's direct model calls have used the tokens or the search units that they may spend, on every
     * slot and for every practice together: a sublimit of the attempt, which the review session's own calls do not
     * count against. It also bounds the direct calls on a connection with no monthly cap. Search units are a limit of their own, never converted to tokens: a Cohere
     * search unit covers from one to a hundred documents of any length up to its chunk size, so no token count is
     * true for it. Only committed calls count, so calls in flight together can each pass and end above the cap.
     */
    boolean isDirectModelCapReached(ProxyRouting.BilledAttempt attempt) {
        return attempt.directModelTokens() >= directModelTokensPerAttempt
                || attempt.directModelSearchUnits() >= directModelSearchUnitsPerAttempt;
    }
}

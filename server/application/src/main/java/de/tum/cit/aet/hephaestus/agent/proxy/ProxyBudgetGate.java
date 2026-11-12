package de.tum.cit.aet.hephaestus.agent.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetHeadroom;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import java.math.BigDecimal;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * In-flight budget backstop for the LLM proxy: the submit and claim gates only decide whether a job
 * may <em>start</em>. The ledger holds each call's upper bound from the moment the call opens, and its cost once it is
 * measured. A call opens only while its own bound fits under the cap ({@link #refusesHold}), like an authorization
 * hold. A purse can overshoot by at most the bounds of the calls that passed this check at the same time, never by a
 * whole run or by each run in turn.
 * The bound: {@code docs/decisions/0026-per-purpose-agent-bindings-and-llm-governance.md}.
 */
@Component
@ConditionalOnWorkerRole
class ProxyBudgetGate {

    /**
     * How long another replica's call can stay out of this replica's verdict. A call that opened or ended on this
     * replica clears its workspace's verdict at once ({@link #spent}).
     */
    private static final Duration TTL = Duration.ofSeconds(5);

    private static final long MAX_CACHED_WORKSPACES = 10_000;

    private final LlmBudgetService budgetService;
    private final long directModelTokensPerAttempt;
    private final long directModelSearchUnitsPerAttempt;
    private final Cache<Long, LlmBudgetHeadroom> headroomByWorkspace;

    ProxyBudgetGate(LlmBudgetService budgetService, PracticeReviewProperties properties) {
        this.budgetService = budgetService;
        this.directModelTokensPerAttempt = properties.directModelTokensPerAttempt();
        this.directModelSearchUnitsPerAttempt = properties.directModelSearchUnitsPerAttempt();
        this.headroomByWorkspace = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_CACHED_WORKSPACES)
                .build();
    }

    /**
     * An unattributable route (no workspace id) fails open. Judged per funding source so an exhausted
     * host budget cannot 429 calls the workspace pays for through its own provider.
     */
    boolean isBlocked(ProxyRouting routing) {
        Long workspaceId = routing.workspaceId();
        if (workspaceId == null) {
            return false;
        }
        LlmBudgetHeadroom headroom = headroomByWorkspace.get(workspaceId, budgetService::headroom);
        return headroom != null
                && headroom.cappedFor(routing.connectionScope())
                && headroom.decide().blocks(routing.connectionScope());
    }

    /**
     * Whether a call must not open because its upper bound does not fit under its purse's cap: the spend of the month,
     * every open or unmeasured call at its bound, plus this call's own bound. Reads the ledger now, not the cached
     * verdict, so a call that opened on another replica a moment ago counts. A call with no bound passes: the verdict
     * ({@link #isBlocked}) already holds its purse once such a call is in the ledger.
     */
    boolean refusesHold(ProxyRouting routing, @Nullable BigDecimal boundUsd) {
        Long workspaceId = routing.workspaceId();
        if (workspaceId == null || boundUsd == null) return false;
        LlmBudgetHeadroom cached = headroomByWorkspace.get(workspaceId, budgetService::headroom);
        if (cached == null || !cached.cappedFor(routing.connectionScope())) return false;
        return budgetService.headroom(workspaceId).refusesHold(routing.connectionScope(), boundUsd);
    }

    /** A call of the workspace opened or ended, and its bound or cost is in the ledger: the next call reads it again. */
    void spent(long workspaceId) {
        headroomByWorkspace.invalidate(workspaceId);
    }

    /**
     * Whether one attempt's direct model calls have used the tokens or the search units that they may spend, on every
     * slot and for every practice together: a sublimit of the attempt, which the review session's own calls do not
     * count against. It also bounds the direct calls on a connection with no monthly cap. Search units are a limit of their own, never converted to tokens: a Cohere
     * search unit covers from one to a hundred documents of any length up to its chunk size, so no token count is
     * true for it. Only ended calls count, so calls in flight together can each pass and end above the cap.
     */
    boolean isDirectModelCapReached(ProxyRouting.BilledAttempt attempt) {
        return attempt.directModelTokens() >= directModelTokensPerAttempt
                || attempt.directModelSearchUnits() >= directModelSearchUnitsPerAttempt;
    }
}

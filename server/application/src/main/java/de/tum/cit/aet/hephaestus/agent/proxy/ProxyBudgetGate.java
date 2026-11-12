package de.tum.cit.aet.hephaestus.agent.proxy;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetHeadroom;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.CacheLoads;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
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
@ConditionalOnAgentRuntime
class ProxyBudgetGate {

    /**
     * How long another replica's call can stay out of this replica's verdict. A call that opened or ended on this
     * replica clears its workspace's verdict at once ({@link #spent}).
     */
    private static final Duration TTL = Duration.ofSeconds(5);

    private static final long MAX_CACHED_WORKSPACES = 10_000;

    private final LlmBudgetService budgetService;
    /** Each workspace's read, loaded outside the cache's lock ({@link CacheLoads}). */
    private final AsyncCache<Long, LlmBudgetHeadroom> headroomByWorkspace;

    ProxyBudgetGate(LlmBudgetService budgetService) {
        this.budgetService = budgetService;
        this.headroomByWorkspace = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_CACHED_WORKSPACES)
                .buildAsync();
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
        LlmBudgetHeadroom headroom = headroom(workspaceId);
        return headroom != null
                && headroom.cappedFor(routing.connectionScope())
                && headroom.decide().blocks(routing.connectionScope());
    }

    /**
     * Whether a call must not open because its upper bound does not fit under its purse's cap: the spend of the month,
     * every open or unmeasured call at its bound, plus this call's own bound. Reads the ledger now, not the cached
     * verdict, so a call that opened on another replica a moment ago counts. A diagnostic without a bound must not
     * open against a capped purse. Other calls without a bound retain the verdict from {@link #isBlocked}.
     */
    boolean refusesHold(ProxyRouting routing, @Nullable BigDecimal boundUsd) {
        Long workspaceId = routing.workspaceId();
        if (workspaceId == null) return false;
        if (boundUsd == null) {
            // A diagnostic must not leave a capped workspace with an unknown charge that nobody can repair.
            // Read the cap now: the administrator can have set it since the request's cached budget check.
            return routing.attempt().sourceType() == UsageSourceType.PROBE
                    && budgetService.headroom(workspaceId).cappedFor(routing.connectionScope());
        }
        LlmBudgetHeadroom cached = headroom(workspaceId);
        if (cached == null || !cached.cappedFor(routing.connectionScope())) return false;
        return budgetService.headroom(workspaceId).refusesHold(routing.connectionScope(), boundUsd);
    }

    /** A call of the workspace opened or ended, and its bound or cost is in the ledger: the next call reads it again. */
    void spent(long workspaceId) {
        headroomByWorkspace.synchronous().invalidate(workspaceId);
    }

    /** The cached verdict, or one read now outside the cache's lock ({@link CacheLoads}). */
    private @Nullable LlmBudgetHeadroom headroom(long workspaceId) {
        @Nullable
        LlmBudgetHeadroom headroom = CacheLoads.get(headroomByWorkspace, workspaceId, budgetService::headroom);
        return headroom;
    }
}

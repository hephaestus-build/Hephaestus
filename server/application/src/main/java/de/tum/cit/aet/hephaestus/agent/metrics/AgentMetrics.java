package de.tum.cit.aet.hephaestus.agent.metrics;

public final class AgentMetrics {
    public static final String AGENT_JOB_BUDGET_HELD = "agent.job.budget.held";
    public static final String AGENT_JOB_BUDGET_REFUSED = "agent.job.budget.refused";
    public static final String AGENT_JOB_COMPOSITION_STOPPED = "agent.job.composition.stopped";
    public static final String AGENT_JOB_DURATION = "agent.job.duration";
    public static final String AGENT_JOB_INFRA_RETRY_REQUEUED = "agent.job.infra.retry.requeued";
    public static final String AGENT_JOB_MODEL_REFUSED = "agent.job.model.refused";
    public static final String AGENT_JOB_SERVER_ERROR = "agent.job.server.error";
    public static final String AGENT_JOB_RETENTION_DELETED = "agent.job.retention.deleted";
    public static final String AGENT_JOB_RETENTION_STRIPPED = "agent.job.retention.stripped";
    public static final String AGENT_JOB_STAGE_DURATION = "agent.job.stage.duration";
    public static final String AGENT_JOB_TOTAL = "agent.job.total";
    public static final String AGENT_RUN_REAPED = "agent.run.reaped";
    /** Runs that have not ended, by kind, band, state and hold; the sampler's gauge. */
    public static final String AGENT_RUN_RUNS = "agent.run.runs";
    /** Queued runs with no hold whose {@code available_at} has passed, by kind and band. */
    public static final String AGENT_RUN_DUE = "agent.run.due";
    /** Seconds since the oldest due run of the kind and band became due; 0 when none is due. */
    public static final String AGENT_RUN_OLDEST_DUE_AGE_SECONDS = "agent.run.oldest_due_age_seconds";
    /** Sampler passes that failed; the gauges kept their last values. */
    public static final String AGENT_RUN_SAMPLER_FAILURE = "agent.run.sampler.failure";
    /** From a run's {@code available_at} to its claim, by kind and band; recorded after the claim committed. */
    public static final String AGENT_RUN_CLAIM_WAIT = "agent.run.claim.wait";

    public static final String EVIDENCE_RECONCILE_LAST_SUCCESS = "evidence.reconcile.last.success";
    public static final String EVIDENCE_RECONCILE_FAILURES = "evidence.reconcile.failures";
    public static final String AGENT_RUN_REAPER_FAILURE = "agent.run.reaper.failure";
    /** Sandboxes taken back, by reason: {@code expired}, {@code start_limit}, {@code idle} or {@code orphan}. */
    public static final String AGENT_SANDBOX_REAPED = "agent.sandbox.reaped";
    /** Ticks or claim deletes of the sandbox reaper that failed; each is tried again on the next tick. */
    public static final String AGENT_SANDBOX_REAPER_FAILURE = "agent.sandbox.reaper.failure";
    /** Dispatch passes that failed to request a due run's sandbox; each is tried again at the next pass. */
    public static final String AGENT_SANDBOX_DISPATCH_FAILURE = "agent.sandbox.dispatch.failure";
    /** Live sandboxes that the server took back before the reaper would, by reason: {@code unisolated}. */
    public static final String AGENT_SANDBOX_REVOKED = "agent.sandbox.revoked";

    public static final String AGENT_PI_ENVELOPE_MISMATCH = "agent.pi.envelope.mismatch";
    public static final String AGENT_PI_RESULT_PARSE_FAILURE = "agent.pi.result.parse.failure";
    public static final String AGENT_REVIEW_PRACTICE_COVERAGE_ELIGIBLE = "agent.review.practice.coverage.eligible";
    public static final String AGENT_REVIEW_PRACTICE_COVERAGE_EVALUATED = "agent.review.practice.coverage.evaluated";
    public static final String AGENT_REVIEW_PRACTICE_COVERAGE_RATIO = "agent.review.practice.coverage.ratio";
    public static final String AGENT_REVIEW_PRACTICE_SCRIPTS = "agent.review.practice_scripts";
    public static final String ATTEMPT_KEY_SYNC_FAILURES = "attempt.key.sync.failures";
    public static final String ATTEMPT_TOKEN_VERIFY = "attempt.token.verify";
    public static final String LLM_BUDGET_ALERT_FAILURE = "llm.budget.alert.failure";
    public static final String LLM_BUDGET_BLOCKED = "llm.budget.blocked";
    public static final String LLM_BUDGET_EXHAUSTED = "llm.budget.exhausted";
    public static final String LLM_POOL_COOLDOWN_ACTIVE = "llm.pool.cooldown.active";
    /** GenAI semantic conventions v1.41.1: from the request after the pool's grant to a served stream's first event. */
    public static final String GEN_AI_CLIENT_TIME_TO_FIRST_CHUNK = "gen_ai.client.operation.time_to_first_chunk";

    public static final String GEN_AI_CLIENT_OPERATION_DURATION = "gen_ai.client.operation.duration";
    public static final String GEN_AI_CLIENT_CALLS = "gen_ai.client.calls";
    public static final String GEN_AI_CLIENT_TOKENS = "gen_ai.client.tokens";

    public static final String LLM_POOL_FIRST_TOKEN_BASELINE = "llm.pool.first.token.baseline";
    public static final String LLM_POOL_FIRST_TOKEN_BASELINE_RESETS = "llm.pool.first.token.baseline.resets";
    public static final String LLM_POOL_LIMIT = "llm.pool.limit";
    public static final String LLM_POOL_LIMIT_DECREASES = "llm.pool.limit.decreases";
    public static final String LLM_POOL_PERMITS_GRANTED = "llm.pool.permits.granted";
    public static final String LLM_POOL_UPSTREAM_ABSORBED = "llm.pool.upstream.absorbed";
    public static final String LLM_POOL_UPSTREAM_BUCKET_REACHED = "llm.pool.upstream.bucket_reached";
    public static final String LLM_POOL_WAITERS = "llm.pool.waiters";
    public static final String LLM_POOL_LEASE_RENEWAL_FAILURES = "llm.pool.lease.renewal.failures";
    public static final String LLM_POOL_PERMITS_EXPIRED = "llm.pool.permits.expired";
    public static final String LLM_POOL_PERMIT_ABANDONED = "llm.pool.permit.abandoned";
    public static final String LLM_POOL_PERMIT_WAIT = "llm.pool.permit.wait";
    public static final String LLM_POOL_PERMITS_ACTIVE = "llm.pool.permits.active";
    public static final String LLM_POOL_SELF_FENCE_CANCELLED = "llm.pool.self_fence.cancelled";
    public static final String LLM_POOL_UPSTREAM_THROTTLED = "llm.pool.upstream.throttled";
    public static final String LLM_PROXY_ERRORS = "llm.proxy.errors";
    public static final String LLM_PROXY_REFUSED = "llm.proxy.refused";
    public static final String LLM_PROXY_UPSTREAM_STALLED = "llm.proxy.upstream.stalled";
    public static final String LLM_PROXY_STREAM_USAGE_UNSUPPORTED = "llm.proxy.stream.usage.unsupported";
    public static final String LLM_PROXY_USAGE_ACCUMULATE_FAILURE = "llm.proxy.usage.accumulate.failure";
    public static final String LLM_PROXY_USAGE_MISSING = "llm.proxy.usage.missing";
    public static final String LLM_PROXY_USAGE_UNPARSEABLE = "llm.proxy.usage.unparseable";
    public static final String LLM_USAGE_COST_CLAMPED = "llm.usage.cost.clamped";
    public static final String LLM_USAGE_REPRICE_FAILURE = "llm.usage.reprice.failure";
    public static final String LLM_USAGE_REPRICE_UNRESOLVED = "llm.usage.reprice.unresolved";
    public static final String LLM_USAGE_UNCOSTED = "llm.usage.uncosted";
    public static final String LLM_USAGE_UNVERIFIABLE = "llm.usage.unverifiable";
    public static final String MENTOR_TURN_COMPLETED = "mentor.turn.completed";
    public static final String MENTOR_TURN_COST = "mentor.turn.cost";
    public static final String MENTOR_TURN_DURATION = "mentor.turn.duration";
    public static final String AGENT_RUN_FIRST_TOKEN = "agent.run.first_token";
    public static final String MENTOR_TURN_STARTED = "mentor.turn.started";
    public static final String MENTOR_TURN_RETRIED = "mentor.turn.retried";
    public static final String MENTOR_TURN_QUEUED = "mentor.turn.queued";
    public static final String MENTOR_TURN_QUEUE_WAIT = "mentor.turn.queue_wait";
    public static final String MENTOR_TURN_CLAIM_FAILURE = "mentor.turn.claim.failure";
    public static final String MENTOR_LIVE_STREAMS = "mentor.live.streams";
    public static final String SANDBOX_GATEWAY_CONNECTIONS = "sandbox.gateway.connections";
    public static final String SANDBOX_GATEWAY_CONNECTIONS_LIMIT = "sandbox.gateway.connections.limit";
    public static final String SANDBOX_GATEWAY_LIMITER_ERRORS = "sandbox.gateway.limiter.errors";
    /** Requests that the gateway refused itself, tagged by {@code reason}. */
    public static final String SANDBOX_GATEWAY_REFUSED = "sandbox.gateway.refused";

    public static final String SANDBOX_GATEWAY_REQUESTS = "sandbox.gateway.requests";
    public static final String SANDBOX_GATEWAY_REQUEST_SIZE = "sandbox.gateway.request.size";
    /** Sandbox Pods removed, tagged by {@code kind} (what they ran) and {@code reason} (why they ended). */
    public static final String SANDBOX_EXITS = "sandbox.exits";

    private AgentMetrics() {}
}

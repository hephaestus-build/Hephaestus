package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The model pools as the admission of reviews reads them: a model's pool, the pool's label on its metrics, whether it
 * is congested, how far apart it admits measurements, and the pool's lock under which those admissions take turns.
 */
@Component
@ConditionalOnAgentRuntime
public class LlmCallPools {
    private final LlmCallPoolRepository pools;
    private final LlmCallPermitRepository rows;
    private final LlmModelResolver models;
    private final LlmCallPoolProperties properties;
    private final Duration permitAging;

    @Autowired
    LlmCallPools(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            LlmModelResolver models,
            LlmCallPoolProperties properties,
            AgentCapacityProperties capacity) {
        this(pools, rows, models, properties, capacity.permitAging());
    }

    LlmCallPools(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            LlmModelResolver models,
            LlmCallPoolProperties properties,
            Duration permitAging) {
        this.pools = pools;
        this.rows = rows;
        this.models = models;
        this.properties = properties;
        this.permitAging = permitAging;
    }

    /** The model's pool. Every model has one, so only a model that is gone has none. */
    public long poolFor(LlmModelResolver.ConnectionRef ref) {
        Long pool = models.resolveCallPool(ref);
        if (pool == null) throw new IllegalStateException("The configured model is no longer available");
        return pool;
    }

    /**
     * Locks the pool row until the caller's transaction ends, so the admissions of reviews to one pool take turns on
     * every server.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAdmission(long poolId) {
        if (pools.lockAdmission(poolId).isEmpty())
            throw new IllegalStateException("Configured call pool is unavailable");
    }

    /**
     * Whether a measuring review's call has waited for the pool longer than the permit aging: the pool is congested.
     */
    public boolean congested(long poolId) {
        return rows.measurementWaitedLong(
                poolId, permitAging.toSeconds(), properties.permitLease().toSeconds());
    }

    /** The pool's label on its metrics: its slug, with the pools of workspace models folded into one. */
    public String poolLabel(long poolId) {
        return pools.findById(poolId)
                .map(pool -> LlmPoolMeters.label(pool.getSlug()))
                .orElse("unknown");
    }

    /** How long a pool must stay uncongested after it admitted a measurement before it admits the next one. */
    public Duration admissionSpacing() {
        return permitAging;
    }
}

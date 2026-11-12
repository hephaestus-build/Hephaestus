package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The lease state of a run. The trigger {@code guard_agent_run} on {@code agent_run} refuses every transition that
 * {@link #canTransitionTo} refuses, so this table and the database state the same rule.
 */
public enum AgentRunState implements Lifecycle<AgentRunState> {
    /** Waiting for a claim. */
    QUEUED,
    /** One holder drives it under the current {@code lease_epoch} until {@code leased_until}. */
    LEASED,
    /** Final. A run that must go on gets a new run ({@link AgentRuns#continueAfter}). */
    ENDED;

    private static final Map<AgentRunState, Set<AgentRunState>> LEGAL = Map.of(
            QUEUED, Set.of(QUEUED, LEASED, ENDED),
            LEASED, Set.of(LEASED, QUEUED, ENDED),
            ENDED, Set.of());

    @Override
    public boolean canTransitionTo(AgentRunState next) {
        return Objects.requireNonNull(LEGAL.get(this)).contains(next);
    }
}

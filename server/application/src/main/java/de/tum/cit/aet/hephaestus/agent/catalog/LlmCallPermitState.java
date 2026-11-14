package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A call permit waits in its pool's queue, holds a grant while its call runs, and closes when the call's transport
 * ends. Removing an abandoned or pruned row is not a transition. {@code LlmCallPermitRepository} writes every move as
 * one atomic native statement whose {@code WHERE} names the legal source state; {@code LlmCallPermitSqlTest} holds
 * those statements to this table.
 */
public enum LlmCallPermitState implements Lifecycle<LlmCallPermitState> {
    WAITING,
    ACTIVE,
    CLOSED;

    private static final Map<LlmCallPermitState, Set<LlmCallPermitState>> LEGAL =
            Map.of(WAITING, Set.of(ACTIVE), ACTIVE, Set.of(CLOSED), CLOSED, Set.of());

    @Override
    public boolean canTransitionTo(LlmCallPermitState next) {
        return Objects.requireNonNull(LEGAL.get(this)).contains(next);
    }
}

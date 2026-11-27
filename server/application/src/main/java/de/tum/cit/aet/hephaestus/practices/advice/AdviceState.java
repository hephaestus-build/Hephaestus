package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.core.ContinuingLifecycle;
import de.tum.cit.aet.hephaestus.core.Lifecycle;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Whether the mentor keeps an advice entry. The owner's dismissal is a separate fact. */
@ContinuingLifecycle("A retired entry stays the one entry for its developer and practice; the next advice reopens it.")
public enum AdviceState implements Lifecycle<AdviceState> {
    /** The entry shows its latest revision, unless the owner dismissed it or its evidence is gone. */
    CURRENT,
    /** The mentor retired it with a reason. A later revision makes it current again. */
    RETIRED;

    private static final Map<AdviceState, Set<AdviceState>> LEGAL = Map.of(
            // CURRENT -> CURRENT is a new revision of a current entry.
            CURRENT, Set.of(CURRENT, RETIRED),
            RETIRED, Set.of(CURRENT));

    @Override
    public boolean canTransitionTo(AdviceState next) {
        return Objects.requireNonNull(LEGAL.get(this)).contains(next);
    }
}

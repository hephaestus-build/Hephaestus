package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One practice's membership of one item in a review. A listed membership is staged while its attempt runs, then
 * judged, reused from an earlier result, or withheld for a recorded reason; those three have no exit. A membership
 * whose plan refuses it starts withheld. {@code ReviewItem} changes its state only through its transition methods,
 * and the one native writer, {@code ReviewStagedPayloadRepository.discardUnusable}, moves only a staged membership.
 */
public enum ReviewItemState implements Lifecycle<ReviewItemState> {
    LISTED,
    STAGED,
    JUDGED,
    REUSED,
    WITHHELD;

    private static final Map<ReviewItemState, Set<ReviewItemState>> LEGAL = Map.of(
            LISTED, Set.of(STAGED, JUDGED, REUSED, WITHHELD),
            STAGED, Set.of(JUDGED, WITHHELD),
            JUDGED, Set.of(),
            REUSED, Set.of(),
            WITHHELD, Set.of());

    @Override
    public boolean canTransitionTo(ReviewItemState next) {
        return Objects.requireNonNull(LEGAL.get(this)).contains(next);
    }
}

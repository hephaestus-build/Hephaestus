package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The checks of one item kind's head, for a practice whose judgment needs them settled. An item kind with no such
 * source has no implementation, and a practice on it never waits for checks.
 */
public interface ItemChecksSource {
    ItemKind kind();

    /** The source kind that holds the checks. A practice waits only when it needs this source. */
    SourceKind source();

    /**
     * What the checks say that a review of {@code pinned} would capture: those the occasion {@code eventId} retained,
     * or the current head's when it is null.
     */
    ItemChecks checks(ItemSnapshot pinned, @Nullable UUID eventId);
}

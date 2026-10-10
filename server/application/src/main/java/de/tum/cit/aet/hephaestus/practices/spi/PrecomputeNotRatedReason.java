package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Locale;

/**
 * Why a precompute script could not have a model rate a place. Never "nothing found". The runner's
 * {@code REASONS} in {@code docker/agents/precompute/lib/contract.ts} holds the same values in their
 * {@link #wire()} form.
 */
public enum PrecomputeNotRatedReason {
    UNAVAILABLE,
    BUDGET,
    DEADLINE,
    TOO_LARGE,
    OFF_FORMAT,
    REFUSED,
    ERROR;

    /** The runner's spelling: lower case, words joined by a hyphen. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}

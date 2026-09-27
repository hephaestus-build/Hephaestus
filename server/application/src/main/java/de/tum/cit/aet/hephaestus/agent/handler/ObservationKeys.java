package de.tum.cit.aet.hephaestus.agent.handler;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The persisted identities of one {@link de.tum.cit.aet.hephaestus.practices.model.Observation}, stamped onto an
 * observation by the handler so downstream stages address the stored row without recomputing a key.
 *
 * @param occurrenceKey this observation alone ({@code observation.occurrence_key}, uniquely constrained) —
 *     the key to use whenever a single observation must be addressed
 * @param recurrenceKey the location this observation shares with other observations across runs
 *     ({@link de.tum.cit.aet.hephaestus.practices.observation.ObservationFingerprint}). Deliberately
 *     many-to-one: several observations of one practice in one file collapse to it, so it can never stand in
 *     for {@code occurrenceKey}.
 * @param id the row's id, which the composition stage cites in {@code basedOn}. Null while admission computes the
 *     keys, before the row exists
 */
public record ObservationKeys(
        String occurrenceKey,
        @Nullable String recurrenceKey,
        @Nullable UUID id) {
    public ObservationKeys(String occurrenceKey, @Nullable String recurrenceKey) {
        this(occurrenceKey, recurrenceKey, null);
    }
}

package de.tum.cit.aet.hephaestus.mentor.spi;

import java.util.Optional;

/** The runtime's proof of its effects, for the transport that writes them. Database only: the transport reads the provider
 * before, outside any transaction. */
public interface MentorEffectProof {
    /** The claimed effect's immutable route and current lease, read-only; empty when the effect is fenced. */
    Optional<ObservationTarget> observationTarget(EffectRef claimed);

    /**
     * Whether the effect may render and write now, under the observation. It joins the caller's short, lock-capable
     * transaction and writes nothing: before render, and as the write fence's predicate inside {@code beginWrite}. There
     * it binds the stored owner and holds the guards of the request, the effect, the notice, the authority and the basis,
     * in that order after the action row, until the write marker commits. No provider work runs here.
     */
    Proof check(EffectRef ref, ProviderObservation observed);

    /** Records a terminal outcome in the outbox's end transaction. */
    void settle(EffectRef ref, EffectOutcome outcome);
}

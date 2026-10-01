package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.signal.RevisionScheme;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import java.util.Objects;
import java.util.Set;

/**
 * One observable thing that can happen to an artifact, declared by the module that owns the artifact.
 *
 * <p>{@link #producedBy()} is load-bearing: the bootstrap verifies every named event type has a
 * registered handler, so a binding target cannot silently go unfireable. An empty set is legal — it
 * means the signal comes from inside Hephaestus, not an ingested event — but an integration may not
 * claim to raise one anyway.
 *
 * @param name        the vendor-neutral name practices bind to and persist under; renaming one is a
 *                    data migration
 * @param displayName human-readable label for authoring surfaces
 * @param producedBy  the ingested event types that raise this signal; provenance, not routing
 * @param revision    how a distinct occurrence is identified, declared per signal since a description
 *                    edit and a push are not the same kind of change
 * @param recommendedForAuthoring whether a practice on this artifact should start out watching this
 *                    signal; the author is free to overrule it
 * @param requestedByHand whether this is the signal a person raises by explicitly asking for a review
 *                    now. Declared here rather than on the descriptor so "named a request signal the
 *                    kind never declared" is unrepresentable rather than merely validated
 * @param bindableForAuthoring whether a practice author can select this signal as an occasion
 */
public record Signal(
        SignalName name,
        String displayName,
        Set<EventTypeKey> producedBy,
        RevisionScheme revision,
        boolean recommendedForAuthoring,
        boolean requestedByHand,
        boolean bindableForAuthoring) {
    /** A signal the authoring surface offers but does not pre-select. */
    public Signal(SignalName name, String displayName, Set<EventTypeKey> producedBy, RevisionScheme revision) {
        this(name, displayName, producedBy, revision, false, false, true);
    }

    /** A signal an ingested event raises, which the authoring surface may pre-select. */
    public Signal(
            SignalName name,
            String displayName,
            Set<EventTypeKey> producedBy,
            RevisionScheme revision,
            boolean recommendedForAuthoring) {
        this(name, displayName, producedBy, revision, recommendedForAuthoring, false, true);
    }

    /** Existing declared signals are authorable unless they are explicit review requests. */
    public Signal(
            SignalName name,
            String displayName,
            Set<EventTypeKey> producedBy,
            RevisionScheme revision,
            boolean recommendedForAuthoring,
            boolean requestedByHand) {
        this(name, displayName, producedBy, revision, recommendedForAuthoring, requestedByHand, !requestedByHand);
    }

    public Signal {
        Objects.requireNonNull(name, "signal name must not be null");
        Objects.requireNonNull(revision, "revision scheme must not be null");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("signal " + name + " must have a display name");
        }
        producedBy = Set.copyOf(Objects.requireNonNullElse(producedBy, Set.of()));
        if (requestedByHand && bindableForAuthoring) {
            throw new IllegalArgumentException("A review request is not a practice occasion");
        }
        if (recommendedForAuthoring && !bindableForAuthoring) {
            throw new IllegalArgumentException("An internal signal cannot be recommended for authoring");
        }
    }

    /** Whether any ingested event of the given integration raises this signal. */
    public boolean isProducedBy(IntegrationKind kind) {
        return producedBy.stream().anyMatch(key -> key.kind() == kind);
    }
}

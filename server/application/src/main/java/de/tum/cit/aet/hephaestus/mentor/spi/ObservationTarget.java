package de.tum.cit.aet.hephaestus.mentor.spi;

/** The claimed effect's route and lease, for the transport's provider read. */
public record ObservationTarget(EffectRef ref, Route route, long leaseEpoch) {}

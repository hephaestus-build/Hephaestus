package de.tum.cit.aet.hephaestus.mentor.spi;

/**
 * The claimed effect's route, its lease epoch and the reader epoch it was admitted under, for the transport's provider
 * read. The transport checks the lease epoch against its own attempt before it reads, and binds the reader epoch to that
 * read, because the provider reports participants, not a generation.
 */
public record ObservationTarget(EffectRef ref, Route route, long leaseEpoch, long readerEpoch) {}

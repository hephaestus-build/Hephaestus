package de.tum.cit.aet.hephaestus.mentor.spi;

import java.util.UUID;

/**
 * What a transport observes for one leased attempt: its frozen route with the exact team, and the question at the
 * revision and provider event its request version admitted. {@code readerEpoch} comes from the request's stored stamp.
 * The transport resolves {@code sourceRef} locally before any provider call, and refuses when it cannot.
 */
public record AttemptObservationTarget(
        UUID attemptId, long leaseEpoch, Route route, MessageRef source, String sourceRef, long readerEpoch) {}

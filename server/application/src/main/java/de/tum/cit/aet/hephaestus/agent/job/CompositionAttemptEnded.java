package de.tum.cit.aet.hephaestus.agent.job;

import java.util.UUID;

/** A composition attempt of a review ended and its end committed; what the review kept for it can be released. */
public record CompositionAttemptEnded(long workspaceId, UUID jobId) {}

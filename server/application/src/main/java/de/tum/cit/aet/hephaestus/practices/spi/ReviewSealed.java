package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;

/**
 * An item review has sealed its observations and withholdings. The item review has one job across retries; {@code
 * jobId} is the job that sealed it. A listener in the seal's transaction writes what the seal hands on, such as the
 * work-place handoff. A durable listener handles the event after commit and uses the review id to deduplicate
 * deliveries.
 */
public record ReviewSealed(UUID reviewId, long workspaceId, UUID jobId) {}

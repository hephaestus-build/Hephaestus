package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.time.Instant;

/**
 * Erases again, after a database restore, a Slack channel that an admin revoked or that Slack deleted after the recovery
 * point, as the erasure recovery manifest lists it.
 */
public interface RestoredSlackChannelErasures {

    /** Erases the original consent period and redacts restored tool results through the recorded copy cutoff. */
    void eraseAgain(long workspaceId, String channelId, Instant erasedAt, Instant copiesErasedThrough);
}

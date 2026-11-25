package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.time.Instant;

/**
 * Erases again, after a database restore, a Slack channel that an admin revoked or that Slack deleted after the recovery
 * point, as the erasure recovery manifest lists it.
 */
public interface RestoredSlackChannelErasures {

    /** Revokes consent, deletes restored channel content, and redacts tool results through the original cutoff. */
    void eraseAgain(long workspaceId, String channelId, Instant erasedAt);
}

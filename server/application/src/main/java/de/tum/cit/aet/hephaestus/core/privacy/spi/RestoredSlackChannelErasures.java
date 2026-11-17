package de.tum.cit.aet.hephaestus.core.privacy.spi;

/**
 * Erases again, after a database restore, a Slack channel that an admin revoked or that Slack deleted after the recovery
 * point, as the erasure recovery manifest lists it.
 */
public interface RestoredSlackChannelErasures {

    /** Revokes the channel's consent and deletes its content, as the erasure did; an erased channel changes nothing. */
    void eraseAgain(long workspaceId, String channelId);
}

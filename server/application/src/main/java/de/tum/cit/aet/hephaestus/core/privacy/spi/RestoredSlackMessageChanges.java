package de.tum.cit.aet.hephaestus.core.privacy.spi;

/**
 * Erases again, after a database restore, the old words of a stored Slack message that its author edited or deleted in
 * Slack after the recovery point, as the erasure recovery manifest lists it.
 */
public interface RestoredSlackMessageChanges {

    /**
     * A deleted message is tombstoned again. An edited message loses its restored words and keeps no edit time, so the
     * next history read of its channel stores the words that Slack shows then.
     *
     * @return whether it erased: false when the restore has no such message in the workspace or its words are gone
     */
    boolean eraseAgain(long workspaceId, long messageId, boolean deleted);
}

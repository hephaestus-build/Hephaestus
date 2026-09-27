package de.tum.cit.aet.hephaestus.practices.spi;

/**
 * Told, inside the transaction that writes it, that practice feedback now waits for a developer on their
 * practice pages. An implementation writes only what must commit or roll back with that feedback, and
 * never reads the feedback itself: opening it is what delivers it.
 */
public interface InAppFeedbackPreparedListener {

    void inAppFeedbackPrepared(long workspaceId, long recipientUserId);
}

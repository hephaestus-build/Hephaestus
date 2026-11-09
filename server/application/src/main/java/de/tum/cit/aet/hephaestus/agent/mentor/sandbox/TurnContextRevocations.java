package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;

/**
 * Hears, from any process, that what a workspace's turns may read changed: it makes a new context generation, so no
 * cached build of the old one is staged again, and deletes the Jobs of the revoked tools Pods. The change itself
 * revoked them ({@link ConversationToolsPodRevocations}). A (re)connect may have missed changes, so it drops the
 * builds of every workspace and deletes the Jobs of every revoked Pod; it revokes no Pod of a running turn.
 */
public final class TurnContextRevocations implements MentorLedgerNotifications.Listener {

    private final TurnContextGenerations generations;
    private final ConversationToolsPods sandboxes;

    public TurnContextRevocations(TurnContextGenerations generations, ConversationToolsPods sandboxes) {
        this.generations = generations;
        this.sandboxes = sandboxes;
    }

    @Override
    public void turnChanged(MentorLedgerEvents.TurnChanged change) {}

    @Override
    public void contextChanged(MentorLedgerEvents.ContextChanged change) {
        generations.changed(change.workspaceId());
        sandboxes.removeRevoked();
    }

    @Override
    public void resync() {
        generations.changedAll();
        sandboxes.removeRevoked();
    }
}

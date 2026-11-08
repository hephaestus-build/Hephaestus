package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;

/**
 * Hears, from any process, that what a workspace's turns may read changed: it makes a new context generation, so no
 * cached build of the old one is staged again, and removes the workspace's tools Pods. A reconnect may have
 * missed changes, so it does both for every workspace.
 */
public final class TurnContextRevocations implements MentorLedgerNotifications.Listener {

    private final TurnContextGenerations generations;
    private final TurnSandboxes sandboxes;

    public TurnContextRevocations(TurnContextGenerations generations, TurnSandboxes sandboxes) {
        this.generations = generations;
        this.sandboxes = sandboxes;
    }

    @Override
    public void turnChanged(MentorLedgerEvents.TurnChanged change) {}

    @Override
    public void contextChanged(MentorLedgerEvents.ContextChanged change) {
        generations.changed(change.workspaceId());
        sandboxes.releaseWorkspace(change.workspaceId());
    }

    @Override
    public void resync() {
        generations.changedAll();
        sandboxes.releaseAll();
    }
}

package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import java.util.Objects;

/** The context folder's held and spare sandboxes, for live adapter qualification. */
public final class TurnSandboxFixtures {
    private TurnSandboxFixtures() {}

    public static int pooled(TurnSandboxes sandboxes) {
        return sandboxes.pooled();
    }

    public static String held(TurnSandboxes sandboxes, TurnAttempt turn) {
        return Objects.requireNonNull(sandboxes.acquire(
                        new TurnSandboxes.Attempt(turn.turnId(), turn.attempt()), turn.workspaceId(), () -> false)
                .containerId);
    }
}

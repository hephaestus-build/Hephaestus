package de.tum.cit.aet.hephaestus.core.action;

/**
 * Carries out one {@link ActionKind}. The executor calls it with no transaction active, and the adapter calls the
 * provider only after {@link ActionAttempt.Fence#beginWrite()} accepted.
 *
 * <p>An adapter whose provider can read back finds an earlier attempt's effect by its marker when
 * {@link ActionAttempt#writeStarted()} is set, before it writes again. An {@link ActionKind#atMostOnce()} kind never
 * sees a started write: the executor ends such an action {@link ActionState#UNCERTAIN} instead.
 */
public interface ActionAdapter {
    ActionKind kind();

    ActionResult execute(ActionAttempt attempt);

    /**
     * Ends the domain row of an action that ended, in the same transaction, whoever ended it: an attempt, expiry or
     * the reaper. It writes to the database only, and never calls a provider.
     */
    default void ended(ActionEnded ended) {}
}

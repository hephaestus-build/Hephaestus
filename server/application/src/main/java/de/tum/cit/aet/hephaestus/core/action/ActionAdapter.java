package de.tum.cit.aet.hephaestus.core.action;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Carries out one {@link ActionKind}. The executor calls it with no transaction active, and the adapter calls the
 * provider only after {@link ActionAttempt.Fence#beginWrite()} accepted.
 *
 * <p>An adapter whose provider can read back finds an earlier attempt's effect by its marker when
 * {@link ActionAttempt#writeStarted()} is set, before it writes again. An {@link ActionKind#atMostOnce()} kind never
 * sees a started write: the executor ends such an action {@link ActionState#UNCERTAIN} instead. An action past its
 * expiry is taken again only when its write may have started, so such an attempt reads back and does not write again.
 */
public interface ActionAdapter {
    ActionKind kind();

    /**
     * The values of every discriminator that this adapter decodes, so that a row of a newer build with another value is
     * left to that build. Declare {@link ActionVariants#one} when the payload names no variant.
     */
    ActionVariants variants();

    ActionResult execute(ActionAttempt attempt);

    /**
     * Checks the source and delivery eligibility of a new provider write in its admission transaction,
     * after the action is locked. A source check locks the domain row until admission commits.
     * Returning false withdraws the action.
     */
    default boolean sourceIsCurrent(@Nullable Long workspaceId, JsonNode payload) {
        return true;
    }

    /**
     * Ends the domain row of an action that ended, in the same transaction, whoever ended it: an attempt, expiry or
     * the reaper. It writes to the database only, and never calls a provider.
     */
    default void ended(ActionEnded ended) {}
}

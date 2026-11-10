package de.tum.cit.aet.hephaestus.core.action;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An action that ended, as its adapter learns it in the transaction that ended it.
 *
 * @param readbackRef the provider's name for the effect, when it is {@link ActionState#DONE}
 */
public record ActionEnded(
        UUID id,
        @Nullable Long workspaceId,
        ActionKind kind,
        String idempotencyKey,
        ActionState state,
        ActionStopReason reason,
        @Nullable String readbackRef) {}

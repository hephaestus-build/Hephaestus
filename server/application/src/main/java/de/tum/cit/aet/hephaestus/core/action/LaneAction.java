package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.authority.AuthorizationStamp;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The newest action of a lane, as its owner reads it to decide what the target needs next.
 *
 * @param rereading the action may have written and waits for its next attempt to read the provider back
 * @param stamp the authorization stamp that the action was queued under; the next action of its lane may keep it
 */
public record LaneAction(
        UUID id,
        ActionKind kind,
        String idempotencyKey,
        ActionState state,
        int attempts,
        boolean rereading,
        Instant nextAttemptAt,
        @Nullable ActionStopReason stopReason,
        Instant createdAt,
        Instant updatedAt,
        AuthorizationStamp stamp) {}

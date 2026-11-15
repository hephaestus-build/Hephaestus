package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One effect to enqueue.
 *
 * @param workspaceId the workspace the effect belongs to; NULL only for an {@link ActionKind#instanceLevel()} kind
 * @param laneKey actions with the same key run one at a time, in enqueue order, such as every write to one comment
 * @param idempotencyKey the natural key of the effect; enqueuing it again returns the existing action
 * @param payload everything the adapter needs, frozen now; email notices resolve names and addresses at send time, and
 *     any other personal data in it belongs to {@code subjectAccountId} or {@code nativeRecipient}
 * @param subjectAccountId the account the effect addresses, so its erasure cancels the action
 * @param maxAttempts the attempts before the action ends {@link ActionStopReason#ATTEMPTS_EXHAUSTED}
 * @param expiresAt when an action that has not started is no longer worth sending
 * @param nativeRecipient the addressed provider identity, retained for native-identity erasure
 */
public record ActionRequest(
        ActionKind kind,
        @Nullable Long workspaceId,
        String laneKey,
        String idempotencyKey,
        JsonNode payload,
        @Nullable Long subjectAccountId,
        int maxAttempts,
        @Nullable Instant expiresAt,
        @Nullable PersonIdentity nativeRecipient) {

    public ActionRequest {
        if (workspaceId == null && !kind.instanceLevel())
            throw new IllegalArgumentException(kind + " actions belong to a workspace");
        if (laneKey.isBlank() || laneKey.length() > 255 || idempotencyKey.isBlank() || idempotencyKey.length() > 255)
            throw new IllegalArgumentException("Lane and idempotency keys are 1 to 255 characters");
        if (maxAttempts < 1) throw new IllegalArgumentException("An action needs at least one attempt");
    }
}

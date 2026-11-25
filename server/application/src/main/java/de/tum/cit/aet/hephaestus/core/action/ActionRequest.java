package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.authority.spi.CheckedAuthority;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One effect to enqueue.
 *
 * @param authority the workspace the effect belongs to, with the authority its request was checked under (ADR 0134):
 *     the send boundary refuses the effect once that authority narrowed. NULL only for an {@link
 *     ActionKind#instanceLevel()} kind, which carries no workspace authority
 * @param laneKey actions with the same key run one at a time, in enqueue order, such as every write to one comment
 * @param idempotencyKey the natural key of the effect; enqueuing it again returns the existing action
 * @param payload everything the adapter needs, frozen now; email notices resolve names and addresses at send time, and
 *     any other personal data in it belongs to {@code subjectAccountId} or {@code nativeRecipient}
 * @param subjectAccountId the account the effect addresses, so its erasure cancels the action
 * @param maxAttempts the attempts before the action ends {@link ActionStopReason#ATTEMPTS_EXHAUSTED}
 * @param expiresAt when an action is no longer worth sending, if it has not started or a lost lease wrote nothing
 * @param nativeRecipient the addressed provider identity, retained for native-identity erasure
 */
public record ActionRequest(
        ActionKind kind,
        @Nullable CheckedAuthority authority,
        String laneKey,
        String idempotencyKey,
        JsonNode payload,
        @Nullable Long subjectAccountId,
        int maxAttempts,
        @Nullable Instant expiresAt,
        @Nullable PersonIdentity nativeRecipient) {

    public ActionRequest {
        if (authority == null && !kind.instanceLevel())
            throw new IllegalArgumentException(kind + " actions belong to a workspace");
        if (laneKey.isBlank() || laneKey.length() > 255 || idempotencyKey.isBlank() || idempotencyKey.length() > 255)
            throw new IllegalArgumentException("Lane and idempotency keys are 1 to 255 characters");
        if (maxAttempts < 1) throw new IllegalArgumentException("An action needs at least one attempt");
    }

    /** The workspace the effect belongs to; NULL only for an {@link ActionKind#instanceLevel()} kind. */
    public @Nullable Long workspaceId() {
        return authority == null ? null : authority.workspaceId();
    }
}

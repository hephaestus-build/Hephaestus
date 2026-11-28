package de.tum.cit.aet.hephaestus.conversation.support;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** A share as the person who made it sees it: to whom, until when, and how often it was read. */
public record SupportShareDTO(
        @NonNull UUID id,
        @NonNull UUID turnId,

        @Schema(description = "The slug of the workspace of the shared turn") @NonNull
        String workspaceSlug,

        @NonNull SupportShareRecipientDTO recipient,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean includesText,
        @NonNull State state,
        @NonNull Instant createdAt,
        @NonNull Instant expiresAt,
        @Nullable Instant revokedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) int readCount,
        @Nullable Instant lastReadAt) {

    public enum State {
        /** The recipient can read it. */
        ACTIVE,
        EXPIRED,
        REVOKED
    }
}

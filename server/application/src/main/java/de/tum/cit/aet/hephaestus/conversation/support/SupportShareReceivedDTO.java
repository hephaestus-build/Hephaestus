package de.tum.cit.aet.hephaestus.conversation.support;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A share as its recipient reads it. The person chose to show their name and this turn; the share shows nothing
 * else about them.
 */
public record SupportShareReceivedDTO(
        @NonNull UUID id,

        @Schema(description = "The person who shared the turn") @NonNull
        String sharedBy,

        @NonNull String workspaceSlug,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean includesText,
        @NonNull Instant createdAt,
        @NonNull Instant expiresAt,

        @Schema(description = "The turn's diagnostics. Absent in the list, and when the turn no longer exists.")
        @Nullable
        SupportShareTurnDTO turn,

        @Schema(description = "The shared messages. Empty in the list, without text, or when the text is withheld.")
        @NonNull
        List<SupportShareMessageDTO> messages,

        @Schema(
                description = "True when the person shared text that was deleted or changed since. The share then"
                        + " shows no text.",
                requiredMode = RequiredMode.REQUIRED)
        boolean textWithheld) {}

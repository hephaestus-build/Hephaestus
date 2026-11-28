package de.tum.cit.aet.hephaestus.conversation.support;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Exactly what the recipient will read, before the person shares it. */
public record SupportSharePreviewDTO(
        @NonNull SupportShareTurnDTO turn,

        @Schema(description = "The messages that the share shows. Empty when the person shares only diagnostics.")
        @NonNull
        List<SupportShareMessageDTO> messages,

        @Schema(description = "Confirms these exact messages when the person shares. Absent without messages.")
        @Nullable
        String textDigest,

        @Schema(description = "The instance admins who can receive the share") @NonNull
        List<SupportShareRecipientDTO> recipients,

        @Schema(description = "How many days the recipient can read the share", requiredMode = RequiredMode.REQUIRED)
        int validForDays) {}

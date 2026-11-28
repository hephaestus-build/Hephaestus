package de.tum.cit.aet.hephaestus.conversation.support;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record SupportShareRequestDTO(
        @Schema(description = "The instance admin who can read the share")
        @NonNull
        @NotNull
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        String recipientAccountId,

        @NonNull @NotNull Boolean includeText,

        @Schema(description = "The preview's text digest; required when the share includes text")
        @Nullable
        @Size(min = 64, max = 64)
        String textDigest) {}

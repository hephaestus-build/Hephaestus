package de.tum.cit.aet.hephaestus.conversation.support;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

public record SupportSharePreviewRequestDTO(
        @Schema(description = "Whether the share shows the turn's messages, or only its diagnostics") @NonNull @NotNull
        Boolean includeText) {}

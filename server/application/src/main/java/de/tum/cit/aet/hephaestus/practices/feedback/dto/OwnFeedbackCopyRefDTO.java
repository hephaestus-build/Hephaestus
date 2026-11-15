package de.tum.cit.aet.hephaestus.practices.feedback.dto;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record OwnFeedbackCopyRefDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FeedbackCopyKind kind,

        @Nullable String externalUrl,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant postedAt) {}

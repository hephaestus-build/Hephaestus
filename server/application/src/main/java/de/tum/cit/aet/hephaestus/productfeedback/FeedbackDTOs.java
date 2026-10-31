package de.tum.cit.aet.hephaestus.productfeedback;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

final class FeedbackDTOs {
    private FeedbackDTOs() {}

    /** Who produced a record, as an administrator sees it; absent once the account is erased. */
    record FeedbackAccountRefDTO(
            @NonNull Long id,
            @NonNull String displayName,
            @Nullable String email) {}

    /** The workspace a record was submitted from; absent for instance-level submissions. */
    record FeedbackWorkspaceRefDTO(
            @NonNull Long id, @NonNull String slug, @NonNull String displayName) {}

    record FeedbackRequestDTO(
            @NotNull ProductFeedback.@NonNull Kind kind,
            @NotBlank @Size(max = 5000) @NonNull String message,

            @Size(max = 500) @Pattern(regexp = "/(?!/)[^?#\\p{Cc}]*") @Nullable
            String pagePath,

            @Size(max = 500) @Pattern(regexp = "[^\\p{Cc}]*") @Nullable
            String userAgent) {}

    record FeedbackTriageDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean resolved) {}

    enum FeedbackFilter {
        OPEN,
        RESOLVED,
        ALL
    }

    record FeedbackItemDTO(
            @NonNull UUID id,
            @Nullable FeedbackAccountRefDTO account,
            @Nullable FeedbackWorkspaceRefDTO workspace,
            ProductFeedback.@NonNull Kind kind,
            @NonNull String message,
            @Nullable String pagePath,
            @Nullable String userAgent,
            @Nullable String appVersion,
            @NonNull Instant createdAt,
            @Nullable Instant resolvedAt,
            @Nullable FeedbackAccountRefDTO resolvedBy) {}
}

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

            /* The page as its route template, such as /w/$workspaceSlug/activity: no query, no address. */
            @Size(max = 500) @Pattern(regexp = "/|(/(?:[a-z0-9][a-z0-9-]*|\\$[A-Za-z]*))+") @Nullable
            String pagePath,

            @Size(max = 500) @Pattern(regexp = "[^\\p{Cc}]*") @Nullable
            String userAgent,

            /* The composer's box: may this report become a public issue? Null keeps the remembered choice, and
            without one the report stays private. */
            @Nullable Boolean share) {}

    record FeedbackTriageDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean resolved) {}

    enum FeedbackFilter {
        /** Waits for an admin: saved here, stopped, or never filed. */
        OPEN,
        /** On its way: drafting, held, queued or filing. */
        IN_FLIGHT,
        /** An open issue on GitHub. */
        ON_GITHUB,
        /** Marked resolved here, or its issue closed. */
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
            @Nullable FeedbackAccountRefDTO resolvedBy,

            /* The report waits here only, and a private repository can take it now. */
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean canFile,

            /* Its author was not told about the model, so it moves as written and no model reads it. */
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean savedBeforeDisclosure,

            /* Everything below comes from the same states as the reporter's view. */
            FilingDTOs.@NonNull ReportState state,
            FilingDTOs.@NonNull ReportRoute route,
            @Nullable String reason,
            @Nullable String issueUrl,
            @Nullable Long issueNumber,
            @Nullable String issueClosedAs,
            @Nullable String issueTitle,
            @Nullable String issueText,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean canTriage) {}
}

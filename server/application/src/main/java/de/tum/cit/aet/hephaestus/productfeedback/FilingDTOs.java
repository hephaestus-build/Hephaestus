package de.tum.cit.aet.hephaestus.productfeedback;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** The wire contract of product feedback on GitHub (ADR 0073). */
final class FilingDTOs {
    private FilingDTOs() {}

    /** Where a report goes, as the person sees it. */
    enum ReportRoute {
        PUBLIC,
        PRIVATE,
        EMAIL
    }

    /** A report's state, as the person sees it. */
    enum ReportState {
        /** The admins have it by email; it is not on GitHub. */
        SENT_TO_ADMINS,
        /** A model drafts the issue. */
        DRAFTING,
        /** A public report waits for its hold to end. */
        HELD,
        /** It is being filed. */
        FILING,
        /** It is an issue on GitHub. */
        FILED,
        /** Filing failed; the admins have it by email and can retry. */
        STOPPED
    }

    /**
     * @param route where the report is going now; it can still change while the state is DRAFTING or HELD
     * @param filedAfter when a held public report is filed, unless the person keeps it private first
     */
    record FeedbackReceiptDTO(
            @NonNull UUID id,
            @NonNull ReportRoute route,
            @NonNull ReportState state,
            @Nullable Instant filedAfter) {}

    /**
     * The person's sharing choice for public issues.
     *
     * @param filesToGitHub reports become GitHub issues on this instance, public or private
     * @param publicRepository {@code owner/name} of the public repository, or null when the instance has none and the
     *     choice is not asked
     * @param allowPublic the remembered choice, or null when the person has not chosen for the current notice
     * @param githubLogin the person's GitHub login, for the mention; null when the account has no GitHub sign-in
     */
    record ProductFeedbackSharingDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean filesToGitHub,
            @Nullable String publicRepository,
            @Nullable Boolean allowPublic,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean mention,
            @Nullable String githubLogin,
            @Nullable Instant decidedAt) {}

    record UpdateProductFeedbackSharingDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean allowPublic,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean mention) {}

    /**
     * One of the person's own reports.
     *
     * @param reason why the report is not public, in words; null for a public one
     * @param issueUrl the public issue; null for a private issue, which the person cannot open
     * @param removed what redaction removed, in words
     * @param draftTitle the drafted title, while the report is held or once it is filed
     * @param draftDescription the drafted main text the person can edit while the report is held
     * @param issueClosedAs {@code completed}, {@code not_planned} or {@code duplicate} once the issue is closed
     */
    record MyProductFeedbackDTO(
            @NonNull UUID id,
            ProductFeedback.@NonNull Kind kind,
            @NonNull String excerpt,
            @NonNull Instant createdAt,
            @NonNull ReportState state,
            @NonNull ReportRoute route,
            @Nullable String reason,
            @Nullable Instant filedAfter,
            @Nullable String issueUrl,
            @Nullable Long issueNumber,
            @Nullable String issueClosedAs,
            @Nullable String removed,
            @Nullable String draftTitle,
            @Nullable String draftDescription,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean allowPublic,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean canKeepPrivate,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean canEdit,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean canMakePrivate,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean canAllowPublic) {}

    record EditProductFeedbackDraftDTO(
            @NotBlank @Size(max = Draft.TITLE_MAX) @NonNull String title,
            @NotBlank @Size(max = Draft.FIELD_MAX) @NonNull String description) {}

    record AllowPublicDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean allowPublic) {}

    /** The result of checking one destination repository. */
    enum RepositoryCheck {
        /** No repository is set. */
        UNSET,
        /** The app is installed, and the visibility fits. */
        INSTALLED,
        /** The app cannot reach the repository: not installed, suspended, or the repository is gone. */
        MISSING,
        /** The private repository is public. Nothing is filed there. */
        NOT_PRIVATE,
        /** The public repository is private. Its issues are not public; this is fine for a test instance. */
        NOT_PUBLIC,
        /** The two repositories have different owners, so an issue cannot move from one to the other. */
        DIFFERENT_OWNER
    }

    /**
     * The instance's product feedback destinations and their health.
     *
     * @param llmModelId the instance model that drafts issues, or null for none (reports then go private undrafted)
     * @param draftsToday drafts made since midnight UTC, against {@code dailyDraftLimit}
     * @param oldestWaitingSince the oldest report not yet on GitHub or in the admin email
     */
    record ProductFeedbackDestinationsDTO(
            @Nullable String publicRepository,
            @Nullable String privateRepository,
            @Nullable Long llmModelId,
            @Schema(requiredMode = RequiredMode.REQUIRED) int dailyDraftLimit,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean paused,
            @Schema(requiredMode = RequiredMode.REQUIRED) int noticeVersion,
            @NonNull RepositoryCheck publicCheck,
            @NonNull RepositoryCheck privateCheck,
            @Schema(requiredMode = RequiredMode.REQUIRED) long draftsToday,
            @Schema(requiredMode = RequiredMode.REQUIRED) long needsAction,
            @Nullable Instant lastFiledAt,
            @Nullable Instant oldestWaitingSince,
            @Nullable Instant snapshotAt) {}

    /**
     * @param newNoticeVersion true to raise the notice version, so that every person chooses again
     */
    record UpdateProductFeedbackDestinationsDTO(
            @Size(max = 200) @Pattern(regexp = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") @Nullable
            String publicRepository,

            @Size(max = 200) @Pattern(regexp = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") @Nullable
            String privateRepository,

            @Nullable Long llmModelId,

            @Min(0) @Max(1000) @Schema(requiredMode = RequiredMode.REQUIRED)
            int dailyDraftLimit,

            @Schema(requiredMode = RequiredMode.REQUIRED) boolean paused,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean newNoticeVersion) {}

    /** A report that failed to file and waits for an admin. */
    record ProductFeedbackNeedsActionDTO(
            @NonNull UUID id,
            ProductFeedback.@NonNull Kind kind,
            @NonNull Instant createdAt,
            @NonNull Instant stoppedAt,
            @NonNull String reason,
            @NonNull ReportRoute route,
            @Nullable String repository) {}

    record ProductFeedbackNeedsActionListDTO(@NonNull List<ProductFeedbackNeedsActionDTO> items) {}
}

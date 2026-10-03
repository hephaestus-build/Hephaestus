package de.tum.cit.aet.hephaestus.productfeedback;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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

    enum QuestionType {
        TEXT,
        SINGLE_CHOICE,
        MULTIPLE_CHOICE,
        RATING,
        NPS
    }

    /**
     * @param allowOther a choice question also takes one free-text answer; false when a stored question omits it
     */
    record QuestionDTO(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_-]+") @NonNull
            String id,

            @NotBlank @Size(max = 300) @NonNull String prompt,
            @NotNull @NonNull QuestionType type,
            @NotNull @Size(max = 20) @NonNull List<@NotBlank @Size(max = 200) String> options,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean required,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean allowOther,
            @Size(max = 60) @Nullable String lowLabel,
            @Size(max = 60) @Nullable String highLabel) {}

    /** One answer; exactly the field matching the question's type is set. */
    record AnswerDTO(
            @NotBlank @Size(max = 80) @NonNull String questionId,
            @Size(max = 4000) @Nullable String text,
            @Size(max = 20) @Nullable List<@NotBlank @Size(max = 200) String> choices,
            @Min(0) @Max(10) @Nullable Integer rating) {}

    record CreateSurveyDTO(
            @NotBlank @Size(max = 160) @NonNull String title,
            @NotBlank @Size(max = 500) @NonNull String description,
            @NotNull Survey.@NonNull Purpose purpose,
            @NotEmpty @Size(max = 20) @NonNull List<@NotNull @Valid QuestionDTO> questions,
            @Nullable Long workspaceId,
            @NotNull @NonNull Instant startsAt,
            @Nullable Instant endsAt) {
        @AssertTrue(message = "endsAt must be after startsAt")
        @Schema(hidden = true)
        private boolean isEndAfterStart() {
            return endsAt == null || endsAt.isAfter(startsAt);
        }
    }

    record SurveyEditDTO(
            @NotBlank @Size(max = 160) @NonNull String title,
            @NotBlank @Size(max = 500) @NonNull String description,
            @NotNull @NonNull Instant startsAt,
            @Nullable Instant endsAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean active) {
        @AssertTrue(message = "endsAt must be after startsAt")
        @Schema(hidden = true)
        private boolean isEndAfterStart() {
            return endsAt == null || endsAt.isAfter(startsAt);
        }
    }

    /**
     * @param researchOrganization the organisation a research survey was published for; absent for a product survey
     */
    record SurveyDTO(
            @NonNull UUID id,
            @NonNull String title,
            @NonNull String description,
            Survey.@NonNull Purpose purpose,
            @Nullable String researchOrganization,
            @NonNull List<QuestionDTO> questions,
            @Nullable FeedbackWorkspaceRefDTO workspace,
            @NonNull Instant startsAt,
            @Nullable Instant endsAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean active,
            @Nullable FeedbackAccountRefDTO createdBy,
            @NonNull Instant createdAt,
            @NonNull ParticipationCountsDTO participation) {}

    /**
     * @param invited every account shown the invitation, including those who then responded or declined
     */
    record ParticipationCountsDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) long invited,
            @Schema(requiredMode = RequiredMode.REQUIRED) long responded,
            @Schema(requiredMode = RequiredMode.REQUIRED) long declined) {}

    /**
     * @param researchOrganization set for a research survey: the organisation whose study the answers join
     * @param seen the account has been shown this invitation; the webapp nudges only while false
     */
    record SurveyInvitationDTO(
            @NonNull UUID id,
            @NonNull String title,
            @NonNull String description,
            Survey.@NonNull Purpose purpose,
            @Nullable String researchOrganization,
            @NonNull List<QuestionDTO> questions,
            @Nullable Instant endsAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean seen) {}

    record SubmitSurveyDTO(@NotNull @Size(max = 20) @NonNull List<@NotNull @Valid AnswerDTO> answers) {}

    record OptionCountDTO(
            @NonNull String value,
            @Schema(requiredMode = RequiredMode.REQUIRED) long count) {}

    /**
     * @param other responses whose choices include a free-text answer; present for choice questions only
     * @param score Net Promoter Score, −100…100, for an NPS question
     */
    record QuestionSummaryDTO(
            @NonNull String questionId,
            @Schema(requiredMode = RequiredMode.REQUIRED) long answered,
            @NonNull List<OptionCountDTO> counts,
            @Nullable Long other,
            @Nullable Double average,
            @Nullable Integer score) {}

    record SurveySummaryDTO(
            @NonNull ParticipationCountsDTO participation,
            @NonNull List<QuestionSummaryDTO> questions) {}

    record SurveyResponseDTO(
            @NonNull UUID id,
            @Nullable FeedbackAccountRefDTO account,
            @Nullable FeedbackWorkspaceRefDTO workspace,
            SurveyParticipation.@NonNull Status status,
            @Nullable List<AnswerDTO> answers,
            @NonNull Instant decidedAt) {}

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

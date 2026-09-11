package de.tum.cit.aet.hephaestus.productfeedback;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
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

    /** The workspace a record was submitted from; absent for instance-level submissions or a purged workspace. */
    record FeedbackWorkspaceRefDTO(
            @NonNull Long id, @NonNull String slug, @NonNull String displayName) {}

    enum QuestionType {
        TEXT,
        SINGLE_CHOICE,
        MULTIPLE_CHOICE,
        /** A 1–5 scale whose endpoints the author labels. */
        RATING,
        /** The standard 0–10 "How likely are you to recommend…" scale with fixed labels. */
        NPS
    }

    record QuestionDTO(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_-]+") @NonNull
            String id,

            @NotBlank @Size(max = 300) @NonNull String prompt,
            @NotNull @NonNull QuestionType type,
            @NotNull @Size(max = 20) @NonNull List<@NotBlank @Size(max = 200) String> options,
            @NonNull boolean required,
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
            @NotEmpty @Size(max = 20) @NonNull List<@NotNull @Valid QuestionDTO> questions,
            @Nullable Long workspaceId,
            @NotNull @NonNull Instant startsAt,
            @Nullable Instant endsAt) {}

    /** The fields that stay editable after publication; the questions are frozen. */
    record SurveyEditDTO(
            @NotBlank @Size(max = 160) @NonNull String title,
            @NotBlank @Size(max = 500) @NonNull String description,
            @NotNull @NonNull Instant startsAt,
            @Nullable Instant endsAt,
            @NonNull boolean active) {}

    record SurveyDTO(
            @NonNull UUID id,
            @NonNull String title,
            @NonNull String description,
            @NonNull List<QuestionDTO> questions,
            @Nullable FeedbackWorkspaceRefDTO workspace,
            @NonNull Instant startsAt,
            @Nullable Instant endsAt,
            @NonNull boolean active,
            @Nullable FeedbackAccountRefDTO createdBy,
            @NonNull Instant createdAt,
            @NonNull ParticipationCountsDTO participation) {}

    record ParticipationCountsDTO(
            @NonNull long invited,
            @NonNull long responded,
            @NonNull long declined) {}

    /** What a member sees: the questions and whether this account has been shown the invitation before. */
    record SurveyInvitationDTO(
            @NonNull UUID id,
            @NonNull String title,
            @NonNull String description,
            @NonNull List<QuestionDTO> questions,
            @Nullable Instant endsAt,
            @NonNull boolean seen) {}

    record SubmitSurveyDTO(@NotNull @Size(max = 20) @NonNull List<@NotNull @Valid AnswerDTO> answers) {}

    record OptionCountDTO(@NonNull String value, @NonNull long count) {}

    /** Per-question aggregate; the fields a type cannot produce are absent. */
    record QuestionSummaryDTO(
            @NonNull String questionId,
            @NonNull long answered,
            @NonNull List<OptionCountDTO> counts,
            @Nullable Double average,
            /** Net Promoter Score, −100…100, for {@link QuestionType#NPS}. */
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

    record FeedbackTriageDTO(@NonNull boolean resolved) {}

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
            @NonNull String appVersion,
            @NonNull Instant createdAt,
            @Nullable Instant resolvedAt,
            @Nullable FeedbackAccountRefDTO resolvedBy) {}
}

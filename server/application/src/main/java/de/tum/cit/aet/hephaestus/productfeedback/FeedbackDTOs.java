package de.tum.cit.aet.hephaestus.productfeedback;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

final class FeedbackDTOs {
    private FeedbackDTOs() {}

    record QuestionDTO(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_-]+") @NonNull
            String id,

            @NotBlank @Size(max = 300) @NonNull String prompt,
            @NotNull @NonNull QuestionType type,
            @NotNull @Size(max = 20) @NonNull List<@NotBlank @Size(max = 200) String> options,
            boolean required) {}

    enum QuestionType {
        TEXT,
        SINGLE_CHOICE,
        RATING
    }

    record CreateSurveyDTO(
            @NotBlank @Size(max = 160) @NonNull String title,
            @NotBlank @Size(max = 500) @NonNull String description,
            @NotEmpty @Size(max = 20) @NonNull List<@NotNull @Valid QuestionDTO> questions,
            @Nullable Long workspaceId,
            @NotNull @NonNull Instant startsAt,
            @Nullable Instant endsAt) {}

    record SurveyDTO(
            @NonNull UUID id,
            @NonNull String title,
            @NonNull String description,
            @NonNull List<QuestionDTO> questions,
            @Nullable Long workspaceId,
            @NonNull Instant startsAt,
            @Nullable Instant endsAt,
            @NonNull boolean active,
            @Nullable Instant createdAt) {}

    record SubmitSurveyDTO(
            @NotNull @Size(max = 20) @NonNull Map<@Size(max = 80) String, @NotNull @Size(max = 4000) String> answers) {}

    record SurveyStatusDTO(@NotNull @NonNull Boolean active) {}

    record FeedbackRequestDTO(
            @NotNull ProductFeedback.@NonNull Kind kind,
            @NotBlank @Size(max = 5000) @NonNull String message,

            @Size(max = 500) @Pattern(regexp = "/(?!/)[^?#\\p{Cc}]*") @Nullable
            String pagePath) {}

    record FeedbackItemDTO(
            @NonNull UUID id,
            @NonNull Long accountId,
            @Nullable Long workspaceId,
            ProductFeedback.@NonNull Kind kind,
            @NonNull String message,
            @Nullable String pagePath,
            @Nullable Instant createdAt) {}

    record SubmissionDTO(
            @NonNull UUID id,
            @NonNull UUID surveyId,
            @NonNull String surveyTitle,
            @NonNull List<QuestionDTO> questions,
            @NonNull Long accountId,
            @Nullable Long workspaceId,
            SurveySubmission.@NonNull Disposition disposition,
            @Nullable Map<String, String> answers,
            @Nullable Instant createdAt) {}
}

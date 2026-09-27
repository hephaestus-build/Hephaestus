package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawal;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

@Schema(description = "Withdraw a card from a developer's practice page, or restore it")
public record UpdateFeedbackWithdrawalRequestDTO(
        @NonNull @NotNull @Schema(description = "true withdraws the card; false restores it")
        Boolean withdrawn,

        @NonNull
        @NotBlank
        @Size(max = FeedbackWithdrawal.MAX_REASON_LENGTH)
        @Schema(description = "Why, kept with the withdrawal for the workspace's administrators")
        String reason) {}

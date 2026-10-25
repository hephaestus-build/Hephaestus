package de.tum.cit.aet.hephaestus.practices.profile.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.OwnFeedbackRefDTO;
import de.tum.cit.aet.hephaestus.practices.result.dto.ResultListDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProfileReviewDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewRefDTO review,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ResultListDTO> results,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<OwnFeedbackRefDTO> feedback,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "This review's place among the reader's reviews of its work, oldest first,"
                        + " whatever the list's filters keep")
        int positionOnWork,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "How many reviews of its work recorded something about the reader")
        int totalOnWork) {
    public ProfileReviewDTO {
        results = List.copyOf(results);
        feedback = List.copyOf(feedback);
    }
}

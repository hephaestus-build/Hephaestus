package de.tum.cit.aet.hephaestus.practices.review.dto;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record DeliveredWorkFeedbackDTO(
        @Nullable ItemRefDTO item, @NonNull List<DeliveredWorkFeedbackItemDTO> feedback) {
    public DeliveredWorkFeedbackDTO {
        feedback = List.copyOf(feedback);
    }
}

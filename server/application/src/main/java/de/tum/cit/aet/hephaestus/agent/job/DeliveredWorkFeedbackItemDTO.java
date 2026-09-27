package de.tum.cit.aet.hephaestus.agent.job;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record DeliveredWorkFeedbackItemDTO(
        @NonNull UUID id,
        @Nullable Instant deliveredAt,
        @NonNull List<DeliveredFeedbackPracticeDTO> practices,
        @NonNull List<DeliveredWorkFeedbackPlacementDTO> placements) {}

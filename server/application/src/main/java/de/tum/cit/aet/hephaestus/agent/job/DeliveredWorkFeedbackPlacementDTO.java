package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.practices.feedback.PlacementAnchorSide;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record DeliveredWorkFeedbackPlacementDTO(
        @NonNull UUID id,
        @NonNull PlacementType type,

        @NonNull
        @Schema(
                description =
                        "Opaque recorded provider comment identity, for deduplication within this work only; never a URL")
        String commentRef,

        @Nullable String path,
        @Nullable Integer startLine,
        @Nullable Integer endLine,
        @Nullable PlacementAnchorSide side,

        @Nullable
        @Schema(
                description =
                        "Provider-returned link on this exact work, or a matching mirrored comment; absent when unavailable. A recorded placement is not a live existence check.")
        String permalink) {}

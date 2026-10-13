package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementAnchorKind;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementAnchorSide;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "A recorded message placement")
public record ReviewPlacementDTO(
        @NonNull UUID id,
        @NonNull PlacementType placementType,

        @Schema(description = "Anchor granularity; null for non-inline placements") @Nullable
        PlacementAnchorKind anchorKind,

        @Schema(description = "Head-side path of the anchored file") @Nullable
        String anchorPath,

        @Schema(description = "First anchored line (1-based)") @Nullable
        Integer anchorStartLine,

        @Schema(description = "Last anchored line (1-based)") @Nullable
        Integer anchorEndLine,

        @Schema(description = "Diff side of the anchor") @Nullable
        PlacementAnchorSide anchorSide,

        @Schema(description = "Provider-native comment identifier") @Nullable
        String postedCommentRef,

        @Schema(description = "Permalink of the posted provider comment; null when the provider gave none") @Nullable
        String postedCommentUrl,

        @Schema(description = "Heph conversation entry that delivered this placement") @Nullable
        UUID mentorEntryId) {
    public static ReviewPlacementDTO from(FeedbackPlacement placement) {
        return new ReviewPlacementDTO(
                placement.getId(),
                placement.getPlacementType(),
                placement.getAnchorKind(),
                placement.getAnchorPath(),
                placement.getAnchorStartLine(),
                placement.getAnchorEndLine(),
                placement.getAnchorSide(),
                placement.getPostedCommentRef(),
                placement.getPostedCommentUrl(),
                placement.getMentorEntryId());
    }
}

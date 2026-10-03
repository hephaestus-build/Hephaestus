package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingObservationDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.TrendDirection;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.TrendSupportDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "A developer's derived qualitative standing for one Group including 1<=n<many practices")
public record PracticeGroupStandingDTO(
        @NonNull @Schema(description = "Group slug") String groupSlug,
        @NonNull @Schema(description = "Group name") String groupName,

        @NonNull @Schema(description = "Derived qualitative standing across the group's practices")
        Standing standing,

        @Nullable
        @Schema(
                description =
                        "Developer guidance aggregated from the group's feedback (null unless the standing is a verdict). "
                                + "The deterministic summary combines standing, next focus, and developer-facing catalog guidance.")
        String guidance,

        @Nullable @Schema(description = "How the guidance text was produced (null when there is no guidance)")
        GuidanceSource guidanceSource,

        @Nullable @Schema(description = "Evidence-weighted, opportunity-indexed direction across the group's practices")
        TrendDirection direction,

        @Nullable @Schema(description = "Evidence support and provenance for the direction")
        TrendSupportDTO trendSupport,

        @Nullable
        @Schema(description = "Calendar span covered by the feedback, for provenance only; never a trend-analysis unit")
        Integer feedbackSpanDays,

        @Nullable @Schema(description = "Oldest contributing observation, for provenance only (null without a verdict)")
        Instant feedbackSince,

        @NonNull
        @Schema(
                description =
                        "Supporting observations the standing derives from (problems first); empty without a verdict")
        List<PracticeStandingObservationDTO> observations,

        @NonNull
        @Schema(
                description =
                        "Distinct pieces of reviewed work the observations come from, per kind (provenance, not a score); empty without a verdict")
        List<FeedbackSourceCountDTO> sources) {
    public enum Standing {
        DEVELOPING,
        STRENGTH,
        MIXED,
        NOT_OBSERVED,
        NO_OPPORTUNITY;

        /** A group's standing is its practices' rolled up, on the same scale under the same names. */
        public PracticeStandingDTO.Standing asPracticeStanding() {
            return switch (this) {
                case DEVELOPING -> PracticeStandingDTO.Standing.DEVELOPING;
                case STRENGTH -> PracticeStandingDTO.Standing.STRENGTH;
                case MIXED -> PracticeStandingDTO.Standing.MIXED;
                case NOT_OBSERVED -> PracticeStandingDTO.Standing.NOT_OBSERVED;
                case NO_OPPORTUNITY -> PracticeStandingDTO.Standing.NO_OPPORTUNITY;
            };
        }
    }

    public static boolean isVerdict(Standing standing) {
        return standing == Standing.DEVELOPING || standing == Standing.MIXED || standing == Standing.STRENGTH;
    }

    public enum GuidanceSource {
        RULE_BASED,
    }
}

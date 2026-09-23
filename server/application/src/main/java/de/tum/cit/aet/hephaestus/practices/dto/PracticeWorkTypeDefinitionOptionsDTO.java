package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewMode;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Review timing, evidence choices, and recommended settings for one type of reviewed work")
public record PracticeWorkTypeDefinitionOptionsDTO(
        @NonNull ArtifactKind artifactKind,

        @NonNull
        @Schema(
                description = "The occasions a practice on this work type can be bound to. A review somebody asks "
                        + "for by hand is not among them — see manualReviewSignal.")
        List<PracticeSignalOptionDTO> signals,
        /**
         * Carried beside {@code signals} rather than inside it so that "every entry here is a choosable
         * occasion" holds without a reader re-deriving the exception from a signal's spelling.
         */
        @Nullable
        @Schema(
                description = "How a person asks for a review of this work type by hand, or absent where the work "
                        + "type admits no such request. Not an occasion to bind to: such a request reviews every "
                        + "practice on the work type whatever state the work is in.")
        PracticeManualReviewSignalDTO manualReviewSignal,

        @NonNull PracticeAutomatedReviewPolicy recommendedPolicy,

        @NonNull
        @Schema(description = "Evidence a new binding on this work type starts with when the author says nothing")
        List<PracticeEvidenceRequirement> recommendedNeeds,

        @NonNull List<PracticeAutomatedReviewMode> supportedAutomatedReviewModes,
        @NonNull List<PracticeEvidenceSourceOptionDTO> allowedSources,
        @NonNull List<ActorRole> subjectRoles) {}

package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.evidence.PrivacyClass;
import de.tum.cit.aet.hephaestus.evidence.RequiredCaptureQuality;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/**
 * @param selectionScope distinct from {@code description}, which says what the source <em>is</em>: this is fixed by the
 *     source contract rather than anything a practice author can configure
 * @param supportsExhaustiveEvidence not derivable from {@code requiredQuality}: two sources at the same floor can
 *     differ on whether a complete capture is reachable at all
 */
@Schema(description = "An evidence source a practice on this kind of work may read")
public record PracticeEvidenceSourceOptionDTO(
        @NonNull String sourceKind,
        @NonNull String displayName,
        @NonNull String description,

        @NonNull
        @Schema(
                description =
                        "How much of the source one capture takes, and the bound past which it is no longer whole")
        String selectionScope,

        @NonNull PrivacyClass privacyClass,

        @NonNull
        @Schema(description = "What requiring this source demands of its capture; fixed by the source contract")
        RequiredCaptureQuality requiredQuality,

        @Schema(
                description =
                        "Whether this source can be captured whole, and so whether a practice may rest a claim about what is absent from it on the capture",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean supportsExhaustiveEvidence) {}

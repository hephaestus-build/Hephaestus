package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonIgnore;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/** One source a practice reads, and how it relates to it. */
@Schema(
        description = "A source a practice reads and the stance it takes towards it",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PracticeEvidenceRequirement(
        @NonNull @NotNull @Schema(description = "Stable source identifier from the selected source contract")
        SourceKind sourceKind,

        @NonNull @NotNull @Schema(description = "Whether an absent or degraded capture refuses the review")
        EvidenceStance stance)
        implements ClosedPracticeInput {
    public PracticeEvidenceRequirement {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(stance, "stance");
    }

    /**
     * Whether an absent or inadequate capture of this source refuses the review.
     *
     * <p>Derived, never serialized: the stance is the stored fact and a second accessor for it would
     * ship a second one.
     */
    @JsonIgnore
    public boolean refuses() {
        return stance.refuses();
    }
}

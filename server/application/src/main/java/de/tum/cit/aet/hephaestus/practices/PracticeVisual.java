package de.tum.cit.aet.hephaestus.practices;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        description = "Developer guidance: one picture of the practice and what it shows. Never changes how the "
                + "practice is reviewed")
public record PracticeVisual(
        @NonNull @Schema(description = "SVG markup. Shapes and text only; colors come from the pv-* theme classes")
        String svg,

        @NonNull
        @Schema(
                description = "What the picture shows, for people who cannot see it",
                maxLength = PracticeGuidanceRules.MAX_ALT_LENGTH)
        String alt)
        implements ClosedPracticeInput {
    public PracticeVisual {
        Objects.requireNonNull(svg, "svg");
        alt = Objects.requireNonNull(alt, "alt").strip();
    }

    /** A hash of the picture and its description, for audit snapshots and digests that must not hold the markup. */
    public String digest() {
        return new CanonicalDigest().add(svg).add(alt).hex();
    }
}

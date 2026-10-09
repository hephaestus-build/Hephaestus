package de.tum.cit.aet.hephaestus.practices;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.NonNull;

@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        description = "Developer guidance: the text of a practice's Guide tab and the SVG figures it shows. Never "
                + "changes how the practice is reviewed")
public record PracticeGuide(
        @NonNull
        @Schema(
                description = "Markdown. Shows a figure with ![description](figures/<name>.svg)",
                maxLength = PracticeGuidanceRules.MAX_GUIDE_LENGTH)
        String markdown,

        @NonNull
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "SVG markup of each figure the Markdown shows, by figure name")
        Map<String, String> figures)
        implements ClosedPracticeInput {
    public PracticeGuide {
        Objects.requireNonNull(markdown, "markdown");
        // Sorted, so equal guides digest and serialize the same whatever order the figures arrived in.
        figures = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(figures, "figures")));
    }

    /** A hash of the text and its figures, for audit snapshots and digests that must not hold the markup. */
    public String digest() {
        CanonicalDigest digest = new CanonicalDigest().add(markdown).addInt(figures.size());
        figures.forEach((name, svg) -> digest.add(name).add(svg));
        return digest.hex();
    }
}

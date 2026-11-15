package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PracticeGuidance(
        @JsonProperty(required = true) @Nullable String whyItMatters,
        @JsonProperty(required = true) @Nullable String whatGoodLooksLike,
        @JsonProperty(required = true) @Nullable String goingWell,
        @JsonProperty(required = true) @Nullable PracticeVisual visual,
        @JsonProperty(required = true) @Nullable PracticeGuide guide)
        implements ClosedPracticeInput {
    public PracticeGuidance {
        if ((whyItMatters != null && whyItMatters.length() > 2000)
                || (whatGoodLooksLike != null && whatGoodLooksLike.length() > 2000)
                || (goingWell != null && goingWell.length() > 2000)) {
            throw new InvalidInputException("Use at most 2000 characters for each guidance sentence.");
        }
    }
}

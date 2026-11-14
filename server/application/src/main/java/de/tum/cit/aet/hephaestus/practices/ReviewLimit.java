package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import java.util.Objects;

public record ReviewLimit(
        @JsonProperty(required = true) String code,
        @JsonProperty(required = true) String description) implements ClosedPracticeInput {
    public static final int CODE_MAX_LENGTH = 64;
    public static final int DESCRIPTION_MAX_LENGTH = 2000;

    public ReviewLimit {
        Objects.requireNonNull(code);
        Objects.requireNonNull(description);
        if (code.isBlank()
                || code.length() > CODE_MAX_LENGTH
                || description.isBlank()
                || description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new InvalidInputException("Give the review limit a code and a description.");
        }
    }
}

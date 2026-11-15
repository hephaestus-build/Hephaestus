package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import java.util.List;
import java.util.Objects;

public record AppliesWhen(
        @JsonProperty(required = true) List<AppliesWhenClause> anyOf,
        @JsonProperty(required = true) String otherwise) implements ClosedPracticeInput {
    public AppliesWhen {
        anyOf = List.copyOf(anyOf);
        Objects.requireNonNull(otherwise);
        if (anyOf.isEmpty() || anyOf.size() > 100 || otherwise.isBlank() || otherwise.length() > 1000) {
            throw new InvalidInputException("Add a condition and explain when this practice does not apply.");
        }
    }
}

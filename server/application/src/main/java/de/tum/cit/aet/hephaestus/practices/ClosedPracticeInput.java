package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Closed practice inputs reject unrecognized fields without changing provider JSON handling. */
public interface ClosedPracticeInput {
    @JsonAnySetter
    default void rejectUnknownField(String field, @Nullable JsonNode value) {
        throw new IllegalArgumentException("Unknown practice definition field: " + field);
    }
}

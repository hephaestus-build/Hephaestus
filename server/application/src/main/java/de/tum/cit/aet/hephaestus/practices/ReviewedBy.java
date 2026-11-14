package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewedBy(
        @JsonProperty(required = true) ReviewedByKind by,
        @JsonProperty(required = true) @Nullable ReviewLimit humanReason,
        @JsonProperty(required = true) List<ReviewLimit> limits)
        implements ClosedPracticeInput {
    public ReviewedBy {
        Objects.requireNonNull(by);
        var codes = new HashSet<String>();
        if (limits.stream().anyMatch(limit -> !codes.add(limit.code()))) {
            throw new InvalidInputException("List each review limit code once.");
        }
        limits = limits.stream().sorted(Comparator.comparing(ReviewLimit::code)).toList();
        if ((by == ReviewedByKind.PEOPLE) != (humanReason != null) || limits.size() > 100) {
            throw new InvalidInputException("Explain why people review this practice. Only people need that reason.");
        }
    }
}

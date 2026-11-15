package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record PracticeReads(
        @JsonProperty(required = true) String contract,
        @JsonProperty(required = true) List<PracticeReadSource> sources) implements ClosedPracticeInput {
    public PracticeReads {
        Objects.requireNonNull(contract);
        if (contract.isBlank() || contract.length() > 32)
            throw new InvalidInputException("Use a source contract version with 1 to 32 characters.");
        var seen = new HashSet<String>();
        if (sources.size() > 100
                || sources.stream().anyMatch(source -> !seen.add(source.source().value()))) {
            throw new InvalidInputException("List each read source once, with at most 100 sources.");
        }
        sources = sources.stream()
                .sorted(Comparator.comparing(source -> source.source().value()))
                .toList();
    }
}

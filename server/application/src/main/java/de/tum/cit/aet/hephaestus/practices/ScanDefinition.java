package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ScanDefinition(
        @JsonProperty(required = true) ItemKind itemKind,
        @JsonProperty(required = true) List<String> include,
        @JsonProperty(required = true) List<String> exclude,
        @JsonProperty(required = true) List<String> context,
        @JsonProperty(required = true) PracticeReads reads)
        implements ClosedPracticeInput {
    public ScanDefinition {
        Objects.requireNonNull(itemKind);
        Objects.requireNonNull(reads);
        include = canonical(include);
        exclude = canonical(exclude);
        context = canonical(context);
        if (include.isEmpty()) throw new InvalidInputException("Include at least one path in the scan.");
    }

    private static List<String> canonical(List<String> values) {
        if (values.size() > 64 || values.size() != Set.copyOf(values).size()) {
            throw new InvalidInputException("Use at most 64 distinct scan paths.");
        }
        values.forEach(ScanDefinition::validatePath);
        return values.stream().sorted().toList();
    }

    static void validatePath(String value) {
        if (value.isBlank()
                || value.length() > 512
                || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*")
                || value.contains("\\")
                || value.indexOf(0) >= 0
                || value.contains("{")
                || value.contains("}")) {
            throw new InvalidInputException("Use a relative scan path without brace expansion.");
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equals(".git")) {
                throw new InvalidInputException("Scan paths cannot contain empty, dot, parent or .git components.");
            }
        }
    }
}

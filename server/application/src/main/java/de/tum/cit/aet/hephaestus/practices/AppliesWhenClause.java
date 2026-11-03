package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppliesWhenClause(
        @Nullable List<String> changedPath,
        @Nullable List<String> diffContains,
        @Nullable String hasItems,
        @Nullable Boolean merged)
        implements ClosedPracticeInput {
    public AppliesWhenClause {
        if ((changedPath == null ? 0 : 1)
                        + (diffContains == null ? 0 : 1)
                        + (hasItems == null ? 0 : 1)
                        + (merged == null ? 0 : 1)
                != 1) {
            throw new IllegalArgumentException("Choose one kind of condition.");
        }
        if (Boolean.FALSE.equals(merged)) {
            throw new IllegalArgumentException("A merged condition can only require that the work merged.");
        }
        if (changedPath != null) {
            changedPath = canonical(changedPath);
            changedPath.forEach(ScanDefinition::validatePath);
        }
        if (diffContains != null) diffContains = canonical(diffContains);
        if (hasItems != null && (hasItems.isBlank() || hasItems.length() > 4096)) {
            throw new IllegalArgumentException("Name the collection this condition reads.");
        }
    }

    private static List<String> canonical(List<String> values) {
        if (values.isEmpty()
                || values.size() > 100
                || values.stream().anyMatch(v -> v.isBlank() || v.length() > 200)
                || values.size() != Set.copyOf(values).size()) {
            throw new IllegalArgumentException(
                    "Use 1 to 100 distinct condition values, each with 1 to 200 characters.");
        }
        return values.stream().sorted().toList();
    }
}

package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewStateDimension;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Finite state selections: alternatives within a dimension, conjunction across dimensions. */
public final class ReviewWhen {
    public static final String DESCRIPTION =
            "Automatic review state selections. An empty object imposes no state restrictions. "
                    + "Each selected dimension requires a nonempty set of non-null values offered by the work type descriptor. "
                    + "Values within a dimension are alternatives; all selected dimensions must match.";

    private ReviewWhen() {}

    public static boolean matches(Map<String, Set<String>> policy, Map<String, String> facts) {
        return policy.entrySet().stream().allMatch(selection -> {
            String fact = facts.get(selection.getKey());
            return fact != null && selection.getValue().contains(fact);
        });
    }

    public static Map<String, Set<String>> recommended(List<ReviewStateDimension> dimensions) {
        var result = new TreeMap<String, Set<String>>();
        dimensions.stream()
                .filter(dimension -> !dimension.recommendedValues().isEmpty())
                .forEach(dimension -> result.put(dimension.key(), dimension.recommendedValues()));
        return normalize(result, dimensions);
    }

    public static Map<String, Set<String>> normalize(
            Map<String, Set<String>> policy, List<ReviewStateDimension> dimensions) {
        var result = new TreeMap<String, Set<String>>();
        canonical(policy).forEach((key, selected) -> {
            var dimension = dimensions.stream()
                    .filter(candidate -> candidate.key().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unsupported review state dimension: " + key));
            var allowed = dimension.values().stream()
                    .map(ReviewStateDimension.Value::value)
                    .collect(Collectors.toSet());
            if (!allowed.containsAll(selected)) {
                throw new IllegalArgumentException("Unsupported review state selection: " + key);
            }
            if (!allowed.equals(selected)) {
                result.put(key, selected);
            }
        });
        return canonical(result);
    }

    public static Map<String, Set<String>> canonical(Map<String, Set<String>> policy) {
        var result = new TreeMap<String, Set<String>>();
        policy.forEach((key, values) -> {
            if (key == null || values == null || values.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Review state selections and values must not be null: " + key);
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Review state selections must not be empty: " + key);
            }
            result.put(key, Collections.unmodifiableSet(new TreeSet<>(values)));
        });
        return Collections.unmodifiableMap(result);
    }
}

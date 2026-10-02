package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;
import java.util.Set;

/** One finite state dimension available for automatic review scheduling. */
public record ReviewStateDimension(String key, String displayName, List<Value> values, Set<String> recommendedValues) {
    public ReviewStateDimension {
        values = List.copyOf(values);
        recommendedValues = Set.copyOf(recommendedValues);
    }

    public record Value(String value, String displayName) {}
}

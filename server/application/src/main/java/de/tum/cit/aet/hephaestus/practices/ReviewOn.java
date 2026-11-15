package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record ReviewOn(
        @JsonProperty(required = true) List<ItemEventName> events,
        @JsonProperty(required = true) Map<String, Set<String>> state)
        implements ClosedPracticeInput {
    public ReviewOn {
        if (events.size() > 100 || events.size() != Set.copyOf(events).size()) {
            throw new InvalidInputException("Choose each event once.");
        }
        events = events.stream()
                .sorted(Comparator.comparing(ItemEventName::value))
                .toList();
        Map<String, Set<String>> copy = new TreeMap<>();
        state.forEach((key, values) -> {
            if (key.isBlank()
                    || values.isEmpty()
                    || values.size() > 100
                    || values.stream().anyMatch(value -> value.isBlank() || value.length() > 200))
                throw new InvalidInputException(
                        "Use nonempty review state dimensions and distinct supported" + " values.");
            copy.put(key, Collections.unmodifiableSet(new TreeSet<>(values)));
        });
        state = Collections.unmodifiableMap(copy);
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static ReviewOn fromJson(
            @JsonProperty(value = "events", required = true) List<ItemEventName> events,
            @JsonProperty(value = "state", required = true) Map<String, List<String>> state) {
        var sets = new TreeMap<String, Set<String>>();
        state.forEach((key, values) -> {
            if (values.size() != Set.copyOf(values).size())
                throw new InvalidInputException("Choose each review state value once.");
            sets.put(key, Set.copyOf(values));
        });
        return new ReviewOn(events, sets);
    }
}

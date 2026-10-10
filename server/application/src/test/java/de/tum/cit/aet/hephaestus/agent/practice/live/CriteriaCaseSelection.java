package de.tum.cit.aet.hephaestus.agent.practice.live;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.provider.Arguments;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class CriteriaCaseSelection {

    static final String PROPERTY = "hephaestus.live.criteriaCases";

    static final String SOURCE = "de.tum.cit.aet.hephaestus.agent.practice.live.CriteriaCaseSelection#criteriaCases";

    static final String NAME = "{argumentSetName}";

    private static final Path CASES = Path.of("src/test/resources/practices/criteria-cases.json");

    private CriteriaCaseSelection() {}

    static Stream<Arguments> criteriaCases() throws IOException {
        JsonNode cases = JsonMapper.builder().build().readTree(CASES.toFile());
        return select(cases, System.getProperty(PROPERTY)).stream()
                .map(scenario -> Arguments.argumentSet(scenario.path("id").asString(), scenario));
    }

    static List<JsonNode> select(JsonNode cases, @Nullable String requested) {
        List<JsonNode> all = StreamSupport.stream(cases.spliterator(), false).toList();
        if (requested == null) return all;
        Set<String> ids = new LinkedHashSet<>();
        for (String part : requested.split(",", -1)) {
            String id = part.strip();
            if (id.isEmpty()) {
                throw new IllegalArgumentException(PROPERTY + " names a blank case ID: '" + requested + "'");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException(PROPERTY + " repeats case ID " + id);
            }
        }
        Set<String> known =
                all.stream().map(scenario -> scenario.path("id").asString()).collect(Collectors.toSet());
        List<String> unknown = ids.stream().filter(id -> !known.contains(id)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(PROPERTY + " names unknown case IDs: " + unknown);
        }
        return all.stream()
                .filter(scenario -> ids.contains(scenario.path("id").asString()))
                .toList();
    }
}

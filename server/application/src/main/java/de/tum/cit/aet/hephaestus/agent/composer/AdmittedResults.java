package de.tum.cit.aet.hephaestus.agent.composer;

import static de.tum.cit.aet.hephaestus.agent.composer.Js.listOf;

import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The admitted results in the shape the composers read them, whatever else the admission answer carries. */
final class AdmittedResults {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private AdmittedResults() {}

    /**
     * One admitted result in the key order the prompts show: its declared fields first, then the others as sent, then
     * its evidence, the evidence's citations each with their index, and its problems with their id last.
     */
    static ObjectNode normalize(JsonNode admitted) {
        var result = JSON.objectNode();
        for (String key : List.of("resultId", "practiceSlug", "outcome"))
            if (admitted.has(key)) result.set(key, admitted.path(key));
        admitted.properties().forEach(property -> {
            if (!Set.of("resultId", "practiceSlug", "outcome", "evidence", "problems")
                    .contains(property.getKey())) result.set(property.getKey(), property.getValue());
        });
        JsonNode sentEvidence = admitted.path("evidence");
        var evidence = JSON.objectNode();
        var citations = indexed(sentEvidence.path("citations"));
        evidence.set("citations", citations);
        sentEvidence.properties().forEach(property -> {
            if (!property.getKey().equals("citations")) evidence.set(property.getKey(), property.getValue());
        });
        result.set("evidence", evidence);
        result.set("citations", citations);
        var problems = result.putArray("problems");
        for (JsonNode sent : listOf(admitted.path("problems"))) {
            var problem = JSON.objectNode();
            problem.set("citations", indexed(sent.path("citations")));
            sent.properties().forEach(property -> {
                if (!Set.of("id", "citations").contains(property.getKey()))
                    problem.set(property.getKey(), property.getValue());
            });
            problem.set("problemId", sent.path("id"));
            problems.add(problem);
        }
        return result;
    }

    private static ArrayNode indexed(JsonNode citations) {
        var list = JSON.arrayNode();
        int index = 0;
        for (JsonNode citation : listOf(citations)) {
            var copy = citation.isObject() ? ((ObjectNode) citation).deepCopy() : JSON.objectNode();
            copy.put("index", index++);
            list.add(copy);
        }
        return list;
    }
}

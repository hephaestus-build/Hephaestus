package de.tum.cit.aet.hephaestus.scenario;

import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model of a scenario: a pure function from a request body to a reply. The same request always gets the same
 * reply, so a {@link Cassette} can stand in for it.
 */
@FunctionalInterface
public interface ScriptedModel {

    String reply(String requestBody);

    /**
     * Repeats every {@code content} of the request. This is the worst model for a leak: whatever reaches the request
     * reaches the answer, so the canary detectors see every leak into the model.
     */
    static ScriptedModel echo() {
        JsonMapper json = JsonMapper.builder().build();
        return body -> {
            JsonNode request = json.readTree(body);
            return request.findValues("content").stream()
                    .map(JsonNode::asString)
                    .collect(Collectors.joining(" | ", "echo: ", ""));
        };
    }
}

package de.tum.cit.aet.hephaestus.agent.composer;

import static de.tum.cit.aet.hephaestus.agent.composer.CompositionEvalCases.JSON;
import static de.tum.cit.aet.hephaestus.agent.composer.CompositionEvalCases.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every labelled composition case renders through main's review composer, and main's acceptance check accepts each
 * reference composition. On request it writes files: {@code COMPOSITION_EVAL_REQUESTS} names the file for the model
 * requests, {@code COMPOSITION_EVAL_GATE} the file for the check of every labelled composition, and
 * {@code COMPOSITION_EVAL_OUTPUTS} with {@code COMPOSITION_EVAL_CHECKED} a model run to check and the file for its
 * result.
 */
@Tag("unit")
class CompositionEvalTest {

    @Test
    void shouldRejectPrivatePiecesOnADestinationTheTurnDoesNotRoute() throws Exception {
        var scenarios = CompositionEvalCases.privateScenarios(CompositionEvalCases.load());
        assertThat(scenarios).hasSize(16);
        for (var scenario : scenarios) {
            var feedback = JSON.objectNode().putArray("feedback");
            for (var result : scenario.admission().results()) {
                if (!result.path("outcome").asString().equals("NOT_MET")) continue;
                var piece = feedback.addObject()
                        .put("destination", scenario.destination())
                        .put("practiceSlug", result.path("practiceSlug").asString())
                        .put("action", "WITHHOLD")
                        .put("withholdReason", "BELOW_BAR");
                piece.putArray("basedOn").add(result.path("resultId").asString());
            }
            var packageValue = JSON.objectNode();
            packageValue.set("feedback", feedback);
            assertThat(scenario.check(packageValue).accepted())
                    .as(scenario.id())
                    .isTrue();
            var wrongDestination = (ObjectNode) feedback.get(0);
            wrongDestination.put(
                    "destination", scenario.destination().equals("PRACTICE_PAGE") ? "CONVERSATION" : "PRACTICE_PAGE");
            assertThat(scenario.check(packageValue).accepted())
                    .as("closed destination in %s", scenario.id())
                    .isFalse();
        }
    }

    @Test
    void shouldRenderEveryCaseAndAcceptEachReferenceComposition() throws Exception {
        var requests = JSON.arrayNode();
        var gate = JSON.arrayNode();
        var scenarios = new LinkedHashMap<String, CompositionEvalCases.Scenario>();
        var refused = new ArrayList<String>();
        for (var scenario : CompositionEvalCases.load()) {
            assertThat(scenarios.put(scenario.id(), scenario))
                    .as("repeated case id %s", scenario.id())
                    .isNull();
            assertThat(scenario.turn().text()).as(scenario.id()).contains("## The review to write");
            requests.add(CompositionEvalCases.request(scenario));
            for (JsonNode composition : scenario.item().path("compositions")) {
                var checked = CompositionEvalCases.check(scenario, scenario.toModel(composition.path("review")));
                gate.addObject()
                        .put("caseId", scenario.id())
                        .put("compositionId", composition.path("id").asString())
                        .put("accepted", checked.accepted())
                        .set("errors", CompositionEvalCases.strings(checked.errors()));
                if ("reference".equals(composition.path("id").asString()) && !checked.accepted())
                    refused.add(scenario.id() + ": " + String.join("; ", checked.errors()));
            }
        }

        String requestsFile = System.getenv("COMPOSITION_EVAL_REQUESTS");
        if (requestsFile != null) {
            var file = JSON.objectNode().put("version", 1).put("system", CompositionEvalCases.system());
            file.set("cases", requests);
            CompositionEvalCases.write(requestsFile, file);
        }
        String gateFile = System.getenv("COMPOSITION_EVAL_GATE");
        if (gateFile != null) {
            var file = JSON.objectNode().put("version", 1);
            file.set("checks", gate);
            CompositionEvalCases.write(gateFile, file);
        }
        String outputs = System.getenv("COMPOSITION_EVAL_OUTPUTS");
        String checkedFile = System.getenv("COMPOSITION_EVAL_CHECKED");
        if (outputs != null && checkedFile != null)
            CompositionEvalCases.write(
                    checkedFile,
                    CompositionEvalCases.checkRun(MAPPER.readTree(Files.readAllBytes(Path.of(outputs))), scenarios));
        assertThat(scenarios).isNotEmpty();
        assertThat(refused)
                .as("main's composer refuses these reference compositions")
                .isEmpty();
    }
}

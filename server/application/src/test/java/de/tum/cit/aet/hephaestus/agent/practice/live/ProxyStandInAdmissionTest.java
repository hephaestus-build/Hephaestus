package de.tum.cit.aet.hephaestus.agent.practice.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("unit")
class ProxyStandInAdmissionTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode request(String observation) {
        return MAPPER.readTree("{\"observations\":[" + observation + "]}");
    }

    private static String observation(String outcome) {
        return """
                {"practiceSlug":"handles-errors","outcome":%s,"severity":%s,
                 "evidenceRationale":"The handler records the failure.",
                 "evidence":{"citations":[{"path":"Loader.swift","startLine":3},{"path":"Loader.swift","startLine":5}]}}
                """.formatted(outcome, "\"NOT_MET\"".equals(outcome) ? "\"MINOR\"" : "null");
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void shouldAdmitEveryOutcomeAsSentWithItsGroundsAndIndexedCitations(Outcome outcome) {
        JsonNode request = request(observation("\"" + outcome.name() + "\""));
        JsonNode sent = request.deepCopy();

        ObjectNode answer = PracticeRunnerLiveLlmTest.ProxyStandIn.admit(request);

        assertThat(answer.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(answer.path("admissionDigest").asString()).isEqualTo("live-test");
        JsonNode admitted = answer.path("observations").get(0);
        assertThat(answer.path("observations").size()).isEqualTo(1);
        assertThat(admitted.path("outcome").asString()).isEqualTo(outcome.name());
        assertThat(UUID.fromString(admitted.path("id").asString())).isNotNull();
        JsonNode original = sent.path("observations").get(0);
        for (String ground : new String[] {"practiceSlug", "severity", "evidenceRationale", "evidence"}) {
            assertThat(admitted.path(ground)).as(ground).isEqualTo(original.path(ground));
        }
        assertThat(admitted.path("citations").get(0).path("index").asInt()).isZero();
        assertThat(admitted.path("citations").get(1).path("index").asInt()).isEqualTo(1);
        assertThat(admitted.path("citations").get(1).path("startLine").asInt()).isEqualTo(5);
        assertThat(request).as("the request is not changed").isEqualTo(sent);
    }

    @Test
    void shouldGiveEachAdmittedObservationItsOwnId() {
        JsonNode request = MAPPER.readTree(
                "{\"observations\":[" + observation("\"MET\"") + "," + observation("\"UNDETERMINED\"") + "]}");

        JsonNode admitted =
                PracticeRunnerLiveLlmTest.ProxyStandIn.admit(request).path("observations");

        assertThat(admitted.get(0).path("id").asString())
                .isNotEqualTo(admitted.get(1).path("id").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "1", "true", "{}", "\"\"", "\"met\"", "\"PASS\""})
    void shouldRefuseAnOutcomeThatNamesNoOutcome(String outcome) {
        JsonNode request = request(observation(outcome));

        assertThatThrownBy(() -> PracticeRunnerLiveLlmTest.ProxyStandIn.admit(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseAnObservationWithoutAnOutcome() {
        JsonNode request = request("{\"practiceSlug\":\"handles-errors\",\"evidence\":{\"citations\":[]}}");

        assertThatThrownBy(() -> PracticeRunnerLiveLlmTest.ProxyStandIn.admit(request))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

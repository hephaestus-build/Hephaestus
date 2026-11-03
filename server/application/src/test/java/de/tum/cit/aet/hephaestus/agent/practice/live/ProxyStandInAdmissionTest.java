package de.tum.cit.aet.hephaestus.agent.practice.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("unit")
class ProxyStandInAdmissionTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final UUID REVIEW = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID JOB = UUID.fromString("20000000-0000-4000-8000-000000000001");

    private static JsonNode request(String... entries) {
        return MAPPER.readTree("{\"contractVersion\":3,\"reviewId\":\"" + REVIEW + "\",\"jobId\":\"" + JOB
                + "\",\"attempt\":0,\"nonResults\":[],\"results\":[" + String.join(",", entries) + "]}");
    }

    private static String entry(String slug, String outcome) {
        return "{\"practiceSlug\":\"" + slug + "\",\"itemId\":\"1\",\"itemVersion\":\"fixture:1\",\"readReceipts\":[],"
                + "\"candidate\":{\"outcome\":\"" + outcome
                + "\",\"summary\":\"Handles errors\",\"explanation\":\"The handler records the failure.\","
                + "\"citations\":[],\"evidence\":{\"openQuestions\":[]},\"coverage\":{\"source\":\"host\",\"complete\":true,\"reads\":0,\"truncatedReads\":0,\"failedReads\":0,\"receipts\":[]}}}";
    }

    @Test
    void shouldAnswerAnEmptySealFromTheStagedPractices() {
        var staged = new ArrayList<JsonNode>();
        byte[] sent = "staged".getBytes(StandardCharsets.UTF_8);

        var first = PracticeRunnerLiveLlmTest.ProxyStandIn.stage(request(entry("first", "MET")), sent, staged);
        PracticeRunnerLiveLlmTest.ProxyStandIn.stage(request(entry("second", "NOT_MET")), sent, staged);
        JsonNode seal = MAPPER.readTree(
                "{\"contractVersion\":3,\"reviewId\":\"" + REVIEW + "\",\"jobId\":\"" + JOB + "\",\"attempt\":0}");
        var answer = PracticeRunnerLiveLlmTest.ProxyStandIn.admit(
                PracticeRunnerLiveLlmTest.ProxyStandIn.sealed(seal, staged));

        assertThat(first.path("practiceSlug").asString()).isEqualTo("first");
        assertThat(first.path("digest").asString()).matches("[0-9a-f]{64}");
        assertThat(MAPPER.treeToValue(answer, AdmissionResponseDTO.class).admitted())
                .hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(ResultOutcome.class)
    void shouldSerializeTheCurrentResponseDtoWithoutChangingTheRequest(ResultOutcome outcome) {
        JsonNode request = request(entry("handles-errors", outcome.name()));
        JsonNode original = request.deepCopy();

        var answer = PracticeRunnerLiveLlmTest.ProxyStandIn.admit(request);
        var response = MAPPER.treeToValue(answer, AdmissionResponseDTO.class);

        assertThat(response.contractVersion()).isEqualTo(AdmissionResponseDTO.CONTRACT_VERSION);
        assertThat(response.admissionDigest()).matches("[0-9a-f]{64}");
        assertThat(response.reviewId()).isEqualTo(REVIEW);
        assertThat(response.jobId()).isEqualTo(JOB);
        assertThat(response.admitted()).singleElement().satisfies(result -> {
            assertThat(result.resultId()).isNotNull();
            assertThat(result.itemId()).isEqualTo("1");
            assertThat(result.outcome()).isEqualTo(outcome);
            assertThat(result.problems()).isEmpty();
        });
        assertThat(answer.has("schemaVersion")).isFalse();
        assertThat(answer.path("admitted").get(0).has("severity")).isFalse();
        assertThat(request).isEqualTo(original);
    }

    @Test
    void shouldPreserveCandidateCitationsInTheAdmittedEvidence() {
        var request = request(entry("handles-errors", "MET"));
        var candidate = (ObjectNode) request.path("results").get(0).path("candidate");
        candidate
                .putArray("citations")
                .addObject()
                .put("sourceKind", "scm.pull-request.core")
                .put("sourcePath", "context/metadata.json")
                .put("path", "title")
                .put("startLine", 1)
                .put("endLine", 1)
                .put("quote", "Handles errors");

        var answer = PracticeRunnerLiveLlmTest.ProxyStandIn.admit(request);
        var citation = answer.path("admitted")
                .get(0)
                .path("evidence")
                .path("citations")
                .get(0);

        assertThat(citation.path("sourceKind").asString()).isEqualTo("scm.pull-request.core");
        assertThat(citation.path("sourcePath").asString()).isEqualTo("context/metadata.json");
        assertThat(citation.path("quote").asString()).isEqualTo("Handles errors");
        assertThat(citation.path("index").asInt()).isZero();
    }

    @Test
    void shouldGiveEachProjectedResultItsOwnId() {
        var answer = PracticeRunnerLiveLlmTest.ProxyStandIn.admit(
                request(entry("handles-errors", "MET"), entry("names-things", "COULD_NOT_DETERMINE")));
        var response = MAPPER.treeToValue(answer, AdmissionResponseDTO.class);

        assertThat(response.admitted().get(0).resultId())
                .isNotEqualTo(response.admitted().get(1).resultId());
    }

    @Test
    void shouldRejectAnOutcomeOutsideTheCurrentContract() {
        assertThatThrownBy(() -> PracticeRunnerLiveLlmTest.ProxyStandIn.admit(request(entry("handles-errors", "PASS"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

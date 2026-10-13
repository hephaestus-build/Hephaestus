package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.ObservationHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.RecentAuthoredWorkContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.ReviewAttemptsContentSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class MentorTurnEvidenceTest extends BaseUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldIndexEachRowByNumberTitleStateDateAndResourceAndCarryNoContent() {
        String authored = """
                {"pullRequests":[{"artifactId":11,"number":21,"title":"Grade the essay","state":"OPEN",
                  "updatedAt":"2026-10-01T12:00:00Z","url":"https://github.com/heph-labs/billing-service/pull/21",
                  "resource":"inputs/context/merge_readiness/11.json",
                  "body":"BODY-NOT-IN-INDEX","labels":["LABEL-NOT-IN-INDEX"]}],
                 "issues":[{"number":4,"title":"Write the guide","state":"CLOSED","updatedAt":"2026-09-30T08:00:00Z",
                  "resource":"inputs/context/issue/40.json"}]}
                """;
        String observations = """
                {"recentObservations":[{"id":"0b6c1f0e-8d3a-4c51-9a4e-2f7d5c9e1a33",
                  "resource":"inputs/context/observations_history/0b6c1f0e-8d3a-4c51-9a4e-2f7d5c9e1a33.json",
                  "practiceSlug":"describe-why","outcome":"NOT_MET","observedAt":"2026-10-01T11:00:00Z",
                  "summary":"SUMMARY-NOT-IN-INDEX","work":{"number":21,"title":"Grade the essay"},
                  "reviewedWork":{"coreCoverage":"UNKNOWN"}}]}
                """;

        JsonNode index = index(Map.of(
                RecentAuthoredWorkContentSource.OUTPUT_KEY, authored,
                ObservationHistoryContentSource.OUTPUT_KEY, observations));

        assertThat(index.path("pullRequests").get(0).propertyNames())
                .containsExactly("number", "title", "state", "updatedAt", "url", "resource");
        assertThat(index.path("pullRequests").get(0).path("url").asString())
                .isEqualTo("https://github.com/heph-labs/billing-service/pull/21");
        assertThat(index.path("issues").get(0).has("url"))
                .as("no url was stored")
                .isFalse();
        assertThat(index.path("pullRequests").get(0).path("resource").asString())
                .isEqualTo("inputs/context/merge_readiness/11.json");
        assertThat(index.path("issues").get(0).path("resource").asString()).isEqualTo("inputs/context/issue/40.json");
        JsonNode observation = index.path("observations").get(0);
        assertThat(observation.path("practiceSlug").asString()).isEqualTo("describe-why");
        assertThat(observation.path("workNumber").asInt()).isEqualTo(21);
        assertThat(index.toString()).doesNotContain("NOT-IN-INDEX", "reviewedWork");
        assertThat(index.path("use").asString()).contains("Fetch");
    }

    @Test
    void shouldNameAMissingOrMalformedSourceAndKeepTheOthers() {
        JsonNode index = index(Map.of(
                ObservationHistoryContentSource.OUTPUT_KEY, "not json", ReviewAttemptsContentSource.OUTPUT_KEY, """
                {"attempts":[{"number":21,"title":"Grade the essay","status":"COMPLETED",
                  "completedAt":"2026-10-01T12:00:00Z","reviewsResource":"inputs/context/review_attempts/issue/21.json",
                  "triggerMode":"AUTO"}]}
                """));

        assertThat(index.path("unavailable").valueStream().map(JsonNode::asString))
                .containsExactly(
                        RecentAuthoredWorkContentSource.OUTPUT_KEY, ObservationHistoryContentSource.OUTPUT_KEY);
        assertThat(index.path("reviewAttempts").get(0).path("reviewsResource").asString())
                .isEqualTo("inputs/context/review_attempts/issue/21.json");
        assertThat(index.path("reviewAttempts").get(0).has("triggerMode")).isFalse();
    }

    @Test
    void shouldStayWithinAboutTwoThousandTokensOnALargeWorkspaceAndCountWhatItLeavesOut() {
        ObjectNode authored = mapper.createObjectNode();
        rows(authored.putArray("pullRequests"), 30, "inputs/context/merge_readiness/%d.json");
        rows(authored.putArray("issues"), 30, "inputs/context/issue/%d.json");
        ObjectNode history = mapper.createObjectNode();
        ArrayNode observations = history.putArray("recentObservations");
        for (int i = 0; i < 60; i++) {
            String id = UUID.randomUUID().toString();
            observations
                    .addObject()
                    .put("id", id)
                    .put("resource", "inputs/context/observations_history/" + id + ".json")
                    .put("practiceSlug", "a-rather-long-practice-slug-" + i)
                    .put("outcome", "NOT_MET")
                    .put("observedAt", "2026-10-01T11:00:00Z")
                    .putObject("work")
                    .put("number", i);
        }
        ObjectNode attempts = mapper.createObjectNode();
        rows(attempts.putArray("attempts"), 20, "unused/%d");

        String receipt = MentorTurnEvidence.receipt(
                mapper,
                Map.of(
                        RecentAuthoredWorkContentSource.OUTPUT_KEY, bytes(authored),
                        ObservationHistoryContentSource.OUTPUT_KEY, bytes(history),
                        ReviewAttemptsContentSource.OUTPUT_KEY, bytes(attempts)));

        assertThat(receipt.length()).isLessThanOrEqualTo(MentorTurnEvidence.MAX_RECEIPT_CHARS);
        // About 3.5 characters a token for this JSON: within 2,000 tokens.
        assertThat(receipt.length() / 3.5).isLessThanOrEqualTo(2_000);
        JsonNode index = mapper.readTree(receipt);
        for (String list : new String[] {"pullRequests", "issues", "observations", "reviewAttempts"}) {
            assertThat(index.path(list).size()
                            + index.path("omitted").path(list).asInt())
                    .as("every row of %s is shown or counted", list)
                    .isEqualTo(
                            switch (list) {
                                case "pullRequests", "issues" -> 30;
                                case "observations" -> 60;
                                default -> 20;
                            });
        }
        assertThat(index.path("pullRequests").get(0).path("title").asString())
                .hasSize(80)
                .endsWith("…");
    }

    private void rows(ArrayNode rows, int count, String resource) {
        for (int i = 0; i < count; i++) {
            rows.addObject()
                    .put("number", i)
                    .put(
                            "title",
                            "A pull request title long enough to be cut short in the index of the turn, number " + i
                                    + " of many")
                    .put("state", "OPEN")
                    .put("status", "COMPLETED")
                    .put("updatedAt", "2026-10-01T12:00:00Z")
                    .put("completedAt", "2026-10-01T12:00:00Z")
                    .put("resource", resource.formatted(i))
                    .put("reviewsResource", "inputs/context/review_attempts/pull_request/" + i + ".json");
        }
    }

    private byte[] bytes(JsonNode node) {
        return mapper.writeValueAsBytes(node);
    }

    private JsonNode index(Map<String, String> inputs) {
        Map<String, byte[]> encoded = new HashMap<>();
        inputs.forEach((key, value) -> encoded.put(key, value.getBytes(StandardCharsets.UTF_8)));
        return mapper.readTree(MentorTurnEvidence.receipt(mapper, encoded));
    }
}

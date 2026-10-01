package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MergeReadinessContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.ObservationHistoryContentSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MentorTurnEvidenceTest extends BaseUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldPreserveCanonicalOutcomeAndCapturedWorkWithoutSourceProse() {
        String observations = """
                {"coverage":{"preparedAt":"2026-10-01T12:00:00Z","maxEntries":50},
                 "recentObservations":[{"id":"observation-id","resource":"observation-resource",
                  "reviewId":"producing-review","artifactKind":"PULL_REQUEST","artifactId":11,
                  "presence":"ABSENT","assessment":"GOOD","outcome":"NEGATIVE",
                  "summary":"SOURCE-PROSE-NOT-IN-RECEIPT","reviewedWork":{"producingReviewStatus":"FAILED","capturedAt":"2026-10-01T11:00:00Z",
                   "titleAndDescriptionCoverage":"DIFFERS_FROM_STORED_WORK","headCoverage":"MATCHES_STORED_WORK",
                   "coreCoverage":"DIFFERS_FROM_STORED_WORK","checkedFields":["title","description","head"],
                   "providerFreshness":"UNKNOWN"}}],"omittedForSize":{"earlierObservations":3}}
                """;
        String readiness = """
                {"readAt":"2026-10-01T12:01:00Z","providerFreshness":"UNKNOWN","pullRequests":[
                 {"artifactId":11,"headSha":"new-head","checks":"FAILURE","checksFor":"CURRENT_HEAD",
                  "description":"BODY-NOT-IN-RECEIPT","latestReviews":[{"reviewer":"service-account","bot":true,
                   "state":"APPROVED","commit":"recorded-associated-head","commitFor":"UNKNOWN","body":"COMMENT-NOT-IN-RECEIPT"}]}]}
                """;
        String receipt = MentorTurnEvidence.forRunner(
                mapper,
                Map.of(
                        ObservationHistoryContentSource.OUTPUT_KEY,
                        observations.getBytes(StandardCharsets.UTF_8),
                        MergeReadinessContentSource.OUTPUT_KEY,
                        readiness.getBytes(StandardCharsets.UTF_8),
                        "inputs/context/current_thread_history.json",
                        "PRIVATE-HISTORY".getBytes(StandardCharsets.UTF_8)));
        var parsed = mapper.readTree(receipt);
        var observation = parsed.path("observations").path("recentObservations").get(0);
        assertThat(observation).isNotNull();
        assertThat(observation.path("outcome").asString()).isEqualTo("NEGATIVE");
        assertThat(observation.path("reviewId").asString()).isEqualTo("producing-review");
        assertThat(observation
                        .path("reviewedWork")
                        .path("producingReviewStatus")
                        .asString())
                .isEqualTo("FAILED");
        assertThat(observation
                        .path("reviewedWork")
                        .path("titleAndDescriptionCoverage")
                        .asString())
                .isEqualTo("DIFFERS_FROM_STORED_WORK");
        assertThat(observation.path("reviewedWork").path("headCoverage").asString())
                .isEqualTo("MATCHES_STORED_WORK");
        assertThat(parsed.path("observations")
                        .path("omittedForSize")
                        .path("earlierObservations")
                        .asInt())
                .isEqualTo(3);
        assertThat(parsed.path("mergeReadiness")
                        .path("pullRequests")
                        .path(0)
                        .path("latestReviews")
                        .path(0)
                        .path("bot")
                        .asBoolean())
                .isTrue();
        var review = parsed.path("mergeReadiness")
                .path("pullRequests")
                .path(0)
                .path("latestReviews")
                .path(0);
        assertThat(review.path("commit").asString()).isEqualTo("recorded-associated-head");
        assertThat(review.path("commitFor").asString()).isEqualTo("UNKNOWN");
        assertThat(receipt).doesNotContain("SOURCE-PROSE", "BODY-NOT", "COMMENT-NOT", "PRIVATE-HISTORY");
        assertThat(parsed.path("providerFreshness").asString()).isEqualTo("UNKNOWN");
    }

    @Test
    void shouldKeepMissingAndMalformedSourcesUnknownWithoutDroppingAvailableEvidence() {
        var parsed = mapper.readTree(MentorTurnEvidence.forRunner(
                mapper,
                Map.of(
                        ObservationHistoryContentSource.OUTPUT_KEY,
                        "not-json".getBytes(StandardCharsets.UTF_8),
                        MergeReadinessContentSource.OUTPUT_KEY,
                        "{\"pullRequests\":[{\"artifactId\":11,\"checks\":\"UNKNOWN\"}]}"
                                .getBytes(StandardCharsets.UTF_8))));
        assertThat(parsed.path("observations").path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(parsed.path("authoredWorkIndex").path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(parsed.path("mergeReadiness")
                        .path("pullRequests")
                        .path(0)
                        .path("artifactId")
                        .asInt())
                .isEqualTo(11);
    }

    @Test
    void shouldOmitWholeRowsAndReportTheBoundWithoutTruncatingIdentities() {
        var observations = mapper.createObjectNode();
        var rows = observations.putArray("recentObservations");
        for (int i = 0; i < 50; i++) {
            rows.addObject()
                    .put("id", Integer.toString(i))
                    .put("resource", "r".repeat(1_000))
                    .put("outcome", "NEGATIVE");
        }
        observations.putArray("abstentions").addObject().put("id", "small-abstention");
        String receipt = MentorTurnEvidence.forRunner(
                mapper, Map.of(ObservationHistoryContentSource.OUTPUT_KEY, mapper.writeValueAsBytes(observations)));
        var selected = mapper.readTree(receipt).path("observations");
        assertThat(receipt.length()).isLessThan(40_000);
        assertThat(selected.path("recentObservations").size()
                        + selected.path("omittedFromReceipt")
                                .path("recentObservations")
                                .asInt())
                .isEqualTo(50);
        for (var row : selected.path("recentObservations")) {
            assertThat(row.path("resource").asString()).hasSize(1_000);
        }
        assertThat(selected.path("abstentions").path(0).path("id").asString()).isEqualTo("small-abstention");
    }
}

package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MergeReadinessContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.ObservationHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.RecentAuthoredWorkContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.ReviewAttemptsContentSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
    void shouldCarryRetainedReviewAttemptsAndTheirBoundsButNoOtherJobFields() {
        String attempts = """
                {"readAt":"2026-10-02T14:00:00Z","coverage":{"lookbackDays":90,"maxEntries":20,"hasMore":true},
                 "attempts":[{"reviewId":"failed-closure","artifactKind":"scm.issue","artifactId":21,"number":21,
                  "url":"https://gitlab.example/course/-/issues/21","state":"CLOSED",
                  "reviewsResource":"inputs/context/review_attempts/issue/21.json","status":"FAILED",
                  "triggerMode":"AUTO","createdAt":"2026-10-01T10:00:00Z","completedAt":"2026-10-01T10:05:00Z",
                  "errorMessage":"NOT-IN-RECEIPT"}]}
                """;
        String authored = """
                {"issues":[{"artifactId":21,"reviewsResource":"inputs/context/review_attempts/issue/21.json",
                  "number":21,"title":"TITLE-NOT-IN-RECEIPT"}]}
                """;
        var parsed = mapper.readTree(MentorTurnEvidence.forRunner(
                mapper,
                Map.of(
                        ReviewAttemptsContentSource.OUTPUT_KEY,
                        attempts.getBytes(StandardCharsets.UTF_8),
                        RecentAuthoredWorkContentSource.OUTPUT_KEY,
                        authored.getBytes(StandardCharsets.UTF_8))));

        var section = parsed.path("reviewAttempts");
        assertThat(section.path("resource").asString()).isEqualTo(ReviewAttemptsContentSource.OUTPUT_KEY);
        assertThat(section.path("coverage").path("hasMore").asBoolean()).isTrue();
        var attempt = section.path("attempts").path(0);
        assertThat(attempt.path("status").asString()).isEqualTo("FAILED");
        assertThat(attempt.path("reviewsResource").asString())
                .isEqualTo("inputs/context/review_attempts/issue/21.json");
        assertThat(attempt.has("errorMessage")).isFalse();
        assertThat(parsed.path("authoredWorkIndex")
                        .path("issues")
                        .path(0)
                        .path("reviewsResource")
                        .asString())
                .isEqualTo("inputs/context/review_attempts/issue/21.json");
        assertThat(parsed.toString()).doesNotContain("NOT-IN-RECEIPT");

        var unavailable = mapper.readTree(MentorTurnEvidence.forRunner(
                mapper,
                Map.of(
                        ReviewAttemptsContentSource.OUTPUT_KEY,
                        "{\"readAt\":\"2026-10-02T14:00:00Z\",\"status\":\"UNAVAILABLE\"}"
                                .getBytes(StandardCharsets.UTF_8))));
        assertThat(unavailable.path("reviewAttempts").path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.path("reviewAttempts").has("attempts")).isFalse();
        assertThat(mapper.readTree(MentorTurnEvidence.forRunner(mapper, Map.of()))
                        .path("reviewAttempts")
                        .path("status")
                        .asString())
                .isEqualTo("UNAVAILABLE");
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

    @Test
    void shouldFitFourFullSectionsWithinWhatTheRunnerAcceptsByOmittingWholeCountedRows() throws Exception {
        String url = "https://gitlab.example/" + "p".repeat(225);
        var readiness = mapper.createObjectNode();
        var pullRequests = readiness.putArray("pullRequests");
        for (int i = 0; i < 25; i++) {
            pullRequests
                    .addObject()
                    .put("artifactId", i)
                    .put("resource", MergeReadinessContentSource.resourceOf(i))
                    .put("url", url)
                    .put("headSha", "a".repeat(40))
                    .put("checksSha", "b".repeat(40))
                    .put("checks", "SUCCESS");
        }
        var observations = mapper.createObjectNode();
        for (String list : List.of("recentObservations", "abstentions", "earlierObservations")) {
            var rows = observations.putArray(list);
            for (int i = 0; i < 30; i++) {
                UUID id = UUID.nameUUIDFromBytes((list + i).getBytes(StandardCharsets.UTF_8));
                rows.addObject()
                        .put("id", id.toString())
                        .put("resource", ObservationHistoryContentSource.resourceOf(id))
                        .put("reviewId", UUID.randomUUID().toString())
                        .put("outcome", "UNDETERMINED")
                        .put("observedAt", "2026-10-01T10:00:00Z");
            }
        }
        var authored = mapper.createObjectNode();
        for (String list : List.of("pullRequests", "issues")) {
            var rows = authored.putArray(list);
            for (int i = 0; i < 20; i++) {
                rows.addObject().put("artifactId", i).put("number", i).put("url", url);
            }
        }
        var attempts = mapper.createObjectNode();
        attempts.putObject("coverage").put("maxEntries", 20).put("hasMore", true);
        var attemptRows = attempts.putArray("attempts");
        List<String> attemptIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String reviewId = UUID.nameUUIDFromBytes(("attempt" + i).getBytes(StandardCharsets.UTF_8))
                    .toString();
            attemptIds.add(reviewId);
            attemptRows
                    .addObject()
                    .put("reviewId", reviewId)
                    .put("artifactKind", "scm.issue")
                    .put("artifactId", 1_000_000_000_000L + i)
                    .put("number", i)
                    .put("url", url)
                    .put("state", "CLOSED")
                    .put(
                            "reviewsResource",
                            "inputs/context/review_attempts/issue/" + (1_000_000_000_000L + i) + ".json")
                    .put("status", "COMPLETED")
                    .put("triggerMode", "AUTO")
                    .put("createdAt", "2026-10-01T10:00:00Z")
                    .put("completedAt", "2026-10-01T10:05:00Z");
        }

        Map<String, byte[]> inputs = Map.of(
                MergeReadinessContentSource.OUTPUT_KEY, mapper.writeValueAsBytes(readiness),
                ObservationHistoryContentSource.OUTPUT_KEY, mapper.writeValueAsBytes(observations),
                RecentAuthoredWorkContentSource.OUTPUT_KEY, mapper.writeValueAsBytes(authored),
                ReviewAttemptsContentSource.OUTPUT_KEY, mapper.writeValueAsBytes(attempts));
        String receipt = MentorTurnEvidence.forRunner(mapper, inputs);

        assertThat(receipt.length()).isLessThanOrEqualTo(MentorTurnEvidence.MAX_RECEIPT_CHARS);
        var parsed = mapper.readTree(receipt);
        assertThat(parsed.has("status")).isFalse();
        Map<String, String> sourceOf = Map.of(
                "mergeReadiness", MergeReadinessContentSource.OUTPUT_KEY,
                "observations", ObservationHistoryContentSource.OUTPUT_KEY,
                "authoredWorkIndex", RecentAuthoredWorkContentSource.OUTPUT_KEY,
                "reviewAttempts", ReviewAttemptsContentSource.OUTPUT_KEY);
        Map<String, Map<String, Integer>> given = Map.of(
                "mergeReadiness", Map.of("pullRequests", 25),
                "observations", Map.of("recentObservations", 30, "abstentions", 30, "earlierObservations", 30),
                "authoredWorkIndex", Map.of("pullRequests", 20, "issues", 20),
                "reviewAttempts", Map.of("attempts", 20));
        int keptTogether = 0;
        int keptAlone = 0;
        for (var section : given.entrySet()) {
            var projected = parsed.path(section.getKey());
            String key = Objects.requireNonNull(sourceOf.get(section.getKey()));
            var alone = mapper.readTree(
                            MentorTurnEvidence.forRunner(mapper, Map.of(key, Objects.requireNonNull(inputs.get(key)))))
                    .path(section.getKey());
            for (var list : section.getValue().entrySet()) {
                int omitted =
                        projected.path("omittedFromReceipt").path(list.getKey()).asInt();
                assertThat(projected.path(list.getKey()).size() + omitted)
                        .as("%s.%s keeps or counts every row", section.getKey(), list.getKey())
                        .isEqualTo(list.getValue());
                keptTogether += projected.path(list.getKey()).size();
                keptAlone += alone.path(list.getKey()).size();
            }
        }
        assertThat(keptTogether)
                .as("the four sections together keep fewer rows than each keeps alone, so the whole receipt was fitted")
                .isLessThan(keptAlone);
        var keptAttempts = parsed.path("reviewAttempts").path("attempts");
        assertThat(keptAttempts.valueStream().map(row -> row.path("reviewId").asString()))
                .containsExactlyElementsOf(attemptIds.subList(0, keptAttempts.size()));
        assertThat(parsed.path("reviewAttempts")
                        .path("coverage")
                        .path("hasMore")
                        .asBoolean())
                .isTrue();
        for (var row : parsed.path("observations").path("recentObservations")) {
            assertThat(row.path("resource").asString())
                    .isEqualTo(ObservationHistoryContentSource.resourceOf(
                            UUID.fromString(row.path("id").asString())));
        }
        assertThat(MentorTurnEvidence.MAX_RECEIPT_CHARS).isEqualTo(40_000);
        assertThat(Files.readString(
                        Path.of("src", "main", "resources", "agent", "pi-mentor-runner.ts"), StandardCharsets.UTF_8))
                .as("the runner refuses a longer receipt before the turn starts")
                .contains("params.currentEvidence.length > 40_000");
    }

    @Test
    void shouldReportAReceiptWhoseMetadataAloneCannotFitAsUnavailable() {
        var attempts = mapper.createObjectNode();
        attempts.putObject("coverage").put("scope", "s".repeat(MentorTurnEvidence.MAX_RECEIPT_CHARS));
        attempts.putArray("attempts").addObject().put("reviewId", "kept-out");

        String receipt = MentorTurnEvidence.forRunner(
                mapper, Map.of(ReviewAttemptsContentSource.OUTPUT_KEY, mapper.writeValueAsBytes(attempts)));

        assertThat(receipt.length()).isLessThanOrEqualTo(MentorTurnEvidence.MAX_RECEIPT_CHARS);
        var parsed = mapper.readTree(receipt);
        assertThat(parsed.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(parsed.path("providerFreshness").asString()).isEqualTo("UNKNOWN");
        assertThat(receipt).doesNotContain("kept-out");
    }
}

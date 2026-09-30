package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ObservationHistoryContentSourceTest extends BaseUnitTest {

    @Mock
    UserRepository userRepository;

    @Mock
    ObservationRepository observationRepository;

    @Mock
    MentorContextQueryRepository queryRepository;

    @Mock
    ConversationConsentGate conversationConsentGate;

    @Mock
    ObservationVisibilityPolicy visibilityPolicy;

    @Mock
    ReviewedWorkCoverage reviewedWorkCoverage;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    ObservationHistoryContentSource provider;

    private static final List<String> VERDICTS = List.of("ASSESSED");
    private static final List<String> ABSTENTIONS = List.of("NOT_APPLICABLE", "UNDETERMINED");

    @BeforeEach
    void authorizeObservations() {
        lenient().when(reviewedWorkCoverage.of(anyLong(), any())).thenAnswer(invocation -> {
            Map<UUID, ObjectNode> nodes = new HashMap<>();
            for (Observation observation : invocation.<java.util.Collection<Observation>>getArgument(1)) {
                nodes.put(observation.getId(), objectMapper.createObjectNode().put("coreCoverage", "UNKNOWN"));
            }
            return nodes;
        });
        lenient()
                .when(observationRepository.findRecentByDeveloperAndWorkspace(
                        any(), any(), any(), eq(ABSTENTIONS), any()))
                .thenReturn(List.of());
        lenient()
                .when(visibilityPolicy.permitsAll(anyLong(), any(), eq(SourceUsePurpose.CONVERSATIONAL_MENTORING)))
                .thenAnswer(invocation -> {
                    Collection<Observation> batch = invocation.getArgument(1);
                    return batch.stream().map(Observation::getId).collect(Collectors.toSet());
                });
    }

    @Test
    void emptyDefaults() throws Exception {
        User user = new User();
        user.setLogin("octo");
        when(userRepository.findById(eq(2L))).thenReturn(Optional.of(user));
        when(observationRepository.findRecentByDeveloperAndWorkspace(
                        eq(2L), eq(1L), any(Instant.class), eq(VERDICTS), any(Pageable.class)))
                .thenReturn(List.of());
        when(queryRepository.findReviewsReceivedSince(eq(1L), eq(2L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID()), files);

        byte[] bytes = files.get("inputs/context/observations_history.json");
        assertThat(bytes).isNotNull();
        JsonNode root = objectMapper.readTree(bytes);
        assertThat(root.get("user").get("login").asString()).isEqualTo("octo");
        assertThat(root.get("summary").get("includedObservations").asLong()).isEqualTo(0L);
        // All presence states present even when count is 0 — keeps the wire shape stable.
        for (Presence v : Presence.values()) {
            assertThat(root.get("summary").get("byPresence").has(v.name())).isTrue();
        }
        for (Severity s : Severity.values()) {
            assertThat(root.get("summary").get("bySeverity").has(s.name())).isTrue();
        }
        assertThat(root.get("recentObservations").isArray()).isTrue();
        assertThat(root.get("reviewsReceived").isArray()).isTrue();
    }

    @Test
    void unauthorizedObservationIsAbsentFromHistoryAndSummary() throws Exception {
        User user = new User();
        user.setLogin("octo");
        Observation observation = Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(observationRepository.findRecentByDeveloperAndWorkspace(
                        eq(2L), eq(1L), any(Instant.class), eq(VERDICTS), any(Pageable.class)))
                .thenReturn(List.of(observation));
        when(queryRepository.findReviewsReceivedSince(eq(1L), eq(2L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        when(visibilityPolicy.permitsAll(1L, List.of(observation), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Set.of());

        ObjectNode root = provider.buildPayload(1L, 2L);

        assertThat(root.path("recentObservations")).isEmpty();
        assertThat(root.path("summary").path("includedObservations").asInt()).isZero();
    }

    @Test
    @DisplayName("raw rubric-voiced reasoning is scrubbed before its detail reaches the mentor")
    void rubricVoicedReasoningIsScrubbed() throws Exception {
        var practice = new Practice();
        practice.setSlug("robust-error-handling");
        // A real student-facing sentence followed by a pure grading-mechanics sentence the detector echoed.
        String reasoning =
                "The retry block swallows the IOException without logging it. The assessment is BAD, capped at MINOR.";
        var observation = Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .summary("Swallowed IOException")
                .practice(practice)
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(Presence.PRESENT)
                .assessment(Assessment.BAD)
                .severity(Severity.MINOR)
                .observedAt(Instant.now())
                .evidenceRationale(reasoning)
                .build();

        when(observationRepository.findRecentByDeveloperAndWorkspace(
                        eq(2L), eq(1L), any(Instant.class), eq(VERDICTS), any(Pageable.class)))
                .thenReturn(List.of(observation));
        JsonNode detail = provider.inspect(1L, 2L, observation.getId());
        String shipped = detail.get("observation").get("evidenceRationale").asString();
        // The student-facing sentence survives; the rubric mechanics ("assessment is BAD", "capped at MINOR")
        // do NOT reach the mentor.
        assertThat(shipped).contains("swallows the IOException");
        assertThat(shipped).doesNotContain("assessment is BAD");
        assertThat(shipped).doesNotContain("capped at MINOR");
    }

    @Test
    @DisplayName("rows: (presence,assessment) matrix nulls + populated reviewsReceived row")
    void recentObservationsAndReviewsPopulated() throws Exception {
        User user = new User();
        user.setLogin("octo");
        when(userRepository.findById(eq(2L))).thenReturn(Optional.of(user));

        var practiceBad = new Practice();
        practiceBad.setSlug("robust-error-handling");
        Instant observedBad = Instant.parse("2025-06-10T08:00:00Z");
        var badObservation = Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .summary("Swallowed IOException")
                .practice(practiceBad)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(123L)
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(Presence.PRESENT)
                .assessment(Assessment.BAD)
                .severity(Severity.MAJOR)
                .observedAt(observedBad)
                .evidence(
                        objectMapper.readTree(
                                "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\",\"artifactPath\":\"inputs/context/diff.patch\",\"path\":\"src/Retry.java\",\"side\":\"NEW\",\"startLine\":42,\"endLine\":42,\"quote\":\"catch (IOException ignored) {}\",\"quoteRedacted\":false}]}"))
                .evidenceRationale("The retry block swallows the IOException.")
                .build();

        var practiceNa = new Practice();
        practiceNa.setSlug("writes-tests");
        Instant observedNa = Instant.parse("2025-06-09T08:00:00Z");
        // NOT_APPLICABLE: assessment AND severity are null — must serialise as JSON null, not the enum name.
        var naObservation = Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .summary("No test surface")
                .practice(practiceNa)
                .assessmentStatus(AssessmentStatus.NOT_APPLICABLE)
                .presence(null)
                .assessment(null)
                .severity(null)
                .observedAt(observedNa)
                .evidenceRationale("Docs-only change.")
                .build();

        when(observationRepository.findRecentByDeveloperAndWorkspace(
                        eq(2L), eq(1L), any(Instant.class), eq(VERDICTS), any(Pageable.class)))
                .thenReturn(List.of(badObservation));
        when(observationRepository.findRecentByDeveloperAndWorkspace(
                        eq(2L), eq(1L), any(Instant.class), eq(ABSTENTIONS), any(Pageable.class)))
                .thenReturn(List.of(naObservation));

        var pr = new PullRequest();
        pr.setNumber(42);
        pr.setTitle("Add retry");
        var review = new PullRequestReview();
        review.setPullRequest(pr);
        var reviewer = new User();
        reviewer.setLogin("mentor-bot");
        review.setAuthor(reviewer);
        review.setState(PullRequestReview.State.CHANGES_REQUESTED);
        review.setBody("Please add a test.");
        review.setHtmlUrl("https://example.test/pr/42#review");
        review.setSubmittedAt(Instant.parse("2025-06-11T12:00:00Z"));
        when(queryRepository.findReviewsReceivedSince(eq(1L), eq(2L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(review));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID()), files);

        JsonNode root = objectMapper.readTree(files.get("inputs/context/observations_history.json"));

        JsonNode obs = root.get("recentObservations");
        assertThat(obs).hasSize(1);
        JsonNode bad = obs.get(0);
        assertThat(bad.get("practiceSlug").asString()).isEqualTo("robust-error-handling");
        assertThat(bad.get("summary").asString()).isEqualTo("Swallowed IOException");
        assertThat(bad.get("presence").asString()).isEqualTo("PRESENT");
        assertThat(bad.get("outcome").asString()).isEqualTo("NEGATIVE");
        assertThat(bad.has("assessment")).isFalse();
        assertThat(bad.get("severity").asString()).isEqualTo("MAJOR");
        assertThat(bad.get("observedAt").asString()).isEqualTo(observedBad.toString());
        assertThat(bad.get("artifactKind").asString()).isEqualTo("scm.pull_request");
        assertThat(bad.get("artifactId").asLong()).isEqualTo(123L);
        assertThat(bad.has("evidence")).isFalse();
        assertThat(bad.has("evidenceRationale")).isFalse();
        assertThat(root.get("detail").get("loaded").asBoolean()).isFalse();
        assertThat(root.get("detail").get("path").asString())
                .isEqualTo("inputs/context/observations_history/<id>.json");

        JsonNode badDetail = provider.inspect(1L, 2L, badObservation.getId());
        assertThat(badDetail.get("list").asString()).isEqualTo("recentObservations");
        assertThat(badDetail
                        .get("observation")
                        .get("reviewedWork")
                        .get("coreCoverage")
                        .asString())
                .isEqualTo("UNKNOWN");
        JsonNode citation =
                badDetail.get("observation").get("evidence").get("citations").get(0);
        assertThat(citation.get("path").asString()).isEqualTo("src/Retry.java");
        assertThat(citation.get("quote").asString()).contains("IOException");
        assertThat(badDetail.get("observation").get("evidenceRationale").asString())
                .isEqualTo("The retry block swallows the IOException.");
        assertThat(provider.inspect(1L, 2L, naObservation.getId()).get("list").asString())
                .isEqualTo("abstentions");

        assertThat(root.get("abstentions")).hasSize(1);
        JsonNode na = root.get("abstentions").get(0);
        assertThat(na.get("assessmentStatus").asString()).isEqualTo("NOT_APPLICABLE");
        // outcome/severity must be JSON null (not the string "null", not absent).
        assertThat(na.get("outcome").isNull()).isTrue();
        assertThat(na.get("severity").isNull()).isTrue();

        JsonNode reviews = root.get("reviewsReceived");
        assertThat(reviews).hasSize(1);
        JsonNode r0 = reviews.get(0);
        assertThat(r0.get("prNumber").asInt()).isEqualTo(42);
        assertThat(r0.get("prTitle").asString()).isEqualTo("Add retry");
        assertThat(r0.get("reviewer").asString()).isEqualTo("mentor-bot");
        assertThat(r0.get("state").asString()).isEqualTo("CHANGES_REQUESTED");
        assertThat(r0.get("hasComment").asBoolean()).isTrue();
        assertThat(r0.get("submittedAt").asString()).isEqualTo("2025-06-11T12:00:00Z");
    }

    @Test
    @DisplayName("an overview of many evidence-heavy rows fits its bound and keeps every row, without evidence")
    void overviewOfEvidenceHeavyRowsKeepsEveryRow() {
        givenDeveloper();
        List<Observation> verdicts = new java.util.ArrayList<>();
        List<Observation> abstentions = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            verdicts.add(
                    observation(AssessmentStatus.ASSESSED, "Summary " + i, citations(1, 6_000), "r".repeat(1_500)));
        }
        for (int i = 0; i < 20; i++) {
            abstentions.add(observation(
                    AssessmentStatus.NOT_APPLICABLE, "Summary " + i, citations(1, 6_000), "r".repeat(1_500)));
        }
        givenHistory(verdicts, abstentions);

        ObjectNode root = provider.buildPayload(1L, 2L);

        assertThat(objectMapper.writeValueAsString(root))
                .hasSizeLessThanOrEqualTo(ObservationHistoryContentSource.OVERVIEW_MAX_CHARS);
        assertThat(ids(root.get("recentObservations"))).isEqualTo(ids(verdicts));
        assertThat(ids(root.get("abstentions"))).isEqualTo(ids(abstentions));
        assertThat(root.findValues("evidence")).isEmpty();
        assertThat(root.findValues("evidenceRationale")).isEmpty();
        assertThat(root.has("omittedForSize")).isFalse();
    }

    @Test
    @DisplayName("an overview too large leaves out the longest summaries, marked, before any row")
    void overviewLeavesOutSummariesBeforeRows() {
        givenDeveloper();
        List<Observation> verdicts = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            verdicts.add(observation(AssessmentStatus.ASSESSED, "s".repeat(1_000 + i), citations(1, 10), "r"));
        }
        givenHistory(verdicts, List.of());

        ObjectNode root = provider.buildPayload(1L, 2L);

        assertThat(objectMapper.writeValueAsString(root))
                .hasSizeLessThanOrEqualTo(ObservationHistoryContentSource.OVERVIEW_MAX_CHARS);
        assertThat(ids(root.get("recentObservations"))).isEqualTo(ids(verdicts));
        List<JsonNode> leftOut = root.get("recentObservations")
                .valueStream()
                .filter(row -> row.path("summaryNotLoaded").asBoolean(false))
                .toList();
        assertThat(leftOut)
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.get("summary").isNull()).isTrue());
        // The longest go first: the newest summary here is the longest.
        assertThat(root.get("recentObservations")
                        .get(49)
                        .path("summaryNotLoaded")
                        .asBoolean(false))
                .isTrue();
        assertThat(root.has("omittedForSize")).isFalse();
    }

    @Test
    @DisplayName("an admissible detail near its limits shortens its longest texts, says so, and keeps every location")
    void detailShortensAnOversizedQuoteButKeepsItsSource() {
        // Admission's own bounds: evidence at most 64 KiB, rationale at most 10,000 characters.
        String quote = "q".repeat(60_000);
        String rationale = "r".repeat(10_000);
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.putArray("citations").add(citation("src/Big.java", quote)).add(citation("src/Small.java", "ok()"));
        Observation observation = observation(AssessmentStatus.ASSESSED, "Big quote", evidence, rationale);
        givenHistory(List.of(observation), List.of());

        ObjectNode detail = provider.inspect(1L, 2L, observation.getId());

        assertThat(objectMapper.writeValueAsString(detail))
                .hasSizeLessThanOrEqualTo(ObservationHistoryContentSource.DETAIL_MAX_CHARS);
        JsonNode big =
                detail.get("observation").get("evidence").get("citations").get(0);
        assertThat(big.get("quoteTruncated").asBoolean()).isTrue();
        assertThat(big.get("quoteChars").asInt()).isEqualTo(60_000);
        assertThat(quote).startsWith(big.get("quote").asString());
        assertThat(big.get("path").asString()).isEqualTo("src/Big.java");
        assertThat(big.get("startLine").asInt()).isEqualTo(3);
        assertThat(big.get("endLine").asInt()).isEqualTo(9);
        JsonNode small =
                detail.get("observation").get("evidence").get("citations").get(1);
        assertThat(small.get("quote").asString()).isEqualTo("ok()");
        assertThat(small.has("quoteTruncated")).isFalse();
        JsonNode row = detail.get("observation");
        assertThat(rationale).startsWith(row.get("evidenceRationale").asString());
        assertThat(row.path("evidenceRationaleTruncated").asBoolean(false))
                .isEqualTo(row.get("evidenceRationale").asString().length() < 10_000);
        assertThat(detail.has("status")).isFalse();
    }

    @Test
    @DisplayName("a detail whose citations cannot fit leaves its evidence out and says so")
    void detailThatCannotFitSaysSo() {
        Observation observation =
                // About 64 KiB of citations, as many as admission accepts, each with a short quote.
                observation(AssessmentStatus.ASSESSED, "Many citations", citations(350, 10), "Because.");
        givenHistory(List.of(observation), List.of());

        ObjectNode detail = provider.inspect(1L, 2L, observation.getId());

        assertThat(objectMapper.writeValueAsString(detail))
                .hasSizeLessThanOrEqualTo(ObservationHistoryContentSource.DETAIL_MAX_CHARS);
        assertThat(detail.get("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(detail.get("observation").get("id").asString())
                .isEqualTo(observation.getId().toString());
        assertThat(detail.get("observation").get("evidenceLoaded").asBoolean()).isFalse();
        assertThat(detail.get("observation").has("evidence")).isFalse();
    }

    @Test
    @DisplayName("a detail answers alike for an unknown id and one this conversation may not use")
    void detailOfAnUnlistedObservationIsNotFound() {
        Observation withheld = observation(AssessmentStatus.ASSESSED, "Hidden", citations(1, 10), "r");
        givenHistory(List.of(withheld), List.of());
        when(visibilityPolicy.permitsAll(1L, List.of(withheld), SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Set.of());

        ObjectNode forbidden = provider.inspect(1L, 2L, withheld.getId());
        ObjectNode unknown = provider.inspect(1L, 2L, UUID.randomUUID());

        assertThat(forbidden.get("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(forbidden.has("observation")).isFalse();
        forbidden.remove("readAt");
        unknown.remove("readAt");
        assertThat(forbidden).isEqualTo(unknown);
    }

    private void givenDeveloper() {
        User user = new User();
        user.setLogin("octo");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
    }

    private void givenHistory(List<Observation> verdicts, List<Observation> abstentions) {
        when(observationRepository.findRecentByDeveloperAndWorkspace(
                        eq(2L), eq(1L), any(Instant.class), eq(VERDICTS), any(Pageable.class)))
                .thenReturn(verdicts);
        lenient()
                .when(observationRepository.findRecentByDeveloperAndWorkspace(
                        eq(2L), eq(1L), any(Instant.class), eq(ABSTENTIONS), any(Pageable.class)))
                .thenReturn(abstentions);
    }

    private Observation observation(AssessmentStatus status, String summary, JsonNode evidence, String rationale) {
        var practice = new Practice();
        practice.setSlug("practice-" + UUID.randomUUID());
        boolean assessed = status == AssessmentStatus.ASSESSED;
        return Observation.builder()
                .id(UUID.randomUUID())
                .agentJobId(UUID.randomUUID())
                .summary(summary)
                .practice(practice)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(7L)
                .assessmentStatus(status)
                .presence(assessed ? Presence.PRESENT : null)
                .assessment(assessed ? Assessment.BAD : null)
                .severity(assessed ? Severity.MINOR : null)
                .observedAt(Instant.parse("2025-06-10T08:00:00Z"))
                .evidence(evidence)
                .evidenceRationale(rationale)
                .build();
    }

    private ObjectNode citations(int count, int quoteChars) {
        ObjectNode evidence = objectMapper.createObjectNode();
        ArrayNode citations = evidence.putArray("citations");
        for (int i = 0; i < count; i++) {
            citations.add(citation("src/File" + i + ".java", "c".repeat(quoteChars)));
        }
        return evidence;
    }

    private ObjectNode citation(String path, String quote) {
        return objectMapper
                .createObjectNode()
                .put("sourceKind", "scm.pull-request.diff")
                .put("artifactPath", "inputs/context/diff.patch")
                .put("path", path)
                .put("side", "NEW")
                .put("startLine", 3)
                .put("endLine", 9)
                .put("quote", quote)
                .put("quoteRedacted", false);
    }

    private static List<String> ids(JsonNode rows) {
        return rows.valueStream().map(row -> row.get("id").asString()).toList();
    }

    private static List<String> ids(List<Observation> rows) {
        return rows.stream().map(o -> o.getId().toString()).toList();
    }

    @Test
    @DisplayName("received reviews with the longest titles are left out oldest first, counted, and the whole fits")
    void overviewLeavesOutTheOldestReceivedReviewsCounted() {
        givenDeveloper();
        List<Observation> verdicts = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            verdicts.add(observation(AssessmentStatus.ASSESSED, "Summary " + i, citations(1, 10), "r"));
        }
        givenHistory(verdicts, List.of());
        List<PullRequestReview> reviews = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            var pr = new PullRequest();
            pr.setNumber(i);
            // A stored title may be 1,024 characters, and each quote doubles when escaped.
            pr.setTitle("\"".repeat(1_024));
            var review = new PullRequestReview();
            review.setPullRequest(pr);
            review.setHtmlUrl("https://example.test/pr/" + i);
            review.setSubmittedAt(Instant.parse("2025-06-11T12:00:00Z").minusSeconds(i));
            reviews.add(review);
        }
        when(queryRepository.findReviewsReceivedSince(eq(1L), eq(2L), any(Instant.class), any(Pageable.class)))
                .thenReturn(reviews);

        ObjectNode root = provider.buildPayload(1L, 2L);

        assertThat(objectMapper.writeValueAsString(root))
                .hasSizeLessThanOrEqualTo(ObservationHistoryContentSource.OVERVIEW_MAX_CHARS);
        assertThat(ids(root.get("recentObservations"))).isEqualTo(ids(verdicts));
        int shown = root.get("reviewsReceived").size();
        assertThat(shown).isLessThan(20);
        assertThat(root.get("omittedForSize").get("reviewsReceived").asInt()).isEqualTo(20 - shown);
        assertThat(root.get("reviewsReceived")
                        .valueStream()
                        .map(r -> r.get("prNumber").asInt()))
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, shown).boxed().toList());
    }
}

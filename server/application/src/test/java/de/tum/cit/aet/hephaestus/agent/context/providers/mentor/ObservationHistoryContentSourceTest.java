package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    ObservationHistoryContentSource provider;

    @BeforeEach
    void authorizeObservations() {
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
                        eq(2L), eq(1L), any(Instant.class), anyBoolean(), any(Pageable.class)))
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
        Observation observation = Observation.builder().id(UUID.randomUUID()).build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(observationRepository.findRecentByDeveloperAndWorkspace(
                        eq(2L), eq(1L), any(Instant.class), anyBoolean(), any(Pageable.class)))
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
    @DisplayName("raw rubric-voiced reasoning is scrubbed before it reaches the mentor")
    void rubricVoicedReasoningIsScrubbed() throws Exception {
        User user = new User();
        user.setLogin("octo");
        when(userRepository.findById(eq(2L))).thenReturn(Optional.of(user));

        var practice = new Practice();
        practice.setSlug("robust-error-handling");
        // A real student-facing sentence followed by a pure grading-mechanics sentence the detector echoed.
        String reasoning =
                "The retry block swallows the IOException without logging it. The assessment is BAD, capped at MINOR.";
        var observation = Observation.builder()
                .id(UUID.randomUUID())
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
                        eq(2L), eq(1L), any(Instant.class), anyBoolean(), any(Pageable.class)))
                .thenReturn(List.of(observation));
        when(queryRepository.findReviewsReceivedSince(eq(1L), eq(2L), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID()), files);

        JsonNode root = objectMapper.readTree(files.get("inputs/context/observations_history.json"));
        String shipped =
                root.get("recentObservations").get(0).get("evidenceRationale").asString();
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
                        eq(2L), eq(1L), any(Instant.class), anyBoolean(), any(Pageable.class)))
                .thenReturn(List.of(badObservation, naObservation));

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
        assertThat(obs).hasSize(2);
        JsonNode bad = obs.get(0);
        assertThat(bad.get("practiceSlug").asString()).isEqualTo("robust-error-handling");
        assertThat(bad.get("summary").asString()).isEqualTo("Swallowed IOException");
        assertThat(bad.get("presence").asString()).isEqualTo("PRESENT");
        assertThat(bad.get("assessment").asString()).isEqualTo("BAD");
        assertThat(bad.get("severity").asString()).isEqualTo("MAJOR");
        assertThat(bad.get("observedAt").asString()).isEqualTo(observedBad.toString());
        assertThat(bad.get("artifactKind").asString()).isEqualTo("scm.pull_request");
        assertThat(bad.get("artifactId").asLong()).isEqualTo(123L);
        assertThat(bad.get("evidence").get("citations").get(0).get("path").asString())
                .isEqualTo("src/Retry.java");
        assertThat(bad.get("evidence").get("citations").get(0).get("quote").asString())
                .contains("IOException");

        JsonNode na = obs.get(1);
        assertThat(na.get("assessmentStatus").asString()).isEqualTo("NOT_APPLICABLE");
        // assessment/severity must be JSON null (not the string "null", not absent).
        assertThat(na.get("assessment").isNull()).isTrue();
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
}

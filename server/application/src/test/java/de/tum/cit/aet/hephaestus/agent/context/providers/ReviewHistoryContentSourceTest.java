package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.StagedArtifactNames;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentity;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewHistoryContentSourceTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long PR_ID = 42L;
    private static final long AUTHOR_ID = 99L;
    private static final long OBSERVED_ARTIFACT_ROW_ID = 306L;
    private static final long DELIVERED_ARTIFACT_ROW_ID = 307L;
    private static final int OBSERVED_ARTIFACT_NUMBER = 22;
    private static final int DELIVERED_ARTIFACT_NUMBER = 23;
    private static final long UNNAMEABLE_ARTIFACT_ROW_ID = 909L;

    private static final Set<Long> ROW_IDS =
            Set.of(OBSERVED_ARTIFACT_ROW_ID, DELIVERED_ARTIFACT_ROW_ID, UNNAMEABLE_ARTIFACT_ROW_ID);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private ObservationVisibilityPolicy visibilityPolicy;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private IssueRepository issueRepository;

    private ReviewHistoryContentSource provider;

    @BeforeEach
    void setUp() {
        provider = new ReviewHistoryContentSource(
                observationRepository,
                feedbackRepository,
                visibilityPolicy,
                pullRequestRepository,
                issueRepository,
                new StagedArtifactNames(ReviewHistoryContentSourceTest::identitiesOf),
                objectMapper);
        lenient()
                .when(observationRepository.findRecentByDeveloperAndWorkspace(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of());
        lenient()
                .when(feedbackRepository.findRecentDeliveredForRecipient(any(), any(), any(), any()))
                .thenReturn(List.of());
        lenient()
                .when(feedbackRepository.findPreparedForRecipient(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(visibilityPolicy.permitsAll(anyLong(), any(), any())).thenAnswer(invocation -> {
            Collection<Observation> batch = invocation.getArgument(1);
            return batch.stream().map(Observation::getId).collect(Collectors.toSet());
        });
        lenient()
                .when(pullRequestRepository.findByIdWithAuthorAndRepository(eq(PR_ID)))
                .thenReturn(Optional.of(pullRequestBy(AUTHOR_ID)));
    }

    @Test
    void answersForBothHistoryKindsWithoutAnyPracticeDeclaringThem() {
        assertThat(provider.sourceKinds())
                .containsExactlyInAnyOrder(
                        ReviewHistoryContentSource.OBSERVATION_HISTORY, ReviewHistoryContentSource.FEEDBACK_HISTORY);
    }

    @Nested
    class WhenOnlyOneHalfIsAskedFor {

        @Test
        void observationHistoryAloneAnswersForObservationHistoryOnly() {
            var captured = provider.capture(prRequest(), Set.of(ReviewHistoryContentSource.OBSERVATION_HISTORY));
            assertThat(captured.files())
                    .containsOnlyKeys("inputs/history/observations.json", "inputs/history/delta.json");
            assertThat(captured.completeness()).containsOnlyKeys(ReviewHistoryContentSource.OBSERVATION_HISTORY);
            assertThat(captured.contentStates()).containsOnlyKeys(ReviewHistoryContentSource.OBSERVATION_HISTORY);
        }

        @Test
        void feedbackHistoryAloneAnswersForFeedbackHistoryOnly() {
            var captured = provider.capture(prRequest(), Set.of(ReviewHistoryContentSource.FEEDBACK_HISTORY));

            assertThat(captured.files())
                    .containsOnlyKeys("inputs/history/feedback.json", "inputs/history/prepared.json");
            assertThat(captured.completeness()).containsOnlyKeys(ReviewHistoryContentSource.FEEDBACK_HISTORY);
            assertThat(captured.contentStates()).containsOnlyKeys(ReviewHistoryContentSource.FEEDBACK_HISTORY);
        }

        @Test
        void theUnaskedHalfIsNotQueried() {
            provider.capture(prRequest(), Set.of(ReviewHistoryContentSource.OBSERVATION_HISTORY));

            verify(feedbackRepository, never()).findRecentDeliveredForRecipient(any(), any(), any(), any());
            verify(feedbackRepository, never()).findPreparedForRecipient(any(), any(), any());
        }
    }

    @Test
    void aFirstEverReviewGetsAPresentAndEmptyHistory() {
        var observationsCapture = captureObservationHistory();
        var feedbackCapture = captureFeedbackHistory();

        JsonNode observations = read(observationsCapture.files().get("inputs/history/observations.json"));
        JsonNode feedback = read(feedbackCapture.files().get("inputs/history/feedback.json"));
        assertThat(observations.get("observations")).isEmpty();
        assertThat(feedback.get("feedback")).isEmpty();
        assertThat(observationsCapture.contentStates())
                .containsEntry(ReviewHistoryContentSource.OBSERVATION_HISTORY, SourceContentState.EMPTY);
        assertThat(feedbackCapture.contentStates())
                .containsEntry(ReviewHistoryContentSource.FEEDBACK_HISTORY, SourceContentState.EMPTY);
    }

    @Test
    void stagesEarlierObservationsWithTheRecurrenceKeyThatLinksThem() {
        when(observationRepository.findRecentByDeveloperAndWorkspace(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(observation("swallows-errors", "rec-1", "Caught and ignored")));

        var captured = captureObservationHistory();

        JsonNode entry = read(captured.files().get("inputs/history/observations.json"))
                .get("observations")
                .get(0);
        assertThat(entry.get("practiceSlug").asString()).isEqualTo("swallows-errors");
        assertThat(entry.get("recurrenceKey").asString()).isEqualTo("rec-1");
        assertThat(entry.get("summary").asString()).isEqualTo("Caught and ignored");
        assertThat(captured.contentStates())
                .containsEntry(ReviewHistoryContentSource.OBSERVATION_HISTORY, SourceContentState.NON_EMPTY);
    }

    @Test
    void stagesFeedbackThatWasAlreadyDeliveredWithItsChannel() {
        when(feedbackRepository.findRecentDeliveredForRecipient(any(), any(), any(), any()))
                .thenReturn(List.of(Feedback.builder()
                        .channel(FeedbackChannel.IN_CONTEXT)
                        .body("Consider handling this error rather than logging it.")
                        .deliveredAt(Instant.parse("2026-07-01T09:00:00Z"))
                        .build()));

        var captured = captureFeedbackHistory();

        JsonNode entry = read(captured.files().get("inputs/history/feedback.json"))
                .get("feedback")
                .get(0);
        assertThat(entry.get("channel").asString()).isEqualTo("IN_CONTEXT");
        assertThat(entry.get("body").asString()).contains("rather than logging it");
        assertThat(captured.contentStates())
                .containsEntry(ReviewHistoryContentSource.FEEDBACK_HISTORY, SourceContentState.NON_EMPTY);
    }

    @Test
    void stagesWhatIsQueuedAndUnreadWithTheKeyThatIdentifiesIt() {
        when(feedbackRepository.findPreparedForRecipient(any(), any(), any()))
                .thenReturn(List.of(Feedback.builder()
                        .channel(FeedbackChannel.IN_APP)
                        .threadKey("in-app:99:swallows-errors")
                        .body("A habit nobody has read yet.")
                        .createdAt(Instant.parse("2026-07-02T09:00:00Z"))
                        .build()));

        JsonNode entry = read(captureFeedbackHistory().files().get("inputs/history/prepared.json"))
                .get("prepared")
                .get(0);

        assertThat(entry.get("threadKey").asString()).isEqualTo("in-app:99:swallows-errors");
        assertThat(entry.get("channel").asString()).isEqualTo("IN_APP");
        assertThat(entry.get("body").asString()).contains("nobody has read yet");
    }

    @Test
    void stagesHowEachLocusMovedWithoutStagingTheKeyItMovedAt() {
        when(observationRepository.findRecentByDeveloperAndWorkspace(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(observation("swallows-errors", "rec-1", "Caught and ignored")));

        JsonNode delta = read(captureObservationHistory().files().get("inputs/history/delta.json"));

        assertThat(delta.get("loci")).hasSize(1);
        JsonNode locus = delta.get("loci").get(0);
        assertThat(locus.get("practiceSlug").asString()).isEqualTo("swallows-errors");
        assertThat(locus.get("status").asString()).isEqualTo("NEW");
        assertThat(locus.has("recurrenceKey")).isFalse();
    }

    @Test
    void theDeltaHoldsNothingTheVisibilityPolicyRefused() {
        when(observationRepository.findRecentByDeveloperAndWorkspace(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(observation("swallows-errors", "rec-1", "Caught and ignored")));
        doReturn(Set.of()).when(visibilityPolicy).permitsAll(anyLong(), any(), any());

        assertThat(read(captureObservationHistory().files().get("inputs/history/delta.json"))
                        .get("loci"))
                .isEmpty();
    }

    @Test
    void neverReportsCompleteBecauseTheWindowIsBounded() {
        assertThat(captureObservationHistory().completeness().values()).containsOnly(SourceCompleteness.PARTIAL);
        assertThat(captureFeedbackHistory().completeness().values()).containsOnly(SourceCompleteness.PARTIAL);
    }

    @Test
    void withholdsAnObservationTheVisibilityPolicyRefuses() {
        when(observationRepository.findRecentByDeveloperAndWorkspace(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(observation("swallows-errors", "rec-1", "Caught and ignored")));
        doReturn(Set.of()).when(visibilityPolicy).permitsAll(anyLong(), any(), any());

        var captured = captureObservationHistory();

        assertThat(read(captured.files().get("inputs/history/observations.json"))
                        .get("observations"))
                .isEmpty();
        assertThat(captured.contentStates())
                .containsEntry(ReviewHistoryContentSource.OBSERVATION_HISTORY, SourceContentState.EMPTY);
    }

    @Test
    void reportsUnavailableRatherThanEmptyWhenTheSubjectCannotBeResolved() {
        when(pullRequestRepository.findByIdWithAuthorAndRepository(eq(PR_ID))).thenReturn(Optional.empty());

        assertUnavailable(captureObservationHistory(), ReviewHistoryContentSource.OBSERVATION_HISTORY);
        assertUnavailable(captureFeedbackHistory(), ReviewHistoryContentSource.FEEDBACK_HISTORY);
        verifyNoInteractions(observationRepository, feedbackRepository);
    }

    private static void assertUnavailable(EvidenceContribution captured, SourceKind kind) {
        assertThat(captured.files()).isEmpty();
        assertThat(captured.stateOverrides()).containsOnlyKeys(kind);
        assertThat(captured.stateOverrides())
                .hasEntrySatisfying(
                        kind,
                        state -> assertThat(state)
                                .isInstanceOfSatisfying(
                                        SourceCaptureState.Unavailable.class,
                                        unavailable -> assertThat(unavailable.reasonCode())
                                                .isEqualTo(SourceAbsenceReason.NOT_FOUND)));
    }

    @Nested
    class NamingTheWorkAnEntryIsAbout {

        @Test
        void anObservationCarriesTheHandleOfTheWorkItWasFiledAgainst() {
            when(observationRepository.findRecentByDeveloperAndWorkspace(any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(List.of(observationAgainst(ArtifactKinds.PULL_REQUEST, OBSERVED_ARTIFACT_ROW_ID)));

            JsonNode artifact = read(captureObservationHistory().files().get("inputs/history/observations.json"))
                    .get("observations")
                    .get(0)
                    .get("artifact");

            assertThat(artifact.get("kind").asString()).isEqualTo("scm.pull_request");
            assertThat(artifact.get("number").asInt()).isEqualTo(OBSERVED_ARTIFACT_NUMBER);
            assertThat(artifact.get("title").asString()).isEqualTo("Batch the practice reads");
            assertThat(artifact.get("container").asString()).isEqualTo("acme/web");
            assertThat(artifact.get("url").asString()).endsWith("/merge_requests/" + OBSERVED_ARTIFACT_NUMBER);
        }

        @Test
        void deliveredFeedbackCarriesTheSameHandle() {
            when(feedbackRepository.findRecentDeliveredForRecipient(any(), any(), any(), any()))
                    .thenReturn(List.of(deliveredAgainst(ArtifactKinds.PULL_REQUEST, DELIVERED_ARTIFACT_ROW_ID)));

            JsonNode artifact = read(captureFeedbackHistory().files().get("inputs/history/feedback.json"))
                    .get("feedback")
                    .get(0)
                    .get("artifact");

            assertThat(artifact.get("number").asInt()).isEqualTo(DELIVERED_ARTIFACT_NUMBER);
            assertThat(artifact.get("container").asString()).isEqualTo("acme/web");
        }

        @Test
        void workNoResolverCanNameIsStagedAsItsKindWithoutANumber() {
            when(observationRepository.findRecentByDeveloperAndWorkspace(any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(
                            List.of(observationAgainst(ArtifactKinds.CONVERSATION_THREAD, UNNAMEABLE_ARTIFACT_ROW_ID)));

            JsonNode artifact = read(captureObservationHistory().files().get("inputs/history/observations.json"))
                    .get("observations")
                    .get(0)
                    .get("artifact");

            assertThat(artifact.get("kind").asString()).isEqualTo("chat.conversation_thread");
            assertThat(artifact.get("title").asString()).isEqualTo("Conversation thread");
            assertThat(artifact.has("number")).isFalse();
            assertThat(artifact.has("url")).isFalse();
        }

        @Test
        void noHistoryFileCarriesARowIdAnywhere() {
            when(observationRepository.findRecentByDeveloperAndWorkspace(any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(List.of(
                            observationAgainst(ArtifactKinds.PULL_REQUEST, OBSERVED_ARTIFACT_ROW_ID),
                            observationAgainst(ArtifactKinds.CONVERSATION_THREAD, UNNAMEABLE_ARTIFACT_ROW_ID)));
            when(feedbackRepository.findRecentDeliveredForRecipient(any(), any(), any(), any()))
                    .thenReturn(List.of(deliveredAgainst(ArtifactKinds.PULL_REQUEST, DELIVERED_ARTIFACT_ROW_ID)));
            when(feedbackRepository.findPreparedForRecipient(any(), any(), any()))
                    .thenReturn(List.of(deliveredAgainst(ArtifactKinds.PULL_REQUEST, DELIVERED_ARTIFACT_ROW_ID)));

            var observationCapture = captureObservationHistory();
            var feedbackCapture = captureFeedbackHistory();
            assertCarriesNoRowId(
                    read(observationCapture.files().get("inputs/history/observations.json")), "observations.json");
            assertCarriesNoRowId(read(observationCapture.files().get("inputs/history/delta.json")), "delta.json");
            assertCarriesNoRowId(read(feedbackCapture.files().get("inputs/history/feedback.json")), "feedback.json");
            assertCarriesNoRowId(read(feedbackCapture.files().get("inputs/history/prepared.json")), "prepared.json");
        }
    }

    @Test
    void doesNotSupportTheMentorChat() {
        assertThat(provider.supports(new ContextRequest.MentorChatRequest(WORKSPACE_ID, AUTHOR_ID, UUID.randomUUID())))
                .isFalse();
        assertThat(provider.supports(prRequest())).isTrue();
    }

    private EvidenceContribution captureObservationHistory() {
        return provider.capture(prRequest(), Set.of(ReviewHistoryContentSource.OBSERVATION_HISTORY));
    }

    private EvidenceContribution captureFeedbackHistory() {
        return provider.capture(prRequest(), Set.of(ReviewHistoryContentSource.FEEDBACK_HISTORY));
    }

    private JsonNode read(byte @Nullable [] bytes) {
        assertThat(bytes).isNotNull();
        return objectMapper.readTree(bytes);
    }

    private ContextRequest.PracticeReviewRequest prRequest() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("pull_request_id", PR_ID);
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setMetadata(metadata);
        return new ContextRequest.PracticeReviewRequest(job);
    }

    private static PullRequest pullRequestBy(long authorId) {
        User author = new User();
        author.setId(authorId);
        PullRequest pullRequest = new PullRequest();
        pullRequest.setAuthor(author);
        return pullRequest;
    }

    private static Map<Long, ArtifactIdentity> identitiesOf(long workspaceId, ArtifactKind kind, Collection<Long> ids) {
        Map<Long, ArtifactIdentity> named = new LinkedHashMap<>();
        for (Long id : ids) {
            named.put(id, identityOf(kind, id));
        }
        return named;
    }

    private static ArtifactIdentity identityOf(ArtifactKind kind, Long id) {
        if (id == OBSERVED_ARTIFACT_ROW_ID) {
            return mergeRequest(kind, id, OBSERVED_ARTIFACT_NUMBER, "Batch the practice reads");
        }
        if (id == DELIVERED_ARTIFACT_ROW_ID) {
            return mergeRequest(kind, id, DELIVERED_ARTIFACT_NUMBER, "Drop the duplicate visibility read");
        }
        return ArtifactIdentity.unresolved(kind, id, "Conversation thread");
    }

    private static ArtifactIdentity mergeRequest(ArtifactKind kind, Long id, int number, String title) {
        return new ArtifactIdentity(
                kind, id, number, title, "acme/web", "https://gitlab.example.com/acme/web/-/merge_requests/" + number);
    }

    private static void assertCarriesNoRowId(JsonNode node, String path) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                assertThat(entry.getKey()).as("staged field at %s", path).isNotEqualTo("artifactId");
                assertCarriesNoRowId(entry.getValue(), path + "." + entry.getKey());
            });
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                assertCarriesNoRowId(node.get(index), path + "[" + index + "]");
            }
            return;
        }
        if (node.isNumber()) {
            assertThat(ROW_IDS.contains(node.asLong()))
                    .as("a row id reached the staged history at %s", path)
                    .isFalse();
        }
    }

    private static Observation observationAgainst(ArtifactKind kind, long artifactId) {
        return observation("swallows-errors", "rec-1", "Caught and ignored", kind, artifactId);
    }

    private static Feedback deliveredAgainst(ArtifactKind kind, long artifactId) {
        return Feedback.builder()
                .channel(FeedbackChannel.IN_CONTEXT)
                .artifactKind(kind)
                .artifactId(artifactId)
                .body("Consider handling this error rather than logging it.")
                .deliveredAt(Instant.parse("2026-07-01T09:00:00Z"))
                .build();
    }

    private static Observation observation(String practiceSlug, String recurrenceKey, String title) {
        return observation(practiceSlug, recurrenceKey, title, null, null);
    }

    private static Observation observation(
            String practiceSlug,
            String recurrenceKey,
            String title,
            @Nullable ArtifactKind artifactKind,
            @Nullable Long artifactId) {
        Practice practice = new Practice();
        practice.setSlug(practiceSlug);
        return Observation.builder()
                .id(UUID.randomUUID())
                .practice(practice)
                .recurrenceKey(recurrenceKey)
                .summary(title)
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .presence(Presence.PRESENT)
                .assessment(Assessment.BAD)
                .artifactKind(artifactKind)
                .artifactId(artifactId)
                .observedAt(Instant.parse("2026-07-01T09:00:00Z"))
                .evidenceRationale("The catch block logs and continues.")
                .build();
    }
}

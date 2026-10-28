package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.PublicationContextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ReviewModelGateway;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.ConversationEligibilityDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PracticePageSupportDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrivateFeedbackHistoryDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrivateProgressDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PublicFeedbackHistoryDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageSupport;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackProgress;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The worker's composition loop over the golden pull-request scenario, with scripted model replies. */
@Tag("unit")
class WorkerFeedbackComposerTest {
    private static final String ERRORS = "30000000-0000-4000-8000-000000000001";
    private static final String DESCRIBE = "30000000-0000-4000-8000-000000000002";
    private static final String ERRORS_PROBLEM = "40000000-0000-4000-8000-000000000001";
    private static final int MAX_RETRIES = 5;

    private final JsonMapper mapper = new JsonMapper();
    private final ResultAdmissionService admission = mock(ResultAdmissionService.class);
    private final JobEvidenceFiles evidence = mock(JobEvidenceFiles.class);
    private final ReviewCompositionContinuation continuation = mock(ReviewCompositionContinuation.class);
    private final ReviewModelGateway gateway = mock(ReviewModelGateway.class);
    private final ReviewJobAssignments assignments = mock(ReviewJobAssignments.class);
    private final ItemDescriptorRegistry items = mock(ItemDescriptorRegistry.class);
    private final List<PrepareFeedbackRequestDTO> prepared = new ArrayList<>();
    /** The packages the server accepted: they decide destinations as prepared rows would. */
    private final List<PrepareFeedbackRequestDTO> accepted = new ArrayList<>();

    private final Map<FeedbackDestination, Set<UUID>> earlierDecisions = new EnumMap<>(FeedbackDestination.class);
    private boolean reviewedEarlier;
    private final List<JsonNode> requests = new ArrayList<>();
    private final Deque<ReviewModelGateway.Answer> replies = new ArrayDeque<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ReviewJob job = RuntimeReviewTestData.job();
    private Path scenario;

    @BeforeEach
    void setUp() throws Exception {
        scenario = Path.of(
                Objects.requireNonNull(getClass().getResource("/agent/golden-prompts/composition-turns/pull-request"))
                        .toURI());
        job.setAttempt(0);
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("a".repeat(64));
        job.setWorkerId("worker-1");
        job.setConfigSnapshot(new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "https://model.example",
                                "model",
                                null,
                                null,
                                null,
                                null,
                                DataHandlingTier.UNDECLARED,
                                null,
                                null),
                        200_000,
                        4_000,
                        600,
                        false,
                        null)
                .toJson(mapper));
        when(assignments.review(job))
                .thenReturn(RuntimeReviewTestData.review(ReviewKind.ITEM, RuntimeReviewTestData.assignment(5L, null)));
        var descriptor = mock(ItemDescriptor.class);
        when(descriptor.copyCapabilities())
                .thenReturn(Set.of(
                        CopyCapability.SUMMARY,
                        CopyCapability.LINE_NOTE,
                        CopyCapability.PRACTICE_PAGE,
                        CopyCapability.CONVERSATION));
        when(items.descriptorFor(any())).thenReturn(Optional.of(descriptor));
        when(admission.privateProgress(job)).thenAnswer(invocation -> progress());
        when(admission.admittedResponse(job))
                .thenAnswer(invocation -> admissionResponse(
                        reviewedEarlier || accepted.stream().anyMatch(PrepareFeedbackRequestDTO::publicComposition)));
        when(admission.publicFeedbackHistory(any()))
                .thenReturn(mapper.readValue(
                        Files.readAllBytes(scenario.resolve("public-history.json")), PublicFeedbackHistoryDTO.class));
        when(admission.prepareFeedback(any(), any())).thenAnswer(invocation -> accept(invocation.getArgument(1)));
        var inputs = workspace(scenario.resolve("workspace"));
        inputs.remove(SandboxLayout.FEEDBACK_COMPOSITION_PATH);
        when(evidence.originalCompositionInputs(any())).thenReturn(Optional.of(inputs));
        when(gateway.call(any(), any())).thenAnswer(invocation -> {
            requests.add(mapper.readTree((byte[]) invocation.getArgument(1)));
            return replies.isEmpty() ? text("Nothing more.") : replies.removeFirst();
        });
    }

    private WorkerFeedbackComposer composer() {
        return new WorkerFeedbackComposer(
                admission,
                evidence,
                continuation,
                gateway,
                assignments,
                items,
                new PracticeReviewProperties(false, 15, 5, null, 12, 16000, 3, 200_000, 1000, Duration.ofMinutes(10)),
                new AgentProperties(
                        true,
                        Duration.ofSeconds(1),
                        5,
                        MAX_RETRIES,
                        Duration.ofSeconds(25),
                        Duration.ofDays(14),
                        Duration.ofDays(90),
                        16),
                meters,
                mapper,
                Duration.ZERO,
                new CompositionCapacity(4));
    }

    @Test
    void shouldComposeTheReviewOnTheWorkThenPrivateFeedbackAndPrepareEachPackage() {
        replies.add(call("report_review", validReview()));
        replies.add(call("report_feedback", privatePieces()));
        replies.add(text("Done."));

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        // Each session offers only its report tool: everything it may use is in its turn.
        assertThat(tools(requests.getFirst())).containsExactly("report_review");
        assertThat(tools(requests.get(1))).containsExactly("report_feedback");
        assertThat(requests.getFirst().path("max_completion_tokens").asInt()).isEqualTo(4_000);
        var review = prepared.getFirst();
        assertThat(review.publicComposition()).isTrue();
        assertThat(review.publicationContext()).isEqualTo("v1:7:" + "d".repeat(64));
        assertThat(review.composition().getFirst().destination()).isEqualTo(FeedbackDestination.ON_WORK);
        assertThat(review.composition().getFirst().notes()).hasSize(1);
        assertThat(prepared.subList(1, prepared.size()))
                .extracting(
                        request -> request.composition().getFirst().destination(),
                        request -> request.composition().getFirst().withheldReason())
                .containsExactly(
                        tuple(FeedbackDestination.PRACTICE_PAGE, null),
                        tuple(FeedbackDestination.CONVERSATION, FeedbackStopReason.COMPOSER_WITHHELD),
                        // The page card decides its practice on the page only. Asked once more, the session leaves
                        // the conversation open, so it is recorded as not composed, as a resumed attempt would read it.
                        tuple(FeedbackDestination.CONVERSATION, FeedbackStopReason.NOT_COMPOSED));
        var finish = requests.getLast().path("messages");
        assertThat(finish.get(finish.size() - 1).path("content").asString())
                .startsWith("## Undecided")
                .contains(" on CONVERSATION.");
    }

    @Test
    void shouldSendTheSameRequestAgainAfterAProviderFailure() {
        replies.add(new ReviewModelGateway.Answer(503, new byte[0], true, null));
        replies.add(call("report_review", validReview()));

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        assertThat(requests.get(1).path("messages"))
                .isEqualTo(requests.getFirst().path("messages"));
        assertThat(prepared.getFirst().publicComposition()).isTrue();
        assertThat(prepared.getFirst().composition().getFirst().withheldReason())
                .isNull();
    }

    @Test
    void shouldSendARefusedReviewBackAndStoreItsCorrection() {
        var incomplete = validReview();
        ((ArrayNode) incomplete.path("decisions")).remove(1);
        replies.add(call("report_review", incomplete));
        replies.add(call("report_review", validReview()));

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        var correction = requests.get(1).path("messages");
        assertThat(correction.get(correction.size() - 1).path("content").asString())
                .startsWith("review refused, nothing was stored:")
                .contains(DESCRIBE + " has no decision");
        assertThat(prepared.getFirst().publicComposition()).isTrue();
        assertThat(prepared.getFirst().composition().getFirst().withheldReason())
                .isNull();
    }

    @Test
    void shouldNameTheRuleAndFieldOfARefusedTextAndStoreTheRewrittenReview() {
        doThrow(new FeedbackTextRefusedException(FeedbackDestination.ON_WORK, "LINK", "notes[1]"))
                .doAnswer(invocation -> accept(invocation.getArgument(1)))
                .when(admission)
                .prepareFeedback(any(), any());
        replies.add(call("report_review", validReview()));
        replies.add(call("report_review", validReview()));

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        var correction = requests.get(1).path("messages");
        assertThat(correction.get(correction.size() - 1).path("content").asString())
                .isEqualTo("review refused, nothing was stored: Inline note 2 has a link that no cited quote supplies."
                        + " Write no links: Hephaestus adds its own.\nSend the whole review again.");
        assertThat(prepared.getFirst().composition().getFirst().withheldReason())
                .isNull();
    }

    @Test
    void shouldNameTheRefusedFieldOfAPrivatePieceForItsRewrite() {
        replies.add(call("report_review", validReview()));
        replies.add(call("report_feedback", privatePieces()));
        replies.add(text("Done."));
        doAnswer(invocation -> {
                    PrepareFeedbackRequestDTO request = invocation.getArgument(1);
                    var piece = request.composition().getFirst();
                    if (piece.destination() != FeedbackDestination.PRACTICE_PAGE || piece.withheldReason() != null)
                        return accept(request);
                    throw new FeedbackTextRefusedException(
                            FeedbackDestination.PRACTICE_PAGE, "MENTION", "page.nextStep");
                })
                .when(admission)
                .prepareFeedback(any(), any());

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        var answer = requests.get(2).path("messages");
        assertThat(answer.get(answer.size() - 1).path("content").asString())
                .contains("#1: the server refused this piece, nothing was stored: The card's next step has an"
                        + " @-mention or Slack mention.");
    }

    @Test
    void shouldComposeTheReviewAgainFromAFreshHistoryReadWhenItsHistoryChanged() {
        doThrow(new PublicationContextRefusedException(FeedbackStopReason.PUBLIC_HISTORY_CHANGED))
                .doAnswer(invocation -> accept(invocation.getArgument(1)))
                .when(admission)
                .prepareFeedback(any(), any());
        replies.add(call("report_review", validReview()));
        replies.add(call("report_review", validReview()));

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        verify(admission, times(2)).publicFeedbackHistory(any());
        assertThat(requests.get(1).path("messages").size())
                .as("a fresh session, not a correction")
                .isEqualTo(2);
        assertThat(prepared.getFirst().publicComposition()).isTrue();
        assertThat(prepared.getFirst().composition().getFirst().withheldReason())
                .isNull();
    }

    @Test
    void shouldSendAServerRefusedPrivatePieceBackForCorrection() {
        replies.add(call("report_review", validReview()));
        replies.add(call("report_feedback", privatePieces()));
        replies.add(call("report_feedback", privatePieces()));
        replies.add(text("Done."));
        doAnswer(invocation -> {
                    PrepareFeedbackRequestDTO request = invocation.getArgument(1);
                    boolean firstCard = request.composition().getFirst().destination()
                                    == FeedbackDestination.PRACTICE_PAGE
                            && prepared.stream()
                                    .noneMatch(earlier ->
                                            earlier.composition().getFirst().destination()
                                                    == FeedbackDestination.PRACTICE_PAGE);
                    if (!firstCard) return accept(request);
                    prepared.add(request);
                    throw new AdmissionConflictException();
                })
                .when(admission)
                .prepareFeedback(any(), any());

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        var answer = requests.get(2).path("messages");
        assertThat(answer.get(answer.size() - 1).path("content").asString())
                .contains("#1: the server refused this piece, nothing was stored")
                .contains("#2: stored a CONVERSATION piece");
        assertThat(prepared)
                .extracting(request -> request.composition().getFirst().destination())
                .containsExactly(
                        FeedbackDestination.ON_WORK,
                        FeedbackDestination.PRACTICE_PAGE,
                        FeedbackDestination.CONVERSATION,
                        FeedbackDestination.PRACTICE_PAGE,
                        FeedbackDestination.CONVERSATION);
    }

    @Test
    void shouldWithholdEachOpenDestinationWhenTheModelNeverDecides() {
        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        // Each session was asked once more, then each routed destination got a recorded withholding.
        assertThat(requests).hasSize(4);
        assertThat(prepared)
                .allSatisfy(
                        request -> assertThat(request.composition().getFirst().withheldReason())
                                .isEqualTo(FeedbackStopReason.NOT_COMPOSED))
                .extracting(request -> request.composition().getFirst().destination())
                .containsExactlyInAnyOrder(
                        FeedbackDestination.ON_WORK,
                        FeedbackDestination.PRACTICE_PAGE,
                        FeedbackDestination.CONVERSATION,
                        FeedbackDestination.CONVERSATION);
    }

    @Test
    void shouldWithholdWithoutCallingTheModelWhenTheInputsAreGone() {
        when(evidence.originalCompositionInputs(any())).thenReturn(Optional.empty());

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        verify(gateway, never()).call(any(), any());
        assertThat(prepared)
                .extracting(request -> request.composition().getFirst().withheldReason())
                .containsOnly(FeedbackStopReason.NOT_COMPOSED)
                .hasSize(4);
    }

    @Test
    void shouldHandTheAttemptOnWithoutCallingTheModelAfterTheDeadline() {
        assertThatThrownBy(() -> composer().composeAfterMeasurement(job, "worker-1", Instant.now()))
                .isInstanceOfSatisfying(
                        CompositionHandedOnException.class,
                        handedOn -> assertThat(handedOn.stop()).isEqualTo(CompositionStop.DEADLINE));

        // A later attempt composes what is still open in a fresh window; nothing is withheld now.
        verify(gateway, never()).call(any(), any());
        assertThat(prepared).isEmpty();
        assertThat(stops("deadline", "handed_on")).isEqualTo(1);
    }

    @Test
    void shouldWithholdWhatTheDeadlineLeftOpenOnTheAttemptAtTheRetryLimit() {
        job.setAttempt(MAX_RETRIES);

        composer().composeAfterMeasurement(job, "worker-1", Instant.now());

        verify(gateway, never()).call(any(), any());
        assertThat(prepared)
                .extracting(request -> request.composition().getFirst().withheldReason())
                .containsOnly(FeedbackStopReason.NOT_COMPOSED)
                .hasSize(4);
        assertThat(stops("deadline", "withheld")).isEqualTo(4);
    }

    @Test
    void shouldHandTheAttemptOnWhenTheProxyRefusesACallPastTheWorkDeadline() {
        answerEveryCall(new ReviewModelGateway.Answer(403, new byte[0], true, ReviewModelGateway.Refusal.WORK_TIME));

        assertThatThrownBy(() -> composer()
                        .composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1))))
                .isInstanceOfSatisfying(
                        CompositionHandedOnException.class,
                        handedOn -> assertThat(handedOn.stop()).isEqualTo(CompositionStop.DEADLINE));
        assertThat(prepared).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"AI_CHOICE, NO_AI, no_ai", "BUDGET, NOT_COMPOSED, budget"})
    void shouldWithholdAtOnceWithTheMostSpecificReasonWhenTheProxyRefuses(
            ReviewModelGateway.Refusal refusal, FeedbackStopReason reason, String cause) {
        answerEveryCall(new ReviewModelGateway.Answer(403, new byte[0], true, refusal));

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        // Each session stops at its first refused call; another attempt would be refused again.
        assertThat(requests).hasSize(2);
        assertThat(prepared)
                .extracting(request -> request.composition().getFirst().withheldReason())
                .containsOnly(reason)
                .hasSize(4);
        assertThat(stops(cause, "withheld")).isEqualTo(4);
    }

    @Test
    void shouldHandTheAttemptOnWhenTheProviderFailsPastTheSessionsCalls() {
        answerEveryCall(new ReviewModelGateway.Answer(503, new byte[0], true, null));

        assertThatThrownBy(() -> composer()
                        .composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1))))
                .isInstanceOfSatisfying(
                        CompositionHandedOnException.class,
                        handedOn -> assertThat(handedOn.stop()).isEqualTo(CompositionStop.PROVIDER));
        // The private feedback waits for the review it follows, and nothing open is withheld.
        assertThat(requests).allSatisfy(request -> assertThat(tools(request)).containsExactly("report_review"));
        assertThat(prepared).isEmpty();
        assertThat(stops("provider", "handed_on")).isEqualTo(1);
    }

    @Test
    void shouldWithholdWhatAProviderFailureLeftOpenOnTheAttemptAtTheRetryLimit() {
        job.setAttempt(MAX_RETRIES);
        answerEveryCall(new ReviewModelGateway.Answer(503, new byte[0], true, null));

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        assertThat(prepared)
                .extracting(request -> request.composition().getFirst().withheldReason())
                .containsOnly(FeedbackStopReason.NOT_COMPOSED)
                .hasSize(4);
        assertThat(stops("provider", "withheld")).isEqualTo(4);
    }

    @Test
    void shouldHandTheAttemptOnAfterAnUnexpectedFailure() {
        when(admission.publicFeedbackHistory(any())).thenThrow(new IllegalStateException("defect"));

        assertThatThrownBy(() -> composer()
                        .composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1))))
                .isInstanceOfSatisfying(CompositionHandedOnException.class, handedOn -> {
                    assertThat(handedOn.stop()).isEqualTo(CompositionStop.UNEXPECTED);
                    assertThat(handedOn).hasRootCauseInstanceOf(IllegalStateException.class);
                });
        assertThat(prepared).isEmpty();
    }

    @Test
    void shouldLeaveAFailedWithholdingToTheExecutorToHandOn() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(admission)
                .prepareFeedback(any(), any());

        assertThatThrownBy(() -> composer()
                        .composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    @Test
    void shouldWithholdErasedInputsWithoutStagingThem() {
        job.setAttempt(1);
        when(continuation.restore(job))
                .thenThrow(new ReviewCompositionContinuation.InputsUnavailableException("erased", true));
        when(continuation.stageWithoutInputs(job)).thenReturn(emptyFolder());

        composer().composeResumed(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        verify(gateway, never()).call(any(), any());
        assertThat(prepared)
                .extracting(request -> request.composition().getFirst().withheldReason())
                .containsOnly(FeedbackStopReason.EVIDENCE_NOT_PERMITTED)
                .hasSize(4);
    }

    @Test
    void shouldWithholdWithTheCauseTheClaimMetWithoutCallingTheModel() {
        job.setAttempt(1);
        when(continuation.stageWithoutInputs(job)).thenReturn(emptyFolder());

        composer().withholdResumed(job, "worker-1", CompositionStop.NO_AI);

        verify(gateway, never()).call(any(), any());
        verify(continuation, never()).restore(any());
        assertThat(prepared)
                .extracting(request -> request.composition().getFirst().withheldReason())
                .containsOnly(FeedbackStopReason.NO_AI)
                .hasSize(4);
    }

    @Test
    void shouldOnlyWithholdOnTheAttemptAfterTheRetryLimit() {
        job.setAttempt(MAX_RETRIES + 1);
        when(continuation.stageWithoutInputs(job)).thenReturn(emptyFolder());

        composer().composeResumed(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        verify(gateway, never()).call(any(), any());
        verify(continuation, never()).restore(any());
        assertThat(prepared)
                .extracting(request -> request.composition().getFirst().withheldReason())
                .containsOnly(FeedbackStopReason.NOT_COMPOSED)
                .hasSize(4);
        assertThat(stops("retries_exhausted", "withheld")).isEqualTo(4);
    }

    @Test
    void shouldStopWithoutWritingWhenTheAttemptNoLongerRunsHere() {
        replies.add(new ReviewModelGateway.Answer(401, new byte[0], false, null));

        assertThatThrownBy(() -> composer()
                        .composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1))))
                .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
        verify(admission, never()).prepareFeedback(any(), any());
    }

    @Test
    void shouldComposeOnlyThePrivateDestinationsAnEarlierAttemptLeftOpen() {
        reviewedEarlier = true;
        earlierDecisions.put(FeedbackDestination.PRACTICE_PAGE, Set.of(UUID.fromString(ERRORS)));
        replies.add(call("report_feedback", privatePieces()));
        replies.add(text("Done."));

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        // The review on the work is accepted and the page decided: the session may write only conversation pieces.
        assertThat(tools(requests.getFirst())).containsExactly("report_feedback");
        JsonNode schema =
                requests.getFirst().path("tools").get(0).path("function").path("parameters");
        assertThat(schema.path("properties")
                        .path("feedback")
                        .path("items")
                        .path("anyOf")
                        .valueStream()
                        .map(branch -> branch.path("properties")
                                .path("destination")
                                .path("enum")
                                .get(0)
                                .asString())
                        .distinct())
                .containsExactly("CONVERSATION");
        assertThat(prepared)
                .extracting(request -> request.composition().getFirst().destination())
                .containsOnly(FeedbackDestination.CONVERSATION);
    }

    @Test
    void shouldGiveAResumedPrivateCompositionTheReviewAnEarlierAttemptPrepared() {
        reviewedEarlier = true;
        when(admission.preparedReview(job))
                .thenReturn(Optional.of(new ResultAdmissionService.PreparedReview(
                        "The description should say why fetches now retry.",
                        List.of(new FeedbackCompositionTrace.Support(UUID.fromString(DESCRIBE), null)),
                        List.of(new ResultAdmissionService.PreparedNote(
                                "This catch returns null and hides the IOException; record or rethrow it.",
                                List.of(new FeedbackCompositionTrace.Support(
                                        UUID.fromString(ERRORS), UUID.fromString(ERRORS_PROBLEM))),
                                new ComposedFeedback.Anchor(UUID.fromString(ERRORS_PROBLEM), 0))),
                        List.of())));
        replies.add(call("report_feedback", privatePieces()));
        replies.add(text("Done."));

        composer().composeAfterMeasurement(job, "worker-1", Instant.now().plus(Duration.ofHours(1)));

        var messages = requests.getFirst().path("messages");
        assertThat(messages.get(messages.size() - 1).path("content").asString())
                .contains("### The review composed for this work")
                .contains("\"body\": \"The description should say why fetches now retry.\"")
                .contains("\"problemId\": \"" + ERRORS_PROBLEM + "\"");
        assertThat(prepared)
                .extracting(request -> request.composition().getFirst().destination())
                .doesNotContain(FeedbackDestination.ON_WORK);
    }

    private void answerEveryCall(ReviewModelGateway.Answer answer) {
        doAnswer(invocation -> {
                    requests.add(mapper.readTree((byte[]) invocation.getArgument(1)));
                    return answer;
                })
                .when(gateway)
                .call(any(), any());
    }

    private double stops(String cause, String outcome) {
        return meters.counter(AgentMetrics.AGENT_JOB_COMPOSITION_STOPPED, "cause", cause, "outcome", outcome)
                .count();
    }

    private static ReviewCompositionContinuation.Restored emptyFolder() {
        return new ReviewCompositionContinuation.Restored(mock(PreparedJobInputs.class), Map.of());
    }

    private PrepareFeedbackResponseDTO accept(PrepareFeedbackRequestDTO request) {
        prepared.add(request);
        accepted.add(request);
        return new PrepareFeedbackResponseDTO(List.of(UUID.randomUUID()), "digest", Instant.now());
    }

    /** What the prepared rows decide, as the server reads its progress. */
    private PrivateFeedbackProgress progress() {
        var decided = new EnumMap<FeedbackDestination, Set<UUID>>(earlierDecisions);
        for (var request : accepted)
            for (var piece : request.composition())
                if (piece.destination() != FeedbackDestination.ON_WORK)
                    decided.merge(piece.destination(), Set.copyOf(piece.resultIds()), (left, right) -> {
                        var both = new HashSet<>(left);
                        both.addAll(right);
                        return both;
                    });
        var all = new HashSet<UUID>();
        decided.values().forEach(all::addAll);
        return new PrivateFeedbackProgress(all, 0, 0, decided);
    }

    private ObjectNode validReview() {
        return (ObjectNode) mapper.readTree("""
                {"decisions":[{"resultId":"%1$s","disposition":"RAISE"},{"resultId":"%2$s","disposition":"RAISE"}],
                 "inline":[{"basedOn":["%3$s"],"body":"This catch returns null and hides the IOException; record or rethrow it.",
                            "anchor":{"problemId":"%3$s","citationIndex":0}}],
                 "summary":{"basedOn":["%2$s"],"body":"The description should say why fetches now retry."}}
                """.formatted(ERRORS, DESCRIBE, ERRORS_PROBLEM));
    }

    private ObjectNode privatePieces() {
        return (ObjectNode) mapper.readTree("""
                {"feedback":[
                  {"destination":"PRACTICE_PAGE","practiceSlug":"handles-errors","basedOn":["%1$s"],"action":"NEW",
                   "title":"Record each caught failure","body":"Two changes return a default value from a catch and record nothing.",
                   "nextStep":"Before you return from a catch, record or rethrow the failure."},
                  {"destination":"CONVERSATION","practiceSlug":"describes-changes","basedOn":["%2$s"],"action":"WITHHOLD",
                   "withholdReason":"BELOW_BAR","witnessIds":[]}]}
                """.formatted(ERRORS, DESCRIBE));
    }

    private ReviewModelGateway.Answer call(String tool, JsonNode arguments) {
        var body = mapper.createObjectNode();
        var message = body.putArray("choices").addObject().putObject("message");
        message.put("role", "assistant");
        message.putNull("content");
        var call = message.putArray("tool_calls").addObject();
        call.put("id", "call-" + UUID.randomUUID());
        call.put("type", "function");
        call.putObject("function").put("name", tool).put("arguments", arguments.toString());
        return new ReviewModelGateway.Answer(200, body.toString().getBytes(StandardCharsets.UTF_8), true, null);
    }

    private ReviewModelGateway.Answer text(String content) {
        var body = mapper.createObjectNode();
        body.putArray("choices")
                .addObject()
                .putObject("message")
                .put("role", "assistant")
                .put("content", content);
        return new ReviewModelGateway.Answer(200, body.toString().getBytes(StandardCharsets.UTF_8), true, null);
    }

    private static List<String> tools(JsonNode request) {
        return request.path("tools")
                .valueStream()
                .map(tool -> tool.path("function").path("name").asString())
                .toList();
    }

    /** The scenario's admission answer as the server's types hold it. */
    private AdmissionResponseDTO admissionResponse(boolean publicReviewed) {
        JsonNode response = readJson(scenario.resolve("admission.json"));
        var admitted = new ArrayList<AdmittedResultDTO>();
        for (JsonNode result : response.path("admitted"))
            admitted.add(mapper.treeToValue(result, AdmittedResultDTO.class));
        JsonNode support = response.path("practicePageSupport");
        var practices = new ArrayList<PracticePageSupportDTO.PracticeSupportDTO>();
        for (JsonNode practice : support.path("practices"))
            practices.add(mapper.treeToValue(practice, PracticePageSupportDTO.PracticeSupportDTO.class));
        return new AdmissionResponseDTO(
                AdmissionResponseDTO.CONTRACT_VERSION,
                job.getReviewId(),
                job.getId(),
                0,
                response.path("admissionDigest").asString(),
                admitted,
                List.of(),
                new PracticePageSupportDTO.Complete(
                        PracticePageSupport.State.COMPLETE,
                        Instant.parse(support.path("readAt").asString()),
                        practices),
                mapper.treeToValue(response.path("conversationEligibility"), ConversationEligibilityDTO.class),
                mapper.treeToValue(response.path("privateHistory"), PrivateFeedbackHistoryDTO.class),
                publicReviewed,
                new PrivateProgressDTO(List.of(), 0, 0));
    }

    private JsonNode readJson(Path file) {
        try {
            return mapper.readTree(Files.readAllBytes(file));
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private static Map<String, byte[]> workspace(Path root) throws IOException {
        var files = new TreeMap<String, byte[]>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList())
                files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        return files;
    }
}

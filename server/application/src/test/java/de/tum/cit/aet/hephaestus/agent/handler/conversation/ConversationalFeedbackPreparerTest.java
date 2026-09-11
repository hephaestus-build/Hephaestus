package de.tum.cit.aet.hephaestus.agent.handler.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackLedgerRecorder;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackSupersession;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.practices.feedback.ConversationBriefBody;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackThreadKey;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class ConversationalFeedbackPreparerTest extends BaseUnitTest {

    private static final long WS = 1L;
    private static final long ALICE = 7L;
    private static final long BOB = 8L;
    private static final String TESTS = "ships-tests-with-the-change";
    private static final String SIZE = "keeps-the-change-reviewable";

    private static final String OBSERVED = "On !18, !20 and !22 the test landed a push after the review comment.";
    private static final String REALISATION = "Writing the test last is what leaves the review to find the gap.";
    private static final String EVIDENCE = "On !18, !20 and !22 the test arrived a push later.";
    private static final String SELF_CHECK = "They name a check they could run before pushing.";

    private final FeedbackRepository feedbackRepository = mock(FeedbackRepository.class);
    private final FeedbackObservationRepository feedbackObservationRepository =
            mock(FeedbackObservationRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final OutboundEgressGuard egressGuard = mock(OutboundEgressGuard.class);

    private final ObservationRepository observationRepository = mock(ObservationRepository.class);
    private final FeedbackSupersession supersession = mock(FeedbackSupersession.class);

    private final ConversationalFeedbackPreparer preparer = new ConversationalFeedbackPreparer(
            feedbackRepository,
            feedbackObservationRepository,
            observationRepository,
            supersession,
            eventPublisher,
            egressGuard);
    private final Map<UUID, String> practiceSlugByObservation = new HashMap<>();

    @BeforeEach
    void deliveryIsAllowedAndNothingIsPreparedYet() {
        lenient().when(egressGuard.deliveryAllowed(any())).thenReturn(true);
        lenient()
                .when(supersession.supersede(anyLong(), anyLong(), any(), any()))
                .thenReturn(FeedbackSupersession.Outcome.standalone());
        lenient().when(feedbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient()
                .when(observationRepository.practiceSlugsFor(any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    Collection<UUID> ids = invocation.getArgument(0);
                    List<ObservationRepository.ObservationPracticeSlug> rows = new ArrayList<>();
                    for (UUID id : ids) {
                        String slug = practiceSlugByObservation.get(id);
                        if (slug != null) {
                            rows.add(new StubPracticeSlug(id, slug));
                        }
                    }
                    return rows;
                });
    }

    private record StubPracticeSlug(UUID observationId, String practiceSlug)
            implements ObservationRepository.ObservationPracticeSlug {
        @Override
        public UUID getObservationId() {
            return observationId;
        }

        @Override
        public String getPracticeSlug() {
            return practiceSlug;
        }
    }

    @Test
    void carriesTheMove() {
        Observation observation = problem(ALICE, TESTS, Severity.MAJOR);

        int prepared = preparer.prepare(UUID.randomUUID(), WS, List.of(observation), List.of(move(TESTS)));

        assertThat(prepared).isEqualTo(1);
        Feedback unit = saved().getFirst();
        assertThat(unit.getChannel()).isEqualTo(FeedbackChannel.IN_CHAT);
        assertThat(unit.getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
        assertThat(unit.getSuppressionReason()).isNull();
        ConversationBriefBody.Brief brief = ConversationBriefBody.parse(unit.getBody());
        assertThat(brief).isNotNull();
        assertThat(brief.situation()).isEqualTo(OBSERVED);
        assertThat(brief.capability()).isEqualTo(REALISATION);
        assertThat(brief.evidenceSummary()).isEqualTo(EVIDENCE);
        assertThat(brief.inConversationSignal()).isEqualTo(SELF_CHECK);
    }

    @Test
    void composedMovesDriveSelection() {
        Observation loud = problem(ALICE, SIZE, Severity.CRITICAL);
        Observation quiet = problem(ALICE, TESTS, Severity.MINOR);

        int prepared = preparer.prepare(UUID.randomUUID(), WS, List.of(loud, quiet), List.of(move(TESTS)));

        assertThat(prepared).isEqualTo(1);
        assertThat(saved()).singleElement().satisfies(unit -> {
            assertThat(unit.getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
            assertThat(ConversationBriefBody.parse(unit.getBody())).isNotNull();
        });
    }

    @Test
    void collapsesLociOfOnePractice() {
        List<Observation> admitted = List.of(
                problem(ALICE, TESTS, Severity.MAJOR),
                problem(ALICE, TESTS, Severity.MAJOR),
                problem(BOB, TESTS, Severity.MAJOR));

        int prepared = preparer.prepare(UUID.randomUUID(), WS, admitted, List.of(move(TESTS)));

        assertThat(prepared).isEqualTo(2);
        assertThat(saved()).extracting(Feedback::getRecipientUserId).containsExactlyInAnyOrder(ALICE, BOB);
    }

    @Test
    void dropsAMoveWithNoEvidence() {
        int prepared = preparer.prepare(
                UUID.randomUUID(), WS, List.of(problem(ALICE, SIZE, Severity.MAJOR)), List.of(move(TESTS)));

        assertThat(prepared).isZero();
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void capsAtThreePerRecipient() {
        List<Observation> admitted = List.of(
                problem(ALICE, "p-one", Severity.CRITICAL),
                problem(ALICE, "p-two", Severity.MAJOR),
                problem(ALICE, "p-three", Severity.MAJOR),
                problem(ALICE, "p-four", Severity.MINOR));
        List<ComposedFeedbackUnit> composed = List.of(move("p-one"), move("p-two"), move("p-three"), move("p-four"));

        int prepared = preparer.prepare(UUID.randomUUID(), WS, admitted, composed);

        assertThat(prepared).isEqualTo(ConversationalFeedbackPreparer.TOP_N_PER_RECIPIENT);
        assertThat(saved())
                .filteredOn(unit -> unit.getDeliveryState() == FeedbackDeliveryState.SUPPRESSED)
                .singleElement()
                .satisfies(unit -> {
                    assertThat(unit.getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.VOLUME_CAPPED);
                    assertThat(unit.getBody()).isNull();
                });
    }

    @Test
    void raisedRowsCarryTheHabitThread() {
        List<Observation> admitted = List.of(
                problem(ALICE, "p-one", Severity.CRITICAL),
                problem(ALICE, "p-two", Severity.MAJOR),
                problem(ALICE, "p-three", Severity.MAJOR),
                problem(ALICE, "p-four", Severity.MINOR));
        List<ComposedFeedbackUnit> composed = List.of(move("p-one"), move("p-two"), move("p-three"), move("p-four"));

        preparer.prepare(UUID.randomUUID(), WS, admitted, composed);

        assertThat(saved())
                .filteredOn(unit -> unit.getDeliveryState() == FeedbackDeliveryState.PREPARED)
                .allSatisfy(unit -> assertThat(unit.getThreadKey()).isNotBlank())
                .extracting(Feedback::getThreadKey)
                .containsExactlyInAnyOrder(
                        threadKeyFor("p-one", ALICE), threadKeyFor("p-two", ALICE), threadKeyFor("p-three", ALICE));
        assertThat(saved())
                .filteredOn(unit -> unit.getDeliveryState() == FeedbackDeliveryState.SUPPRESSED)
                .singleElement()
                .extracting(Feedback::getThreadKey)
                .isNull();
    }

    @Test
    void missingCompositionCreatesNoQueueEntry() {
        int prepared =
                preparer.prepare(UUID.randomUUID(), WS, List.of(problem(ALICE, TESTS, Severity.MAJOR)), List.of());

        assertThat(prepared).isZero();
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void supersedeRetiresTheQueuedMove() {
        UUID retired = UUID.randomUUID();
        String threadKey = threadKeyFor(TESTS, ALICE);
        when(supersession.supersede(WS, ALICE, FeedbackChannel.IN_CHAT, threadKey))
                .thenReturn(new FeedbackSupersession.Outcome(FeedbackSupersession.Disposition.SUPERSEDED, retired));

        int prepared = preparer.prepare(
                UUID.randomUUID(),
                WS,
                List.of(problem(ALICE, TESTS, Severity.MAJOR)),
                List.of(supersede(TESTS, threadKey)));

        assertThat(prepared).isEqualTo(1);
        assertThat(saved()).singleElement().satisfies(unit -> {
            assertThat(unit.getThreadKey()).isEqualTo(threadKey);
            assertThat(unit.getReplacesId()).isEqualTo(retired);
            assertThat(unit.getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
        });
    }

    @Test
    void supersedeLosingToADeliveredMoveStillWrites() {
        UUID alreadyRaised = UUID.randomUUID();
        String threadKey = threadKeyFor(TESTS, ALICE);
        when(supersession.supersede(WS, ALICE, FeedbackChannel.IN_CHAT, threadKey))
                .thenReturn(
                        new FeedbackSupersession.Outcome(FeedbackSupersession.Disposition.CONTINUED, alreadyRaised));

        int prepared = preparer.prepare(
                UUID.randomUUID(),
                WS,
                List.of(problem(ALICE, TESTS, Severity.MAJOR)),
                List.of(supersede(TESTS, threadKey)));

        assertThat(prepared).isEqualTo(1);
        assertThat(saved()).singleElement().satisfies(unit -> {
            assertThat(unit.getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
            assertThat(unit.getReplacesId()).isEqualTo(alreadyRaised);
        });
    }

    @Test
    void refusesACrossHabitSupersession() {
        String someoneElsesHabit = threadKeyFor(SIZE, ALICE);

        int prepared = preparer.prepare(
                UUID.randomUUID(),
                WS,
                List.of(problem(ALICE, TESTS, Severity.MAJOR)),
                List.of(supersede(TESTS, someoneElsesHabit)));

        assertThat(prepared).isEqualTo(1);
        verify(supersession, never()).supersede(anyLong(), anyLong(), any(), any());
        assertThat(saved()).singleElement().satisfies(unit -> {
            assertThat(unit.getThreadKey()).isEqualTo(threadKeyFor(TESTS, ALICE));
            assertThat(unit.getReplacesId()).isNull();
        });
    }

    @Test
    void anUnresolvedPracticeClaimsNothing() {
        preparer.prepare(UUID.randomUUID(), WS, List.of(problem(ALICE, null, Severity.MAJOR)), List.of());

        verify(supersession, never()).supersede(anyLong(), anyLong(), any(), any());
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void reRunDoesNotSupersedeTwice() {
        when(feedbackRepository.existsByAgentJobIdAndPosition(any(), anyInt())).thenReturn(true);

        preparer.prepare(
                UUID.randomUUID(),
                WS,
                List.of(problem(ALICE, TESTS, Severity.MAJOR)),
                List.of(supersede(TESTS, threadKeyFor(TESTS, ALICE))));

        verify(supersession, never()).supersede(anyLong(), anyLong(), any(), any());
    }

    @Test
    void reRunIsIdempotent() {
        when(feedbackRepository.existsByAgentJobIdAndPosition(any(), anyInt())).thenReturn(true);
        List<Observation> admitted =
                List.of(problem(ALICE, TESTS, Severity.MAJOR), problem(ALICE, SIZE, Severity.MINOR));

        int prepared = preparer.prepare(UUID.randomUUID(), WS, admitted, List.of(move(TESTS), move(SIZE)));

        assertThat(prepared).isZero();
        verify(feedbackRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void ordinalsIgnoreComposition() {
        List<Observation> admitted =
                List.of(problem(ALICE, SIZE, Severity.CRITICAL), problem(ALICE, TESTS, Severity.MINOR));

        preparer.prepare(UUID.randomUUID(), WS, admitted, List.of(move(TESTS)));

        assertThat(saved())
                .singleElement()
                .extracting(Feedback::getPosition)
                .isEqualTo(FeedbackLedgerRecorder.IN_CHAT_UNIT_ORDINAL_BASE + 1);
    }

    @Test
    void withholdWritesNothing() {
        int prepared = preparer.prepare(
                UUID.randomUUID(), WS, List.of(problem(ALICE, TESTS, Severity.MAJOR)), List.of(withhold(TESTS)));

        assertThat(prepared).isZero();
        verify(feedbackRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void withholdIsNotSilence() {
        List<Observation> admitted =
                List.of(problem(ALICE, TESTS, Severity.MAJOR), problem(ALICE, SIZE, Severity.CRITICAL));

        int prepared = preparer.prepare(UUID.randomUUID(), WS, admitted, List.of(withhold(TESTS)));

        assertThat(prepared).isZero();
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void doesNotFallBackToTheRanking() {
        List<Observation> admitted =
                List.of(problem(ALICE, TESTS, Severity.MINOR), problem(ALICE, SIZE, Severity.CRITICAL));

        int prepared = preparer.prepare(UUID.randomUUID(), WS, admitted, List.of());

        assertThat(prepared).isZero();
        verify(feedbackRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void unresolvedPracticeWithoutCompositionCreatesNothing() {
        int prepared =
                preparer.prepare(UUID.randomUUID(), WS, List.of(problem(ALICE, null, Severity.MAJOR)), List.of());

        assertThat(prepared).isZero();
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void silentModePreparesNothing() {
        when(egressGuard.deliveryAllowed(any())).thenReturn(false);

        int prepared = preparer.prepare(
                UUID.randomUUID(), WS, List.of(problem(ALICE, TESTS, Severity.MAJOR)), List.of(move(TESTS)));

        assertThat(prepared).isZero();
        verify(feedbackRepository, never()).save(any());
    }

    private List<Feedback> saved() {
        ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    private static String threadKeyFor(String practiceSlug, long recipient) {
        return FeedbackThreadKey.forPractice(practiceSlug, recipient, FeedbackChannel.IN_CHAT);
    }

    private static ComposedFeedbackUnit move(String practiceSlug) {
        return move(practiceSlug, ComposedFeedbackUnit.Action.NEW, null);
    }

    private static ComposedFeedbackUnit supersede(String practiceSlug, @Nullable String supersedesThreadKey) {
        return move(practiceSlug, ComposedFeedbackUnit.Action.SUPERSEDE, supersedesThreadKey);
    }

    private static ComposedFeedbackUnit move(
            String practiceSlug, ComposedFeedbackUnit.Action action, @Nullable String supersedesThreadKey) {
        return new ComposedFeedbackUnit(
                FeedbackChannel.IN_CHAT,
                practiceSlug,
                List.of("obs-0"),
                action,
                supersedesThreadKey,
                null,
                "The test arrives after the review, not with the change",
                null,
                null,
                new ComposedFeedbackUnit.ConversationBrief(OBSERVED, REALISATION, EVIDENCE, SELF_CHECK, null),
                null);
    }

    private static ComposedFeedbackUnit withhold(String practiceSlug) {
        return new ComposedFeedbackUnit(
                FeedbackChannel.IN_CHAT,
                practiceSlug,
                List.of("obs-0"),
                ComposedFeedbackUnit.Action.WITHHOLD,
                null,
                ComposedFeedbackUnit.WithholdReason.ALREADY_SAID,
                null,
                null,
                null,
                null,
                null);
    }

    private Observation problem(long about, @Nullable String practiceSlug, Severity severity) {
        Observation observation = mock(Observation.class);
        UUID id = UUID.randomUUID();
        lenient().when(observation.getId()).thenReturn(id);
        lenient().when(observation.getPresence()).thenReturn(Presence.ABSENT);
        org.mockito.Mockito.lenient().when(observation.getAssessmentStatus()).thenReturn(AssessmentStatus.ASSESSED);
        lenient().when(observation.getAssessment()).thenReturn(Assessment.BAD);
        lenient().when(observation.getSeverity()).thenReturn(severity);
        lenient().when(observation.getArtifactKind()).thenReturn(ArtifactKinds.PULL_REQUEST);
        lenient().when(observation.getArtifactId()).thenReturn(100L);
        lenient().when(observation.getAboutUserId()).thenReturn(about);
        lenient().when(observation.getOrigin()).thenReturn(ObservationOrigin.LIVE);
        if (practiceSlug != null) {
            practiceSlugByObservation.put(id, practiceSlug);
        }
        return observation;
    }
}

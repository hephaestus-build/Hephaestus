package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackLedgerRecorder;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackSupersession;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackThreadKey;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** What actually gets written, and — as importantly — what does not. */
class InAppFeedbackPreparerTest extends BaseUnitTest {

    private static final UUID JOB_ID = UUID.randomUUID();
    private static final long WORKSPACE_ID = 7L;
    private static final int BASE = FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE;

    private final FeedbackRepository feedbackRepository = mock(FeedbackRepository.class);
    private final FeedbackObservationRepository feedbackObservationRepository =
            mock(FeedbackObservationRepository.class);
    private final FeedbackSupersession supersession = mock(FeedbackSupersession.class);
    private final InAppFeedbackPreparer preparer =
            new InAppFeedbackPreparer(feedbackRepository, feedbackObservationRepository, supersession);

    @Test
    void writesAnAdmittedMessageAsAPreparedInAppUnitCarryingItsBody() {
        stubSave();

        int prepared = preparer.prepare(JOB_ID, WORKSPACE_ID, 11L, List.of(admitted("ships-tests")), BASE);

        assertThat(prepared).isEqualTo(1);
        Feedback written = captureSaved().getFirst();
        assertThat(written.getChannel()).isEqualTo(FeedbackChannel.IN_APP);
        assertThat(written.getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
        assertThat(written.getRecipientUserId()).isEqualTo(11L);
        assertThat(written.getAboutUserId()).isEqualTo(11L);
        assertThat(written.getPosition()).isEqualTo(BASE);
        // Carries its words at write time — there is no later actor on this lane to compose them.
        assertThat(written.getBody()).contains("### A pattern").contains("**Try next:** Do the thing");
        // Unanchored: the message is about several pieces of work, so naming one would misdescribe it.
        assertThat(written.getArtifactId()).isNull();
    }

    /**
     * The bug this test exists for: one job files observations against several people, and
     * {@code (agent_job_id, position)} is unique. A base fixed for the whole job would make the second
     * recipient's first unit collide with the first recipient's, and the idempotency guard would read
     * that collision as "already written" and drop the row without a word.
     */
    @Test
    void givesEachRecipientOrdinalsThatCannotCollideWithAnothers() {
        stubSave();

        preparer.prepare(JOB_ID, WORKSPACE_ID, 11L, List.of(admitted("a"), admitted("b")), BASE);
        preparer.prepare(JOB_ID, WORKSPACE_ID, 22L, List.of(admitted("a"), admitted("b")), BASE + 2);

        assertThat(captureSaved())
                .extracting(Feedback::getPosition)
                .containsExactly(BASE, BASE + 1, BASE + 2, BASE + 3);
    }

    /**
     * A refusal that is a property of the evidence is not a withholding to explain — no message was ever
     * owed, so no row is written and no ordinal is consumed by a body that does not exist.
     */
    @Test
    void writesNothingForAMessageTheRouterRefused() {
        int prepared = preparer.prepare(
                JOB_ID,
                WORKSPACE_ID,
                11L,
                List.of(new InAppFeedbackPreparer.RoutedMessage(
                        message("x"), InAppRoutingDecision.UNCORROBORATED, List.of(), null)),
                BASE);

        assertThat(prepared).isZero();
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void isIdempotentOnARerunOfTheSameJob() {
        when(feedbackRepository.existsByAgentJobIdAndPosition(eq(JOB_ID), anyInt()))
                .thenReturn(true);

        int prepared = preparer.prepare(JOB_ID, WORKSPACE_ID, 11L, List.of(admitted("ships-tests")), BASE);

        assertThat(prepared).isZero();
        verify(feedbackRepository, never()).save(any());
    }

    /** Writing past the band would address another band's rows; fail loudly instead of silently. */
    @Test
    void refusesToWritePastItsOrdinalBand() {
        int lastSlot = BASE + FeedbackLedgerRecorder.UNIT_ORDINAL_BAND_WIDTH - 1;

        assertThatThrownBy(() ->
                        preparer.prepare(JOB_ID, WORKSPACE_ID, 11L, List.of(admitted("a"), admitted("b")), lastSlot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceed the ordinal band");
    }

    @Test
    void bindsEveryPieceOfEvidenceToTheUnitItStandsOn() {
        stubSave();
        Observation first = Observation.builder().id(UUID.randomUUID()).build();
        Observation second = Observation.builder().id(UUID.randomUUID()).build();

        preparer.prepare(
                JOB_ID,
                WORKSPACE_ID,
                11L,
                List.of(new InAppFeedbackPreparer.RoutedMessage(
                        message("ships-tests"), InAppRoutingDecision.ADMIT, List.of(first, second), null)),
                BASE);

        verify(feedbackObservationRepository, times(2)).insertIfAbsent(any(), any(), eq("PRIMARY"), anyInt());
    }

    /**
     * The key is what makes "find the card I would replace" a lookup rather than a guess, so a card must
     * be written under the same key the next run will compute for the same habit — hashed, in the one
     * vocabulary the column holds, not a lane-local spelling.
     */
    @Test
    void keysACardOnTheHabitItIsAbout() {
        stubSave();

        preparer.prepare(JOB_ID, WORKSPACE_ID, 11L, List.of(admitted("ships-tests")), BASE);

        assertThat(captureSaved().getFirst().getThreadKey()).isEqualTo(threadKeyFor("ships-tests", 11L));
    }

    @Test
    void shouldReplaceNothingWhenNoCardAboutTheHabitIsOpen() {
        stubSave();

        preparer.prepare(JOB_ID, WORKSPACE_ID, 11L, List.of(admitted("ships-tests")), BASE);

        assertThat(captureSaved().getFirst().getReplacesId()).isNull();
        verify(supersession, never()).replaceOpen(anyLong(), any());
    }

    @Test
    void shouldRetireTheOpenCardAndPointTheNewOneAtItWhenOneIsOpen() {
        stubSave();
        UUID open = UUID.randomUUID();
        when(supersession.replaceOpen(WORKSPACE_ID, open))
                .thenReturn(new FeedbackSupersession.Outcome(FeedbackSupersession.Disposition.SUPERSEDED, open));

        preparer.prepare(JOB_ID, WORKSPACE_ID, 11L, List.of(replacing("ships-tests", open)), BASE);

        Feedback written = captureSaved().getFirst();
        assertThat(written.getReplacesId()).isEqualTo(open);
        assertThat(written.getThreadKey()).isEqualTo(threadKeyFor("ships-tests", 11L));
    }

    /**
     * The whole point of the zero-rows path: the card this one was to replace was claimed by another run,
     * and the message it wrote is still owed to the developer. Losing the claim must cost the continuity
     * link, not the message.
     */
    @Test
    void stillWritesTheCardWhenThereWasNothingLeftToRetire() {
        stubSave();
        UUID open = UUID.randomUUID();
        when(supersession.replaceOpen(WORKSPACE_ID, open)).thenReturn(FeedbackSupersession.Outcome.standalone());

        int prepared = preparer.prepare(JOB_ID, WORKSPACE_ID, 11L, List.of(replacing("ships-tests", open)), BASE);

        assertThat(prepared).isEqualTo(1);
        assertThat(captureSaved().getFirst().getReplacesId()).isNull();
    }

    /**
     * A re-run reaching a unit it already wrote must not claim a second time: the card it would retire is
     * the one this very unit replaced on the first pass.
     */
    @Test
    void doesNotRetireASecondCardOnARerun() {
        when(feedbackRepository.existsByAgentJobIdAndPosition(eq(JOB_ID), anyInt()))
                .thenReturn(true);

        preparer.prepare(JOB_ID, WORKSPACE_ID, 11L, List.of(replacing("ships-tests", UUID.randomUUID())), BASE);

        verify(supersession, never()).replaceOpen(anyLong(), any());
    }

    private void stubSave() {
        when(feedbackRepository.save(any(Feedback.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private List<Feedback> captureSaved() {
        ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private static InAppFeedbackPreparer.RoutedMessage admitted(String practiceSlug) {
        return new InAppFeedbackPreparer.RoutedMessage(
                message(practiceSlug), InAppRoutingDecision.ADMIT, List.of(), null);
    }

    /** An admitted message about a habit whose previous card is still open on the page. */
    private static InAppFeedbackPreparer.RoutedMessage replacing(String practiceSlug, UUID open) {
        return new InAppFeedbackPreparer.RoutedMessage(
                message(practiceSlug), InAppRoutingDecision.ADMIT, List.of(), open);
    }

    private static ComposedInAppMessage message(String practiceSlug) {
        return new ComposedInAppMessage(practiceSlug, "A pattern", "What keeps happening.", "Do the thing");
    }

    private static String threadKeyFor(String practiceSlug, long recipientUserId) {
        return FeedbackThreadKey.forPractice(practiceSlug, recipientUserId, FeedbackChannel.IN_APP);
    }
}

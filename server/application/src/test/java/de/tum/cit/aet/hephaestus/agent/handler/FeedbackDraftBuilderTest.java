package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedProblemSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class FeedbackDraftBuilderTest extends BaseUnitTest {
    @Test
    void shouldAllocateAStableSummaryCopyFromAnAdmittedBasis() {
        var job = RuntimeReviewTestData.job();
        UUID resultId = UUID.randomUUID();
        var candidate = mock(ResultCandidate.class);
        when(candidate.developerId()).thenReturn(5L);
        var piece = new ComposedFeedback(
                FeedbackDestination.ON_WORK,
                List.of(resultId),
                "Use clear names.",
                List.of(),
                null,
                new FeedbackCompositionTrace(
                        List.of(new FeedbackCompositionTrace.CopySupport(
                                FeedbackCopyKind.SUMMARY,
                                0,
                                List.of(new FeedbackCompositionTrace.Support(resultId, null)))),
                        List.of(),
                        null,
                        null),
                null);
        var drafts = FeedbackDraftBuilder.build(
                job,
                new PrepareFeedbackRequestDTO(
                        job.getReviewId(),
                        job.getId(),
                        job.getAttempt(),
                        List.copyOf(Set.of(resultId)),
                        List.of(piece),
                        piece.destination() == FeedbackDestination.ON_WORK,
                        null),
                List.of(new AdmittedResultSnapshot(resultId, candidate, List.of())),
                List.of(),
                Map.of());
        var draft = drafts.getFirst();
        assertThat(draft.id()).isEqualTo(FeedbackIds.onWork(1, job.getReviewId(), 5));
        assertThat(draft.basis().getFirst().resultId()).isEqualTo(resultId);
        assertThat(draft.copies().getFirst().kind()).isEqualTo(FeedbackCopyKind.SUMMARY);
        assertThat(draft.copies().getFirst().body()).isNull();
        assertThat(draft.body()).isEqualTo("Use clear names.");
        assertThat(draft.copies().getFirst().writeKey())
                .isEqualTo(draft.copies().getFirst().id().toString());
    }

    @Test
    void shouldRejectAComposerResultThatWasNotInTheFencedBasis() {
        var job = RuntimeReviewTestData.job();
        var piece = new ComposedFeedback(
                FeedbackDestination.CONVERSATION,
                List.of(UUID.randomUUID()),
                "A private brief",
                List.of(),
                null,
                new FeedbackCompositionTrace(List.of(), List.of(), null, null),
                null);
        assertThatThrownBy(() -> FeedbackDraftBuilder.build(
                        job,
                        new PrepareFeedbackRequestDTO(
                                job.getReviewId(),
                                job.getId(),
                                job.getAttempt(),
                                List.copyOf(Set.of()),
                                List.of(piece),
                                piece.destination() == FeedbackDestination.ON_WORK,
                                null),
                        List.of(),
                        List.of(),
                        Map.of()))
                .isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldRejectDeveloperlessResultsAsFeedback() {
        var job = RuntimeReviewTestData.job();
        UUID id = UUID.randomUUID();
        var candidate = mock(ResultCandidate.class);
        when(candidate.developerId()).thenReturn(null);
        var piece = new ComposedFeedback(
                FeedbackDestination.CONVERSATION,
                List.of(id),
                "A private brief",
                List.of(),
                null,
                new FeedbackCompositionTrace(List.of(), List.of(), null, null),
                null);
        assertThatThrownBy(() -> FeedbackDraftBuilder.build(
                        job,
                        new PrepareFeedbackRequestDTO(
                                job.getReviewId(),
                                job.getId(),
                                job.getAttempt(),
                                List.copyOf(Set.of(id)),
                                List.of(piece),
                                piece.destination() == FeedbackDestination.ON_WORK,
                                null),
                        List.of(new AdmittedResultSnapshot(id, candidate, List.of())),
                        List.of(),
                        Map.of()))
                .isInstanceOf(AdmissionConflictException.class);
    }

    @Test
    void shouldRetainTheSelectedSecondCitationAndMixedQuietDecisionWithItsWitness() {
        var job = RuntimeReviewTestData.job();
        UUID resultId = UUID.randomUUID(), problemId = UUID.randomUUID(), quietId = UUID.randomUUID();
        var candidate = mock(ResultCandidate.class);
        when(candidate.developerId()).thenReturn(5L);
        var trace = new FeedbackCompositionTrace(
                List.of(new FeedbackCompositionTrace.CopySupport(
                        FeedbackCopyKind.LINE_NOTE,
                        0,
                        List.of(new FeedbackCompositionTrace.Support(resultId, problemId)))),
                List.of(new FeedbackCompositionTrace.QuietDecision(
                        List.of(new FeedbackCompositionTrace.Support(quietId, null)),
                        "ALREADY_SAID",
                        List.of("github:review-comment:7"))),
                null,
                null);
        var piece = new ComposedFeedback(
                FeedbackDestination.ON_WORK,
                List.of(resultId, quietId),
                null,
                List.of(new ComposedFeedback.Note(problemId, 1, "Check the token before forwarding it.")),
                null,
                trace,
                null);
        var draft = FeedbackDraftBuilder.build(
                        job,
                        new PrepareFeedbackRequestDTO(
                                job.getReviewId(),
                                job.getId(),
                                job.getAttempt(),
                                List.copyOf(Set.of(resultId, quietId)),
                                List.of(piece),
                                piece.destination() == FeedbackDestination.ON_WORK,
                                null),
                        List.of(
                                new AdmittedResultSnapshot(
                                        resultId,
                                        candidate,
                                        List.of(new AdmittedProblemSnapshot(problemId, mock(ProblemCandidate.class)))),
                                new AdmittedResultSnapshot(quietId, candidate, List.of())),
                        List.of(),
                        Map.of(
                                new ComposedFeedback.Anchor(problemId, 0),
                                        new FeedbackAnchor("old.java", 4, 4, CitationSide.OLD),
                                new ComposedFeedback.Anchor(problemId, 1),
                                        new FeedbackAnchor("new.java", 8, 9, CitationSide.NEW)))
                .getFirst();
        assertThat(draft.copies().getFirst().path()).isEqualTo("new.java");
        assertThat(draft.copies().getFirst().startLine()).isEqualTo(8);
        assertThat(draft.copies().getFirst().side()).isEqualTo(CitationSide.NEW);
        assertThat(draft.basis()).hasSize(1);
        assertThat(draft.basis().getFirst().problemId()).isEqualTo(problemId);
        assertThat(draft.composition()).isEqualTo(trace);
    }

    @Test
    void shouldPassStructuredPracticePageFieldsToPreparation() {
        var job = RuntimeReviewTestData.job();
        UUID resultId = UUID.randomUUID();
        var candidate = mock(ResultCandidate.class);
        when(candidate.developerId()).thenReturn(5L);
        var trace = new FeedbackCompositionTrace(
                List.of(new FeedbackCompositionTrace.CopySupport(
                        FeedbackCopyKind.SUMMARY, 0, List.of(new FeedbackCompositionTrace.Support(resultId, null)))),
                List.of(),
                null,
                resultId);
        var page = new ComposedFeedback.Page(
                "Guard the token boundary",
                "Two changes forward unchecked tokens.",
                "Check invalid tokens before forwarding a request.");
        var piece = new ComposedFeedback(
                FeedbackDestination.PRACTICE_PAGE, List.of(resultId), null, List.of(), null, trace, page);
        var draft = FeedbackDraftBuilder.build(
                        job,
                        new PrepareFeedbackRequestDTO(
                                job.getReviewId(),
                                job.getId(),
                                job.getAttempt(),
                                List.copyOf(Set.of(resultId)),
                                List.of(piece),
                                piece.destination() == FeedbackDestination.ON_WORK,
                                null),
                        List.of(new AdmittedResultSnapshot(resultId, candidate, List.of())),
                        List.of(),
                        Map.of())
                .getFirst();
        assertThat(draft.body()).isNull();
        assertThat(draft.page()).isEqualTo(new FeedbackDraft.Page(page.title(), page.body(), page.nextStep()));
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackDestination.class,
            names = {"ON_WORK", "CONVERSATION"})
    void shouldBindOnlyPublicDraftsToTheRequestsContextRatherThanTheComposedTrace(FeedbackDestination destination) {
        var job = RuntimeReviewTestData.job();
        UUID resultId = UUID.randomUUID();
        var candidate = mock(ResultCandidate.class);
        when(candidate.developerId()).thenReturn(5L);
        var trace = new FeedbackCompositionTrace(
                        List.of(new FeedbackCompositionTrace.CopySupport(
                                destination == FeedbackDestination.ON_WORK
                                        ? FeedbackCopyKind.SUMMARY
                                        : FeedbackCopyKind.CHAT_TURN,
                                0,
                                List.of(new FeedbackCompositionTrace.Support(resultId, null)))),
                        List.of(),
                        null,
                        null)
                .withPublicationContext("untrusted-composed-context");
        var piece = new ComposedFeedback(
                destination, List.of(resultId), "Cover the failure case.", List.of(), null, trace, null);
        var request = new PrepareFeedbackRequestDTO(
                job.getReviewId(),
                job.getId(),
                job.getAttempt(),
                List.of(resultId),
                List.of(piece),
                destination == FeedbackDestination.ON_WORK,
                "captured-request-context");
        var draft = FeedbackDraftBuilder.build(
                        job,
                        request,
                        List.of(new AdmittedResultSnapshot(resultId, candidate, List.of())),
                        List.of(),
                        Map.of())
                .getFirst();
        var stored = Objects.requireNonNull(draft.composition());
        if (destination == FeedbackDestination.ON_WORK)
            assertThat(stored.publicationContext()).isEqualTo("captured-request-context");
        else assertThat(stored.publicationContext()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "ON_WORK, summary, summary",
        "ON_WORK, note, notes[0]",
        "CONVERSATION, summary, summary",
        "PRACTICE_PAGE, title, page.title",
        "PRACTICE_PAGE, body, page.body",
        "PRACTICE_PAGE, nextStep, page.nextStep"
    })
    void shouldRefuseThePackageWhenAnyModelWrittenTextBreaksTheTextPolicy(
            FeedbackDestination destination, String field, String expectedField) {
        var job = RuntimeReviewTestData.job();
        UUID resultId = UUID.randomUUID(), problemId = UUID.randomUUID();
        var candidate = mock(ResultCandidate.class);
        when(candidate.developerId()).thenReturn(5L);
        var admitted = List.of(new AdmittedResultSnapshot(
                resultId, candidate, List.of(new AdmittedProblemSnapshot(problemId, mock(ProblemCandidate.class)))));
        var anchors = Map.of(
                new ComposedFeedback.Anchor(problemId, 0), new FeedbackAnchor("Export.java", 4, 4, CitationSide.NEW));
        Function<String, PrepareFeedbackRequestDTO> request = words -> {
            @Nullable String summary = field.equals("summary") ? words : null;
            var notes = field.equals("note")
                    ? List.of(new ComposedFeedback.Note(problemId, 0, words))
                    : List.<ComposedFeedback.Note>of();
            var page = destination == FeedbackDestination.PRACTICE_PAGE
                    ? new ComposedFeedback.Page(
                            field.equals("title") ? words : "Keep the failure cause",
                            field.equals("body") ? words : "Two handlers drop the cause.",
                            field.equals("nextStep") ? words : "Rethrow the failure with its cause.")
                    : null;
            var copy = switch (destination) {
                case ON_WORK ->
                    field.equals("note")
                            ? new FeedbackCompositionTrace.CopySupport(
                                    FeedbackCopyKind.LINE_NOTE,
                                    0,
                                    List.of(new FeedbackCompositionTrace.Support(resultId, problemId)))
                            : new FeedbackCompositionTrace.CopySupport(
                                    FeedbackCopyKind.SUMMARY,
                                    0,
                                    List.of(new FeedbackCompositionTrace.Support(resultId, null)));
                case CONVERSATION ->
                    new FeedbackCompositionTrace.CopySupport(
                            FeedbackCopyKind.CHAT_TURN,
                            0,
                            List.of(new FeedbackCompositionTrace.Support(resultId, null)));
                case PRACTICE_PAGE ->
                    new FeedbackCompositionTrace.CopySupport(
                            FeedbackCopyKind.SUMMARY, 0, List.of(new FeedbackCompositionTrace.Support(resultId, null)));
            };
            var piece = new ComposedFeedback(
                    destination,
                    List.of(resultId),
                    summary,
                    notes,
                    null,
                    new FeedbackCompositionTrace(List.of(copy), List.of(), null, resultId),
                    page);
            return new PrepareFeedbackRequestDTO(
                    job.getReviewId(),
                    job.getId(),
                    job.getAttempt(),
                    List.of(resultId),
                    List.of(piece),
                    destination == FeedbackDestination.ON_WORK,
                    null);
        };
        assertThat(FeedbackDraftBuilder.build(
                        job, request.apply("Keep the failure cause."), admitted, List.of(), anchors))
                .hasSize(1);
        assertThatThrownBy(() -> FeedbackDraftBuilder.build(
                        job, request.apply("Ask @octocat about the cause."), admitted, List.of(), anchors))
                .isInstanceOfSatisfying(FeedbackTextRefusedException.class, refused -> {
                    assertThat(refused.destination()).isEqualTo(destination);
                    assertThat(refused.rule()).isEqualTo("MENTION");
                    assertThat(refused.field()).isEqualTo(expectedField);
                });
    }
}

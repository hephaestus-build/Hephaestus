package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepairStatus;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The readback flags a note of this piece whose problem a note of another review on the work also cites, by practice
 * and identity, while that note still counts: posted, not withdrawn, not closed as fixed. Pairs inside the piece are
 * the check's, so they are not flagged again.
 */
@Tag("unit")
class FeedbackReadbackRepeatsTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final long WORKSPACE = 1;
    private static final long ITEM = 7;
    private static final long DEVELOPER = 3;
    private static final Instant AT = Instant.parse("2026-11-22T10:00:00Z");
    private static final UUID THIS_REVIEW = UUID.randomUUID();
    private static final UUID OTHER_REVIEW = UUID.randomUUID();
    private static final UUID THIS_PIECE = UUID.randomUUID();
    private static final UUID OTHER_PIECE = UUID.randomUUID();
    private static final UUID THIS_RESULT = UUID.randomUUID();
    private static final UUID OTHER_RESULT = UUID.randomUUID();
    private static final UUID THIS_PROBLEM = UUID.randomUUID();
    private static final UUID OTHER_PROBLEM = UUID.randomUUID();
    private static final String IDENTITY = "key:dropped-failure";
    private static final String OTHER_BODY = "This catch hides the failure. Record it so the cause is not lost.";

    private final FeedbackRepository feedback = mock(FeedbackRepository.class);
    private final FeedbackCopyRepository copies = mock(FeedbackCopyRepository.class);
    private final FeedbackVisibilityService visibility = mock(FeedbackVisibilityService.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final FeedbackPublicHistory history = mock(FeedbackPublicHistory.class);
    private final FeedbackReadbackRepeats repeats = new FeedbackReadbackRepeats(
            feedback, copies, visibility, reviews, mock(ResultRepository.class), history, MAPPER);

    @Test
    void shouldFlagEachNoteOfThisPieceWhenANoteOfAnotherReviewCitesTheSameProblem() {
        var scenario = new Scenario();
        var first = scenario.note(THIS_PIECE, 1, THIS_RESULT, THIS_PROBLEM);
        var second = scenario.note(THIS_PIECE, 2, THIS_RESULT, THIS_PROBLEM);
        var other = scenario.note(OTHER_PIECE, 1, OTHER_RESULT, OTHER_PROBLEM);
        scenario.stub("handles-errors");

        assertThat(repeats.of(scenario.piece(), List.of(found(first), found(second))))
                .containsExactly(repeat(first, other), repeat(second, other));
    }

    @Test
    void shouldNotFlagTwoNotesOfThisPieceThatCiteTheSameProblem() {
        var scenario = new Scenario();
        var first = scenario.note(THIS_PIECE, 1, THIS_RESULT, THIS_PROBLEM);
        var second = scenario.note(THIS_PIECE, 2, THIS_RESULT, THIS_PROBLEM);
        scenario.stub("handles-errors");

        assertThat(repeats.of(scenario.piece(), List.of(found(first), found(second))))
                .isEmpty();
    }

    @Test
    void shouldNotFlagANoteWhenTheOtherNoteIsClosedAsFixed() {
        var scenario = new Scenario();
        var mine = scenario.note(THIS_PIECE, 1, THIS_RESULT, THIS_PROBLEM);
        var other = scenario.note(OTHER_PIECE, 1, OTHER_RESULT, OTHER_PROBLEM);
        scenario.stub("handles-errors");
        when(copies.findClosedAsFixed(eq(WORKSPACE), anyCollection())).thenReturn(List.of(other.getId()));

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).isEmpty();
    }

    @Test
    void shouldNotFlagANoteWhenTheOtherNoteIsRetracted() {
        var scenario = new Scenario();
        var mine = scenario.note(THIS_PIECE, 1, THIS_RESULT, THIS_PROBLEM);
        scenario.note(OTHER_PIECE, 1, OTHER_RESULT, OTHER_PROBLEM, FeedbackCopyState.RETRACTED);
        scenario.stub("handles-errors");

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).isEmpty();
    }

    @Test
    void shouldNotFlagANoteWhenTheOtherPieceIsWithdrawn() {
        var scenario = new Scenario();
        var mine = scenario.note(THIS_PIECE, 1, THIS_RESULT, THIS_PROBLEM);
        scenario.note(OTHER_PIECE, 1, OTHER_RESULT, OTHER_PROBLEM);
        scenario.stub("handles-errors");
        when(visibility.withdrawnAt(eq(WORKSPACE), anyCollection())).thenReturn(Map.of(OTHER_PIECE, AT));

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).isEmpty();
    }

    @Test
    void shouldNotFlagANoteWhenItsOwnThreadIsResolved() {
        var scenario = new Scenario();
        var mine = scenario.note(THIS_PIECE, 1, THIS_RESULT, THIS_PROBLEM);
        scenario.note(OTHER_PIECE, 1, OTHER_RESULT, OTHER_PROBLEM);
        scenario.stub("handles-errors");

        assertThat(repeats.of(
                        scenario.piece(),
                        List.of(new CopyReadback(
                                mine.getId(), CopyReadback.Status.FOUND, "b", "b", null, true, false))))
                .isEmpty();
    }

    @Test
    void shouldNotFlagANoteWhenTheOtherNoteCitesTheSameKeyForAnotherPractice() {
        var scenario = new Scenario();
        var mine = scenario.note(THIS_PIECE, 1, THIS_RESULT, THIS_PROBLEM);
        scenario.note(OTHER_PIECE, 1, OTHER_RESULT, OTHER_PROBLEM);
        scenario.stub("names-things");

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).isEmpty();
    }

    private static CopyReadback found(FeedbackCopy copy) {
        return new CopyReadback(copy.getId(), CopyReadback.Status.FOUND, "b", "b", null, false, false);
    }

    private static FeedbackRepairStatus.Repeat repeat(FeedbackCopy mine, FeedbackCopy other) {
        return new FeedbackRepairStatus.Repeat(
                mine.getId(),
                List.of(THIS_PROBLEM),
                new FeedbackRepairStatus.OtherNote(
                        other.getPath(), other.getStartLine(), other.getEndLine(), other.getExternalUrl(), OTHER_BODY));
    }

    /** This piece and another review's piece on the same work, with their notes and composition support. */
    private final class Scenario {
        private final List<FeedbackCopy> mine = new ArrayList<>();
        private final List<FeedbackCopy> others = new ArrayList<>();
        private final List<FeedbackCompositionTrace.CopySupport> mySupport = new ArrayList<>();
        private final List<FeedbackCompositionTrace.CopySupport> otherSupport = new ArrayList<>();

        Feedback piece() {
            return Feedback.builder()
                    .id(THIS_PIECE)
                    .workspaceId(WORKSPACE)
                    .reviewId(THIS_REVIEW)
                    .itemId(ITEM)
                    .developerId(DEVELOPER)
                    .destination(FeedbackDestination.ON_WORK)
                    .state(FeedbackState.DELIVERED)
                    .createdAt(AT)
                    .deliveredAt(AT)
                    .build();
        }

        FeedbackCopy note(UUID piece, int position, UUID result, UUID problem) {
            return note(piece, position, result, problem, FeedbackCopyState.POSTED);
        }

        FeedbackCopy note(UUID piece, int position, UUID result, UUID problem, FeedbackCopyState state) {
            var copy = FeedbackCopy.builder()
                    .id(UUID.randomUUID())
                    .workspaceId(WORKSPACE)
                    .feedbackId(piece)
                    .kind(FeedbackCopyKind.LINE_NOTE)
                    .position(position)
                    .state(state)
                    .body(piece.equals(THIS_PIECE) ? "Record the failure here." : OTHER_BODY)
                    .path("src/App.java")
                    .startLine(10 + position)
                    .endLine(10 + position)
                    .externalRef("note-" + piece + "-" + position)
                    .externalUrl("https://github.com/acme/app/pull/7#discussion_r" + position)
                    .createdAt(AT)
                    .build();
            (piece.equals(THIS_PIECE) ? mine : others).add(copy);
            (piece.equals(THIS_PIECE) ? mySupport : otherSupport)
                    .add(new FeedbackCompositionTrace.CopySupport(
                            FeedbackCopyKind.LINE_NOTE,
                            position,
                            List.of(new FeedbackCompositionTrace.Support(result, problem))));
            return copy;
        }

        /** Both results are read; this one is of {@code handles-errors}, the other one of {@code otherPractice}. */
        void stub(String otherPractice) {
            when(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(WORKSPACE, THIS_PIECE))
                    .thenReturn(mine);
            when(copies.findByWorkspaceIdAndFeedbackIdInOrderByPositionAsc(eq(WORKSPACE), anyCollection()))
                    .thenAnswer(invocation -> others);
            when(feedback.findDeliveredOnWorkOfOtherReviews(WORKSPACE, DEVELOPER, ITEM, THIS_REVIEW))
                    .thenReturn(
                            others.isEmpty()
                                    ? List.of()
                                    : List.of(new FeedbackRepository.OnWorkPiece(OTHER_PIECE, OTHER_REVIEW)));
            when(reviews.findByWorkspaceIdAndIdIn(eq(WORKSPACE), anyCollection()))
                    .thenReturn(List.of(
                            review(THIS_REVIEW, THIS_PIECE, mySupport),
                            review(OTHER_REVIEW, OTHER_PIECE, otherSupport)));
            var custody = new HistoryCustody(true, false, List.of(), List.of(), List.of());
            when(history.authorizeAll(anyLong(), anyCollection(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                    .thenReturn(Map.of(
                            THIS_RESULT,
                            new FeedbackPublicHistory.AuthorizedSupport(THIS_RESULT, "handles-errors", custody),
                            OTHER_RESULT,
                            new FeedbackPublicHistory.AuthorizedSupport(OTHER_RESULT, otherPractice, custody)));
            when(history.identities(eq(WORKSPACE), any()))
                    .thenReturn(Map.of(THIS_PROBLEM, IDENTITY, OTHER_PROBLEM, IDENTITY));
        }

        private Review review(UUID id, UUID piece, List<FeedbackCompositionTrace.CopySupport> support) {
            var checks = MAPPER.createObjectNode();
            checks.putObject("feedbackComposition")
                    .set(
                            piece.toString(),
                            MAPPER.valueToTree(new FeedbackCompositionTrace(support, List.of(), null, null)));
            return Review.builder().id(id).workspaceId(WORKSPACE).checks(checks).build();
        }
    }
}

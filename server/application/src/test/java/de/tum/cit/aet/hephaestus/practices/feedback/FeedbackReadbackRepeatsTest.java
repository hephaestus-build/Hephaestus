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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The readback flags a note of this piece whose problem a note of another review on the work also cites, by practice
 * and identity, when that note reached the work after this piece was prepared and still counts. Pairs inside the
 * piece are the check's, so they are not flagged again.
 */
@Tag("unit")
class FeedbackReadbackRepeatsTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final long WORKSPACE = 1;
    private static final long ITEM = 7;
    private static final Instant PREPARED = Instant.parse("2026-11-22T10:00:00Z");
    private static final Instant LATER = PREPARED.plusSeconds(60);
    private static final UUID THIS_REVIEW = UUID.randomUUID();
    private static final UUID OTHER_REVIEW = UUID.randomUUID();
    private static final UUID THIS_PIECE = UUID.randomUUID();
    private static final UUID OTHER_PIECE = UUID.randomUUID();
    private static final UUID THIS_RESULT = UUID.randomUUID();
    private static final UUID OTHER_RESULT = UUID.randomUUID();
    private static final UUID THIS_PROBLEM = UUID.randomUUID();
    private static final UUID OTHER_PROBLEM = UUID.randomUUID();
    private static final UUID UNRELATED_PROBLEM = UUID.randomUUID();
    private static final String IDENTITY = "key:dropped-failure";
    private static final String OTHER_BODY = "This catch hides the failure. Record it so the cause is not lost.";
    private static final HistoryCustody COMPLETE = new HistoryCustody(true, false, List.of(), List.of(), List.of());

    private final FeedbackRepository feedback = mock(FeedbackRepository.class);
    private final FeedbackCopyRepository copies = mock(FeedbackCopyRepository.class);
    private final FeedbackBasisRepository basis = mock(FeedbackBasisRepository.class);
    private final FeedbackVisibilityService visibility = mock(FeedbackVisibilityService.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final FeedbackPublicHistory history = mock(FeedbackPublicHistory.class);
    private final FeedbackReadbackRepeats repeats = new FeedbackReadbackRepeats(
            feedback, copies, basis, visibility, reviews, mock(ResultRepository.class), history, MAPPER);

    @Test
    void shouldFlagEachNoteOfThisPieceWhenANoteOfAnotherReviewPostedLaterCitesTheSameProblem() {
        var scenario = new Scenario();
        var first = scenario.mine(1, THIS_PROBLEM);
        var second = scenario.mine(2, THIS_PROBLEM);
        var other = scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER);
        scenario.stub("handles-errors", COMPLETE);

        assertThat(repeats.of(scenario.piece(), List.of(found(first), found(second))))
                .containsExactly(repeat(first, other, OTHER_BODY), repeat(second, other, OTHER_BODY));
    }

    @Test
    void shouldNotFlagANoteWhenTheOtherNoteWasPostedBeforeThisPieceWasPrepared() {
        var scenario = new Scenario();
        var mine = scenario.mine(1, THIS_PROBLEM);
        scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, PREPARED.minusSeconds(60));
        scenario.stub("handles-errors", COMPLETE);

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).isEmpty();
    }

    @Test
    void shouldNotFlagTwoNotesOfThisPieceThatCiteTheSameProblem() {
        var scenario = new Scenario();
        var first = scenario.mine(1, THIS_PROBLEM);
        var second = scenario.mine(2, THIS_PROBLEM);
        // Another review's note on the work cites an unrelated problem, so the own pair is compared and left out.
        scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER, UNRELATED_PROBLEM);
        scenario.stub("handles-errors", COMPLETE);

        assertThat(repeats.of(scenario.piece(), List.of(found(first), found(second))))
                .isEmpty();
    }

    @Test
    void shouldLetTheNewestRevisionOfAProviderCommentSpeakForItWhenItRetractedTheComment() {
        var scenario = new Scenario();
        var mine = scenario.mine(1, THIS_PROBLEM);
        var newer = UUID.randomUUID();
        scenario.other(newer, 1, FeedbackCopyState.RETRACTED, LATER);
        scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER);
        scenario.stub("handles-errors", COMPLETE);

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).isEmpty();
    }

    @Test
    void shouldNameTheNewestRevisionOfAProviderCommentOnce() {
        var scenario = new Scenario();
        var mine = scenario.mine(1, THIS_PROBLEM);
        var newer = scenario.other(UUID.randomUUID(), 1, FeedbackCopyState.POSTED, LATER.plusSeconds(60));
        scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER);
        scenario.stub("handles-errors", COMPLETE);

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).containsExactly(repeat(mine, newer, OTHER_BODY));
    }

    @Test
    void shouldNotFlagANoteWhenTheOtherNoteIsClosedAsFixed() {
        var scenario = new Scenario();
        var mine = scenario.mine(1, THIS_PROBLEM);
        var other = scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER);
        scenario.stub("handles-errors", COMPLETE);
        when(copies.findClosedAsFixed(eq(WORKSPACE), anyCollection())).thenReturn(List.of(other.getId()));

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).isEmpty();
    }

    @Test
    void shouldNotFlagANoteWhenTheOtherPieceIsWithdrawn() {
        var scenario = new Scenario();
        var mine = scenario.mine(1, THIS_PROBLEM);
        scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER);
        scenario.stub("handles-errors", COMPLETE);
        when(visibility.withdrawnAt(eq(WORKSPACE), anyCollection())).thenReturn(Map.of(OTHER_PIECE, LATER));

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).isEmpty();
    }

    @Test
    void shouldNotFlagANoteWhenTheOtherPieceIsMarkedIncorrect() {
        var scenario = new Scenario();
        var mine = scenario.mine(1, THIS_PROBLEM);
        scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER);
        scenario.stub("handles-errors", COMPLETE);
        when(basis.incorrectFeedbackIds(eq(WORKSPACE), anyCollection())).thenReturn(List.of(OTHER_PIECE));

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).isEmpty();
    }

    @Test
    void shouldNotFlagANoteWhenTheOtherNoteRestsOnAnIncorrectResult() {
        var scenario = new Scenario();
        var mine = scenario.mine(1, THIS_PROBLEM);
        var other = scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER);
        scenario.stub("handles-errors", COMPLETE);
        when(copies.incorrectCopyIdsOf(eq(WORKSPACE), anyCollection())).thenReturn(Set.of(other.getId()));

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).isEmpty();
    }

    @Test
    void shouldNotFlagANoteWhenItsOwnThreadIsResolved() {
        var scenario = new Scenario();
        var mine = scenario.mine(1, THIS_PROBLEM);
        scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER);
        scenario.stub("handles-errors", COMPLETE);

        assertThat(repeats.of(
                        scenario.piece(),
                        List.of(new CopyReadback(
                                mine.getId(), CopyReadback.Status.FOUND, "b", "b", null, true, false))))
                .isEmpty();
    }

    @Test
    void shouldNotFlagANoteWhenTheOtherNoteCitesTheSameKeyForAnotherPractice() {
        var scenario = new Scenario();
        var mine = scenario.mine(1, THIS_PROBLEM);
        scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER);
        scenario.stub("names-things", COMPLETE);

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).isEmpty();
    }

    @Test
    void shouldLeaveTheOtherNotesWordsOutWhenItsCustodyIsIncomplete() {
        var scenario = new Scenario();
        var mine = scenario.mine(1, THIS_PROBLEM);
        var other = scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER);
        scenario.stub("handles-errors", new HistoryCustody(false, false, List.of(), List.of(), List.of()));

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).containsExactly(repeat(mine, other, null));
    }

    @Test
    void shouldLeaveTheOtherNotesWordsOutWhenTheReadMayNotUseAResultOfItsBasis() {
        var scenario = new Scenario();
        var mine = scenario.mine(1, THIS_PROBLEM);
        var other = scenario.other(OTHER_PIECE, 1, FeedbackCopyState.POSTED, LATER);
        scenario.stub("handles-errors", COMPLETE);
        when(basis.findReadRows(eq(WORKSPACE), anyCollection()))
                .thenReturn(List.of(
                        new FeedbackBasisRepository.ReadRow(OTHER_PIECE, OTHER_RESULT, OTHER_PROBLEM, 0),
                        new FeedbackBasisRepository.ReadRow(OTHER_PIECE, UUID.randomUUID(), null, 1)));

        assertThat(repeats.of(scenario.piece(), List.of(found(mine)))).containsExactly(repeat(mine, other, null));
    }

    private static CopyReadback found(FeedbackCopy copy) {
        return new CopyReadback(copy.getId(), CopyReadback.Status.FOUND, "b", "b", null, false, false);
    }

    private static FeedbackRepairStatus.Repeat repeat(FeedbackCopy mine, FeedbackCopy other, @Nullable String words) {
        return new FeedbackRepairStatus.Repeat(
                mine.getId(),
                List.of(THIS_PROBLEM),
                new FeedbackRepairStatus.OtherNote(
                        other.getPath(), other.getStartLine(), other.getEndLine(), other.getExternalUrl(), words));
    }

    /** This piece and the pieces of another review on the same work, newest first, with their composition support. */
    private final class Scenario {
        private final List<FeedbackCopy> mine = new ArrayList<>();
        private final List<FeedbackCopy> others = new ArrayList<>();
        private final Map<UUID, List<FeedbackCompositionTrace.CopySupport>> support = new LinkedHashMap<>();
        private final List<UUID> otherPieces = new ArrayList<>();

        Feedback piece() {
            return Feedback.builder()
                    .id(THIS_PIECE)
                    .workspaceId(WORKSPACE)
                    .reviewId(THIS_REVIEW)
                    .itemId(ITEM)
                    .developerId(3)
                    .destination(FeedbackDestination.ON_WORK)
                    .state(FeedbackState.DELIVERED)
                    .createdAt(PREPARED)
                    .deliveredAt(PREPARED)
                    .build();
        }

        FeedbackCopy mine(int position, UUID problem) {
            var copy = copy(THIS_PIECE, position, FeedbackCopyState.POSTED, PREPARED, "Record the failure here.");
            mine.add(copy);
            support(THIS_PIECE, position, THIS_RESULT, problem);
            return copy;
        }

        /** A note of another review's piece; pieces are added newest first, and one position shares one comment. */
        FeedbackCopy other(UUID piece, int position, FeedbackCopyState state, Instant postedAt) {
            return other(piece, position, state, postedAt, OTHER_PROBLEM);
        }

        FeedbackCopy other(UUID piece, int position, FeedbackCopyState state, Instant postedAt, UUID problem) {
            var copy = copy(piece, position, state, postedAt, OTHER_BODY);
            others.add(copy);
            if (!otherPieces.contains(piece)) otherPieces.add(piece);
            support(piece, position, OTHER_RESULT, problem);
            return copy;
        }

        private FeedbackCopy copy(UUID piece, int position, FeedbackCopyState state, Instant postedAt, String body) {
            boolean own = piece.equals(THIS_PIECE);
            return FeedbackCopy.builder()
                    .id(UUID.randomUUID())
                    .workspaceId(WORKSPACE)
                    .feedbackId(piece)
                    .kind(FeedbackCopyKind.LINE_NOTE)
                    .position(position)
                    .state(state)
                    .body(body)
                    .path("src/App.java")
                    .startLine(10 + position)
                    .endLine(10 + position)
                    .externalRef((own ? "mine-" : "other-") + position)
                    .externalUrl("https://github.com/acme/app/pull/7#discussion_r" + (own ? 0 : position))
                    .postedAt(postedAt)
                    .createdAt(postedAt)
                    .build();
        }

        private void support(UUID piece, int position, UUID result, UUID problem) {
            support.computeIfAbsent(piece, ignored -> new ArrayList<>())
                    .add(new FeedbackCompositionTrace.CopySupport(
                            FeedbackCopyKind.LINE_NOTE,
                            position,
                            List.of(new FeedbackCompositionTrace.Support(result, problem))));
        }

        /** This result is of {@code handles-errors}; the other one is of {@code otherPractice}, read with {@code custody}. */
        void stub(String otherPractice, HistoryCustody custody) {
            when(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(WORKSPACE, THIS_PIECE))
                    .thenReturn(mine);
            when(copies.findByWorkspaceIdAndFeedbackIdInOrderByPositionAsc(eq(WORKSPACE), anyCollection()))
                    .thenAnswer(invocation -> others);
            when(feedback.findDeliveredOnWorkOfOtherReviews(WORKSPACE, ITEM, THIS_REVIEW))
                    .thenReturn(otherPieces.stream()
                            .map(piece -> new FeedbackRepository.OnWorkPiece(piece, OTHER_REVIEW))
                            .toList());
            when(reviews.findByWorkspaceIdAndIdIn(eq(WORKSPACE), anyCollection()))
                    .thenReturn(List.of(
                            review(THIS_REVIEW, Set.of(THIS_PIECE)), review(OTHER_REVIEW, Set.copyOf(otherPieces))));
            when(basis.findReadRows(eq(WORKSPACE), anyCollection()))
                    .thenReturn(otherPieces.stream()
                            .map(piece -> new FeedbackBasisRepository.ReadRow(piece, OTHER_RESULT, OTHER_PROBLEM, 0))
                            .toList());
            when(history.authorizeAll(anyLong(), anyCollection(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                    .thenReturn(Map.of(
                            THIS_RESULT,
                            new FeedbackPublicHistory.AuthorizedSupport(THIS_RESULT, "handles-errors", COMPLETE),
                            OTHER_RESULT,
                            new FeedbackPublicHistory.AuthorizedSupport(OTHER_RESULT, otherPractice, custody)));
            var identities = new HashMap<UUID, String>();
            identities.put(THIS_PROBLEM, IDENTITY);
            identities.put(OTHER_PROBLEM, IDENTITY);
            identities.put(UNRELATED_PROBLEM, "key:another-problem");
            when(history.identities(eq(WORKSPACE), any())).thenReturn(identities);
        }

        private Review review(UUID id, Set<UUID> pieces) {
            var compositions = MAPPER.createObjectNode();
            for (var piece : pieces)
                compositions.set(
                        piece.toString(),
                        MAPPER.valueToTree(new FeedbackCompositionTrace(
                                Objects.requireNonNull(support.get(piece)), List.of(), null, null)));
            var checks = MAPPER.createObjectNode();
            checks.set("feedbackComposition", compositions);
            return Review.builder().id(id).workspaceId(WORKSPACE).checks(checks).build();
        }
    }
}

package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackResponseSuppressionFilter;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponse;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/** A developer's answer holds back the same problem later, but an answer on a private destination stays private. */
@Tag("integration")
class FeedbackResponseSuppressionIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    @Autowired
    FeedbackResponseSuppressionFilter suppression;

    private Problem problem;

    @BeforeEach
    void keyTheProblem() {
        problem = problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(
                        workspace.getId(), List.of(supportingResult.getId()))
                .getFirst();
        jdbc.update("UPDATE problem SET key='cover-failure' WHERE id=?", problem.getId());
    }

    @ParameterizedTest
    @CsvSource({
        "PRACTICE_PAGE,DISAGREE",
        "PRACTICE_PAGE,NOT_APPLICABLE",
        "CONVERSATION,DISAGREE",
        "CONVERSATION,NOT_APPLICABLE"
    })
    void shouldPrepareFeedbackOnTheWorkWhenTheDeveloperAnsweredAPrivatePiece(
            FeedbackDestination privately, FeedbackResolution resolution) {
        answer(privately, resolution, reviewId, supportingResult.getId(), problem.getId());

        prepare(false);

        assertThat(current().getState()).isEqualTo(FeedbackState.READY);
        assertThat(current().getStopReason()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"DISAGREE,REACTED_DISAGREE", "NOT_APPLICABLE,REACTED_NOT_APPLICABLE"})
    void shouldWithholdFeedbackOnTheWorkWhenTheDeveloperAnsweredAPieceOnTheWork(
            FeedbackResolution resolution, FeedbackStopReason expected) {
        answer(FeedbackDestination.ON_WORK, resolution, reviewId, supportingResult.getId(), problem.getId());

        prepare(false);

        assertThat(current().getState()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(current().getStopReason()).isEqualTo(expected);
    }

    @Test
    void shouldWithholdFeedbackOnTheWorkWhenTheDeveloperDisagreedOnTheWorkInAnEarlierReview() {
        var earlier = earlierReview();
        answer(
                FeedbackDestination.ON_WORK,
                FeedbackResolution.DISAGREE,
                earlier.review(),
                earlier.result(),
                earlier.problem());

        prepare(false);

        assertThat(current().getState()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(current().getStopReason()).isEqualTo(FeedbackStopReason.REACTED_DISAGREE);
    }

    @Test
    void shouldPrepareFeedbackOnTheWorkWhenTheDeveloperDisagreedPrivatelyInAnEarlierReview() {
        var earlier = earlierReview();
        answer(
                FeedbackDestination.PRACTICE_PAGE,
                FeedbackResolution.DISAGREE,
                earlier.review(),
                earlier.result(),
                earlier.problem());

        prepare(false);

        assertThat(current().getState()).isEqualTo(FeedbackState.READY);
        assertThat(current().getStopReason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackDestination.class,
            names = {"PRACTICE_PAGE", "CONVERSATION"})
    void shouldHoldBackPrivateFeedbackWhenTheDeveloperDisagreedWithAPrivatePiece(FeedbackDestination privately) {
        answer(privately, FeedbackResolution.DISAGREE, reviewId, supportingResult.getId(), problem.getId());

        assertThat(suppression.suppressedProblems(workspace.getId(), privately, List.of(problem.getId())))
                .containsExactlyEntriesOf(Map.of(problem.getId(), FeedbackStopReason.REACTED_DISAGREE));
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackDestination.class,
            names = {"PRACTICE_PAGE", "CONVERSATION"})
    void shouldHoldBackPrivateFeedbackWhenTheDeveloperDisagreedWithAPieceOnTheWork(FeedbackDestination privately) {
        answer(
                FeedbackDestination.ON_WORK,
                FeedbackResolution.DISAGREE,
                reviewId,
                supportingResult.getId(),
                problem.getId());

        assertThat(suppression.suppressedProblems(workspace.getId(), privately, List.of(problem.getId())))
                .containsExactlyEntriesOf(Map.of(problem.getId(), FeedbackStopReason.REACTED_DISAGREE));
    }

    private record Earlier(UUID review, UUID result, UUID problem) {}

    /** An earlier review of the same practice on the same work, whose ended result found the same problem key. */
    private Earlier earlierReview() {
        UUID review = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO review SELECT (jsonb_populate_record(NULL::review, to_jsonb(v)
                  || jsonb_build_object('id', ?::text))).* FROM review v WHERE v.id = ?
                """, review.toString(), reviewId);
        jdbc.update("""
                INSERT INTO review_practice SELECT (jsonb_populate_record(NULL::review_practice, to_jsonb(p)
                  || jsonb_build_object('review_id', ?::text))).* FROM review_practice p WHERE p.review_id = ?
                """, review.toString(), reviewId);
        jdbc.update("""
                INSERT INTO review_item SELECT (jsonb_populate_record(NULL::review_item, to_jsonb(m)
                  || jsonb_build_object('review_id', ?::text))).* FROM review_item m WHERE m.review_id = ?
                """, review.toString(), reviewId);
        // One statement: a NOT_MET result must hold its problem when the statement ends.
        UUID copiedProblem = Objects.requireNonNull(jdbc.queryForObject(
                """
                WITH copied AS (
                  INSERT INTO result SELECT (jsonb_populate_record(NULL::result, to_jsonb(r)
                    || jsonb_build_object('id', ?::text, 'review_id', ?::text,
                      'ended_at', r.recorded_at, 'end_reason', 'REPLACED'))).*
                  FROM result r WHERE r.id = ? RETURNING id)
                INSERT INTO problem SELECT (jsonb_populate_record(NULL::problem, to_jsonb(p)
                  || jsonb_build_object('id', gen_random_uuid()::text, 'result_id', (SELECT id FROM copied)::text))).*
                FROM problem p WHERE p.id = ? RETURNING id
                """, UUID.class, result.toString(), review.toString(), supportingResult.getId(), problem.getId()));
        return new Earlier(review, result, copiedProblem);
    }

    /** Delivers a piece of {@code review} to {@code destination} about the problem, and records the developer's answer. */
    private void answer(
            FeedbackDestination destination,
            FeedbackResolution resolution,
            UUID review,
            UUID resultId,
            UUID problemId) {
        var prior = feedback.saveAndFlush(Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .reviewId(review)
                .itemId(destination == FeedbackDestination.PRACTICE_PAGE ? null : itemId)
                .developerId(developerId)
                .destination(destination)
                .state(FeedbackState.DELIVERED)
                .body("Add a focused regression test.")
                .createdAt(AT)
                .deliveredAt(AT)
                .build());
        basis.saveAndFlush(FeedbackBasis.builder()
                .id(FeedbackIds.basis(prior.getId(), resultId, problemId))
                .workspaceId(workspace.getId())
                .feedbackId(prior.getId())
                .resultId(resultId)
                .problemId(problemId)
                .developerId(developerId)
                .ordinal(0)
                .build());
        responses.saveAndFlush(FeedbackResponse.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .feedbackId(prior.getId())
                .resolution(resolution)
                .comment(
                        resolution == FeedbackResolution.DISAGREE
                                ? "The failure case is covered by the contract test."
                                : null)
                .createdAt(AT)
                .build());
    }
}

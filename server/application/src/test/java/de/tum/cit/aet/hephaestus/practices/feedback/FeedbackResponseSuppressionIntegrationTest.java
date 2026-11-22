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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
    @EnumSource(
            value = FeedbackDestination.class,
            names = {"PRACTICE_PAGE", "CONVERSATION"})
    void shouldPrepareFeedbackOnTheWorkWhenTheDeveloperDisagreedWithAPrivatePiece(FeedbackDestination privately) {
        disagreeWith(privately);

        prepare(false);

        assertThat(current().getState()).isEqualTo(FeedbackState.READY);
        assertThat(current().getStopReason()).isNull();
    }

    @Test
    void shouldWithholdFeedbackOnTheWorkWhenTheDeveloperDisagreedWithAPieceOnTheWork() {
        disagreeWith(FeedbackDestination.ON_WORK);

        prepare(false);

        assertThat(current().getState()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(current().getStopReason()).isEqualTo(FeedbackStopReason.REACTED_DISAGREE);
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackDestination.class,
            names = {"PRACTICE_PAGE", "CONVERSATION"})
    void shouldHoldBackPrivateFeedbackWhenTheDeveloperDisagreedWithAPrivatePiece(FeedbackDestination privately) {
        disagreeWith(privately);

        assertThat(suppression.suppressedProblems(workspace.getId(), privately, List.of(problem.getId())))
                .containsExactlyEntriesOf(Map.of(problem.getId(), FeedbackStopReason.REACTED_DISAGREE));
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackDestination.class,
            names = {"PRACTICE_PAGE", "CONVERSATION"})
    void shouldHoldBackPrivateFeedbackWhenTheDeveloperDisagreedWithAPieceOnTheWork(FeedbackDestination privately) {
        disagreeWith(FeedbackDestination.ON_WORK);

        assertThat(suppression.suppressedProblems(workspace.getId(), privately, List.of(problem.getId())))
                .containsExactlyEntriesOf(Map.of(problem.getId(), FeedbackStopReason.REACTED_DISAGREE));
    }

    /** Delivers an earlier piece to {@code destination} about the problem, and records the developer's disagreement. */
    private void disagreeWith(FeedbackDestination destination) {
        var prior = feedback.saveAndFlush(Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .reviewId(reviewId)
                .itemId(destination == FeedbackDestination.PRACTICE_PAGE ? null : itemId)
                .developerId(developerId)
                .destination(destination)
                .state(FeedbackState.DELIVERED)
                .body("Add a focused regression test.")
                .createdAt(AT)
                .deliveredAt(AT)
                .build());
        basis.saveAndFlush(FeedbackBasis.builder()
                .id(FeedbackIds.basis(prior.getId(), supportingResult.getId(), problem.getId()))
                .workspaceId(workspace.getId())
                .feedbackId(prior.getId())
                .resultId(supportingResult.getId())
                .problemId(problem.getId())
                .developerId(developerId)
                .ordinal(0)
                .build());
        responses.saveAndFlush(FeedbackResponse.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .feedbackId(prior.getId())
                .resolution(FeedbackResolution.DISAGREE)
                .comment("The failure case is covered by the contract test.")
                .createdAt(AT)
                .build());
    }
}

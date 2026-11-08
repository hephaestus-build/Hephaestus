package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecision;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("integration")
class FeedbackRepairApprovalIntegrationTest extends AbstractFeedbackIntegrationTest {
    @Autowired
    FeedbackRepairApproval approvals;

    @Autowired
    FeedbackContentDigest digests;

    @Test
    void shouldRetainARealApprovalForFormattingThatKeepsWordsAndDestinations() {
        var prior = words("Read [the test](https://example.test/old).");
        var next = words("Read **[the test](https://example.test/old)**.");
        approve(prior, digests.current(workspace.getId(), prior.getId()));
        inherit(next, prior);
        assertThat(approvals.sameWords(prior, next)).isTrue();
        assertThat(approvals.approved(feedback.findById(next.getId()).orElseThrow()))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Read [the test](https://example.test/new).",
                "Read [the test](https://example.test/old \"Changed title\").",
                "Read ![the test](https://example.test/old)."
            })
    void shouldRequireApprovalAgainForAChangedLinkOrImage(String changed) {
        var prior = words("Read [the test](https://example.test/old).");
        var next = words(changed);
        approve(prior, digests.current(workspace.getId(), prior.getId()));
        inherit(next, prior);
        assertThat(approvals.sameWords(prior, next)).isFalse();
        assertThat(approvals.approved(feedback.findById(next.getId()).orElseThrow()))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"if ready:\n    delete()\n", "if ready:\ndelete()\n"})
    void shouldBindApprovalToExactCodeLiterals(String changed) {
        var prior = words("```python\nif ready:\n    delete()\n``` ");
        var next = words("```python\n" + changed + "``` ");
        approve(prior, digests.current(workspace.getId(), prior.getId()));
        inherit(next, prior);
        assertThat(approvals.approved(feedback.findById(next.getId()).orElseThrow()))
                .isEqualTo(changed.equals("if ready:\n    delete()\n"));
    }

    @Test
    void shouldRequireApprovalAgainForChangedWordsOrARevokedApproval() {
        var prior = words("Cover the failing path.");
        var changed = words("Cover the successful path.");
        approve(prior, digests.current(workspace.getId(), prior.getId()));
        inherit(changed, prior);
        assertThat(approvals.approved(feedback.findById(changed.getId()).orElseThrow()))
                .isFalse();
        var same = words("Cover the failing path.");
        inherit(same, prior);
        assertThat(approvals.approved(feedback.findById(same.getId()).orElseThrow()))
                .isTrue();
        decisions.saveAndFlush(FeedbackDecision.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .feedbackId(prior.getId())
                .action(FeedbackDecisionAction.REJECT)
                .actorAccountId(developerId)
                .contentDigest(digests.current(workspace.getId(), prior.getId()))
                .reason("Needs revision")
                .decidedAt(Instant.now().plusSeconds(1))
                .build());
        assertThat(approvals.approved(feedback.findById(same.getId()).orElseThrow()))
                .isFalse();
    }

    @Test
    void shouldRefuseAnApprovalThatDoesNotMatchTheRetainedContent() {
        var prior = words("Cover the failing path.");
        var same = words("Cover the failing path.");
        approve(prior, "0".repeat(64));
        inherit(same, prior);
        assertThat(approvals.approved(feedback.findById(same.getId()).orElseThrow()))
                .isFalse();
    }

    private Feedback words(String body) {
        var row = feedback.saveAndFlush(Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .reviewId(reviewId)
                .itemId(itemId)
                .developerId(developerId)
                .destination(FeedbackDestination.ON_WORK)
                .state(FeedbackState.READY)
                .body(body)
                .createdAt(AT)
                .build());
        summary(row, FeedbackCopyState.PLANNED);
        return row;
    }

    private void approve(Feedback row, String digest) {
        decisions.saveAndFlush(FeedbackDecision.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .feedbackId(row.getId())
                .action(FeedbackDecisionAction.APPROVE)
                .actorAccountId(developerId)
                .contentDigest(digest)
                .decidedAt(Instant.now())
                .build());
    }

    private void inherit(Feedback next, Feedback prior) {
        feedback.updateComposition(
                workspace.getId(),
                next.getId(),
                mapper.createObjectNode().put("approvalFrom", prior.getId().toString()));
    }
}

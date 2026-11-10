package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackUsefulness;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

class FeedbackDecisionErasureIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    @Autowired
    FeedbackDecisionService decisionService;

    @Autowired
    FeedbackContentDigest digests;

    @Autowired
    FeedbackResponseService responseService;

    @Autowired
    FeedbackCopyCleanupCustody custody;

    @Test
    void shouldSendApprovedFeedbackThroughTheSameCopyFenceAndRetainItsChecks() {
        when(gate.requiresApproval(any())).thenReturn(true);
        prepare(true);
        assertThat(current().getState()).isEqualTo(FeedbackState.AWAITING_APPROVAL);
        assertThat(sends.findById(feedbackId)).isEmpty();
        decisionService.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(
                        FeedbackDecisionAction.APPROVE, null, null, digests.current(workspace.getId(), feedbackId)));
        sender.send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.noteCreates).isEqualTo(1);
        assertThat(current().getChecks())
                .containsKeys(FeedbackCheckStage.COMPOSITION, FeedbackCheckStage.APPROVED, FeedbackCheckStage.EGRESS);
        assertThat(children()).allSatisfy(copy -> {
            assertThat(copy.getWriteStartedAt()).isNotNull();
            assertThat(copy.getExternalRef()).isNotNull();
        });
    }

    @Test
    void shouldPreserveApprovalAndWriteIdentityAcrossAnAcknowledgedFallbackAndPreparationReplay() {
        when(gate.requiresApproval(any())).thenReturn(true);
        prepare(true);
        var approvedDigest = digests.current(workspace.getId(), feedbackId);
        decisionService.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(FeedbackDecisionAction.APPROVE, null, null, approvedDigest));
        provider.fallback = true;
        sender.send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
        var fallback = children().get(1);
        assertThat(fallback.getKind()).isEqualTo(FeedbackCopyKind.FILE_NOTE);
        assertThat(fallback.getId()).isEqualTo(FeedbackIds.copy(feedbackId, FeedbackCopyKind.LINE_NOTE, 1));
        assertThat(digests.current(workspace.getId(), feedbackId)).isEqualTo(approvedDigest);
        assertThat(decisions
                        .findLatestApproval(workspace.getId(), feedbackId)
                        .orElseThrow()
                        .getContentDigest())
                .isEqualTo(approvedDigest);
        prepare(true);
        sender.send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.noteCreates).isEqualTo(1);
        assertThat(children().get(1).getExternalRef()).isEqualTo(fallback.getExternalRef());
        assertThat(children().get(1).getWriteStartedAt()).isEqualTo(fallback.getWriteStartedAt());
    }

    @Test
    void shouldRefuseApprovalChangesAfterAnyPhysicalFenceAndRetainCleanupCustody() {
        prepare(false);
        provider.summaryFailure = Failure.AFTER_CREATE;
        sender.send(workspace.getId(), feedbackId);
        assertThatThrownBy(() -> decisionService.decide(
                        workspace.getId(),
                        feedbackId,
                        developerId,
                        new FeedbackDecisionInput(
                                FeedbackDecisionAction.REJECT,
                                null,
                                "Remove this guidance.",
                                digests.current(workspace.getId(), feedbackId))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> custody.requireAcknowledged(workspace.getId(), List.of(feedbackId)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(children().getFirst().getWriteStartedAt()).isNotNull();
        assertThat(current().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
    }

    @Test
    void shouldPermitAResponseToAConfirmedSiblingButNotToUnknownAppearanceAlone() {
        prepare(true);
        provider.noteFailure = Failure.AFTER_CREATE;
        sender.send(workspace.getId(), feedbackId);
        var response = responseService.respond(
                workspace.getId(),
                feedbackId,
                developerId,
                FeedbackResolution.ADDRESSED,
                FeedbackUsefulness.HELPFUL,
                null);
        assertThat(response.resolution()).isEqualTo(FeedbackResolution.ADDRESSED);
        assertThat(current().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
    }

    @Test
    void shouldSettleWithdrawalAtTheFenceWithoutInventingAnApprovalRefusal() {
        prepare(false);
        var owner = owner(feedbackId);
        assertThat(transactions.claim(workspace.getId(), feedbackId, owner)).isNotNull();
        var withdrawal = decisionService.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(FeedbackDecisionAction.WITHDRAW, null, "The context needs review.", null));
        assertThat(transactions.fence(
                        workspace.getId(),
                        feedbackId,
                        owner,
                        List.of(children().getFirst().getId()),
                        List.of()))
                .isFalse();
        finishSend(feedbackId, owner, false);
        var send = sends.findById(feedbackId).orElseThrow();
        assertThat(send.getState()).isEqualTo(FeedbackSendState.DONE);
        assertThat(children()).allSatisfy(copy -> {
            assertThat(copy.getState()).isEqualTo(FeedbackCopyState.PLANNED);
            assertThat(copy.getWriteStartedAt()).isNull();
            assertThat(copy.getExternalRef()).isNull();
        });
        assertThat(decisions
                        .findLatestVisibility(workspace.getId(), feedbackId)
                        .orElseThrow()
                        .getId())
                .isEqualTo(withdrawal.decisionId());
        assertThat(current().getStopReason()).isNull();
        assertThat(provider.physical).isEmpty();
        decisionService.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(FeedbackDecisionAction.RESTORE, null, "The context is confirmed.", null));
        assertThat(sends.findById(feedbackId).orElseThrow().getState()).isEqualTo(FeedbackSendState.PENDING);
        sender.send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(provider.summaryCreates).isEqualTo(1);
    }

    @Test
    void shouldCorrectAndRestoreTheSameSummaryAfterWithdrawalWithoutAnotherCreate() {
        prepare(false);
        sender.send(workspace.getId(), feedbackId);
        var copy = children().getFirst();
        decisionService.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(FeedbackDecisionAction.WITHDRAW, null, "The context needs review.", null));
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.physical.get(copy.getId())).isEqualTo("This feedback was withdrawn.");
        decisionService.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(FeedbackDecisionAction.RESTORE, null, "The basis is confirmed.", null));
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.physical.get(copy.getId())).isEqualTo(current().getBody());
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.updates).isEqualTo(2);
        assertThat(children().getFirst().getExternalRef()).isEqualTo(copy.getExternalRef());
        assertThat(children().getFirst().getPostedAt()).isEqualTo(copy.getPostedAt());
    }
}

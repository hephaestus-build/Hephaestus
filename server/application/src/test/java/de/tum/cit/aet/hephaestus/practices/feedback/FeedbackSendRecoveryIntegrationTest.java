package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.LookupPresence;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendRecovery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackUsefulness;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FeedbackSendRecoveryIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    @Test
    void shouldRecoverAnExpiredClaimBeforeAnyFenceWithOneCreate() {
        prepare(false);
        var attempt = transactions.claim(workspace.getId(), feedbackId, "crashed-before-fence");
        assertThat(attempt).isNotNull();
        assertThat(children().getFirst().getWriteStartedAt()).isNull();
        expireClaim();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
    }

    @Test
    void shouldReadBackACrashAfterTheFenceBeforeAnyProviderCall() {
        prepare(false);
        String owner = "crashed-after-fence";
        var attempt = transactions.claim(workspace.getId(), feedbackId, owner);
        assertThat(attempt).isNotNull();
        var copyId = children().getFirst().getId();
        assertThat(transactions.fence(workspace.getId(), feedbackId, owner, List.of(copyId), List.of()))
                .isTrue();
        var firstFence = children().getFirst().getWriteStartedAt();
        expireClaim();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isZero();
        assertThat(current().getState()).isEqualTo(FeedbackState.FAILED);
        assertThat(children().getFirst().getReadback()).isEqualTo(FeedbackCopyReadback.ABSENT);
        store.retry(workspace.getId(), feedbackId, developerId);
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(children().getFirst().getWriteStartedAt()).isEqualTo(firstFence);
    }

    @Test
    void shouldSettleAnUnknownPostBeforeOpeningItsWithdrawalCorrection() {
        prepare(false);
        provider.summaryFailure = Failure.AFTER_CREATE;
        sender.send(workspace.getId(), feedbackId);
        store.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(FeedbackDecisionAction.WITHDRAW, null, "Withdraw this guidance.", null));
        assertThat(sends.findById(feedbackId).orElseThrow().getAction()).isEqualTo(FeedbackSendAction.POST);
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(sends.findById(feedbackId).orElseThrow().getAction()).isEqualTo(FeedbackSendAction.RETRACT);
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.updates).isEqualTo(1);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        assertThat(current().getDeliveredAt()).isNotNull();
    }

    @Test
    void shouldOnlyReadBackAnUnknownCopyAndWithholdUnrequestedSiblingsDuringRecovery() {
        prepare(true);
        String owner = "lost-package-owner";
        transactions.claim(workspace.getId(), feedbackId, owner);
        var summary = children().getFirst();
        transactions.fence(workspace.getId(), feedbackId, owner, List.of(summary.getId()), List.of());
        provider.physical.put(summary.getId(), current().getBody());
        expireClaim();
        recovery().recover();
        assertThat(provider.summaryCreates).isZero();
        assertThat(provider.noteCreates).isZero();
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.WITHHELD);
        assertThat(children().get(1).getWriteStartedAt()).isNull();
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
    }

    @Test
    void shouldNeverCreateAConfirmedCopyWhenRecoveryRunsAfterMigration() {
        seedHistoricalCopy(true);
        var original = children().getFirst();
        recovery().recover();
        recovery().recover();
        assertThat(provider.summaryCreates).isZero();
        assertThat(children().getFirst().getExternalRef()).isEqualTo(original.getExternalRef());
        assertThat(children().getFirst().getWriteMarker()).isEqualTo("<!-- released-feedback-marker -->");
        assertThat(current().getDeliveredAt()).isEqualTo(AT);
    }

    @Test
    void shouldReconcileAnUnknownCopyBeforeAnyCreate() {
        seedHistoricalCopy(false);
        recovery().recover();
        assertThat(provider.summaryCreates).isZero();
        assertThat(provider.lastSummaryLookupMarker).isEqualTo("<!-- released-feedback-marker -->");
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(children().getFirst().getWriteStartedAt()).isEqualTo(AT);
        assertThat(children().getFirst().getWriteKey()).isEqualTo("released-write-key");
    }

    @Test
    void shouldNotCreateACommentWhenAProviderCorrectionIsRestored() {
        seedHistoricalCopy(true);
        var copy = children().getFirst();
        copies.confirmCorrection(workspace.getId(), feedbackId, copy.getId(), FeedbackCopyState.RETRACTED.name());
        provider.physical.put(copy.getId(), "This feedback was withdrawn.");
        jdbc.update(
                "UPDATE feedback_send SET"
                        + " action='RETRACT',state='PENDING',next_attempt_at=now()-interval '1 second'"
                        + " WHERE workspace_id=? AND feedback_id=?",
                workspace.getId(),
                feedbackId);
        recovery().recover();
        recovery().recover();
        assertThat(provider.summaryCreates).isZero();
        assertThat(provider.updates).isEqualTo(1);
        assertThat(provider.physical.get(copy.getId())).isEqualTo(current().getBody());
        assertThat(children().getFirst().getExternalRef()).isEqualTo(copy.getExternalRef());
        assertThat(children().getFirst().getPostedAt()).isEqualTo(AT);
    }

    private FeedbackSendRecovery recovery() {
        return new FeedbackSendRecovery(sends, newSender(), Clock.systemUTC());
    }

    private void seedHistoricalCopy(boolean confirmed) {
        prepare(false);
        var copy = children().getFirst();
        jdbc.update(
                "UPDATE feedback_copy SET write_key='released-write-key',write_marker='<!--"
                        + " released-feedback-marker"
                        + " -->',write_started_at=?,state=?,external_ref=?,posted_at=? WHERE id=? AND"
                        + " workspace_id=?",
                Timestamp.from(AT),
                confirmed ? "POSTED" : "WRITE_STARTED",
                confirmed ? copy.getId().toString() : null,
                confirmed ? Timestamp.from(AT) : null,
                copy.getId(),
                workspace.getId());
        jdbc.update(
                "UPDATE feedback SET state=?,stop_reason=?,delivered_at=? WHERE id=? AND" + " workspace_id=?",
                confirmed ? "DELIVERED" : "UNCONFIRMED",
                confirmed ? null : "DELIVERY_UNCONFIRMED",
                confirmed ? Timestamp.from(AT) : null,
                feedbackId,
                workspace.getId());
        jdbc.update(
                "UPDATE feedback_send SET state=?,next_attempt_at=now()-interval '1 second' WHERE"
                        + " workspace_id=? AND feedback_id=?",
                confirmed ? "DONE" : "UNCERTAIN",
                workspace.getId(),
                feedbackId);
        provider.physical.put(copy.getId(), current().getBody());
    }

    private void expireClaim() {
        jdbc.update(
                "UPDATE feedback_send SET lease_expires_at=now()-interval '1"
                        + " second',next_attempt_at=now()-interval '1 second' WHERE workspace_id=? AND"
                        + " feedback_id=?",
                workspace.getId(),
                feedbackId);
    }

    @Test
    void shouldRecoverTheOriginalCopyAfterTheProviderCreatedItButItsResponseWasLost() {
        prepare(false);
        provider.summaryFailure = Failure.AFTER_CREATE;
        sender.send(workspace.getId(), feedbackId);
        var fence = children().getFirst().getWriteStartedAt();
        assertThat(current().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(provider.physical).containsKey(children().getFirst().getId());
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(children().getFirst().getWriteStartedAt()).isEqualTo(fence);
    }

    @Test
    void shouldRequireAnExplicitRetryAfterReadbackProvesTheCopyAbsent() {
        prepare(false);
        provider.summaryFailure = Failure.BEFORE_CREATE;
        sender.send(workspace.getId(), feedbackId);
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.FAILED);
        assertThat(children().getFirst().getReadback()).isEqualTo(FeedbackCopyReadback.ABSENT);
        assertThat(provider.summaryCreates).isEqualTo(1);
        var fence = children().getFirst().getWriteStartedAt();
        provider.summaryFailure = Failure.NONE;
        store.retry(workspace.getId(), feedbackId, developerId);
        newSender().send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(provider.summaryCreates).isEqualTo(2);
        assertThat(children().getFirst().getWriteStartedAt()).isEqualTo(fence);
    }

    @Test
    void shouldKeepUnknownVisibilityAfterTheBoundedReadbackBudgetWithoutAnotherPost() {
        prepare(false);
        provider.summaryFailure = Failure.AFTER_CREATE;
        sender.send(workspace.getId(), feedbackId);
        provider.readback = LookupPresence.UNKNOWN;
        for (int attempt = 0; attempt < 8; attempt++) {
            due();
            newSender().send(workspace.getId(), feedbackId);
        }
        assertThat(current().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(sends.findById(feedbackId).orElseThrow().getState()).isEqualTo(FeedbackSendState.DONE);
        assertThatThrownBy(() -> store.retry(workspace.getId(), feedbackId, developerId))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldRetryAWithdrawalWithoutAnotherDecisionWhenTheProviderDidNotAcknowledgeARemoval() {
        prepare(true);
        sender.send(workspace.getId(), feedbackId);
        store.respond(
                workspace.getId(),
                feedbackId,
                developerId,
                FeedbackResolution.ADDRESSED,
                FeedbackUsefulness.HELPFUL,
                null);
        var posted = children();
        assertThat(posted).allSatisfy(copy -> assertThat(copy.getState()).isEqualTo(FeedbackCopyState.POSTED));
        provider.retraction = CopyUpdateKind.TRANSIENT;
        withdraw();
        for (int attempt = 0; attempt < 8; attempt++) {
            due();
            newSender().send(workspace.getId(), feedbackId);
        }
        var stopped = sends.findById(feedbackId).orElseThrow();
        assertThat(stopped.getAction()).isEqualTo(FeedbackSendAction.RETRACT);
        assertThat(stopped.getState()).isEqualTo(FeedbackSendState.DONE);
        assertThat(stopped.getLastError()).isEqualTo("Provider visibility is unknown.");
        assertThat(children().get(0).getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(children().get(1).getReadback()).isEqualTo(FeedbackCopyReadback.TRANSIENT);
        long decided = decisionCount();
        provider.retraction = CopyUpdateKind.GONE;

        store.retry(workspace.getId(), feedbackId, developerId);
        assertThat(sends.findById(feedbackId).orElseThrow().getState()).isEqualTo(FeedbackSendState.PENDING);
        due();
        newSender().send(workspace.getId(), feedbackId);

        var settled = sends.findById(feedbackId).orElseThrow();
        assertThat(settled.getAction()).isEqualTo(FeedbackSendAction.RETRACT);
        assertThat(settled.getState()).isEqualTo(FeedbackSendState.DONE);
        assertThat(settled.getLastError()).isNull();
        assertThat(settled.getAttempts()).isEqualTo(stopped.getAttempts() + 1);
        assertThat(children()).allSatisfy(copy -> {
            assertThat(copy.getState()).isEqualTo(FeedbackCopyState.RETRACTED);
            assertThat(copy.getReadback()).isNull();
        });
        assertThat(children())
                .extracting(FeedbackCopy::getId, FeedbackCopy::getExternalRef, FeedbackCopy::getPostedAt)
                .containsExactlyElementsOf(posted.stream()
                        .map(copy -> tuple(copy.getId(), copy.getExternalRef(), copy.getPostedAt()))
                        .toList());
        assertThat(provider.physical).doesNotContainKey(posted.get(1).getId());
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.noteCreates).isEqualTo(1);
        assertThat(decisionCount()).isEqualTo(decided);
        assertThat(visibility.withdrawn(workspace.getId(), feedbackId)).isTrue();
        assertThat(responses
                        .findCurrent(workspace.getId(), feedbackId)
                        .orElseThrow()
                        .getResolution())
                .isEqualTo(FeedbackResolution.ADDRESSED);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
    }

    @Test
    void shouldRefuseAWithdrawalRetryWhileItsCorrectionIsPendingOrLeased() {
        prepare(true);
        sender.send(workspace.getId(), feedbackId);
        withdraw();
        assertThatThrownBy(() -> store.retry(workspace.getId(), feedbackId, developerId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("The withdrawal is still in progress.");
        due();
        assertThat(transactions.claim(workspace.getId(), feedbackId, "leased-correction"))
                .isNotNull();
        assertThatThrownBy(() -> store.retry(workspace.getId(), feedbackId, developerId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("The withdrawal is still in progress.");
        var leased = sends.findById(feedbackId).orElseThrow();
        assertThat(leased.getAction()).isEqualTo(FeedbackSendAction.RETRACT);
        assertThat(leased.getState()).isEqualTo(FeedbackSendState.SENDING);
        assertThat(leased.getLeaseOwner()).isEqualTo("leased-correction");
        assertThat(provider.updates).isZero();
    }

    @Test
    void shouldRefuseAWithdrawalRetryWhenTheProviderCannotRemoveThePostedCopy() {
        prepare(true);
        sender.send(workspace.getId(), feedbackId);
        provider.retraction = CopyUpdateKind.UNSUPPORTED;
        withdraw();
        due();
        newSender().send(workspace.getId(), feedbackId);
        var stopped = sends.findById(feedbackId).orElseThrow();
        assertThat(stopped.getState()).isEqualTo(FeedbackSendState.DONE);
        var note = children().get(1);
        assertThat(note.getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(note.getReadback()).isNull();
        assertThat(note.getLastError()).isEqualTo("This provider cannot correct the posted copy.");
        assertThatThrownBy(() -> store.retry(workspace.getId(), feedbackId, developerId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No withdrawn copy can be retried.");
        var after = sends.findById(feedbackId).orElseThrow();
        assertThat(after.getState()).isEqualTo(FeedbackSendState.DONE);
        assertThat(after.getAttempts()).isEqualTo(stopped.getAttempts());
    }

    private long decisionCount() {
        return decisions
                .findByWorkspaceIdAndFeedbackIdOrderByDecidedAtDescIdDesc(workspace.getId(), feedbackId)
                .size();
    }
}

package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.LookupPresence;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.RepeatedSummaryCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

class FeedbackCopyDeliveryIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    @Autowired
    RepeatedSummaryCheck repeated;

    @Test
    void shouldRecognizeOnlyConfirmedSummaryWordsForTheSameDeveloperAndWork() {
        prepare(false);
        String body = Objects.requireNonNull(current().getBody());
        assertThat(repeated.repeats(workspace.getId(), itemId, developerId, body))
                .isFalse();
        sender.send(workspace.getId(), feedbackId);
        assertThat(repeated.repeats(workspace.getId(), itemId, developerId, body))
                .isTrue();
        assertThat(repeated.repeats(workspace.getId() + 1, itemId, developerId, body))
                .isFalse();
        assertThat(repeated.repeats(workspace.getId(), itemId + 1, developerId, body))
                .isFalse();
        assertThat(repeated.repeats(workspace.getId(), itemId, developerId + 1, body))
                .isFalse();
        assertThat(repeated.repeats(workspace.getId(), itemId, developerId, body + " Add another test."))
                .isFalse();
    }

    @Test
    void shouldNotTreatUnknownSummaryVisibilityAsARepeatedDeliveredNote() {
        prepare(false);
        provider.summaryFailure = Failure.AFTER_CREATE;
        sender.send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(current().getDeliveredAt()).isNull();
        assertThat(repeated.repeats(
                        workspace.getId(),
                        itemId,
                        developerId,
                        Objects.requireNonNull(current().getBody())))
                .isFalse();
    }

    @Test
    void shouldCommitEveryPhysicalCopyFenceBeforeCreate() {
        prepare(true);
        sender.send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(current().getDeliveredAt()).isNotNull();
        assertThat(current().getChecks().keySet())
                .contains(FeedbackCheckStage.COMPOSITION, FeedbackCheckStage.AUTOMATIC, FeedbackCheckStage.EGRESS);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.noteCreates).isEqualTo(1);
        assertThat(children()).allSatisfy(copy -> {
            assertThat(copy.getExternalRef()).isEqualTo(copy.getId().toString());
            assertThat(copy.getWriteStartedAt()).isNotNull();
            assertThat(copy.getWriteMarker()).isEqualTo(FeedbackIds.writeMarker(copy.getId()));
        });
    }

    @Test
    void shouldKeepTheFrozenCoordinatesAndIdentityWhenTheProviderUsesAFileNoteFallback() {
        prepare(true);
        provider.fallback = true;
        sender.send(workspace.getId(), feedbackId);
        var copy = children().get(1);
        assertThat(copy.getKind()).isEqualTo(FeedbackCopyKind.FILE_NOTE);
        assertThat(copy.getId()).isEqualTo(FeedbackIds.copy(feedbackId, FeedbackCopyKind.LINE_NOTE, 1));
        assertThat(copy.getPath()).isEqualTo("src/A.java");
        assertThat(copy.getStartLine()).isEqualTo(10);
        assertThat(copy.getEndLine()).isEqualTo(12);
        assertThat(copy.getSide()).isEqualTo(CitationSide.OLD);
        assertThat(copy.getWriteMarker()).isEqualTo(FeedbackIds.writeMarker(copy.getId()));
    }

    @Test
    void shouldKeepConfirmedSiblingAppearanceWhileAnotherCopyIsUnknown() {
        prepare(true);
        provider.noteFailure = Failure.AFTER_CREATE;
        sender.send(workspace.getId(), feedbackId);
        var first = current().getDeliveredAt();
        assertThat(first).isNotNull();
        assertThat(current().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(repeated.repeats(
                        workspace.getId(),
                        itemId,
                        developerId,
                        Objects.requireNonNull(current().getBody())))
                .isTrue();
        provider.readback = LookupPresence.UNKNOWN;
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(current().getDeliveredAt()).isEqualTo(first);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.noteCreates).isEqualTo(1);
    }

    @Test
    void shouldFailACopyTheProviderNeverSentOnceTheSendSettles() {
        prepare(true);
        provider.noteFailure = Failure.NOT_SENT;
        sender.send(workspace.getId(), feedbackId);
        assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.FAILED);
        assertThat(children().get(1).getReadback()).isEqualTo(FeedbackCopyReadback.ABSENT);
        assertThat(current().getState()).isEqualTo(FeedbackState.FAILED);
        assertThat(current().getDeliveredAt()).isNotNull();
        provider.noteFailure = Failure.NONE;
        store.retry(workspace.getId(), feedbackId, developerId);
        newSender().send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(provider.noteCreates).isEqualTo(1);
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void shouldLeaveACopyThatWithdrawalStoppedForTheRetractionAndNotFailIt() {
        prepare(true);
        // A decision row, not the decision service: the service would wait on the write that this hook interrupts.
        provider.beforeNotes = () -> jdbc.update(
                "INSERT INTO feedback_decision(id,workspace_id,feedback_id,action,note) VALUES (?,?,?,'WITHDRAW',?)",
                UUID.randomUUID(),
                workspace.getId(),
                feedbackId,
                "Withdraw this guidance.");
        sender.send(workspace.getId(), feedbackId);
        assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.PLANNED);
        assertThat(children().get(1).getReadback()).isNull();
        assertThat(sends.findById(feedbackId).orElseThrow().getAction()).isEqualTo(FeedbackSendAction.RETRACT);
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(children().get(0).getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.PLANNED);
        assertThat(provider.noteCreates).isZero();
    }

    @Test
    void shouldNotReportDeliveryUntilARetriedSiblingCopyPosts() {
        prepare(true);
        provider.noteFailure = Failure.BEFORE_CREATE;
        sender.send(workspace.getId(), feedbackId);
        var first = current().getDeliveredAt();
        assertThat(first).isNotNull();
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.FAILED);
        assertThat(current().getDeliveredAt()).isEqualTo(first);
        provider.noteFailure = Failure.NONE;
        store.retry(workspace.getId(), feedbackId, developerId);
        assertThat(current().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        newSender().send(workspace.getId(), feedbackId);
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(current().getDeliveredAt()).isEqualTo(first);
        assertThat(provider.summaryCreates).isEqualTo(1);
    }
}

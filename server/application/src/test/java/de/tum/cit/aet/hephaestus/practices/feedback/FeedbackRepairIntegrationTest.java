package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackCopyReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackOwnCopyEditor;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackReadbackMeters;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.PostedCopyCorrector;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepairStatus;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class FeedbackRepairIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    @Autowired
    FeedbackRepair repairs;

    @Autowired
    PersonDataWriteFence privacy;

    private boolean readbackUnavailable;

    @Test
    void shouldKeepImmutableWordsAndEditTheSameProviderObjectWithOneNotification() {
        prepare(false);
        var transport = transport(false);
        transport.sender().send(workspace.getId(), feedbackId);
        var original = current();
        var originalCopy = children().getFirst();
        var proposal = changed("Cover the exception path.");
        repairs.check(proposal, feedbackId, "checked-one");
        var receipt = repairs.prepare(proposal, feedbackId, "checked-one");
        UUID revision = receipt.pieces().getFirst().feedbackId();
        assertThat(revision).isNotEqualTo(feedbackId);
        assertThat(sends.findById(revision).orElseThrow().getAction()).isEqualTo(FeedbackSendAction.EDIT);
        assertThat(feedback.findById(feedbackId).orElseThrow().getBody()).isEqualTo(original.getBody());
        assertThat(repairs.prepare(proposal, feedbackId, "checked-one")).isEqualTo(receipt);
        assertThat(repairs.status(workspace.getId(), reviewId).edits()).isEqualTo(1);
        transport.sender().send(workspace.getId(), revision);
        var edited = copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspace.getId(), revision)
                .getFirst();
        assertThat(edited.getState())
                .withFailMessage(
                        "state=%s reason=%s error=%s updates=%s",
                        edited.getState(), edited.getStopReason(), edited.getLastError(), provider.updates)
                .isEqualTo(FeedbackCopyState.POSTED);
        assertThat(edited.getExternalRef()).isEqualTo(originalCopy.getExternalRef());
        assertThat(edited.getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.updates).isEqualTo(1);
        assertThat(repairs.status(workspace.getId(), reviewId)).satisfies(status -> {
            assertThat(status.feedbackId()).isEqualTo(revision);
            assertThat(status.copies()).singleElement().satisfies(copy -> {
                assertThat(copy.body()).contains("Cover the exception path.");
                assertThat(copy.renderedBody()).contains("Provider rendered");
            });
        });
    }

    @Test
    void shouldRecordUnavailableProviderProofWithoutKeepingTheComposerWaiting() {
        prepare(false);
        readbackUnavailable = true;
        transport(false).sender().send(workspace.getId(), feedbackId);
        assertThat(repairs.status(workspace.getId(), reviewId)).satisfies(status -> {
            assertThat(status.state()).isEqualTo(FeedbackRepairStatus.State.READBACK);
            assertThat(status.copies()).singleElement().satisfies(copy -> {
                assertThat(copy.status()).isEqualTo(CopyReadback.Status.UNAVAILABLE);
                assertThat(copy.body()).isNull();
            });
        });
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThatThrownBy(() -> repairs.check(changed("Cover the exception path."), feedbackId, "unavailable"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefreshUnavailableReadbackOnceWithoutAnotherProviderWrite() {
        prepare(false);
        readbackUnavailable = true;
        var transport = transport(false);
        transport.sender().send(workspace.getId(), feedbackId);
        assertThat(repairs.status(workspace.getId(), reviewId).copies())
                .singleElement()
                .satisfies(copy -> assertThat(copy.status()).isEqualTo(CopyReadback.Status.UNAVAILABLE));
        readbackUnavailable = false;
        assertThat(repairs.readback(workspace.getId(), reviewId, feedbackId).state())
                .isEqualTo(FeedbackRepairStatus.State.WAITING);
        assertThat(sends.findById(feedbackId).orElseThrow().getAction()).isEqualTo(FeedbackSendAction.READBACK);
        assertThat(repairs.readbackStatus(workspace.getId(), reviewId, feedbackId)
                        .state())
                .isEqualTo(FeedbackRepairStatus.State.WAITING);
        transport.sender().send(workspace.getId(), feedbackId);
        assertThat(repairs.readbackStatus(workspace.getId(), reviewId, feedbackId)
                        .copies())
                .singleElement()
                .satisfies(copy -> assertThat(copy.status()).isEqualTo(CopyReadback.Status.FOUND));
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.updates).isZero();
    }

    @Test
    void shouldSpendThreeEditsAcrossRevisionsAndNeverResetTheBudgetOnReplay() {
        prepare(false);
        var transport = transport(false);
        transport.sender().send(workspace.getId(), feedbackId);
        UUID latest = feedbackId;
        for (int index = 1; index <= 3; index++) {
            var proposal = changed("Cover exception path " + index + ".");
            UUID previous = latest;
            var receipt = repairs.prepare(proposal, previous, "draft-" + index);
            assertThat(repairs.prepare(proposal, previous, "draft-" + index)).isEqualTo(receipt);
            latest = receipt.pieces().getFirst().feedbackId();
            transport.sender().send(workspace.getId(), latest);
            assertThat(repairs.status(workspace.getId(), reviewId).edits()).isEqualTo(index);
        }
        UUID target = latest;
        assertThatThrownBy(() -> repairs.prepare(changed("Another edit."), target, "fourth"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Budget spent");
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.updates).isEqualTo(3);
        assertThat(repairs.status(workspace.getId(), reviewId).edits()).isEqualTo(3);
    }

    @Test
    void shouldKeepThePostedCopyWhenARecipientTakesControlBeforeTheEdit() {
        prepare(false);
        var initial = transport(false);
        initial.sender().send(workspace.getId(), feedbackId);
        var receipt = repairs.prepare(changed("Cover the exception path."), feedbackId, "human-state");
        UUID revision = receipt.pieces().getFirst().feedbackId();
        transport(true).sender().send(workspace.getId(), revision);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.updates).isZero();
        assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspace.getId(), revision))
                .allSatisfy(copy -> assertThat(copy.getState()).isEqualTo(FeedbackCopyState.WITHHELD));
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.POSTED);
    }

    @Test
    void shouldRejectALateReadbackFromAnExpiredSendOwner() {
        prepare(false);
        var transport = transport(false);
        transport.sender().send(workspace.getId(), feedbackId);
        String recorded = current().getComposition().toString();
        UUID physicalId = UUID.fromString(children().getFirst().getExternalRef());
        provider.physical.put(physicalId, "A later provider observation.");
        var queued = new FeedbackSendQueue(sends, Clock.systemUTC(), copies, visibility, mock(ConfigAuditPort.class));
        queued.queue(current(), FeedbackSendAction.READBACK, FeedbackSendState.PENDING);
        assertThat(transactions.claim(workspace.getId(), feedbackId, "new-owner"))
                .isNotNull();
        transport.readback().capture(workspace.getId(), feedbackId, "old-owner");
        assertThat(current().getComposition().toString()).isEqualTo(recorded);
        transport.readback().capture(workspace.getId(), feedbackId, "new-owner");
        assertThat(current()
                        .getComposition()
                        .path("readback")
                        .path(0)
                        .path("body")
                        .asString())
                .contains("A later provider observation.");
        transactions.finish(workspace.getId(), feedbackId, "new-owner", false);
    }

    @Test
    void shouldKeepAConfirmedThreadReferenceImmutableInPostgreSQL() {
        prepare(false);
        sender.send(workspace.getId(), feedbackId);
        UUID copyId = children().getFirst().getId();
        jdbc.update(
                "UPDATE feedback_copy SET thread_ref='owned-thread' WHERE workspace_id=? AND id=?",
                workspace.getId(),
                copyId);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE feedback_copy SET thread_ref='another-thread' WHERE workspace_id=? AND id=?",
                        workspace.getId(),
                        copyId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("thread binding is immutable");
        assertThat(copies.findById(copyId).orElseThrow().getThreadRef()).isEqualTo("owned-thread");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldReadBackAnAmbiguousEditWithoutAnotherProviderWrite(boolean applied) {
        prepare(false);
        var transport = transport(false);
        transport.sender().send(workspace.getId(), feedbackId);
        var originalRef = children().getFirst().getExternalRef();
        UUID revision = repairs.prepare(changed("Cover the exception path."), feedbackId, "uncertain-edit")
                .pieces()
                .getFirst()
                .feedbackId();
        provider.updateFailure = applied ? Failure.AFTER_CREATE : Failure.BEFORE_CREATE;
        transport.sender().send(workspace.getId(), revision);
        assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspace.getId(), revision)
                        .getFirst()
                        .getState())
                .isEqualTo(FeedbackCopyState.WRITE_STARTED);
        jdbc.update(
                "UPDATE feedback_send SET next_attempt_at=? WHERE workspace_id=? AND feedback_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                workspace.getId(),
                revision);
        provider.updateFailure = Failure.NONE;
        transport.sender().send(workspace.getId(), revision);
        var copy = copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspace.getId(), revision)
                .getFirst();
        assertThat(copy.getState()).isEqualTo(applied ? FeedbackCopyState.POSTED : FeedbackCopyState.WRITE_STARTED);
        if (applied) assertThat(copy.getExternalRef()).isEqualTo(originalRef);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.updates).isEqualTo(1);
    }

    @Test
    void shouldWithdrawAndRestoreOnlyTheLatestWordsOnTheSameProviderObject() {
        prepare(false);
        var transport = transport(false);
        transport.sender().send(workspace.getId(), feedbackId);
        String ref = Objects.requireNonNull(children().getFirst().getExternalRef());
        UUID revision = repairs.prepare(changed("Cover the exception path."), feedbackId, "visibility-edit")
                .pieces()
                .getFirst()
                .feedbackId();
        transport.sender().send(workspace.getId(), revision);
        assertThat(current().getState()).isEqualTo(FeedbackState.REPLACED);
        store.decide(
                workspace.getId(),
                revision,
                developerId,
                new FeedbackDecisionInput(FeedbackDecisionAction.WITHDRAW, null, "Withdraw this guidance.", null));
        transport.sender().send(workspace.getId(), revision);
        assertThat(provider.physical.get(UUID.fromString(ref))).contains("withdrawn");
        store.decide(
                workspace.getId(),
                revision,
                developerId,
                new FeedbackDecisionInput(FeedbackDecisionAction.RESTORE, null, "Restore the current guidance.", null));
        transport.sender().send(workspace.getId(), revision);
        assertThat(provider.physical.get(UUID.fromString(ref))).isEqualTo("Cover the exception path.");
        assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspace.getId(), revision)
                        .getFirst()
                        .getExternalRef())
                .isEqualTo(ref);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(visibility.withdrawn(workspace.getId(), revision)).isFalse();
    }

    @Test
    void shouldPreserveAnEarlierWithdrawalThatArrivesWhileTheQuietEditWaits() {
        prepare(false);
        var transport = transport(false);
        transport.sender().send(workspace.getId(), feedbackId);
        UUID revision = repairs.prepare(changed("Cover the exception path."), feedbackId, "pending-withdrawal")
                .pieces()
                .getFirst()
                .feedbackId();
        store.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(FeedbackDecisionAction.WITHDRAW, null, "Withdraw this guidance.", null));
        assertThat(visibility.withdrawn(workspace.getId(), revision)).isTrue();
        transport.sender().send(workspace.getId(), revision);
        assertThat(provider.updates).isZero();
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspace.getId(), revision)
                        .getFirst()
                        .getWriteStartedAt())
                .isNull();
    }

    private FeedbackDraft changed(String words) {
        var draft = draft(false);
        return new FeedbackDraft(
                draft.id(),
                draft.workspaceId(),
                draft.reviewId(),
                draft.itemId(),
                draft.developerId(),
                draft.destination(),
                draft.state(),
                draft.stopReason(),
                words,
                draft.basis(),
                draft.copies(),
                draft.checks(),
                draft.composition(),
                draft.page());
    }

    private record Transport(FeedbackSendService sender, FeedbackProviderReadback readback) {}

    private Transport transport(boolean humanEdited) {
        var reader = mock(FeedbackCopyReader.class);
        when(reader.kind()).thenReturn(IntegrationKind.GITHUB);
        when(reader.read(any(), any())).thenAnswer(call -> {
            List<CopyReadRequest> requested = call.getArgument(1);
            return requested.stream()
                    .map(copy -> {
                        if (readbackUnavailable) throw new IllegalStateException("Provider unavailable");
                        if (humanEdited) return CopyReadback.refused(copy.copyId(), CopyReadback.Status.HUMAN_EDITED);
                        UUID external = UUID.fromString(copy.externalRef());
                        String body = provider.physical.get(external);
                        if (body == null) return CopyReadback.refused(copy.copyId(), CopyReadback.Status.GONE);
                        if (!copy.writeMarker().equals(provider.markers.get(external)))
                            return CopyReadback.refused(copy.copyId(), CopyReadback.Status.NOT_OWNED);
                        return new CopyReadback(
                                copy.copyId(),
                                CopyReadback.Status.FOUND,
                                body + "\n" + provider.markers.get(external),
                                "<p>Provider rendered: " + body + "</p>",
                                null,
                                false,
                                false);
                    })
                    .toList();
        });
        var targets = mock(FeedbackTargetResolver.class);
        when(targets.resolve(any())).thenReturn(target);
        when(targets.resolveExisting(any())).thenReturn(target);
        var readback = new FeedbackProviderReadback(
                feedback,
                copies,
                targets,
                List.of(reader),
                new TransactionTemplate(transactionManager),
                mapper,
                Clock.systemUTC(),
                mock(ApplicationEventPublisher.class),
                privacy,
                sends);
        var editor = new FeedbackOwnCopyEditor(
                copies, feedback, List.of(reader), List.of(provider), List.of(provider), transactions);
        var sender = new FeedbackSendService(
                transactions,
                targets,
                List.of(provider),
                List.of(provider),
                new PostedCopyCorrector(copies, visibility, List.of(provider), List.of(provider), transactions),
                new FeedbackReadbackMeters(new SimpleMeterRegistry()),
                readback,
                editor);
        return new Transport(sender, readback);
    }
}

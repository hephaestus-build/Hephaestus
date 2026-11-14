package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Tag("integration")
class FeedbackProviderWriteBoundaryIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    @Autowired
    FeedbackDecisionService decisionService;

    @Autowired
    PersonDataService personData;

    @Autowired
    PersonDataWriteFence writeFence;

    private void decide(FeedbackDecisionAction action) {
        decisionService.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(action, null, "Changed on review.", null));
    }

    private void assertUnlocked(WriteObservation observation) {
        assertThat(observation.calls).isEqualTo(1);
        assertThat(observation.identities).isPositive();
        assertThat(observation.problems).isEmpty();
    }

    /** The probe must see what it claims to: a fence that is held shows both locks. */
    @Test
    void shouldSeeTheRecipientLockedWhileAFenceHoldsIt() {
        var observation = new WriteObservation();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(writeFence.holdForAccountWrite(developerId)).isTrue();
            observation.observe();
        });
        assertThat(observation.problems)
                .contains("a transaction is active", "the account row is locked", "an identity lock is held");
    }

    @Test
    void shouldPostASummaryWithNoTransactionAndTheRecipientUnlocked() {
        prepare(false);
        var observation = observeNextWrite();
        sender.send(workspace.getId(), feedbackId);
        assertUnlocked(observation);
        assertThat(provider.summaryCreates).isEqualTo(1);
    }

    @Test
    void shouldPostAPackageWithNoTransactionAndTheRecipientUnlocked() {
        prepare(true);
        var observation = observeNextWrite();
        sender.send(workspace.getId(), feedbackId);
        assertUnlocked(observation);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.noteCreates).isEqualTo(1);
    }

    @Test
    void shouldRemoveACopyWithNoTransactionAndTheRecipientUnlocked() {
        prepare(true);
        sender.send(workspace.getId(), feedbackId);
        decide(FeedbackDecisionAction.WITHDRAW);
        var observation = observeNextWrite();
        newSender().send(workspace.getId(), feedbackId);
        assertUnlocked(observation);
        assertThat(provider.retracted).hasSize(1);
    }

    @Test
    void shouldRestoreACopyWithNoTransactionAndTheRecipientUnlocked() {
        prepare(true);
        sender.send(workspace.getId(), feedbackId);
        decide(FeedbackDecisionAction.WITHDRAW);
        newSender().send(workspace.getId(), feedbackId);
        decide(FeedbackDecisionAction.RESTORE);
        var observation = observeNextWrite();
        newSender().send(workspace.getId(), feedbackId);
        assertUnlocked(observation);
        assertThat(provider.restored).hasSize(1);
    }

    @Test
    void shouldRefuseAnErasureDuringTheProviderCallAndRecordTheReceiptAfter() {
        prepare(false);
        var refusals = new ArrayList<String>();
        provider.duringWrite = () -> {
            try {
                personData.eraseForAccount(developerId);
            } catch (ResponseStatusException refused) {
                refusals.add(refused.getStatusCode().value() + " " + refused.getReason());
            }
        };
        sender.send(workspace.getId(), feedbackId);
        assertThat(refusals).singleElement().asString().startsWith("409 ").contains("unresolved provider write");
        assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id = ?", String.class, developerId))
                .isEqualTo("ACTIVE");
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(children().getFirst().getExternalRef()).isNotNull();
        assertThat(current().getState()).isEqualTo(FeedbackState.DELIVERED);
    }

    @Test
    void shouldCallNoProviderWhenTheRecipientIsRefusedBeforeTheFence() {
        prepare(true);
        jdbc.update("UPDATE account SET status = 'BLOCKED' WHERE id = ?", developerId);
        sender.send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isZero();
        assertThat(provider.noteCreates).isZero();
        assertThat(children()).allSatisfy(copy -> {
            assertThat(copy.getState()).isNotEqualTo(FeedbackCopyState.WRITE_STARTED);
            assertThat(copy.getWriteStartedAt()).isNull();
        });
        assertThat(current().getStopReason()).isEqualTo(FeedbackStopReason.PERSON_SUPPRESSED);
    }

    @Test
    void shouldDropASuccessfulResponseThatArrivesAfterItsLeaseMovedOn() {
        prepare(false);
        provider.duringWrite = this::expireClaim;
        sender.send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.POSTED);
    }

    /**
     * A send writes copies in position order, whatever order their ids sort in: the ids are hashes and the claim locks
     * the rows in id order. A withdrawal that lands during the first write must therefore spare the same copy in both
     * orders.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRestoreCopiesInPositionOrderWhateverTheirIdOrderWhenAWithdrawalLandsDuringTheFirstWrite(
            boolean summaryIdSortsFirst) {
        var random = new Random();
        var low = new UUID(0x1000000000000000L | (random.nextLong() & 0x0FFFFFFFFFFFFFFFL), random.nextLong());
        var high = new UUID(0x7000000000000000L | (random.nextLong() & 0x0FFFFFFFFFFFFFFFL), random.nextLong());
        UUID summaryId = summaryIdSortsFirst ? low : high, noteId = summaryIdSortsFirst ? high : low;
        var piece = piece(FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, AT);
        retractedCopy(piece, summaryId, FeedbackCopyKind.SUMMARY, 0);
        retractedCopy(piece, noteId, FeedbackCopyKind.LINE_NOTE, 1);
        provider.physical.put(summaryId, "This feedback was withdrawn.");
        provider.physical.put(noteId, "This feedback was withdrawn.");
        queueSend(piece.getId(), FeedbackSendAction.RETRACT);
        provider.duringWrite = () -> decisionService.decide(
                workspace.getId(),
                piece.getId(),
                developerId,
                new FeedbackDecisionInput(FeedbackDecisionAction.WITHDRAW, null, "Changed on review.", null));
        sender.send(workspace.getId(), piece.getId());
        // The summary has position 0: it is the first write, the withdrawal then spares the note.
        assertThat(provider.corrected).containsExactly(summaryId);
        assertThat(provider.restored).isEmpty();
        assertThat(copy(summaryId).getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(copy(noteId).getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        assertThat(provider.physical.get(summaryId)).isEqualTo(piece.getBody());
        // The send that ended after the withdrawal queued the correction of the copy it had just restored.
        ActionTestSupport.due(jdbc, "feedback:" + piece.getId());
        sender.send(workspace.getId(), piece.getId());
        assertThat(provider.corrected).containsExactly(summaryId, summaryId);
        assertThat(copy(summaryId).getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        assertThat(copy(noteId).getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        assertThat(provider.physical.get(summaryId)).isEqualTo("This feedback was withdrawn.");
    }

    private void retractedCopy(Feedback piece, UUID id, FeedbackCopyKind kind, int position) {
        boolean note = kind == FeedbackCopyKind.LINE_NOTE;
        copies.saveAndFlush(FeedbackCopy.builder()
                .id(id)
                .workspaceId(workspace.getId())
                .feedbackId(piece.getId())
                .kind(kind)
                .position(position)
                .state(FeedbackCopyState.RETRACTED)
                .body(note ? "Cover the failure case." : null)
                .path(note ? "src/A.java" : null)
                .startLine(note ? 10 : null)
                .endLine(note ? 12 : null)
                .side(note ? CitationSide.OLD : null)
                .writeKey(id.toString())
                .writeMarker(FeedbackIds.writeMarker(id))
                .writeStartedAt(AT)
                .externalRef(id.toString())
                .postedAt(AT)
                .createdAt(AT)
                .build());
    }

    private FeedbackCopy copy(UUID id) {
        return copies.findById(id).orElseThrow();
    }

    @Test
    void shouldCreateNothingWhenARestoreMeetsACopyThatTheProviderNoLongerHolds() {
        prepare(true);
        sender.send(workspace.getId(), feedbackId);
        decide(FeedbackDecisionAction.WITHDRAW);
        newSender().send(workspace.getId(), feedbackId);
        decide(FeedbackDecisionAction.RESTORE);
        provider.duringWrite = provider.physical::clear;
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.physical).isEmpty();
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(provider.noteCreates).isEqualTo(1);
        assertThat(children()).allSatisfy(copy -> assertThat(copy.getState()).isEqualTo(FeedbackCopyState.RETRACTED));
    }
}

package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyForm;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyWriteFence;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyWriteResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNoteWriter;
import de.tum.cit.aet.hephaestus.integration.core.spi.LookupPresence;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryWriter;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.ApprovedFeedbackDeliveryListener;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendRecovery;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendTransactions;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.PostedCopyCorrector;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackBasisInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckFacts;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

public abstract class AbstractFeedbackDeliveryIntegrationTest extends AbstractFeedbackIntegrationTest {
    @Autowired
    protected FeedbackStoreService store;

    @Autowired
    protected FeedbackSendTransactions transactions;

    @Autowired
    protected FeedbackVisibilityService visibility;

    @MockitoBean
    protected FeedbackGate gate;

    @MockitoBean
    MentorReadinessQuery conversationReadiness;

    @MockitoBean
    FeedbackSendRecovery scheduledRecovery;

    @MockitoBean
    ApprovedFeedbackDeliveryListener approvalDelivery;

    protected UUID feedbackId;
    protected Result supportingResult;
    protected FeedbackTarget target;
    protected Provider provider;
    protected FeedbackSendService sender;

    @BeforeEach
    void configureProvider() {
        when(gate.evaluate(any(), any(), anyBoolean())).thenAnswer(call -> {
            Feedback row = call.getArgument(0);
            boolean incorrect = basis.hasIncorrectBasis(row.getWorkspaceId(), row.getId());
            return new FeedbackCheck(
                    call.getArgument(1),
                    !incorrect,
                    incorrect ? FeedbackStopReason.RESULT_MARKED_INCORRECT : null,
                    Instant.now(),
                    new FeedbackCheckFacts(0, 0, true, true, true, true, incorrect, true, true, false));
        });
        // The page decides its unread pieces in one gate read; each piece gets the answer stubbed above.
        when(gate.evaluateAll(anyLong(), anyCollection(), any(), anyBoolean())).thenAnswer(call -> {
            Map<UUID, FeedbackCheck> checks = new LinkedHashMap<>();
            for (Feedback row : call.<Collection<Feedback>>getArgument(1))
                checks.put(row.getId(), gate.evaluate(row, call.getArgument(2), call.getArgument(3)));
            return checks;
        });
        when(conversationReadiness.isReady(anyLong())).thenReturn(true);
        when(gate.mode(any())).thenReturn(FeedbackMode.AUTOMATIC);
        when(gate.evaluateComposition(any(), anyLong(), anyList(), any())).thenAnswer(call -> {
            List<Result> support = call.getArgument(2);
            boolean incorrect = basis.hasIncorrectResults(
                    call.<ReviewSnapshot>getArgument(0).workspaceId(),
                    support.stream().map(Result::getId).toList());
            return new FeedbackCheck(
                    FeedbackCheckStage.COMPOSITION,
                    !incorrect,
                    incorrect ? FeedbackStopReason.RESULT_MARKED_INCORRECT : null,
                    Instant.now(),
                    new FeedbackCheckFacts(0, 0, true, true, true, true, incorrect, true, true, false));
        });
        supportingResult = negativeResult();
        feedbackId = FeedbackIds.onWork(workspace.getId(), reviewId, developerId);
        target = new FeedbackTarget(
                new ItemRef(workspace.getId(), itemId, new ItemKind("scm.pull_request"), 42L, null, null),
                new IntegrationRef(IntegrationKind.GITHUB, workspace.getId(), "https://example.test"),
                new ItemVersion("v1", null));
        provider = new Provider();
        sender = newSender();
    }

    protected FeedbackSendService newSender() {
        var targets = mock(FeedbackTargetResolver.class);
        when(targets.resolve(any())).thenReturn(target);
        when(targets.resolveExisting(any())).thenReturn(target);
        var corrector =
                new PostedCopyCorrector(basis, copies, visibility, List.of(provider), List.of(provider), transactions);
        return new FeedbackSendService(transactions, targets, List.of(provider), List.of(provider), corrector);
    }

    protected void prepare(boolean note) {
        assertThat(store.prepareAll(workspace.getId(), reviewId, List.of(draft(note)))
                        .pieces()
                        .getFirst()
                        .feedbackId())
                .isEqualTo(feedbackId);
    }

    protected FeedbackDraft draft(boolean note) {
        UUID summary = FeedbackIds.copy(feedbackId, FeedbackCopyKind.SUMMARY, 0);
        var planned = new ArrayList<FeedbackCopyInput>();
        planned.add(new FeedbackCopyInput(
                summary,
                FeedbackCopyKind.SUMMARY,
                0,
                null,
                null,
                null,
                null,
                null,
                summary.toString(),
                FeedbackIds.writeMarker(summary)));
        if (note) {
            UUID id = FeedbackIds.copy(feedbackId, FeedbackCopyKind.LINE_NOTE, 1);
            planned.add(new FeedbackCopyInput(
                    id,
                    FeedbackCopyKind.LINE_NOTE,
                    1,
                    "Cover the failure case.",
                    "src/A.java",
                    10,
                    12,
                    CitationSide.OLD,
                    id.toString(),
                    FeedbackIds.writeMarker(id)));
        }
        return new FeedbackDraft(
                feedbackId,
                workspace.getId(),
                reviewId,
                itemId,
                developerId,
                FeedbackDestination.ON_WORK,
                FeedbackState.READY,
                null,
                "Add a focused regression test.",
                List.of(new FeedbackBasisInput(supportingResult.getId(), null, 0)),
                planned,
                Map.of(),
                null,
                null);
    }

    protected Feedback current() {
        return feedback.findById(feedbackId).orElseThrow();
    }

    protected List<FeedbackCopy> children() {
        return copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspace.getId(), feedbackId);
    }

    protected void due() {
        jdbc.update(
                "UPDATE feedback_send SET next_attempt_at=? WHERE workspace_id=? AND feedback_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                workspace.getId(),
                feedbackId);
    }

    protected enum Failure {
        NONE,
        BEFORE_CREATE,
        AFTER_CREATE
    }

    protected final class Provider implements SummaryWriter, LineNoteWriter {
        int summaryCreates, noteCreates, updates;
        Failure summaryFailure = Failure.NONE, noteFailure = Failure.NONE;
        LookupPresence readback = LookupPresence.FOUND;
        CopyUpdateKind retraction = CopyUpdateKind.GONE;
        boolean fallback;

        @Nullable
        String lastSummaryLookupMarker;

        final Map<UUID, String> physical = new HashMap<>();

        @Override
        public IntegrationKind kind() {
            return IntegrationKind.GITHUB;
        }

        private void committed(UUID id) {
            assertThat(copies.findById(id).orElseThrow().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
            assertThat(copies.findById(id).orElseThrow().getWriteStartedAt()).isNotNull();
        }

        @Override
        public SummaryHandle postSummary(FeedbackTarget target, SummaryContent content) {
            committed(content.copyId());
            summaryCreates++;
            if (summaryFailure == Failure.BEFORE_CREATE)
                throw new IllegalStateException("Provider request was not received.");
            physical.put(content.copyId(), content.body());
            if (summaryFailure == Failure.AFTER_CREATE) throw new IllegalStateException("Provider response was lost.");
            return new SummaryHandle(content.copyId().toString(), "https://example.test/comments/" + content.copyId());
        }

        @Override
        public SummaryLookup findExistingSummary(FeedbackTarget target, SummaryContent content) {
            lastSummaryLookupMarker = content.writeMarker();
            if (readback == LookupPresence.UNKNOWN) return new SummaryLookup(LookupPresence.UNKNOWN, null);
            if (!physical.containsKey(content.copyId())) return new SummaryLookup(LookupPresence.ABSENT, null);
            return new SummaryLookup(
                    LookupPresence.FOUND,
                    new SummaryHandle(
                            content.copyId().toString(), "https://example.test/comments/" + content.copyId()));
        }

        @Override
        public CopyUpdateReceipt updateSummary(FeedbackTarget target, SummaryHandle existing, SummaryContent content) {
            updates++;
            UUID id = UUID.fromString(existing.externalRef());
            if (!physical.containsKey(id))
                return new CopyUpdateReceipt(id, CopyUpdateKind.GONE, existing.externalRef(), existing.url(), null);
            physical.put(id, content.body());
            return new CopyUpdateReceipt(id, CopyUpdateKind.EDITED, existing.externalRef(), existing.url(), null);
        }

        @Override
        public CopyWriteResult postImmutablePackage(FeedbackTarget target, List<LineNote> notes, CopyWriteFence fence) {
            var receipts = new ArrayList<CopyReceipt>();
            var withheld = new ArrayList<UUID>();
            for (var note : notes) {
                if (!fence.beforeCreate(List.of(note.copyId()), List.copyOf(receipts))) {
                    withheld.add(note.copyId());
                    continue;
                }
                committed(note.copyId());
                noteCreates++;
                if (noteFailure == Failure.BEFORE_CREATE)
                    throw new IllegalStateException("Provider request was not received.");
                physical.put(note.copyId(), note.body());
                if (noteFailure == Failure.AFTER_CREATE) throw new IllegalStateException("Provider response was lost.");
                receipts.add(receipt(note.copyId()));
            }
            return new CopyWriteResult(receipts, withheld);
        }

        private CopyReceipt receipt(UUID id) {
            return new CopyReceipt(
                    id,
                    CopyDisposition.POSTED,
                    id.toString(),
                    "https://example.test/notes/" + id,
                    true,
                    fallback ? CopyForm.FILE_NOTE : CopyForm.LINE_NOTE);
        }

        @Override
        public @Nullable List<CopyReceipt> findPosted(FeedbackTarget target, List<LineNote> notes) {
            if (this.readback == LookupPresence.UNKNOWN) return null;
            return notes.stream()
                    .filter(note -> physical.containsKey(note.copyId()))
                    .map(note -> receipt(note.copyId()))
                    .toList();
        }

        @Override
        public List<CopyUpdateReceipt> retract(FeedbackTarget target, List<CopyReceipt> receipts) {
            return receipts.stream()
                    .map(receipt -> {
                        if (retraction == CopyUpdateKind.GONE) physical.remove(receipt.copyId());
                        return new CopyUpdateReceipt(
                                receipt.copyId(), retraction, receipt.externalRef(), receipt.externalUrl(), null);
                    })
                    .toList();
        }
    }
}

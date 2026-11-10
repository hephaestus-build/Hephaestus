package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
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
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryWriter;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackOwnCopyEditor;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackReadbackMeters;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendAdapter;
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
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.context.ApplicationContext;
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

    @Autowired
    protected ActionExecutor executor;

    @Autowired
    protected AgentActions actions;

    @Autowired
    protected ApplicationContext context;

    private final Map<UUID, ActionAttempt> owners = new HashMap<>();

    protected UUID feedbackId;
    protected Result supportingResult;
    protected FeedbackTarget target;
    protected Provider provider;
    protected Sender sender;

    @BeforeEach
    void configureProvider() {
        when(gate.evaluate(any(), any(), anyBoolean())).thenAnswer(call -> {
            Feedback row = call.getArgument(0);
            boolean incorrect = basis.isIncorrect(row.getWorkspaceId(), row.getId());
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

    protected Sender newSender() {
        var targets = mock(FeedbackTargetResolver.class);
        when(targets.resolve(any())).thenReturn(target);
        when(targets.resolveExisting(any())).thenReturn(target);
        var corrector = new PostedCopyCorrector(copies, visibility, List.of(provider), List.of(provider), transactions);
        return sender(new FeedbackSendService(
                transactions,
                targets,
                List.of(provider),
                List.of(provider),
                corrector,
                new FeedbackReadbackMeters(new SimpleMeterRegistry()),
                mock(FeedbackProviderReadback.class),
                mock(FeedbackOwnCopyEditor.class)));
    }

    protected Sender sender(FeedbackSendService service) {
        return new Sender(service);
    }

    /** Carries out a feedback's due send, as the executor would, through this test's provider. */
    protected final class Sender {
        private final FeedbackSendService service;

        Sender(FeedbackSendService service) {
            this.service = service;
        }

        public void send(long workspaceId, UUID feedbackId) {
            ActionTestSupport.runLane(
                    executor,
                    jdbc,
                    "feedback:" + feedbackId,
                    new FeedbackSendAdapter(ActionKind.DELIVER_FEEDBACK, service, transactions));
        }
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

    protected void withdraw() {
        store.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(FeedbackDecisionAction.WITHDRAW, null, "Withdraw this guidance.", null));
    }

    protected void due() {
        ActionTestSupport.due(jdbc, "feedback:" + feedbackId);
    }

    /** Claims the feedback's due send, as a process that then stops at any point would. */
    protected ActionAttempt claimSend(UUID id) {
        return ActionTestSupport.claimLane(jdbc, mapper, "feedback:" + id).orElseThrow();
    }

    /** The claimed send of a feedback, claimed on first use. */
    protected ActionAttempt owner(UUID id) {
        return owners.computeIfAbsent(id, this::claimSend);
    }

    /**
     * Settles a claimed send as its attempt would, and records the result as the executor would. An exhausted send is
     * on its last attempt.
     */
    protected void finishSend(UUID id, ActionAttempt owner, boolean exhausted) {
        if (exhausted) jdbc.update("UPDATE agent_action SET attempts = max_attempts WHERE id = ?", owner.id());
        ActionTestSupport.record(context, owner, transactions.finish(workspace.getId(), id, owner));
    }

    /** Ends the lease of the feedback's running send, as if its process stopped. */
    protected void expireClaim() {
        ActionTestSupport.expireLease(jdbc, "feedback:" + feedbackId);
    }

    /** Queues a send that is due now, as an earlier release left it. */
    protected void queueSend(UUID id, FeedbackSendAction action) {
        jdbc.update(
                """
                INSERT INTO agent_action (id, workspace_id, kind, lane_key, idempotency_key, state, payload,
                  subject_account_id, max_attempts, next_attempt_at, created_at, updated_at)
                SELECT uuidv7(), f.workspace_id, ?, 'feedback:' || f.id,
                       'feedback:' || f.id || ':' || (SELECT count(*) + 1 FROM agent_action a
                                                       WHERE a.lane_key = 'feedback:' || f.id) || ':' || ?,
                       'ADMITTED', jsonb_build_object('feedbackId', f.id), f.developer_id, 8,
                       now() - interval '1 second', now(), now()
                  FROM feedback f WHERE f.id = ?
                """,
                switch (action) {
                    case POST, READBACK -> "DELIVER_FEEDBACK";
                    case EDIT -> "EDIT_FEEDBACK";
                    case RETRACT -> "RETRACT_FEEDBACK";
                },
                action.name(),
                id);
    }

    protected enum Failure {
        NONE,
        NOT_SENT,
        BEFORE_CREATE,
        AFTER_CREATE
    }

    protected final class Provider implements SummaryWriter, LineNoteWriter {
        int summaryCreates, noteCreates, updates;
        final List<UUID> retracted = new ArrayList<>();
        final List<UUID> restored = new ArrayList<>();
        Failure summaryFailure = Failure.NONE, noteFailure = Failure.NONE, updateFailure = Failure.NONE;
        LookupPresence readback = LookupPresence.FOUND;
        CopyUpdateKind retraction = CopyUpdateKind.GONE;
        boolean fallback;

        @Nullable
        Runnable beforeNotes;

        @Nullable
        String lastSummaryLookupMarker;

        final Map<UUID, String> physical = new HashMap<>();
        final Map<UUID, String> markers = new HashMap<>();

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
            if (content.writeMarker() != null) markers.put(content.copyId(), content.writeMarker());
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
            if (updateFailure == Failure.BEFORE_CREATE)
                throw new IllegalStateException("The quiet edit response was lost.");
            UUID id = UUID.fromString(existing.externalRef());
            if (!physical.containsKey(id))
                return new CopyUpdateReceipt(
                        content.copyId(), CopyUpdateKind.GONE, existing.externalRef(), existing.url(), null);
            physical.put(id, content.body());
            if (content.writeMarker() != null) markers.put(id, content.writeMarker());
            if (updateFailure == Failure.AFTER_CREATE)
                throw new IllegalStateException("The quiet edit response was lost.");
            return new CopyUpdateReceipt(
                    content.copyId(), CopyUpdateKind.EDITED, existing.externalRef(), existing.url(), null);
        }

        /**
         * Writes the summary, then the notes, each behind its own fence, as an adapter may. Like an adapter it never
         * throws for a provider failure: a lost answer leaves the copy attempted, so only readback can settle it.
         */
        @Override
        public CopyWriteResult postPackage(FeedbackTarget target, OnWorkPackage publication, CopyWriteFence fence) {
            var receipts = new ArrayList<CopyReceipt>();
            var summary = publication.summary();
            if (summary != null) {
                if (!fence.beforeCreate(List.of(summary.copyId()), List.of())) return CopyWriteResult.of(List.of());
                try {
                    var handle = postSummary(target, summary);
                    receipts.add(new CopyReceipt(
                            summary.copyId(), CopyDisposition.POSTED, handle.externalRef(), handle.url(), true, null));
                } catch (IllegalStateException unknown) {
                    receipts.add(CopyReceipt.attempted(summary.copyId()));
                }
            }
            if (beforeNotes != null) beforeNotes.run();
            var notes = publication.notes();
            if (notes.isEmpty()) return CopyWriteResult.of(receipts);
            if (!fence.beforeCreate(notes.stream().map(LineNote::copyId).toList(), List.copyOf(receipts)))
                return CopyWriteResult.of(receipts);
            for (var note : notes) {
                if (noteFailure == Failure.NOT_SENT) {
                    receipts.add(CopyReceipt.notSent(note.copyId()));
                    continue;
                }
                committed(note.copyId());
                noteCreates++;
                if (noteFailure == Failure.BEFORE_CREATE || noteFailure == Failure.AFTER_CREATE) {
                    if (noteFailure == Failure.AFTER_CREATE) physical.put(note.copyId(), note.body());
                    receipts.add(CopyReceipt.attempted(note.copyId()));
                    continue;
                }
                physical.put(note.copyId(), note.body());
                receipts.add(receipt(note.copyId()));
            }
            return CopyWriteResult.of(receipts);
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
        public @Nullable List<CopyReceipt> findPosted(FeedbackTarget target, OnWorkPackage publication) {
            var notes = publication.notes();
            if (this.readback == LookupPresence.UNKNOWN) return null;
            var found = new ArrayList<CopyReceipt>();
            var summary = publication.summary();
            if (summary != null) {
                lastSummaryLookupMarker = summary.writeMarker();
                if (physical.containsKey(summary.copyId()))
                    found.add(new CopyReceipt(
                            summary.copyId(),
                            CopyDisposition.PRESERVED_EXISTING,
                            summary.copyId().toString(),
                            "https://example.test/comments/" + summary.copyId(),
                            true,
                            null));
            }
            notes.stream()
                    .filter(note -> physical.containsKey(note.copyId()))
                    .map(note -> receipt(note.copyId()))
                    .forEach(found::add);
            return found;
        }

        @Override
        public List<CopyUpdateReceipt> editOwn(FeedbackTarget target, List<OwnedEdit> notes) {
            return notes.stream()
                    .map(note -> {
                        UUID id = UUID.fromString(note.copy().externalRef());
                        if (!physical.containsKey(id))
                            return CopyUpdateReceipt.gone(note.copy().copyId(), "Deleted");
                        physical.put(id, note.note().body());
                        return new CopyUpdateReceipt(
                                note.copy().copyId(),
                                CopyUpdateKind.EDITED,
                                note.copy().externalRef(),
                                note.copy().externalUrl(),
                                null);
                    })
                    .toList();
        }

        @Override
        public List<CopyUpdateReceipt> restore(FeedbackTarget target, List<RetractedNote> notes) {
            return notes.stream()
                    .map(note -> {
                        restored.add(note.copy().copyId());
                        var gone = !physical.containsKey(note.copy().copyId());
                        return new CopyUpdateReceipt(
                                note.copy().copyId(),
                                gone ? CopyUpdateKind.GONE : CopyUpdateKind.EDITED,
                                note.copy().externalRef(),
                                note.copy().externalUrl(),
                                null);
                    })
                    .toList();
        }

        @Override
        public List<CopyUpdateReceipt> retract(FeedbackTarget target, List<CopyReceipt> receipts) {
            return receipts.stream()
                    .map(receipt -> {
                        retracted.add(receipt.copyId());
                        if (retraction == CopyUpdateKind.GONE) physical.remove(receipt.copyId());
                        return new CopyUpdateReceipt(
                                receipt.copyId(), retraction, receipt.externalRef(), receipt.externalUrl(), null);
                    })
                    .toList();
        }
    }
}

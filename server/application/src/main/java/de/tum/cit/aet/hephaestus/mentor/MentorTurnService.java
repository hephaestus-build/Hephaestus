package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The turn lifecycle on the ledger: submit, claim, end, lease expiry and stop
 * ({@code docs/contributor/unified-pi-runtime.mdx} § Turn lifecycle). Billing and feedback delivery are
 * {@code agent.mentor.turn}'s part of ending a turn ({@code MentorTurnCompletion}).
 */
@Service
public class MentorTurnService {

    /** One automatic retry after a lost lease. */
    static final int MAX_ATTEMPTS = 2;

    /** Shown to the developer as the failed reply, so it is in the product voice. */
    private static final String LEASE_EXPIRED = "Heph could not finish this reply. Try again.";

    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;
    private final MentorTurnRepository turns;
    private final MentorEntryService entryService;
    private final MentorRedaction redaction;
    private final PersonDataCopyFence copies;
    private final PersonDataWriteFence writeFence;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int maxFollowUps;

    public MentorTurnService(
            MentorConversationRepository conversations,
            MentorEntryRepository entries,
            MentorTurnRepository turns,
            MentorEntryService entryService,
            MentorRedaction redaction,
            PersonDataCopyFence copies,
            PersonDataWriteFence writeFence,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${hephaestus.mentor.turn.max-follow-ups:3}") int maxFollowUps) {
        this.conversations = conversations;
        this.entries = entries;
        this.turns = turns;
        this.entryService = entryService;
        this.redaction = redaction;
        this.copies = copies;
        this.writeFence = writeFence;
        this.events = events;
        this.clock = clock;
        this.maxFollowUps = maxFollowUps;
    }

    public record Submission(MentorTurn turn, MentorEntry question, boolean created) {}

    /**
     * An ended attempt, read under the turn's lock after the proxy's fence closed.
     *
     * @param proxyUsage what the LLM proxy recorded for the attempt's calls
     */
    public record EndedTurn(MentorTurn turn, long developerId, MentorTurnUsage proxyUsage) {}

    /** An attempt whose lease expired, and what became of its turn. */
    public record LostAttempt(
            MentorTurn turn, int attempt, @Nullable JsonNode admission, MentorTurnUsage proxyUsage) {}

    /**
     * Heph's answer to a turn and the observations it showed, in the order Heph linked them.
     *
     * @param shownIn each shown observation, with the message whose {@code link_observation} call showed it
     */
    public record Answer(
            MentorTurn turn, long developerId, @Nullable UUID answerEntryId, Map<UUID, UUID> shownIn) {

        public List<UUID> shownObservationIds() {
            return List.copyOf(shownIn.keySet());
        }
    }

    /**
     * A developer's question.
     *
     * @param requestId the submission's idempotency key
     * @param slackTs the Slack message's {@code ts}, for a question asked in Slack
     * @param pageContext the app page the question was asked from, as the app described it
     */
    public record Question(
            MentorTurn.Surface surface,
            String text,
            String requestId,
            @Nullable String slackTs,
            @Nullable String pageContext) {}

    @Transactional
    public Submission submit(long workspaceId, UUID conversationId, Question asked) {
        String requestId = asked.requestId();
        MentorConversationService.holdWrite(
                writeFence,
                conversations
                        .findUserIdByIdAndWorkspaceId(conversationId, workspaceId)
                        .orElseThrow(
                                () -> new EntityNotFoundException("MentorConversation", conversationId.toString())));
        MentorConversation conversation = entryService.lock(workspaceId, conversationId);
        Optional<MentorTurn> earlier = turns.findByWorkspaceIdAndRequestId(workspaceId, requestId);
        if (earlier.isPresent()) {
            MentorTurn turn = earlier.get();
            if (!turn.getConversationId().equals(conversationId)) {
                throw new MentorRejectedException(MentorRejectedException.Reason.REQUEST_ID_REUSED);
            }
            return new Submission(turn, entries.findQuestion(turn.getWorkspaceId(), turn.getId()), false);
        }
        if (entries.findByWorkspaceIdAndRequestId(workspaceId, requestId).isPresent()) {
            throw new MentorRejectedException(MentorRejectedException.Reason.REQUEST_ID_REUSED);
        }
        if (turns.countQueuedByWorkspaceIdAndConversationId(workspaceId, conversationId) >= maxFollowUps) {
            throw new MentorRejectedException(MentorRejectedException.Reason.FOLLOW_UPS_FULL);
        }
        MentorTurn turn = turns.saveAndFlush(
                new MentorTurn(conversation, requestId, asked.surface(), asked.pageContext(), clock.instant()));
        MentorEntry question = entryService.insert(
                conversation,
                MentorEntryDraft.question(
                        asked.surface().entrySurface(), asked.text(), turn.getId(), requestId, asked.slackTs()));
        entryService.appended(conversation, List.of(question));
        changed(turn);
        return new Submission(turn, question, true);
    }

    /** Starts the oldest turn a worker may run, if there is one. */
    @Transactional
    public Optional<MentorTurn> claimNext(String workerId, Duration lease) {
        return turns.lockClaimable().map(turn -> {
            turn.start(workerId, turns.databaseNow(), clock.instant().plus(lease));
            changed(turn);
            return turn;
        });
    }

    @Transactional
    public MentorTurn renewLease(UUID turnId, int attempt, String workerId, Duration lease) {
        MentorTurn turn = held(turnId, attempt, workerId);
        turn.renew(clock.instant().plus(lease));
        return turn;
    }

    /** Freezes the model and rates the attempt runs with, so even a lost attempt is billed at them. */
    @Transactional
    public void admit(UUID turnId, int attempt, String workerId, JsonNode admission) {
        held(turnId, attempt, workerId).admit(admission);
    }

    /**
     * Replaces the running attempt's partial answer with the host's newest whole snapshot: one conditional write and
     * its notification. A stopped attempt takes no partial answer, because the stop cleared it.
     */
    @Transactional
    public void updatePartial(UUID turnId, int attempt, String workerId, JsonNode partial) {
        turns.commitWithoutFlushWait();
        MentorTurnRepository.RunningAttempt running = turns.writePartial(turnId, attempt, workerId, partial.toString())
                .orElseThrow(() -> new MentorLeaseLostException(turnId, attempt));
        events.publishEvent(new MentorLedgerEvents.TurnChanged(
                running.getWorkspaceId(),
                running.getConversationId(),
                turnId,
                MentorTurn.Status.RUNNING,
                MentorTurn.Surface.valueOf(running.getSurface()),
                attempt));
    }

    /**
     * Ends the attempt and stores the Pi entries it appended, in order; the Pi entry of the question updates its row.
     * This is the only write of an attempt's entries, so an attempt that never ends stores nothing, and its retry
     * starts from the same path. The end clears the lease, so a repeated end finds none and stores nothing twice. A
     * failed attempt stores only the summaries it wrote before its question, so the path keeps its question and the
     * next turn does not shorten the conversation again. An attempt whose question was erased while it ran stores
     * nothing: Heph does not answer words that the developer took back or that a Slack removal erased.
     */
    @Transactional
    public EndedTurn end(
            UUID turnId,
            int attempt,
            String workerId,
            MentorTurn.Status outcome,
            List<JsonNode> appended,
            @Nullable String error) {
        List<JsonNode> stored = outcome != MentorTurn.Status.FAILED
                ? appended
                : appended.stream()
                        .filter(piEntry -> MentorPiEntries.kind(piEntry) == MentorEntry.Kind.SUMMARY)
                        .toList();
        boolean copiesData = stored.stream().anyMatch(MentorPiEntries::copiesPersonData);
        if (copiesData) {
            // Before any row lock: a person erasure holds this fence while it locks ledger rows.
            copies.holdForCapture();
        }
        MentorTurn turn = held(turnId, attempt, workerId);
        append(turn, stored, copiesData);
        turn.end(outcome, error, clock.instant());
        turns.flush();
        changed(turn);
        return new EndedTurn(turn, developerId(turn), usage(turn));
    }

    /** Records the cost an ended attempt was billed with; its usage is already on the row. */
    @Transactional
    public void recordCost(UUID turnId, @Nullable BigDecimal costUsd) {
        turns.lockById(turnId)
                .orElseThrow(() -> new EntityNotFoundException("MentorTurn", turnId.toString()))
                .recordCost(costUsd);
    }

    /** Stops a turn. Stopping a turn that already ended changes nothing. */
    @Transactional
    public MentorTurn stop(long workspaceId, UUID turnId) {
        MentorTurn turn = turns.lockByIdAndWorkspaceId(turnId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorTurn", turnId.toString()));
        if (turn.stopIfActive(clock.instant())) {
            changed(turn);
        }
        return turn;
    }

    /** Stops the running turn of a conversation, if one runs. Queued follow-ups stay queued. */
    @Transactional
    public Optional<MentorTurn> stopRunning(long workspaceId, UUID conversationId) {
        Optional<MentorTurn> running = turns
                .findByWorkspaceIdAndConversationIdAndStatus(workspaceId, conversationId, MentorTurn.Status.RUNNING)
                .stream()
                .findFirst()
                .flatMap(found -> turns.lockById(found.getId()))
                .filter(locked ->
                        locked.getStatus() == MentorTurn.Status.RUNNING && locked.stopIfActive(clock.instant()));
        running.ifPresent(this::changed);
        return running;
    }

    /** The turns whose lease expired, oldest first. */
    @Transactional(readOnly = true)
    public List<UUID> expiredLeases() {
        return turns.findExpiredLeases(clock.instant());
    }

    /**
     * Takes an expired lease back. A running turn returns to the queue for its one retry, or fails after it; a stopped
     * turn stays stopped. The lost attempt stored nothing ({@link #end}).
     */
    @Transactional
    public Optional<LostAttempt> expireLease(UUID turnId) {
        Optional<MentorTurn> locked = turns.lockById(turnId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        MentorTurn turn = locked.get();
        Instant now = clock.instant();
        Instant leasedUntil = turn.getLeasedUntil();
        if (!turn.isLeased() || leasedUntil == null || !leasedUntil.isBefore(now)) {
            return Optional.empty();
        }
        int attempt = turn.getAttempt();
        JsonNode admission = turn.getAdmission();
        MentorTurnUsage proxyUsage = usage(turn);
        if (turn.getStatus() == MentorTurn.Status.RUNNING && attempt < MAX_ATTEMPTS) {
            turn.requeue();
        } else {
            turn.end(MentorTurn.Status.FAILED, LEASE_EXPIRED, now);
        }
        changed(turn);
        return Optional.of(new LostAttempt(turn, attempt, admission, proxyUsage));
    }

    /** The developer's question that started the turn. */
    @Transactional(readOnly = true)
    public MentorEntry question(long workspaceId, UUID turnId) {
        return entries.findQuestion(workspaceId, turnId);
    }

    public MentorTurn require(long workspaceId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorTurn", turnId.toString()));
    }

    /**
     * Heph's answer: the turn's last {@code HEPH} entry that is not erased. The observations it showed are the
     * {@code link_observation} calls of the turn whose tool result succeeded, in the order Heph linked them.
     */
    @Transactional(readOnly = true)
    public Answer answer(long workspaceId, UUID turnId) {
        MentorTurn turn = require(workspaceId, turnId);
        List<MentorEntry> messages =
                entries
                        .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(workspaceId, turnId, MentorEntry.Kind.HEPH)
                        .stream()
                        .filter(entry -> !entry.isRedacted())
                        .toList();
        if (messages.isEmpty()) {
            return new Answer(turn, developerId(turn), null, Map.of());
        }
        Set<String> succeeded = new HashSet<>(entries.findShownObservationCallIds(workspaceId, List.of(turnId)));
        Map<UUID, UUID> shown = new LinkedHashMap<>();
        for (MentorEntry message : messages) {
            for (MentorPiEntries.Link link : MentorPiEntries.links(message.getPayload())) {
                if (succeeded.contains(link.callId())) {
                    shown.putIfAbsent(link.observationId(), message.getId());
                }
            }
        }
        return new Answer(turn, developerId(turn), messages.getLast().getId(), Collections.unmodifiableMap(shown));
    }

    /**
     * Stores the attempt's entries under the lock of its tree, then erases the copies that read data erased while it
     * ran ({@link MentorRedaction#eraseStaleCopies}).
     *
     * @param fenced whether the caller holds the person-data copy fence, which it takes when the attempt appended a
     *     result that copies person data
     */
    private void append(MentorTurn turn, List<JsonNode> appended, boolean fenced) {
        if (appended.isEmpty()) {
            return;
        }
        Instant attemptStart = Objects.requireNonNull(turn.getStartedAt(), "A held turn has started");
        MentorConversation conversation = entryService.lockWithTree(turn.getWorkspaceId(), turn.getConversationId());
        // A redaction locks this tree first, so the question cannot be erased after this read.
        MentorEntry question = entries.findQuestion(turn.getWorkspaceId(), turn.getId());
        if (question.isRedacted()) {
            return;
        }
        List<MentorEntry> inserted = new ArrayList<>(appended.size());
        for (JsonNode piEntry : appended) {
            MentorEntry.Kind kind = MentorPiEntries.kind(piEntry);
            if (kind == MentorEntry.Kind.USER) {
                entries.recordPiEntry(turn.getWorkspaceId(), question.getId(), piEntry.toString());
                continue;
            }
            MentorEntry entry = entryService.insert(
                    conversation,
                    new MentorEntryDraft(
                            kind,
                            turn.getSurface().entrySurface(),
                            MentorPiEntries.text(piEntry),
                            piEntry,
                            turn.getId(),
                            null,
                            null));
            inserted.add(entry);
        }
        entryService.appended(conversation, inserted);
        redaction.eraseStaleCopies(
                inserted,
                attemptStart,
                fenced && copies.erasedSince(MentorEntryRepository.COPIES_PERSON_DATA_STORE, attemptStart));
    }

    /** The turn, locked, if this attempt of this worker still holds it. */
    private MentorTurn held(UUID turnId, int attempt, String workerId) {
        return turns.lockById(turnId)
                .filter(turn -> turn.isLeased() && turn.getAttempt() == attempt && workerId.equals(turn.getWorkerId()))
                .orElseThrow(() -> new MentorLeaseLostException(turnId, attempt));
    }

    public long developerId(MentorTurn turn) {
        return conversations
                .findByIdAndWorkspaceId(turn.getConversationId(), turn.getWorkspaceId())
                .map(MentorConversation::getUserId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "MentorConversation", turn.getConversationId().toString()));
    }

    private static MentorTurnUsage usage(MentorTurn turn) {
        return new MentorTurnUsage(
                turn.getInputTokens(),
                turn.getOutputTokens(),
                turn.getCachedTokens(),
                turn.getCacheWriteTokens(),
                turn.getReasoningTokens(),
                turn.getLlmCalls());
    }

    private void changed(MentorTurn turn) {
        events.publishEvent(MentorLedgerEvents.TurnChanged.of(turn));
    }
}

package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.action.LaneAction;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestDTO;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The developer's Hephaestus conversation as the app reads it: pages of messages, threads, turns, and the live changes
 * to them. A read for the app is the account's own: a conversation, entry or turn of another member is not found. The
 * live stream's reads ({@link #liveTurn}, {@link #liveEntry}) and {@link #shown} read any member's rows; the live
 * reads name the developer each row belongs to.
 *
 * <p>The app shows the developer's messages, Hephaestus's messages and weekly digests ({@link ConversationEntryRepository#SHOWN}).
 * A Hephaestus message shows what its successful link tool calls showed: results, problems and prepared feedback, with
 * where to read about them. A question carries the outcome of its turn, so a stopped or failed turn survives a
 * reload. A weekly digest is rendered by {@link WeeklyDigestFacts}.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ConversationTimeline {

    public static final int DEFAULT_PAGE = 50;
    public static final int MAX_PAGE = 100;
    static final int MAX_THREADS = 50;

    private final ConversationRepository conversations;
    private final ConversationEntryRepository entries;
    private final ConversationTurnRepository turns;
    private final ConversationTurnWaits waits;
    private final ConversationTurnProgress progress;
    private final WeeklyDigestFacts weeklyDigests;
    private final ResultHistoryQuery results;
    private final FeedbackHistoryQuery feedback;
    private final ObjectMapper objectMapper;
    private final AgentActions actions;

    /**
     * The partial answers of running turns reach this process only while it listens to the ledger, from the moment the
     * application is ready. The build profiles serve no turn, and their database cannot {@code LISTEN}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnServerRole
    @Profile("!specs & !cds-training")
    static class PartialAnswersConfiguration {
        @Bean
        ApplicationListener<ApplicationReadyEvent> conversationPartialAnswers(
                ConversationLedgerNotifications notifications) {
            return event -> notifications.startListening();
        }
    }

    /** A value the live stream sends, and the developer whose conversation it belongs to. */
    public record Owned<T>(long accountId, T value) {}

    @Transactional(readOnly = true)
    public ConversationDTO conversation(long workspaceId, long accountId) {
        Optional<Conversation> found = main(workspaceId, accountId);
        if (found.isEmpty()) {
            return ConversationDTO.NONE;
        }
        Conversation main = found.get();
        List<ConversationTurn> activeTurns = turns.findActiveInTree(workspaceId, main.getId());
        Map<UUID, ConversationTurn.WaitingFor> waiting = waits.waits(workspaceId, activeTurns);
        List<ConversationTurnDTO> active = activeTurns.stream()
                .filter(turn -> turn.getSurface() != ConversationTurn.Surface.SYSTEM)
                .map(turn -> turn(
                        turn,
                        parentEntryId(main, turn.getConversationId()),
                        waiting.get(turn.getId()),
                        progress.snapshot(turn)))
                .toList();
        List<ConversationThreadSummaryDTO> threads =
                entries.findThreadSummariesOfMain(workspaceId, main.getId(), MAX_THREADS).stream()
                        .map(ConversationThreadSummaryDTO::from)
                        .toList();
        return new ConversationDTO(
                main.getId(),
                entries.findLatestShownSeq(workspaceId, main.getId()),
                active,
                threads,
                entries.findLatestAnswerAt(workspaceId, main.getId()),
                main.getLastReadAt());
    }

    /** A page of the main conversation's messages before {@code cursor}, or the newest page. */
    @Transactional(readOnly = true)
    public CursorPageDTO<ConversationEntryDTO> entries(
            long workspaceId, long accountId, @Nullable String cursor, int size) {
        long before = seqBefore(cursor);
        return main(workspaceId, accountId)
                .map(main -> page(main, before, size))
                .orElse(CursorPageDTO.empty());
    }

    /** The thread on one of the main conversation's messages; a message without a thread has no replies yet. */
    @Transactional(readOnly = true)
    public ConversationThreadDTO thread(
            long workspaceId, long accountId, UUID parentEntryId, @Nullable String cursor, int size) {
        long before = seqBefore(cursor);
        ConversationEntry parent = ownedEntry(workspaceId, accountId, parentEntryId)
                .filter(entry -> entry.getKind().isMessage())
                .orElseThrow(() -> new EntityNotFoundException("ConversationEntry", parentEntryId.toString()));
        Conversation main = owner(parent);
        if (main.isThread()) {
            throw new EntityNotFoundException("ConversationEntry", parentEntryId.toString());
        }
        ConversationEntryDTO parentDto = dtos(main, List.of(parent)).getFirst();
        Optional<Conversation> thread = conversations.findByWorkspaceIdAndParentEntryId(workspaceId, parentEntryId);
        if (thread.isEmpty()) {
            return new ConversationThreadDTO(parentDto, null, CursorPageDTO.empty());
        }
        return new ConversationThreadDTO(parentDto, thread.get().getId(), page(thread.get(), before, size));
    }

    @Transactional(readOnly = true)
    public ConversationTurnDTO turn(long workspaceId, long accountId, UUID turnId) {
        ConversationTurn turn = ownedTurn(workspaceId, accountId, turnId);
        Conversation conversation = conversation(workspaceId, turn.getConversationId());
        return turn(turn, conversation.getParentEntryId(), waitingFor(turn), progress.snapshot(turn));
    }

    @Transactional(readOnly = true)
    public ConversationEntryDTO entry(long workspaceId, long accountId, UUID entryId) {
        ConversationEntry entry = ownedEntry(workspaceId, accountId, entryId)
                .orElseThrow(() -> new EntityNotFoundException("ConversationEntry", entryId.toString()));
        return dtos(owner(entry), List.of(entry)).getFirst();
    }

    /** The account's entry, for a write that the account may make only to its own conversation. */
    @Transactional(readOnly = true)
    public ConversationEntry requireOwnedEntry(long workspaceId, long accountId, UUID entryId) {
        return ownedEntry(workspaceId, accountId, entryId)
                .orElseThrow(() -> new EntityNotFoundException("ConversationEntry", entryId.toString()));
    }

    /** The account's turn, for a write that the account may make only to its own conversation. */
    @Transactional(readOnly = true)
    public ConversationTurn requireOwnedTurn(long workspaceId, long accountId, UUID turnId) {
        return ownedTurn(workspaceId, accountId, turnId);
    }

    private ConversationTurn ownedTurn(long workspaceId, long accountId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .filter(turn ->
                        conversation(workspaceId, turn.getConversationId()).getAccountId() == accountId)
                .orElseThrow(() -> new EntityNotFoundException("ConversationTurn", turnId.toString()));
    }

    /** A turn as the live stream sends it, with the developer it belongs to. */
    @Transactional(readOnly = true)
    public Optional<Owned<ConversationTurnDTO>> liveTurn(long workspaceId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .filter(turn -> turn.getSurface() != ConversationTurn.Surface.SYSTEM)
                .flatMap(turn -> conversations
                        .findByIdAndWorkspaceId(turn.getConversationId(), workspaceId)
                        .map(conversation -> new Owned<>(
                                conversation.getAccountId(),
                                turn(
                                        turn,
                                        conversation.getParentEntryId(),
                                        waitingFor(turn),
                                        progress.snapshot(turn)))));
    }

    /**
     * An entry as the live stream sends it, with the developer it belongs to. Empty when the app does not show the
     * entry. A Hephaestus message that was erased is sent as deleted, so the app removes it.
     */
    @Transactional(readOnly = true)
    public Optional<Owned<ConversationEntryDTO>> liveEntry(long workspaceId, UUID entryId) {
        Optional<ConversationEntry> changed = entries.findByIdAndWorkspaceId(entryId, workspaceId);
        if (changed.isEmpty()) {
            return Optional.empty();
        }
        ConversationEntry entry = changed.get();
        if (!entry.getKind().isMessage()) {
            return Optional.empty();
        }
        if (entry.getSurface() == ConversationEntry.Surface.SYSTEM
                && !entry.isRedacted()
                && !entries.isShown(workspaceId, entryId)) {
            return Optional.empty();
        }
        Conversation conversation = conversation(workspaceId, entry.getConversationId());
        ConversationEntryDTO dto = dtos(conversation, List.of(entry)).getFirst();
        return dto.deleted() || shows(dto)
                ? Optional.of(new Owned<>(conversation.getAccountId(), dto))
                : Optional.empty();
    }

    /** The messages among {@code rows} of one conversation that the app shows, as it shows them. */
    List<ConversationEntryDTO> shown(Conversation conversation, List<ConversationEntry> rows) {
        return dtos(conversation, rows).stream()
                .filter(ConversationTimeline::shows)
                .toList();
    }

    /** A Hephaestus message shows only when it has text or shows feedback. */
    private static boolean shows(ConversationEntryDTO entry) {
        return entry.kind() != ConversationEntryDTO.Kind.ANSWER
                || !entry.text().isEmpty()
                || !entry.links().isEmpty();
    }

    private Optional<Conversation> main(long workspaceId, long accountId) {
        return conversations.findMain(workspaceId, accountId);
    }

    private Optional<ConversationEntry> ownedEntry(long workspaceId, long accountId, UUID entryId) {
        return entries.findByIdAndWorkspaceId(entryId, workspaceId)
                .filter(entry -> owner(entry).getAccountId() == accountId);
    }

    private Conversation owner(ConversationEntry entry) {
        return conversation(entry.getWorkspaceId(), entry.getConversationId());
    }

    private Conversation conversation(long workspaceId, UUID conversationId) {
        return conversations
                .findByIdAndWorkspaceId(conversationId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Conversation", conversationId.toString()));
    }

    private @Nullable UUID parentEntryId(Conversation main, UUID conversationId) {
        return conversationId.equals(main.getId())
                ? null
                : conversation(main.getWorkspaceId(), conversationId).getParentEntryId();
    }

    private CursorPageDTO<ConversationEntryDTO> page(Conversation conversation, long before, int requested) {
        int size = Math.clamp(requested, 1, MAX_PAGE);
        List<ConversationEntry> newestFirst =
                entries.findShownBefore(conversation.getWorkspaceId(), conversation.getId(), before, size + 1);
        List<ConversationEntry> page = new ArrayList<>(newestFirst.subList(0, Math.min(size, newestFirst.size())));
        // A page can show fewer messages than it read, so the cursor is the oldest entry read, not the oldest shown.
        String nextCursor =
                newestFirst.size() > size ? String.valueOf(page.getLast().getSeq()) : null;
        return new CursorPageDTO<>(shown(conversation, page.reversed()), nextCursor);
    }

    private static long seqBefore(@Nullable String cursor) {
        if (cursor == null) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The cursor is not valid. Reload the conversation and try again.", e);
        }
    }

    /**
     * Maps entries of one conversation, with the threads on top-level ones, the feedback Hephaestus showed, the outcome of
     * each question's turn and each weekly digest as it reads now.
     */
    private List<ConversationEntryDTO> dtos(Conversation conversation, List<ConversationEntry> rows) {
        long workspaceId = conversation.getWorkspaceId();
        Map<UUID, ConversationThreadSummaryDTO> threads = Map.of();
        if (!conversation.isThread() && !rows.isEmpty()) {
            threads =
                    entries
                            .findThreadSummaries(
                                    workspaceId,
                                    rows.stream().map(ConversationEntry::getId).toList())
                            .stream()
                            .map(ConversationThreadSummaryDTO::from)
                            .collect(
                                    Collectors.toMap(ConversationThreadSummaryDTO::parentEntryId, Function.identity()));
        }
        Set<UUID> linkingTurns = new HashSet<>();
        Set<UUID> questionTurns = new HashSet<>();
        for (ConversationEntry row : rows) {
            UUID turnId = row.getTurnId();
            if (turnId != null
                    && row.getKind() == ConversationEntry.Kind.ANSWER
                    && !links(row).isEmpty()) {
                linkingTurns.add(turnId);
            }
            if (turnId != null && row.getKind() == ConversationEntry.Kind.USER) {
                questionTurns.add(turnId);
            }
        }
        Set<ConversationPiEntries.ShownCall> shownCalls =
                linkingTurns.isEmpty() ? Set.of() : entries.shownLinkCalls(workspaceId, linkingTurns);
        Map<UUID, List<ConversationLinkDTO>> links = links(conversation, rows, shownCalls);
        Map<UUID, ConversationTurn> questionTurnById = questionTurns.isEmpty()
                ? Map.of()
                : turns.findAllById(questionTurns).stream()
                        .filter(turn -> turn.getWorkspaceId() == workspaceId)
                        .collect(Collectors.toMap(ConversationTurn::getId, Function.identity()));
        Map<UUID, WeeklyDigestDTO> digests = digests(conversation, rows);
        Map<String, LaneAction> replies = actions.byKeys(
                workspaceId,
                rows.stream()
                        .filter(row -> row.getKind() == ConversationEntry.Kind.USER
                                && row.getSurface() == ConversationEntry.Surface.SLACK)
                        .map(row -> ConversationSlackReplies.replyKey(row.getId()))
                        .toList());
        List<ConversationEntryDTO> mapped = new ArrayList<>(rows.size());
        for (ConversationEntry row : rows) {
            UUID turnId = row.getTurnId();
            mapped.add(dto(
                    conversation,
                    row,
                    threads.get(row.getId()),
                    links.getOrDefault(row.getId(), List.of()),
                    row.getKind() == ConversationEntry.Kind.USER && turnId != null
                            ? questionTurnById.get(turnId)
                            : null,
                    digests.get(row.getId()),
                    slackDelivery(row, replies)));
        }
        return mapped;
    }

    /**
     * What each Hephaestus message showed, in call order: the results, problems and prepared feedback of its successful link
     * calls. Each names its practice and the work it is about when the developer may still read them: a problem through
     * its result, and feedback by its own item and the practice of its results when it has one practice.
     */
    private Map<UUID, List<ConversationLinkDTO>> links(
            Conversation conversation, List<ConversationEntry> rows, Set<ConversationPiEntries.ShownCall> shownCalls) {
        Map<UUID, List<ConversationLinkDTO>> shown = new HashMap<>();
        Map<UUID, Optional<ResultHistoryEntry>> read = new HashMap<>();
        for (ConversationEntry row : rows) {
            UUID turnId = row.getTurnId();
            if (row.getKind() != ConversationEntry.Kind.ANSWER || row.isRedacted() || turnId == null) {
                continue;
            }
            List<ConversationLinkDTO> links = links(row).stream()
                    .filter(link -> shownCalls.contains(link.in(turnId)))
                    .map(link -> link(conversation, link, read))
                    .toList();
            if (!links.isEmpty()) {
                shown.put(row.getId(), links);
            }
        }
        return shown;
    }

    private ConversationLinkDTO link(
            Conversation conversation, ConversationPiEntries.Link link, Map<UUID, Optional<ResultHistoryEntry>> read) {
        long workspaceId = conversation.getWorkspaceId();
        long accountId = conversation.getAccountId();
        return switch (link.kind()) {
            case RESULT -> {
                var result = read.computeIfAbsent(link.id(), id -> readable(conversation, id));
                yield new ConversationLinkDTO(
                        ConversationLinkDTO.Kind.RESULT,
                        link.id(),
                        link.text(),
                        result.map(ResultHistoryEntry::practiceSlug).orElse(null),
                        result.map(ResultHistoryEntry::itemId).orElse(null));
            }
            case PROBLEM -> {
                var result = results.findOfProblem(
                        workspaceId, link.id(), accountId, SourceUsePurpose.CONVERSATIONAL_MENTORING);
                yield new ConversationLinkDTO(
                        ConversationLinkDTO.Kind.PROBLEM,
                        link.id(),
                        link.text(),
                        result.map(ResultHistoryEntry::practiceSlug).orElse(null),
                        result.map(ResultHistoryEntry::itemId).orElse(null));
            }
            case FEEDBACK -> {
                var shown = feedback.find(workspaceId, link.id(), accountId);
                yield new ConversationLinkDTO(
                        ConversationLinkDTO.Kind.FEEDBACK,
                        link.id(),
                        link.text(),
                        shown.map(FeedbackHistoryEntry::practiceSlugs)
                                .filter(slugs -> slugs.size() == 1)
                                .map(List::getFirst)
                                .orElse(null),
                        shown.map(FeedbackHistoryEntry::itemId).orElse(null));
            }
        };
    }

    private Optional<ResultHistoryEntry> readable(Conversation conversation, UUID resultId) {
        return results.find(
                conversation.getWorkspaceId(),
                resultId,
                conversation.getAccountId(),
                SourceUsePurpose.CONVERSATIONAL_MENTORING);
    }

    /** The weekly digests among {@code rows} as they read now, rendered together, by entry id. */
    private Map<UUID, WeeklyDigestDTO> digests(Conversation conversation, List<ConversationEntry> rows) {
        List<UUID> ids = new ArrayList<>();
        List<WeeklyDigestPayload> payloads = new ArrayList<>();
        for (ConversationEntry row : rows) {
            if (row.getKind() != ConversationEntry.Kind.DIGEST || row.isRedacted()) {
                continue;
            }
            WeeklyDigestPayload.fromJson(row.getPayload(), objectMapper).ifPresent(payload -> {
                ids.add(row.getId());
                payloads.add(payload);
            });
        }
        List<WeeklyDigestFacts.Rendered> rendered =
                weeklyDigests.render(conversation.getWorkspaceId(), conversation.getAccountId(), payloads);
        Map<UUID, WeeklyDigestDTO> digests = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            digests.put(ids.get(i), rendered.get(i).digest());
        }
        return digests;
    }

    private static ConversationEntryDTO dto(
            Conversation conversation,
            ConversationEntry entry,
            @Nullable ConversationThreadSummaryDTO thread,
            List<ConversationLinkDTO> links,
            @Nullable ConversationTurn questionTurn,
            @Nullable WeeklyDigestDTO digest,
            ConversationEntry.@Nullable SlackDelivery slackDelivery) {
        return new ConversationEntryDTO(
                entry.getId(),
                entry.getConversationId(),
                conversation.getParentEntryId(),
                entry.getSeq(),
                switch (entry.getKind()) {
                    case USER -> ConversationEntryDTO.Kind.USER;
                    case DIGEST -> ConversationEntryDTO.Kind.DIGEST;
                    case ANSWER -> ConversationEntryDTO.Kind.ANSWER;
                    case TOOL_RESULT, SYSTEM, SUMMARY ->
                        throw new IllegalArgumentException("Only messages are shown, not " + entry.getKind());
                },
                entry.getSurface(),
                entry.getText(),
                entry.getRequestId(),
                entry.getTurnId(),
                questionTurn == null ? null : questionTurn.getStatus(),
                questionTurn == null ? null : questionTurn.getError(),
                entry.getCreatedAt(),
                entry.getEditedAt(),
                slackDelivery,
                entry.isRedacted(),
                entry.getVote(),
                links,
                thread,
                digest);
    }

    /** How a Slack question's reply stands: its outbox action, including its terminal outcome. */
    private static ConversationEntry.@Nullable SlackDelivery slackDelivery(
            ConversationEntry row, Map<String, LaneAction> replies) {
        if (row.getKind() != ConversationEntry.Kind.USER || row.getSurface() != ConversationEntry.Surface.SLACK)
            return null;
        return ConversationSlackReplies.delivery(replies.get(ConversationSlackReplies.replyKey(row.getId())));
    }

    /** The link tool calls in a Hephaestus message, in order. */
    private static List<ConversationPiEntries.Link> links(ConversationEntry entry) {
        return entry.getKind() == ConversationEntry.Kind.ANSWER
                ? ConversationPiEntries.links(entry.getPayload())
                : List.of();
    }

    private ConversationTurn.@Nullable WaitingFor waitingFor(ConversationTurn turn) {
        return waits.waits(turn.getWorkspaceId(), List.of(turn)).get(turn.getId());
    }

    private static ConversationTurnDTO turn(
            ConversationTurn turn,
            @Nullable UUID parentEntryId,
            ConversationTurn.@Nullable WaitingFor waitingFor,
            @Nullable JsonNode partial) {
        return new ConversationTurnDTO(
                turn.getId(),
                turn.getConversationId(),
                parentEntryId,
                turn.getRequestId(),
                turn.getStatus(),
                waitingFor,
                turn.getAttempt(),
                turn.getSurface(),
                partial == null || turn.getStatus() != ConversationTurn.Status.RUNNING
                        ? null
                        : ConversationTurnPartialDTO.from(partial),
                turn.getError(),
                turn.getCreatedAt(),
                turn.getFinishedAt());
    }
}

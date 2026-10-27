package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The developer's Heph conversation as the app reads it: pages of messages, threads, turns, and the live changes
 * to them. A read for the app is the account's own: a conversation, entry or turn of another member is not found. The
 * live stream's reads ({@link #liveTurn}, {@link #liveEntry}) and {@link #shown} read any member's rows; the live
 * reads name the developer each row belongs to.
 *
 * <p>The app shows the developer's messages, Heph's messages and weekly digests ({@link MentorEntryRepository#SHOWN}).
 * A Heph message shows what its successful link tool calls showed: results, problems and prepared feedback, with
 * where to read about them. A question carries the outcome of its turn, so a stopped or failed turn survives a
 * reload. A weekly digest is rendered by {@link WeeklyDigestFacts}.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class MentorTimeline {

    public static final int DEFAULT_PAGE = 50;
    public static final int MAX_PAGE = 100;
    static final int MAX_THREADS = 50;

    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;
    private final MentorTurnRepository turns;
    private final MentorTurnService turnService;
    private final WeeklyDigestFacts weeklyDigests;
    private final ResultHistoryQuery results;
    private final FeedbackHistoryQuery feedback;
    private final ObjectMapper objectMapper;

    /** A value the live stream sends, and the developer whose conversation it belongs to. */
    public record Owned<T>(long accountId, T value) {}

    @Transactional(readOnly = true)
    public MentorConversationDTO conversation(long workspaceId, long accountId) {
        Optional<MentorConversation> found = main(workspaceId, accountId);
        if (found.isEmpty()) {
            return MentorConversationDTO.NONE;
        }
        MentorConversation main = found.get();
        List<MentorTurn> activeTurns = turns.findActiveInTree(workspaceId, main.getId());
        Map<UUID, MentorTurn.WaitingFor> waits = turnService.waits(workspaceId, activeTurns);
        List<MentorTurnDTO> active = activeTurns.stream()
                .map(turn -> turn(turn, parentEntryId(main, turn.getConversationId()), waits.get(turn.getId())))
                .toList();
        List<MentorThreadSummaryDTO> threads =
                entries.findThreadSummariesOfMain(workspaceId, main.getId(), MAX_THREADS).stream()
                        .map(MentorThreadSummaryDTO::from)
                        .toList();
        return new MentorConversationDTO(
                main.getId(),
                entries.findLatestShownSeq(workspaceId, main.getId()),
                active,
                threads,
                entries.findLatestAnswerAt(workspaceId, main.getId()),
                main.getLastReadAt());
    }

    /** A page of the main conversation's messages before {@code beforeSeq}, or the newest page. */
    @Transactional(readOnly = true)
    public MentorEntryPageDTO entries(long workspaceId, long accountId, @Nullable Long beforeSeq, int limit) {
        return main(workspaceId, accountId)
                .map(main -> page(main, beforeSeq, limit))
                .orElse(MentorEntryPageDTO.EMPTY);
    }

    /** The thread on one of the main conversation's messages; a message without a thread has no replies yet. */
    @Transactional(readOnly = true)
    public MentorThreadDTO thread(
            long workspaceId, long accountId, UUID parentEntryId, @Nullable Long beforeSeq, int limit) {
        MentorEntry parent = ownedEntry(workspaceId, accountId, parentEntryId)
                .filter(entry -> entry.getKind().isMessage())
                .orElseThrow(() -> new EntityNotFoundException("MentorEntry", parentEntryId.toString()));
        MentorConversation main = owner(parent);
        if (main.isThread()) {
            throw new EntityNotFoundException("MentorEntry", parentEntryId.toString());
        }
        MentorEntryDTO parentDto = dtos(main, List.of(parent)).getFirst();
        Optional<MentorConversation> thread =
                conversations.findByWorkspaceIdAndParentEntryId(workspaceId, parentEntryId);
        if (thread.isEmpty()) {
            return new MentorThreadDTO(parentDto, null, List.of(), null);
        }
        MentorEntryPageDTO page = page(thread.get(), beforeSeq, limit);
        return new MentorThreadDTO(parentDto, thread.get().getId(), page.entries(), page.earlierBefore());
    }

    @Transactional(readOnly = true)
    public MentorTurnDTO turn(long workspaceId, long accountId, UUID turnId) {
        MentorTurn turn = ownedTurn(workspaceId, accountId, turnId);
        MentorConversation conversation = conversation(workspaceId, turn.getConversationId());
        return turn(turn, conversation.getParentEntryId(), turnService.waitingFor(turn));
    }

    @Transactional(readOnly = true)
    public MentorEntryDTO entry(long workspaceId, long accountId, UUID entryId) {
        MentorEntry entry = ownedEntry(workspaceId, accountId, entryId)
                .orElseThrow(() -> new EntityNotFoundException("MentorEntry", entryId.toString()));
        return dtos(owner(entry), List.of(entry)).getFirst();
    }

    /** The account's entry, for a write that the account may make only to its own conversation. */
    @Transactional(readOnly = true)
    public MentorEntry requireOwnedEntry(long workspaceId, long accountId, UUID entryId) {
        return ownedEntry(workspaceId, accountId, entryId)
                .orElseThrow(() -> new EntityNotFoundException("MentorEntry", entryId.toString()));
    }

    /** The account's turn, for a write that the account may make only to its own conversation. */
    @Transactional(readOnly = true)
    public MentorTurn requireOwnedTurn(long workspaceId, long accountId, UUID turnId) {
        return ownedTurn(workspaceId, accountId, turnId);
    }

    private MentorTurn ownedTurn(long workspaceId, long accountId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .filter(turn ->
                        conversation(workspaceId, turn.getConversationId()).getAccountId() == accountId)
                .orElseThrow(() -> new EntityNotFoundException("MentorTurn", turnId.toString()));
    }

    /** A turn as the live stream sends it, with the developer it belongs to. */
    @Transactional(readOnly = true)
    public Optional<Owned<MentorTurnDTO>> liveTurn(long workspaceId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .flatMap(turn -> conversations
                        .findByIdAndWorkspaceId(turn.getConversationId(), workspaceId)
                        .map(conversation -> new Owned<>(
                                conversation.getAccountId(),
                                turn(turn, conversation.getParentEntryId(), turnService.waitingFor(turn)))));
    }

    /**
     * An entry as the live stream sends it, with the developer it belongs to. Empty when the app does not show the
     * entry. A Heph message that was erased is sent as deleted, so the app removes it.
     */
    @Transactional(readOnly = true)
    public Optional<Owned<MentorEntryDTO>> liveEntry(long workspaceId, UUID entryId) {
        Optional<MentorEntry> changed = entries.findByIdAndWorkspaceId(entryId, workspaceId);
        if (changed.isEmpty()) {
            return Optional.empty();
        }
        MentorEntry entry = changed.get();
        if (!entry.getKind().isMessage()) {
            return Optional.empty();
        }
        MentorConversation conversation = conversation(workspaceId, entry.getConversationId());
        MentorEntryDTO dto = dtos(conversation, List.of(entry)).getFirst();
        return dto.deleted() || shows(dto)
                ? Optional.of(new Owned<>(conversation.getAccountId(), dto))
                : Optional.empty();
    }

    /** The messages among {@code rows} of one conversation that the app shows, as it shows them. */
    List<MentorEntryDTO> shown(MentorConversation conversation, List<MentorEntry> rows) {
        return dtos(conversation, rows).stream().filter(MentorTimeline::shows).toList();
    }

    /** A Heph message shows only when it has text or shows feedback. */
    private static boolean shows(MentorEntryDTO entry) {
        return entry.kind() != MentorEntryDTO.Kind.HEPH
                || !entry.text().isEmpty()
                || !entry.links().isEmpty();
    }

    private Optional<MentorConversation> main(long workspaceId, long accountId) {
        return conversations.findMain(workspaceId, accountId);
    }

    private Optional<MentorEntry> ownedEntry(long workspaceId, long accountId, UUID entryId) {
        return entries.findByIdAndWorkspaceId(entryId, workspaceId)
                .filter(entry -> owner(entry).getAccountId() == accountId);
    }

    private MentorConversation owner(MentorEntry entry) {
        return conversation(entry.getWorkspaceId(), entry.getConversationId());
    }

    private MentorConversation conversation(long workspaceId, UUID conversationId) {
        return conversations
                .findByIdAndWorkspaceId(conversationId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorConversation", conversationId.toString()));
    }

    private @Nullable UUID parentEntryId(MentorConversation main, UUID conversationId) {
        return conversationId.equals(main.getId())
                ? null
                : conversation(main.getWorkspaceId(), conversationId).getParentEntryId();
    }

    private MentorEntryPageDTO page(MentorConversation conversation, @Nullable Long beforeSeq, int limit) {
        int size = Math.clamp(limit, 1, MAX_PAGE);
        List<MentorEntry> newestFirst = entries.findShownBefore(
                conversation.getWorkspaceId(),
                conversation.getId(),
                beforeSeq == null ? Long.MAX_VALUE : beforeSeq,
                size + 1);
        List<MentorEntry> page = new ArrayList<>(newestFirst.subList(0, Math.min(size, newestFirst.size())));
        // A page can show fewer messages than it read, so the cursor is the oldest entry read, not the oldest shown.
        Long earlierBefore = newestFirst.size() > size ? page.getLast().getSeq() : null;
        return new MentorEntryPageDTO(shown(conversation, page.reversed()), earlierBefore);
    }

    /**
     * Maps entries of one conversation, with the threads on top-level ones, the feedback Heph showed, the outcome of
     * each question's turn and each weekly digest as it reads now.
     */
    private List<MentorEntryDTO> dtos(MentorConversation conversation, List<MentorEntry> rows) {
        long workspaceId = conversation.getWorkspaceId();
        Map<UUID, MentorThreadSummaryDTO> threads = Map.of();
        if (!conversation.isThread() && !rows.isEmpty()) {
            threads = entries
                    .findThreadSummaries(
                            workspaceId, rows.stream().map(MentorEntry::getId).toList())
                    .stream()
                    .map(MentorThreadSummaryDTO::from)
                    .collect(Collectors.toMap(MentorThreadSummaryDTO::parentEntryId, Function.identity()));
        }
        Set<UUID> linkingTurns = new HashSet<>();
        Set<UUID> questionTurns = new HashSet<>();
        for (MentorEntry row : rows) {
            UUID turnId = row.getTurnId();
            if (turnId != null
                    && row.getKind() == MentorEntry.Kind.HEPH
                    && !links(row).isEmpty()) {
                linkingTurns.add(turnId);
            }
            if (turnId != null && row.getKind() == MentorEntry.Kind.USER) {
                questionTurns.add(turnId);
            }
        }
        Set<MentorPiEntries.ShownCall> shownCalls =
                linkingTurns.isEmpty() ? Set.of() : entries.shownLinkCalls(workspaceId, linkingTurns);
        Map<UUID, List<MentorLinkDTO>> links = links(conversation, rows, shownCalls);
        Map<UUID, MentorTurn> questionTurnById = questionTurns.isEmpty()
                ? Map.of()
                : turns.findAllById(questionTurns).stream()
                        .filter(turn -> turn.getWorkspaceId() == workspaceId)
                        .collect(Collectors.toMap(MentorTurn::getId, Function.identity()));
        Map<UUID, WeeklyDigestDTO> digests = digests(conversation, rows);
        List<MentorEntryDTO> mapped = new ArrayList<>(rows.size());
        for (MentorEntry row : rows) {
            UUID turnId = row.getTurnId();
            mapped.add(dto(
                    conversation,
                    row,
                    threads.get(row.getId()),
                    links.getOrDefault(row.getId(), List.of()),
                    row.getKind() == MentorEntry.Kind.USER && turnId != null ? questionTurnById.get(turnId) : null,
                    digests.get(row.getId())));
        }
        return mapped;
    }

    /**
     * What each Heph message showed, in call order: the results, problems and prepared feedback of its successful link
     * calls. Each names its practice and the work it is about when the developer may still read them: a problem through
     * its result, and feedback by its own item and the practice of its results when it has one practice.
     */
    private Map<UUID, List<MentorLinkDTO>> links(
            MentorConversation conversation, List<MentorEntry> rows, Set<MentorPiEntries.ShownCall> shownCalls) {
        Map<UUID, List<MentorLinkDTO>> shown = new HashMap<>();
        Map<UUID, Optional<ResultHistoryEntry>> read = new HashMap<>();
        for (MentorEntry row : rows) {
            UUID turnId = row.getTurnId();
            if (row.getKind() != MentorEntry.Kind.HEPH || row.isRedacted() || turnId == null) {
                continue;
            }
            List<MentorLinkDTO> links = links(row).stream()
                    .filter(link -> shownCalls.contains(link.in(turnId)))
                    .map(link -> link(conversation, link, read))
                    .toList();
            if (!links.isEmpty()) {
                shown.put(row.getId(), links);
            }
        }
        return shown;
    }

    private MentorLinkDTO link(
            MentorConversation conversation, MentorPiEntries.Link link, Map<UUID, Optional<ResultHistoryEntry>> read) {
        long workspaceId = conversation.getWorkspaceId();
        long accountId = conversation.getAccountId();
        return switch (link.kind()) {
            case RESULT -> {
                var result = read.computeIfAbsent(link.id(), id -> readable(conversation, id));
                yield new MentorLinkDTO(
                        MentorLinkDTO.Kind.RESULT,
                        link.id(),
                        link.text(),
                        result.map(ResultHistoryEntry::practiceSlug).orElse(null),
                        result.map(ResultHistoryEntry::itemId).orElse(null));
            }
            case PROBLEM -> {
                var result = results.findOfProblem(
                        workspaceId, link.id(), accountId, SourceUsePurpose.CONVERSATIONAL_MENTORING);
                yield new MentorLinkDTO(
                        MentorLinkDTO.Kind.PROBLEM,
                        link.id(),
                        link.text(),
                        result.map(ResultHistoryEntry::practiceSlug).orElse(null),
                        result.map(ResultHistoryEntry::itemId).orElse(null));
            }
            case FEEDBACK -> {
                var shown = feedback.find(workspaceId, link.id(), accountId);
                yield new MentorLinkDTO(
                        MentorLinkDTO.Kind.FEEDBACK,
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

    private Optional<ResultHistoryEntry> readable(MentorConversation conversation, UUID resultId) {
        return results.find(
                conversation.getWorkspaceId(),
                resultId,
                conversation.getAccountId(),
                SourceUsePurpose.CONVERSATIONAL_MENTORING);
    }

    /** The weekly digests among {@code rows} as they read now, rendered together, by entry id. */
    private Map<UUID, WeeklyDigestDTO> digests(MentorConversation conversation, List<MentorEntry> rows) {
        List<UUID> ids = new ArrayList<>();
        List<WeeklyDigestPayload> payloads = new ArrayList<>();
        for (MentorEntry row : rows) {
            if (row.getKind() != MentorEntry.Kind.DIGEST || row.isRedacted()) {
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

    private static MentorEntryDTO dto(
            MentorConversation conversation,
            MentorEntry entry,
            @Nullable MentorThreadSummaryDTO thread,
            List<MentorLinkDTO> links,
            @Nullable MentorTurn questionTurn,
            @Nullable WeeklyDigestDTO digest) {
        return new MentorEntryDTO(
                entry.getId(),
                entry.getConversationId(),
                conversation.getParentEntryId(),
                entry.getSeq(),
                switch (entry.getKind()) {
                    case USER -> MentorEntryDTO.Kind.USER;
                    case DIGEST -> MentorEntryDTO.Kind.DIGEST;
                    case HEPH -> MentorEntryDTO.Kind.HEPH;
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
                entry.getKind() == MentorEntry.Kind.USER && entry.getSurface() == MentorEntry.Surface.SLACK
                        ? entry.getSlackDelivery()
                        : null,
                entry.isRedacted(),
                entry.getVote(),
                links,
                thread,
                digest);
    }

    /** The link tool calls in a Heph message, in order. */
    private static List<MentorPiEntries.Link> links(MentorEntry entry) {
        return entry.getKind() == MentorEntry.Kind.HEPH ? MentorPiEntries.links(entry.getPayload()) : List.of();
    }

    private static MentorTurnDTO turn(
            MentorTurn turn, @Nullable UUID parentEntryId, MentorTurn.@Nullable WaitingFor waitingFor) {
        JsonNode partial = turn.getPartial();
        return new MentorTurnDTO(
                turn.getId(),
                turn.getConversationId(),
                parentEntryId,
                turn.getRequestId(),
                turn.getStatus(),
                waitingFor,
                turn.getAttempt(),
                turn.getSurface(),
                partial == null || turn.getStatus() != MentorTurn.Status.RUNNING
                        ? null
                        : MentorTurnPartialDTO.from(partial),
                turn.getError(),
                turn.getCreatedAt(),
                turn.getFinishedAt());
    }
}

package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationReferences;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestDTO;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import java.util.ArrayList;
import java.util.Collection;
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
 * A Heph message shows the feedback its successful {@code link_observation} calls showed, with where to read about
 * it. A question carries the outcome of its turn, so a stopped or failed turn survives a reload. A weekly digest is
 * rendered by {@link WeeklyDigestFacts}.
 */
@Service
@RequiredArgsConstructor
public class MentorTimeline {

    public static final int DEFAULT_PAGE = 50;
    public static final int MAX_PAGE = 100;
    static final int MAX_THREADS = 50;

    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;
    private final MentorTurnRepository turns;
    private final WeeklyDigestFacts weeklyDigests;
    private final ObservationReferences observationReferences;
    private final ObjectMapper objectMapper;

    /** A value the live stream sends, and the developer whose conversation it belongs to. */
    public record Owned<T>(long developerId, T value) {}

    @Transactional(readOnly = true)
    public MentorConversationDTO conversation(long workspaceId, Collection<Long> accountActorIds) {
        Optional<MentorConversation> found = main(workspaceId, accountActorIds);
        if (found.isEmpty()) {
            return MentorConversationDTO.NONE;
        }
        MentorConversation main = found.get();
        List<MentorTurnDTO> active = turns.findActiveInTree(workspaceId, main.getId()).stream()
                .map(turn -> turn(turn, parentEntryId(main, turn.getConversationId())))
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
    public MentorEntryPageDTO entries(
            long workspaceId, Collection<Long> accountActorIds, @Nullable Long beforeSeq, int limit) {
        return main(workspaceId, accountActorIds)
                .map(main -> page(main, beforeSeq, limit))
                .orElse(MentorEntryPageDTO.EMPTY);
    }

    /** The thread on one of the main conversation's messages; a message without a thread has no replies yet. */
    @Transactional(readOnly = true)
    public MentorThreadDTO thread(
            long workspaceId,
            Collection<Long> accountActorIds,
            UUID parentEntryId,
            @Nullable Long beforeSeq,
            int limit) {
        MentorEntry parent = ownedEntry(workspaceId, accountActorIds, parentEntryId)
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
    public MentorTurnDTO turn(long workspaceId, Collection<Long> accountActorIds, UUID turnId) {
        MentorTurn turn = ownedTurn(workspaceId, accountActorIds, turnId);
        MentorConversation conversation = conversation(workspaceId, turn.getConversationId());
        return turn(turn, conversation.getParentEntryId());
    }

    @Transactional(readOnly = true)
    public MentorEntryDTO entry(long workspaceId, Collection<Long> accountActorIds, UUID entryId) {
        MentorEntry entry = ownedEntry(workspaceId, accountActorIds, entryId)
                .orElseThrow(() -> new EntityNotFoundException("MentorEntry", entryId.toString()));
        return dtos(owner(entry), List.of(entry)).getFirst();
    }

    /** The account's entry, for a write that the account may make only to its own conversation. */
    @Transactional(readOnly = true)
    public MentorEntry requireOwnedEntry(long workspaceId, Collection<Long> accountActorIds, UUID entryId) {
        return ownedEntry(workspaceId, accountActorIds, entryId)
                .orElseThrow(() -> new EntityNotFoundException("MentorEntry", entryId.toString()));
    }

    /** The account's turn, for a write that the account may make only to its own conversation. */
    @Transactional(readOnly = true)
    public MentorTurn requireOwnedTurn(long workspaceId, Collection<Long> accountActorIds, UUID turnId) {
        return ownedTurn(workspaceId, accountActorIds, turnId);
    }

    private MentorTurn ownedTurn(long workspaceId, Collection<Long> accountActorIds, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .filter(turn -> accountActorIds.contains(
                        conversation(workspaceId, turn.getConversationId()).getUserId()))
                .orElseThrow(() -> new EntityNotFoundException("MentorTurn", turnId.toString()));
    }

    /** A turn as the live stream sends it, with the developer it belongs to. */
    @Transactional(readOnly = true)
    public Optional<Owned<MentorTurnDTO>> liveTurn(long workspaceId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .flatMap(turn -> conversations
                        .findByIdAndWorkspaceId(turn.getConversationId(), workspaceId)
                        .map(conversation ->
                                new Owned<>(conversation.getUserId(), turn(turn, conversation.getParentEntryId()))));
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
        return dto.deleted() || shows(dto) ? Optional.of(new Owned<>(conversation.getUserId(), dto)) : Optional.empty();
    }

    /** The messages among {@code rows} of one conversation that the app shows, as it shows them. */
    List<MentorEntryDTO> shown(MentorConversation conversation, List<MentorEntry> rows) {
        return dtos(conversation, rows).stream().filter(MentorTimeline::shows).toList();
    }

    /** A Heph message shows only when it has text or shows feedback. */
    private static boolean shows(MentorEntryDTO entry) {
        return entry.kind() != MentorEntryDTO.Kind.HEPH
                || !entry.text().isEmpty()
                || !entry.observations().isEmpty();
    }

    private Optional<MentorConversation> main(long workspaceId, Collection<Long> accountActorIds) {
        if (accountActorIds.isEmpty()) {
            return Optional.empty();
        }
        return conversations.findMainsByWorkspaceIdAndUserIdIn(workspaceId, accountActorIds).stream()
                .findFirst();
    }

    private Optional<MentorEntry> ownedEntry(long workspaceId, Collection<Long> accountActorIds, UUID entryId) {
        return entries.findByIdAndWorkspaceId(entryId, workspaceId)
                .filter(entry -> accountActorIds.contains(owner(entry).getUserId()));
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
        Set<String> shownCalls = linkingTurns.isEmpty()
                ? Set.of()
                : new HashSet<>(entries.findShownObservationCallIds(workspaceId, linkingTurns));
        Map<UUID, List<MentorObservationLinkDTO>> observations = observations(workspaceId, rows, shownCalls);
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
                    observations.getOrDefault(row.getId(), List.of()),
                    row.getKind() == MentorEntry.Kind.USER && turnId != null ? questionTurnById.get(turnId) : null,
                    digests.get(row.getId())));
        }
        return mapped;
    }

    /** The feedback each Heph message showed, in call order, with where the app can link to it. */
    private Map<UUID, List<MentorObservationLinkDTO>> observations(
            long workspaceId, List<MentorEntry> rows, Set<String> shownCalls) {
        Map<UUID, List<MentorPiEntries.Link>> shown = new HashMap<>();
        for (MentorEntry row : rows) {
            if (row.getKind() != MentorEntry.Kind.HEPH || row.isRedacted()) {
                continue;
            }
            List<MentorPiEntries.Link> links = links(row).stream()
                    .filter(link -> shownCalls.contains(link.callId()))
                    .toList();
            if (!links.isEmpty()) {
                shown.put(row.getId(), links);
            }
        }
        if (shown.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ObservationReferences.Reference> references = observationReferences.find(
                workspaceId,
                shown.values().stream()
                        .flatMap(List::stream)
                        .map(MentorPiEntries.Link::observationId)
                        .collect(Collectors.toSet()));
        Map<UUID, List<MentorObservationLinkDTO>> observations = new HashMap<>();
        shown.forEach((entryId, links) -> observations.put(
                entryId,
                links.stream()
                        .map(link -> MentorObservationLinkDTO.of(
                                link.observationId(), link.text(), references.get(link.observationId())))
                        .toList()));
        return observations;
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
                weeklyDigests.render(conversation.getWorkspaceId(), conversation.getUserId(), payloads);
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
            List<MentorObservationLinkDTO> observations,
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
                entry.isRedacted(),
                entry.getVote(),
                observations,
                thread,
                digest);
    }

    /** The {@code link_observation} calls in a Heph message, in order. */
    private static List<MentorPiEntries.Link> links(MentorEntry entry) {
        return entry.getKind() == MentorEntry.Kind.HEPH ? MentorPiEntries.links(entry.getPayload()) : List.of();
    }

    private static MentorTurnDTO turn(MentorTurn turn, @Nullable UUID parentEntryId) {
        JsonNode partial = turn.getPartial();
        return new MentorTurnDTO(
                turn.getId(),
                turn.getConversationId(),
                parentEntryId,
                turn.getRequestId(),
                turn.getStatus(),
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

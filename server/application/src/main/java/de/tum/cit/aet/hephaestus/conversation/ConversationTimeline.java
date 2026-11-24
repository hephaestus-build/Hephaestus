package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

/**
 * The developer's Hephaestus conversation as the app reads it: pages of messages, threads, turns, and the live changes
 * to them. Every read is the account's own: a conversation, entry or turn of another member is not found. The caller
 * binds that account as the transaction's owner first ({@code OwnerScope}).
 *
 * <p>The app shows the developer's messages, Hephaestus's messages and weekly digests ({@link ConversationEntryRepository#SHOWN}).
 * {@link ConversationEntryViews} maps each message as the app shows it.
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
    private final ConversationEntryViews views;

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

    @Transactional(readOnly = true)
    public ConversationDTO conversation(long workspaceId, long accountId) {
        Optional<Conversation> found = main(workspaceId, accountId);
        if (found.isEmpty()) {
            return ConversationDTO.NONE;
        }
        Conversation main = found.get();
        List<ConversationTurn> activeTurns = turns.findActiveInTree(workspaceId, main.getId());
        Map<UUID, ConversationTurn.WaitingFor> waiting = waits.waits(workspaceId, activeTurns);
        Set<UUID> working = working(workspaceId, activeTurns);
        List<ConversationTurnDTO> active = activeTurns.stream()
                .filter(turn -> turn.getSurface() != ConversationTurn.Surface.SYSTEM)
                .map(turn -> turn(
                        turn,
                        parentEntryId(main, turn.getConversationId()),
                        waiting.get(turn.getId()),
                        working.contains(turn.getId()),
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
        ConversationEntryDTO parentDto = views.dtos(main, List.of(parent)).getFirst();
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
        return turn(
                turn,
                conversation.getParentEntryId(),
                waitingFor(turn),
                working(workspaceId, List.of(turn)).contains(turn.getId()),
                progress.snapshot(turn));
    }

    @Transactional(readOnly = true)
    public ConversationEntryDTO entry(long workspaceId, long accountId, UUID entryId) {
        ConversationEntry entry = ownedEntry(workspaceId, accountId, entryId)
                .orElseThrow(() -> new EntityNotFoundException("ConversationEntry", entryId.toString()));
        return views.dtos(owner(entry), List.of(entry)).getFirst();
    }

    /** Checks ownership before a writer locks and loads the entry. */
    @Transactional(readOnly = true)
    public void requireOwnedEntry(long workspaceId, long accountId, UUID entryId) {
        if (entries.findAccountIdByIdAndWorkspaceId(entryId, workspaceId)
                .filter(owner -> owner == accountId)
                .isEmpty()) {
            throw new EntityNotFoundException("ConversationEntry", entryId.toString());
        }
    }

    /** Checks ownership before a writer locks and loads the turn. */
    @Transactional(readOnly = true)
    public void requireOwnedTurn(long workspaceId, long accountId, UUID turnId) {
        if (turns.findAccountIdByIdAndWorkspaceId(turnId, workspaceId)
                .filter(owner -> owner == accountId)
                .isEmpty()) {
            throw new EntityNotFoundException("ConversationTurn", turnId.toString());
        }
    }

    private ConversationTurn ownedTurn(long workspaceId, long accountId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .filter(turn ->
                        conversation(workspaceId, turn.getConversationId()).getAccountId() == accountId)
                .orElseThrow(() -> new EntityNotFoundException("ConversationTurn", turnId.toString()));
    }

    /** The account's turn as the live stream sends it. Empty when it is another account's or the app does not show it. */
    @Transactional(readOnly = true)
    public Optional<ConversationTurnDTO> liveTurn(long workspaceId, long accountId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .filter(turn ->
                        turn.getAccountId() == accountId && turn.getSurface() != ConversationTurn.Surface.SYSTEM)
                .flatMap(turn -> conversations
                        .findByIdAndWorkspaceId(turn.getConversationId(), workspaceId)
                        .map(conversation -> turn(
                                turn,
                                conversation.getParentEntryId(),
                                waitingFor(turn),
                                working(workspaceId, List.of(turn)).contains(turn.getId()),
                                progress.snapshot(turn))));
    }

    /**
     * The account's entry as the live stream sends it. Empty when it is another account's or the app does not show it.
     * A Hephaestus message that was erased is sent as deleted, so the app removes it.
     */
    @Transactional(readOnly = true)
    public Optional<ConversationEntryDTO> liveEntry(long workspaceId, long accountId, UUID entryId) {
        Optional<ConversationEntry> changed =
                entries.findByIdAndWorkspaceId(entryId, workspaceId).filter(entry -> entry.getAccountId() == accountId);
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
        ConversationEntryDTO dto = views.dtos(conversation, List.of(entry)).getFirst();
        return dto.deleted() || ConversationEntryViews.shows(dto) ? Optional.of(dto) : Optional.empty();
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
        return new CursorPageDTO<>(views.shown(conversation, page.reversed()), nextCursor);
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

    private ConversationTurn.@Nullable WaitingFor waitingFor(ConversationTurn turn) {
        return waits.waits(turn.getWorkspaceId(), List.of(turn)).get(turn.getId());
    }

    /** The running turns among {@code shown} whose current attempt has an admitted model call. */
    private Set<UUID> working(long workspaceId, Collection<ConversationTurn> shown) {
        List<UUID> running = shown.stream()
                .filter(turn -> turn.getStatus() == ConversationTurn.Status.RUNNING)
                .map(ConversationTurn::getId)
                .toList();
        return running.isEmpty() ? Set.of() : Set.copyOf(turns.findWorkingIds(workspaceId, running));
    }

    private static ConversationTurnDTO turn(
            ConversationTurn turn,
            @Nullable UUID parentEntryId,
            ConversationTurn.@Nullable WaitingFor waitingFor,
            boolean working,
            @Nullable JsonNode partial) {
        boolean running = turn.getStatus() == ConversationTurn.Status.RUNNING;
        return new ConversationTurnDTO(
                turn.getId(),
                turn.getConversationId(),
                parentEntryId,
                turn.getRequestId(),
                turn.getStatus(),
                waitingFor,
                turn.getAttempt(),
                turn.getSurface(),
                running ? (working ? ConversationTurn.Phase.WORKING : ConversationTurn.Phase.STARTING) : null,
                ConversationTurn.MAX_ATTEMPTS,
                partial == null || !running ? null : ConversationTurnPartialDTO.from(partial),
                turn.getError(),
                turn.getStatus() == ConversationTurn.Status.FAILED
                        ? ConversationTurn.FailureReason.of(turn.getFailureCause())
                        : null,
                turn.getCreatedAt(),
                turn.getFinishedAt());
    }
}

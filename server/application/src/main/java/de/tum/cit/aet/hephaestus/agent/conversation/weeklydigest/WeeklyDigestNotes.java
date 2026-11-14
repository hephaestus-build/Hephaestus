package de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest;

import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** What a transport needs to send, or to send again, the note that a weekly digest is ready. */
@Component
@RequiredArgsConstructor
public class WeeklyDigestNotes {

    private final ConversationEntryService entries;
    private final ConversationService conversations;
    private final ConversationTurnAccess turns;
    private final ObjectMapper objectMapper;

    /**
     * One digest's note: everything it says, which is the week and nothing about its contents.
     *
     * @param conversationId the developer's main conversation, which holds the digest
     * @param accountId the developer whose conversation it is
     * @param weekDays the week's days, such as "5–11 October"
     */
    public record Note(UUID entryId, UUID conversationId, long accountId, String weekDays, Instant writtenAt) {}

    /** The note of a digest entry; empty for any other entry, or a digest that was erased. */
    @Transactional(readOnly = true)
    public Optional<Note> note(long workspaceId, UUID entryId) {
        Optional<ConversationEntry> found = entries.find(workspaceId, entryId)
                .filter(entry -> entry.getKind() == ConversationEntry.Kind.DIGEST && !entry.isRedacted());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ConversationEntry entry = found.get();
        if (entry.getTurnId() != null
                && turns.find(workspaceId, entry.getTurnId())
                        .filter(turn -> turn.status() == ConversationTurn.Status.DONE)
                        .isEmpty()) {
            return Optional.empty();
        }
        return WeeklyDigestPayload.fromJson(entry.getPayload(), objectMapper).map(payload -> {
            Conversation conversation = conversations.require(workspaceId, entry.getConversationId());
            return new Note(
                    entry.getId(),
                    conversation.getId(),
                    conversation.getAccountId(),
                    payload.window().days(),
                    entry.getCreatedAt());
        });
    }
}

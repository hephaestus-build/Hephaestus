package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upsert/delete operations for {@link ChatMessageVote}. Ownership is enforced indirectly:
 * the vote can only target a message inside a thread the caller already owns, which is
 * verified by {@link ChatThreadService#getOwnedThread(Long, UUID)} before dispatch.
 *
 * <p>The vote table uses {@code message_id} as the primary key, so each user/message pair
 * collapses to a single row — repeated POSTs simply flip {@code is_upvoted}.
 */
@Service
@RequiredArgsConstructor
public class ChatMessageVoteService {

    private final ChatMessageVoteRepository chatMessageVoteRepository;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * Verifies the message exists inside the given thread, then upserts the vote atomically.
     * The DB-side {@code INSERT ... ON CONFLICT DO UPDATE} replaces the prior read-modify-write
     * that 500'd on concurrent vote POSTs to the same message id.
     *
     * @throws EntityNotFoundException when the message does not exist or does not belong
     *                                 to the supplied thread.
     */
    @Transactional
    public ChatMessageVote upsert(UUID threadId, UUID messageId, boolean isUpvoted) {
        ChatMessage message = chatMessageRepository
                .findById(messageId)
                .filter(m -> m.getThread() != null && m.getThread().getId().equals(threadId))
                .orElseThrow(() -> new EntityNotFoundException("ChatMessage", messageId.toString()));
        chatMessageVoteRepository.upsert(message.getId(), isUpvoted);
        // findById is in the same READ_COMMITTED tx that just committed the upsert above; row
        // is now visible. Return a fresh read so the caller observes the persisted timestamps.
        return chatMessageVoteRepository
                .findById(message.getId())
                .orElseThrow(() -> new IllegalStateException("Upsert returned 0 rows for message " + messageId));
    }

    /** No-op if the vote does not exist (idempotent DELETE semantics). */
    @Transactional
    public void delete(UUID threadId, UUID messageId) {
        boolean inThread = chatMessageRepository
                .findById(messageId)
                .filter(m -> m.getThread() != null && m.getThread().getId().equals(threadId))
                .isPresent();
        if (!inThread) {
            throw new EntityNotFoundException("ChatMessage", messageId.toString());
        }
        chatMessageVoteRepository.deleteById(messageId);
    }
}

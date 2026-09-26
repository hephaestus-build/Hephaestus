package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Read/write paths over {@link ChatThread} that enforce workspace + owner scoping at the
 * service boundary. Controllers must never bypass these methods to talk to the repository
 * directly — the workspace/owner gate is the only guard against cross-user thread access.
 */
@Service
@RequiredArgsConstructor
public class ChatThreadService {

    private final ChatThreadRepository chatThreadRepository;
    private final ChatMessageVoteRepository chatMessageVoteRepository;
    private final ObjectMapper objectMapper;

    /** Thread summaries (id, title, createdAt) owned by the current account, newest first. */
    @Transactional(readOnly = true)
    public List<ChatThreadSummaryDTO> listSummariesForCurrentUser(Long workspaceId) {
        return chatThreadRepository
                .findSummariesByWorkspaceAndUserIdIn(workspaceId, requireAccountActorIds(), Pageable.unpaged())
                .getContent();
    }

    /**
     * Load a thread within the workspace; throws {@link EntityNotFoundException} if the
     * thread does not exist OR is not owned by the current user (404, not 403 — we don't
     * confirm/deny existence to non-owners).
     */
    @Transactional(readOnly = true)
    public ChatThread getOwnedThread(Long workspaceId, UUID threadId) {
        return requireOwnedThread(workspaceId, threadId);
    }

    private ChatThread requireOwnedThread(Long workspaceId, UUID threadId) {
        return chatThreadRepository
                .findByIdAndWorkspaceIdAndUserIdIn(threadId, workspaceId, requireAccountActorIds())
                .orElseThrow(() -> new EntityNotFoundException("ChatThread", threadId.toString()));
    }

    /** A thread belongs to the account whichever of its actors in the workspace started it. */
    private static Set<Long> requireAccountActorIds() {
        Set<Long> actorIds = CurrentScmIdentityHolder.getAccountActorIds();
        if (actorIds.isEmpty()) {
            throw new EntityNotFoundException("User", "current authenticated user");
        }
        return actorIds;
    }

    /** Delete a thread (cascades to messages, votes). Owner-scoped via {@link #getOwnedThread}. */
    @Transactional
    public void deleteOwnedThread(Long workspaceId, UUID threadId) {
        chatThreadRepository.delete(requireOwnedThread(workspaceId, threadId));
    }

    /**
     * The thread, its messages and its votes in one read-only transaction. Votes are read only after
     * the owner check, so they cannot reach anyone but the thread's owner.
     */
    @Transactional(readOnly = true)
    public ChatThreadDetailDTO loadOwnedThreadDetail(Long workspaceId, UUID threadId) {
        ChatThread thread = requireOwnedThread(workspaceId, threadId);
        List<ChatMessageDTO> messages = thread.getAllMessages().stream()
                .map(msg -> ChatMessageDTO.from(msg, msg.getParts(), objectMapper))
                .toList();
        Map<UUID, Boolean> votes = chatMessageVoteRepository.findByMessage_Thread_Id(thread.getId()).stream()
                .collect(Collectors.toMap(ChatMessageVote::getMessageId, ChatMessageVote::getIsUpvoted));
        return new ChatThreadDetailDTO(thread.getId(), thread.getTitle(), thread.getCreatedAt(), messages, votes);
    }
}

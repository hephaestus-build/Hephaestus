package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.mentor.ThreadSurface;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class DefaultMentorSlackThreadService implements MentorSlackThreadService {

    private final ChatThreadRepository chatThreadRepository;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;

    @Override
    @Transactional
    public UUID ensureSlackThread(long workspaceId, @Nullable UUID chatThreadId, long developerId) {
        if (chatThreadId != null) {
            var existing = chatThreadRepository.findByIdAndWorkspaceId(chatThreadId, workspaceId);
            if (existing.isPresent()) {
                return existing.get().getId();
            }
        }
        User user = userRepository
                .findById(developerId)
                .orElseThrow(() -> new EntityNotFoundException("User", String.valueOf(developerId)));
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", String.valueOf(workspaceId)));
        ChatThread thread = new ChatThread();
        thread.setId(chatThreadId != null ? chatThreadId : UUID.randomUUID());
        thread.setUser(user);
        thread.setWorkspace(workspace);
        thread.setSurface(ThreadSurface.SLACK_DM);
        return chatThreadRepository.save(thread).getId();
    }

    @Override
    @Transactional
    public int purgeSlackThreads(long workspaceId) {
        // Slack deletes its mapping rows first; deleting a chat thread cascades to its messages.
        return chatThreadRepository.deleteByWorkspaceIdAndSurface(workspaceId, ThreadSurface.SLACK_DM);
    }
}

package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deletes every Hephaestus conversation of a purged workspace, with its entries and turns, through the owner function
 * that reaches every person's rows. A soft purge keeps the workspace row, so its foreign-key cascade does not fire.
 */
@Component
@RequiredArgsConstructor
public class ConversationWorkspacePurgeAdapter implements WorkspacePurgeContributor {

    private final ConversationRepository conversations;

    /** Turns reference agent runs, so remove them before the agent purge removes the remaining runs. */
    @Override
    public int getOrder() {
        return -60;
    }

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        conversations.purgeWorkspace(workspaceId);
    }
}

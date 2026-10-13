package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.mentor.MentorConversationRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deletes every Heph conversation of a purged workspace, with its entries and turns. A soft purge keeps the workspace
 * row, so its foreign-key cascade does not fire.
 */
@Component
@RequiredArgsConstructor
public class MentorWorkspacePurgeAdapter implements WorkspacePurgeContributor {

    private final MentorEntryRepository entries;
    private final MentorTurnRepository turns;
    private final MentorConversationRepository conversations;

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        entries.deleteByWorkspaceId(workspaceId);
        turns.deleteByWorkspaceId(workspaceId);
        conversations.deleteByWorkspaceId(workspaceId);
    }
}

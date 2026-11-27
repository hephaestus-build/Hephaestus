package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.runtime.MentorSourceRepository;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorWorkspaceErasure;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The mentor rows of a workspace belong to many accounts, so no owner scope reaches them all: the owner function
 * {@code mentor_erase_workspace} erases them. A Slack disconnect or restore calls it through the Slack content eraser,
 * and a workspace purge calls it here; a second call erases nothing.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MentorWorkspaceCleanup implements MentorWorkspaceErasure, WorkspacePurgeContributor {
    private final MentorSourceRepository sources;

    @Override
    public void eraseWorkspace(long workspaceId) {
        sources.eraseWorkspace(workspaceId);
    }

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        sources.eraseWorkspace(workspaceId);
    }
}

package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Looks up a workspace's review defaults by id, for the callers that hold an id rather than the entity.
 *
 * <p>A workspace deleted between the read that produced the id and this one resolves to
 * {@link WorkspaceReviewDefaults#UNSET} rather than throwing: every caller is already deciding what to do
 * with observations, and failing that decision over a race is worse than answering with defaults.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceReviewDefaultsProvider {

    private final WorkspaceRepository workspaceRepository;

    @Transactional(readOnly = true)
    public WorkspaceReviewDefaults forWorkspace(Long workspaceId) {
        return WorkspaceReviewDefaults.of(
                workspaceRepository.findById(workspaceId).orElse(null));
    }

    /**
     * The autonomy this workspace actually stored, or {@code null} when it has never chosen one — distinct from
     * {@link #forWorkspace}, which answers what is in force rather than whether anybody decided it.
     */
    @Transactional(readOnly = true)
    public @Nullable PracticeAutonomy rawDefaultAutonomy(Long workspaceId) {
        return workspaceRepository
                .findById(workspaceId)
                .map(workspace -> workspace.getReviewSettings().getDefaultAutonomy())
                .map(PracticeAutonomy::valueOf)
                .orElse(null);
    }
}

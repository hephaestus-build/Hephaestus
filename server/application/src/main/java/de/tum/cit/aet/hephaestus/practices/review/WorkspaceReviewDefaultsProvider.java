package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A workspace deleted between reads resolves to {@link WorkspaceReviewDefaults#UNSET}. */
@Service
@RequiredArgsConstructor
public class WorkspaceReviewDefaultsProvider {

    private final WorkspaceQueries workspaceRepository;

    @Transactional(readOnly = true)
    public WorkspaceReviewDefaults forWorkspace(Long workspaceId) {
        return workspaceRepository
                .findById(workspaceId)
                .map(workspace -> WorkspaceReviewDefaults.of(workspace.reviewSettings()))
                .orElse(WorkspaceReviewDefaults.UNSET);
    }

    /**
     * The autonomy this workspace actually stored, or {@code null} when it has never chosen one — distinct from
     * {@link #forWorkspace}, which answers what is in force rather than whether anybody decided it.
     */
    @Transactional(readOnly = true)
    public @Nullable FeedbackMode rawDefaultFeedbackMode(Long workspaceId) {
        return workspaceRepository
                .findById(workspaceId)
                .map(workspace -> workspace.reviewSettings().defaultFeedbackMode())
                .map(FeedbackMode::valueOf)
                .orElse(null);
    }
}

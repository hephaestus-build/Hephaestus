package de.tum.cit.aet.hephaestus.workspace.settings;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

@WorkspaceAgnostic("Every target operation carries its explicit workspace_id tenant boundary")
public interface PracticeReviewRepositoryTargetRepository
        extends JpaRepository<PracticeReviewRepositoryTarget, PracticeReviewRepositoryTarget.Key> {
    List<PracticeReviewRepositoryTarget> findByWorkspaceId(Long workspaceId);
}

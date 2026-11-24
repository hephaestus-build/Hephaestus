package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

@WorkspaceAgnostic("Group reads and deletion carry workspaceId")
public interface PracticeGroupRepository extends JpaRepository<PracticeGroup, Long> {
    List<PracticeGroup> findByWorkspaceIdOrderByPositionAscNameAsc(Long workspaceId);

    List<PracticeGroup> findByWorkspaceIdAndShownToPeopleTrueOrderByPositionAscNameAsc(Long workspaceId);

    Optional<PracticeGroup> findByWorkspaceIdAndSlug(Long workspaceId, String slug);

    boolean existsByWorkspaceIdAndSlug(Long workspaceId, String slug);

    @Query("SELECT COALESCE(MAX(g.position), -1) FROM PracticeGroup g WHERE g.workspace.id =" + " :workspaceId")
    int findMaxPosition(Long workspaceId);

    @Modifying
    @Query("DELETE FROM PracticeGroup g WHERE g.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(Long workspaceId);
}

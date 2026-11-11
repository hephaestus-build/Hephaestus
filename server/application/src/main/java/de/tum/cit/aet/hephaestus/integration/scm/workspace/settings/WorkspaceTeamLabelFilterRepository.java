package de.tum.cit.aet.hephaestus.integration.scm.workspace.settings;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@WorkspaceAgnostic("Queried by explicit workspace ID - settings queries always workspace-scoped")
public interface WorkspaceTeamLabelFilterRepository
        extends JpaRepository<WorkspaceTeamLabelFilter, WorkspaceTeamLabelFilter.Id> {
    List<WorkspaceTeamLabelFilter> findByWorkspaceId(Long workspaceId);

    List<WorkspaceTeamLabelFilter> findByWorkspaceIdAndTeamId(Long workspaceId, Long teamId);

    @Query("""
        SELECT wtlf.label
        FROM WorkspaceTeamLabelFilter wtlf
        JOIN FETCH wtlf.label.repository
        WHERE wtlf.workspace.id = :workspaceId
          AND wtlf.team.id = :teamId
        """)
    Set<Label> findLabelsByWorkspaceAndTeam(@Param("workspaceId") Long workspaceId, @Param("teamId") Long teamId);

    @Query("""
        SELECT wtlf
        FROM WorkspaceTeamLabelFilter wtlf
        JOIN FETCH wtlf.label l
        JOIN FETCH l.repository
        WHERE wtlf.workspace.id = :workspaceId
          AND wtlf.team.id IN :teamIds
        """)
    List<WorkspaceTeamLabelFilter> findByWorkspaceIdAndTeamIds(
            @Param("workspaceId") Long workspaceId, @Param("teamIds") Set<Long> teamIds);

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM WorkspaceTeamLabelFilter wtlf
        WHERE wtlf.workspace.id = :workspaceId
          AND wtlf.team.id = :teamId
          AND wtlf.label.id = :labelId
        """)
    void deleteByWorkspaceIdAndTeamIdAndLabelId(
            @Param("workspaceId") Long workspaceId, @Param("teamId") Long teamId, @Param("labelId") Long labelId);

    @Modifying
    @Transactional
    @Query("""
        DELETE FROM WorkspaceTeamLabelFilter wtlf
        WHERE wtlf.workspace.id = :workspaceId
          AND wtlf.team.id = :teamId
        """)
    void deleteAllByWorkspaceIdAndTeamId(@Param("workspaceId") Long workspaceId, @Param("teamId") Long teamId);

    @Modifying
    @Transactional
    @Query("DELETE FROM WorkspaceTeamLabelFilter wtlf WHERE wtlf.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);
}

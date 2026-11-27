package de.tum.cit.aet.hephaestus.project;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectTeamRepository extends JpaRepository<ProjectTeam, UUID> {

    /** The database deletes each team's members with it. */
    @Modifying
    @Query("DELETE FROM ProjectTeam t WHERE t.workspaceId = :workspaceId")
    void deleteByWorkspaceId(@Param("workspaceId") long workspaceId);
}

package de.tum.cit.aet.hephaestus.project;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectTeamMemberRepository extends JpaRepository<ProjectTeamMember, UUID> {

    @Query("""
        SELECT COUNT(m) > 0 FROM ProjectTeamMember m
        JOIN ProjectTeam t ON t.id = m.teamId AND t.workspaceId = m.workspaceId
        WHERE m.workspaceId = :workspaceId AND t.workspaceId = :workspaceId
          AND m.teamId = :teamId AND m.accountId = :accountId AND t.removedAt IS NULL
        """)
    boolean isInStandingTeam(
            @Param("workspaceId") long workspaceId,
            @Param("teamId") UUID teamId,
            @Param("accountId") long accountId);

    /** Adds the claimed account to every team of the absorbed one that it is not in yet. */
    @Modifying
    @WorkspaceAgnostic("Account absorption spans workspaces")
    @Query(value = """
        INSERT INTO project_team_member(id, workspace_id, team_id, account_id, joined_at)
        SELECT gen_random_uuid(), workspace_id, team_id, :target, joined_at
        FROM project_team_member WHERE account_id = :source
        ON CONFLICT (team_id, account_id) DO NOTHING
        """, nativeQuery = true)
    void absorbAccount(@Param("target") long target, @Param("source") long source);
}

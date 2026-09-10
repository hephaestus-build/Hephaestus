package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@WorkspaceAgnostic("Queried by explicit workspace ID - membership queries always workspace-scoped")
public interface WorkspaceMembershipRepository extends JpaRepository<WorkspaceMembership, WorkspaceMembership.Id> {
    List<WorkspaceMembership> findByWorkspace_Id(Long workspaceId);

    @Query("""
            SELECT wm
            FROM WorkspaceMembership wm
            JOIN FETCH wm.user
            WHERE wm.workspace.id = :workspaceId
        """)
    List<WorkspaceMembership> findAllWithUserByWorkspaceId(@Param("workspaceId") Long workspaceId);

    @Query("""
            SELECT wm FROM WorkspaceMembership wm
            JOIN FETCH wm.user
            WHERE wm.workspace.id = :workspaceId
        """)
    Page<WorkspaceMembership> findAllByWorkspace_Id(@Param("workspaceId") Long workspaceId, Pageable pageable);

    @Query("""
            SELECT wm FROM WorkspaceMembership wm
            JOIN FETCH wm.user
            WHERE wm.workspace.id = :workspaceId AND wm.user.id = :userId
        """)
    Optional<WorkspaceMembership> findByWorkspace_IdAndUser_Id(
            @Param("workspaceId") Long workspaceId, @Param("userId") Long userId);

    Optional<WorkspaceMembership> findFirstByWorkspace_IdAndUser_LoginIgnoreCaseOrderByUser_Id(
            Long workspaceId, String login);

    List<WorkspaceMembership> findAllByWorkspace_IdAndUser_IdIn(Long workspaceId, Collection<Long> userIds);

    @Query("""
            SELECT DISTINCT u
            FROM WorkspaceMembership wm
            JOIN wm.user u
            LEFT JOIN FETCH u.teamMemberships tm
            LEFT JOIN FETCH tm.team t
            WHERE wm.workspace.id = :workspaceId
            AND u.type = 'USER'
        """)
    List<User> findHumanUsersWithTeamsByWorkspaceId(@Param("workspaceId") Long workspaceId);

    List<WorkspaceMembership> findByUser_Id(Long userId);

    /** Account visibility unions memberships across its linked SCM actors. */
    List<WorkspaceMembership> findByUser_IdIn(Collection<Long> userIds);

    List<WorkspaceMembership> findByWorkspace_IdAndUser_IdIn(Long workspaceId, Collection<Long> userIds);

    /** Account-owned actor memberships, with workspace data loaded for cross-module projections. */
    @Query("""
            SELECT wm FROM WorkspaceMembership wm
            JOIN FETCH wm.workspace
            JOIN FETCH wm.user u
            WHERE u.id IN :userIds
            ORDER BY wm.workspace.id, u.id
        """)
    List<WorkspaceMembership> findAllWithWorkspaceByUserIdIn(@Param("userIds") Collection<Long> userIds);

    long countByWorkspace_IdAndRole(Long workspaceId, WorkspaceRole role);

    long countByWorkspace_Id(Long workspaceId);

    /** Oldest membership first for the instance-admin support overview. */
    @Query("""
            SELECT wm.user FROM WorkspaceMembership wm
            WHERE wm.workspace.id = :workspaceId AND wm.role = :role
            ORDER BY wm.createdAt, wm.user.id
        """)
    List<User> findUsersByWorkspaceIdAndRole(@Param("workspaceId") Long workspaceId, @Param("role") WorkspaceRole role);

    @Query("SELECT wm.user.id FROM WorkspaceMembership wm WHERE wm.workspace.id = :workspaceId AND wm.hidden = true")
    Set<Long> findHiddenUserIdsByWorkspaceId(@Param("workspaceId") Long workspaceId);

    /** Does not replace an existing membership or its role under concurrent insertion. */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO workspace_membership (workspace_id, user_id, role, league_points, hidden, created_at)
        VALUES (:workspaceId, :userId, :role, :leaguePoints, false, CURRENT_TIMESTAMP)
        ON CONFLICT (workspace_id, user_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("workspaceId") Long workspaceId,
            @Param("userId") Long userId,
            @Param("role") String role,
            @Param("leaguePoints") int leaguePoints);

    @Modifying
    @Transactional
    @Query("DELETE FROM WorkspaceMembership wm WHERE wm.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);
}

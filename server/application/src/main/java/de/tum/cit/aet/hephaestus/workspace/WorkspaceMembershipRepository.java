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

/**
 * Repository for {@link WorkspaceMembership} entities.
 * Manages the relationship between users and workspaces, including role
 * assignments.
 */
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

    @Query(value = """
            SELECT wm FROM WorkspaceMembership wm
            JOIN FETCH wm.user
            WHERE wm.workspace.id = :workspaceId AND wm.user.type = 'USER'
            ORDER BY wm.user.login
        """, countQuery = """
            SELECT count(wm) FROM WorkspaceMembership wm
            WHERE wm.workspace.id = :workspaceId AND wm.user.type = 'USER'
        """)
    Page<WorkspaceMembership> findHumanMembers(@Param("workspaceId") Long workspaceId, Pageable pageable);

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

    /**
     * Memberships for ANY of the given SCM users — the multi-identity form of {@link #findByUser_Id}.
     * A single Hephaestus account can mirror several SCM users (one per linked provider identity), so
     * workspace visibility unions their memberships. Empty input yields an empty list.
     */
    List<WorkspaceMembership> findByUser_IdIn(Collection<Long> userIds);

    /** This workspace's membership rows for ANY of the given SCM users (multi-identity role resolution). */
    List<WorkspaceMembership> findByWorkspace_IdAndUser_IdIn(Long workspaceId, Collection<Long> userIds);

    /**
     * All memberships for the given SCM logins, with the workspace eagerly fetched. Used by the
     * {@code core.auth} GDPR data-export to flatten a principal's workspace memberships without
     * the auth module importing workspace domain types. Login match is case-insensitive.
     */
    @Query("""
            SELECT wm FROM WorkspaceMembership wm
            JOIN FETCH wm.workspace
            JOIN wm.user u
            WHERE LOWER(u.login) IN :logins
        """)
    List<WorkspaceMembership> findAllWithWorkspaceByUserLoginInLowercase(@Param("logins") Collection<String> logins);

    long countByWorkspace_IdAndRole(Long workspaceId, WorkspaceRole role);

    /** Total member count for a workspace (all roles). Backs the instance-admin workspaces overview. */
    long countByWorkspace_Id(Long workspaceId);

    /** Members in the given role, oldest membership first — for the instance-admin overview/support entry. */
    @Query("""
            SELECT wm.user FROM WorkspaceMembership wm
            WHERE wm.workspace.id = :workspaceId AND wm.role = :role
            ORDER BY wm.createdAt, wm.user.id
        """)
    List<User> findUsersByWorkspaceIdAndRole(@Param("workspaceId") Long workspaceId, @Param("role") WorkspaceRole role);

    @Query("SELECT wm.user.id FROM WorkspaceMembership wm WHERE wm.workspace.id = :workspaceId AND wm.hidden = true")
    Set<Long> findHiddenUserIdsByWorkspaceId(@Param("workspaceId") Long workspaceId);

    /**
     * Atomically inserts a membership if absent (race-condition safe).
     */
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

    /**
     * Deletes all memberships for a workspace.
     * Used during workspace purge to clean up membership data.
     *
     * @param workspaceId the workspace ID
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM WorkspaceMembership wm WHERE wm.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);
}

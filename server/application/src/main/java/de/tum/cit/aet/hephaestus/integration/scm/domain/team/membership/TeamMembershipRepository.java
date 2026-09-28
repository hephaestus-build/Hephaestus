package de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for team membership records.
 *
 * <p>Memberships are scoped through their team which carries scope through
 * the Team.organization relationship.
 */
@Repository
@WorkspaceAgnostic("Memberships scoped through team_id -> team.workspace_id")
public interface TeamMembershipRepository extends JpaRepository<TeamMembership, TeamMembership.Id> {
    /**
     * Delete a membership by team and user IDs.
     *
     * @param teamId the team ID
     * @param userId the user ID
     */
    void deleteByTeam_IdAndUser_Id(Long teamId, Long userId);

    /**
     * Check if a membership exists by team and user IDs.
     *
     * @param teamId the team ID
     * @param userId the user ID
     * @return true if the membership exists
     */
    boolean existsByTeam_IdAndUser_Id(Long teamId, Long userId);

    /**
     * Collect distinct user IDs of every member of a team under the given root group path (case-insensitive) on one
     * provider instance.
     * <p>
     * Used to reconcile workspace memberships from the team graph — e.g., tutors
     * who are subgroup maintainers and therefore appear in {@code team_membership}
     * but not in {@code organization_membership}, and students who are members only of a project, whom the team
     * of the project's group lists. Another instance can host the same path.
     *
     * @param organization the root group full path (e.g., {@code "ase/introcourse"})
     * @param providerId   the provider instance the teams were synced from
     * @return distinct user IDs of all members of teams under that root group
     */
    @Query("""
            SELECT DISTINCT tm.user.id
            FROM TeamMembership tm
            WHERE LOWER(tm.team.organization) = LOWER(:organization)
            AND tm.team.provider.id = :providerId
        """)
    Set<Long> findDistinctUserIdsOfTeams(
            @Param("organization") String organization, @Param("providerId") Long providerId);
}

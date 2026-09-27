package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Every operation carries its explicit workspace_id tenant boundary")
public interface HiddenFormerMemberRepository extends JpaRepository<HiddenFormerMember, HiddenFormerMember.Key> {

    /** Takes the remembered preference back; {@code 1} when the actor was hidden when they left. */
    @Modifying
    @Query("DELETE FROM HiddenFormerMember former WHERE former.workspaceId = :workspaceId AND former.userId = :userId")
    int deleteByWorkspaceIdAndUserId(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM HiddenFormerMember former WHERE former.workspaceId = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);
}

package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface WorkspaceMemberOnboardingRepository extends JpaRepository<WorkspaceMemberOnboarding, Long> {
    Optional<WorkspaceMemberOnboarding> findByWorkspace_IdAndAccountId(Long workspaceId, Long accountId);

    @Modifying
    @Query("DELETE FROM WorkspaceMemberOnboarding m WHERE m.workspace.id = :workspaceId")
    int deleteByWorkspaceId(Long workspaceId);

    @WorkspaceAgnostic("Account erasure removes that account's setup rows in every workspace")
    @Modifying
    @Query("DELETE FROM WorkspaceMemberOnboarding m WHERE m.accountId = :accountId")
    int deleteByAccountId(Long accountId);
}

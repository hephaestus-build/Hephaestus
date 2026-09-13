package de.tum.cit.aet.hephaestus.workspace.onboarding;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface WorkspaceOnboardingSettingsRepository extends JpaRepository<WorkspaceOnboardingSettings, Long> {
    Optional<WorkspaceOnboardingSettings> findByWorkspaceId(Long workspaceId);

    @Modifying
    @Query("DELETE FROM WorkspaceOnboardingSettings s WHERE s.workspaceId = :workspaceId")
    int deleteByWorkspaceId(Long workspaceId);
}

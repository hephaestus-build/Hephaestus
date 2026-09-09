package de.tum.cit.aet.hephaestus.workspace.onboarding;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorkspaceAccessPolicyRepository extends JpaRepository<WorkspaceAccessPolicy, Long> {
    Optional<WorkspaceAccessPolicy> findByWorkspace_Id(Long workspaceId);

    List<WorkspaceAccessPolicy> findByEnabledTrue();

    void deleteAllByWorkspace_Id(Long workspaceId);
}

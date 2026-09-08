package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubAccessActionRepository extends JpaRepository<GitHubAccessAction, Long> {
    Optional<GitHubAccessAction> findByIdAndWorkspace_IdAndTarget_Id(Long id, Long workspaceId, Long targetId);

    List<GitHubAccessAction> findByWorkspace_IdAndTarget_IdAndStatusInOrderById(
            Long workspaceId, Long targetId, Collection<GitHubAccessAction.Status> statuses);

    List<GitHubAccessAction> findTop50ByWorkspace_IdAndTarget_IdOrderByCreatedAtDesc(Long workspaceId, Long targetId);

    @WorkspaceAgnostic(
            "Instance operational metric returns only the count of unresolved manual decisions, never tenant or identity data")
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(a) FROM GitHubAccessAction a WHERE a.status = 'MANUAL_RECOVERY'")
    long countManualRecovery();

    void deleteAllByWorkspace_IdAndMembership_Id(Long workspaceId, Long membershipId);

    void deleteAllByWorkspace_Id(Long workspaceId);
}

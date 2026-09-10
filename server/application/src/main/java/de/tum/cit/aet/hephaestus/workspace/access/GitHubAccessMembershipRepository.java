package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubAccessMembershipRepository extends JpaRepository<GitHubAccessMembership, Long> {
    List<GitHubAccessMembership> findByWorkspace_IdAndTarget_IdOrderById(Long workspaceId, Long targetId);

    Optional<GitHubAccessMembership> findByWorkspace_IdAndTarget_IdAndGithubUserId(
            Long workspaceId, Long targetId, long githubUserId);

    Optional<GitHubAccessMembership> findByIdAndWorkspace_IdAndTarget_Id(Long id, Long workspaceId, Long targetId);

    @WorkspaceAgnostic(
            "Current-account status, unlink and erasure address only rows owned by the authenticated or erased account")
    List<GitHubAccessMembership> findByAccountId(Long accountId);

    List<GitHubAccessMembership> findByWorkspace_IdAndAccountId(Long workspaceId, Long accountId);

    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT m.directorySubject FROM GitHubAccessMembership m WHERE m.workspace.id = :workspaceId AND m.target.directoryProviderId = :providerId AND m.directorySubject IS NOT NULL AND (m.managed = true OR m.revocationRequested = true)")
    List<String> trackedDirectorySubjects(Long workspaceId, Long providerId);

    @WorkspaceAgnostic(
            "Instance operational metric returns only the count of pending GitHub removals, never tenant or identity data")
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(m) FROM GitHubAccessMembership m WHERE m.revocationRequested = true")
    long countPendingRemovals();

    void deleteAllByWorkspace_Id(Long workspaceId);
}

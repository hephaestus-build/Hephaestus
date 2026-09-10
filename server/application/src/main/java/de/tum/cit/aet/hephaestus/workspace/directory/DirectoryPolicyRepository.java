package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DirectoryPolicyRepository extends JpaRepository<DirectoryPolicy, Long> {
    Optional<DirectoryPolicy> findByWorkspace_Id(Long workspaceId);

    void deleteAllByWorkspace_Id(Long workspaceId);

    @WorkspaceAgnostic("Account erasure clears only approval attribution belonging to that account")
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE DirectoryPolicy p SET p.approvedByAccountId = null WHERE p.approvedByAccountId = :accountId")
    void clearApprover(long accountId);

    @WorkspaceAgnostic("The scheduler enumerates policy workspace IDs, never returns tenant data to callers")
    @Query(
            "SELECT p.workspace.id FROM DirectoryPolicy p WHERE p.status IN ('ACTIVE', 'PAUSED') AND p.workspace.status = 'ACTIVE'")
    List<Long> findManagedWorkspaceIds();

    @WorkspaceAgnostic("Self-service discovery is limited to subjects verified through this account's identity links")
    @Query(
            value =
                    "SELECT p.* FROM directory_policy p WHERE p.registration_id = :registrationId AND p.status = 'ACTIVE' AND jsonb_exists(p.active_snapshot -> 'eligibleSubjects', :subject)",
            nativeQuery = true)
    List<DirectoryPolicy> findEligibleForSubject(String registrationId, String subject);
}

package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GitHubAccessTargetRepository extends JpaRepository<GitHubAccessTarget, Long> {
    List<GitHubAccessTarget> findByWorkspace_IdOrderById(Long workspaceId);

    Optional<GitHubAccessTarget> findByIdAndWorkspace_Id(Long id, Long workspaceId);

    Optional<GitHubAccessTarget> findByConnectionIdAndWorkspace_Id(Long connectionId, Long workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM GitHubAccessTarget t WHERE t.id = :id AND t.workspace.id = :workspaceId")
    Optional<GitHubAccessTarget> lock(Long workspaceId, Long id);

    @WorkspaceAgnostic(
            "An expiring random owner-handoff capability selects one bound target; GitHub ownership is independently verified before authorization")
    @Query(
            "SELECT t FROM GitHubAccessTarget t WHERE t.handoffHash = :hash AND t.handoffExpiresAt > :now AND t.status <> 'ENDED'")
    Optional<GitHubAccessTarget> findHandoff(String hash, Instant now);

    interface ScheduledTarget {
        Long getWorkspaceId();

        Long getTargetId();
    }

    @WorkspaceAgnostic(
            "The scheduler enumerates target addresses only and establishes the workspace context for each independent job")
    @Query(
            "SELECT t.workspace.id AS workspaceId, t.id AS targetId FROM GitHubAccessTarget t WHERE t.status IN ('ACTIVE','ENDING') AND t.workspace.status = 'ACTIVE' AND (t.retryAt IS NULL OR t.retryAt <= :now)")
    List<ScheduledTarget> scheduled(Instant now);

    @WorkspaceAgnostic("Account erasure clears only an authorization made by the erased account")
    @Query(
            value =
                    "SELECT t.* FROM github_access_target t WHERE t.approved_by_account_id = :accountId OR t.handoff_issued_by_account_id = :accountId OR (t.owner_authorization ->> 'accountId')::bigint = :accountId",
            nativeQuery = true)
    List<GitHubAccessTarget> authorizedByAccount(long accountId);

    @WorkspaceAgnostic(
            "Account erasure removes private preview identity data for that account, without changing approved external policy")
    @Modifying
    @Query(
            value =
                    "UPDATE github_access_target t SET preview = NULL WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(t.preview -> 'eligibility' -> 'candidates') c WHERE (c ->> 'accountId')::bigint = :accountId)",
            nativeQuery = true)
    void erasePreviewIdentity(long accountId);

    @Modifying
    @Query(value = """
        UPDATE github_access_target t SET preview = NULL WHERE t.workspace_id = :workspaceId
          AND EXISTS (SELECT 1 FROM jsonb_array_elements(t.preview -> 'eligibility' -> 'candidates') c
            WHERE (c ->> 'accountId')::bigint = :accountId)
        """, nativeQuery = true)
    void erasePreviewIdentityInWorkspace(Long workspaceId, Long accountId);

    void deleteAllByWorkspace_Id(Long workspaceId);
}

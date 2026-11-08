package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MembershipRepository extends JpaRepository<Membership, Membership.Id> {
    List<Membership> findByWorkspace_Id(Long workspaceId);

    boolean existsByWorkspace_IdAndId_AccountIdAndLeftAtIsNull(long workspaceId, long accountId);

    @Query(
            "SELECT m.id.accountId FROM Membership m WHERE m.workspace.id = :workspaceId AND m.reviewSelected = true ORDER BY m.id.accountId")
    List<Long> findSelectedAccountIds(@Param("workspaceId") long workspaceId);

    @Query("""
        SELECT m FROM Membership m WHERE m.workspace.id = :workspaceId
          AND (m.reviewSelected = true OR m.id.accountId IN :accountIds)
        """)
    List<Membership> findReviewSelectionMembers(
            @Param("workspaceId") long workspaceId, @Param("accountIds") Collection<Long> accountIds);

    @Query("""
        SELECT m.id.accountId FROM Membership m JOIN Account a ON a.id = m.id.accountId
        WHERE m.workspace.id = :workspaceId AND m.leftAt IS NULL
          AND a.status IN (de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.ACTIVE,
                           de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.UNCLAIMED,
                           de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.SUSPENDED)
        ORDER BY a.displayName, a.id
        """)
    Page<Long> findViewableAccountIds(@Param("workspaceId") long workspaceId, Pageable pageable);

    @Query("""
        SELECT m.id.accountId FROM Membership m JOIN Account a ON a.id = m.id.accountId
        WHERE m.workspace.id = :workspaceId AND m.leftAt IS NULL
          AND m.role IN (de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole.ADMIN,
                         de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole.OWNER)
          AND a.status = de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.ACTIVE
        ORDER BY m.id.accountId
        """)
    List<Long> findAdministratorAccountIds(@Param("workspaceId") long workspaceId);

    @Query("""
        SELECT COUNT(m) > 0 FROM Membership m JOIN Account a ON a.id = m.id.accountId
        WHERE m.workspace.id = :workspaceId AND m.id.accountId = :accountId
          AND m.leftAt IS NULL AND m.hidden = false
          AND a.status IN (de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.ACTIVE,
                           de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.UNCLAIMED)
        """)
    boolean isActiveVisibleMember(@Param("workspaceId") long workspaceId, @Param("accountId") long accountId);

    @Query("""
        SELECT COUNT(m) > 0 FROM Membership m JOIN Account a ON a.id = m.id.accountId
        WHERE m.workspace.id = :workspaceId AND m.id.accountId = :accountId AND m.leftAt IS NULL
          AND m.role IN (de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole.ADMIN,
                         de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole.OWNER)
          AND a.status = de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.ACTIVE
        """)
    boolean isAdministrator(@Param("workspaceId") long workspaceId, @Param("accountId") long accountId);

    @Query("""
        SELECT m.id.accountId FROM Membership m JOIN Account a ON a.id = m.id.accountId
        WHERE m.workspace.id = :workspaceId AND (:allAccounts = true OR m.id.accountId IN :accountIds)
          AND m.leftAt IS NULL AND m.hidden = false
          AND (a.aiChoice IS NULL OR a.aiChoice <> de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice.NO_AI)
          AND a.status IN (de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.ACTIVE,
                           de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.UNCLAIMED)
          AND (:providerId IS NULL OR EXISTS (SELECT i.id FROM Identity i
               WHERE i.account.id = a.id AND i.providerId = :providerId AND i.disabledAt IS NULL))
        """)
    Set<Long> findEligibleAccountIds(
            @Param("workspaceId") long workspaceId,
            @Param("accountIds") Collection<Long> accountIds,
            @Param("allAccounts") boolean allAccounts,
            @Param("providerId") @Nullable Long providerId);

    @Modifying
    @WorkspaceAgnostic("Account absorption spans workspaces")
    @Query(value = """
        INSERT INTO membership(workspace_id,account_id,role,hidden,review_selected,onboarding_seen_revision,
            public_activity_seen,joined_at,left_at)
        SELECT workspace_id,:target,role,hidden,review_selected,onboarding_seen_revision,public_activity_seen,joined_at,left_at
        FROM membership WHERE account_id=:source
        ON CONFLICT(workspace_id,account_id) DO UPDATE SET
            role=CASE WHEN membership.role='OWNER' OR EXCLUDED.role='OWNER' THEN 'OWNER'
                WHEN membership.role='ADMIN' OR EXCLUDED.role='ADMIN' THEN 'ADMIN' ELSE 'MEMBER' END,
            hidden=membership.hidden OR EXCLUDED.hidden,
            review_selected=membership.review_selected OR EXCLUDED.review_selected,
            onboarding_seen_revision=GREATEST(membership.onboarding_seen_revision,EXCLUDED.onboarding_seen_revision),
            public_activity_seen=membership.public_activity_seen OR EXCLUDED.public_activity_seen,
            joined_at=LEAST(membership.joined_at,EXCLUDED.joined_at),
            left_at=CASE WHEN membership.left_at IS NULL OR EXCLUDED.left_at IS NULL THEN NULL
                ELSE GREATEST(membership.left_at,EXCLUDED.left_at) END
        """, nativeQuery = true)
    int absorbAccount(@Param("target") long target, @Param("source") long source);

    Optional<Membership> findByWorkspace_IdAndId_AccountId(Long workspaceId, Long accountId);

    @WorkspaceAgnostic("An account reads its memberships across workspaces")
    List<Membership> findById_AccountId(Long accountId);

    /** Registry discovery returns only tenant IDs for the account, not membership data across tenants. */
    @Query("SELECT m.workspace.id FROM Membership m WHERE m.id.accountId = :accountId AND m.leftAt IS NULL")
    List<Long> findActiveWorkspaceIdsByAccountId(@Param("accountId") Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @WorkspaceAgnostic("Account absorption locks memberships across workspaces")
    @Query("""
        SELECT m FROM Membership m WHERE m.id.accountId IN :accountIds
        ORDER BY m.workspace.id, m.id.accountId
        """)
    List<Membership> lockByAccountIds(@Param("accountIds") Collection<Long> accountIds);

    @Query("""
        SELECT count(m) FROM Membership m
        WHERE m.workspace.id = :workspaceId AND m.role = :role AND m.leftAt IS NULL
        """)
    long countByWorkspace_IdAndRole(@Param("workspaceId") Long workspaceId, @Param("role") WorkspaceRole role);

    long countByWorkspace_Id(Long workspaceId);

    @Query("""
        SELECT wm.id.accountId FROM Membership wm
        WHERE wm.workspace.id = :workspaceId AND wm.role = :role AND wm.leftAt IS NULL
        ORDER BY wm.joinedAt, wm.id.accountId
        """)
    List<Long> findAccountIdsByWorkspaceIdAndRole(
            @Param("workspaceId") Long workspaceId, @Param("role") WorkspaceRole role);

    /** Does not replace an existing membership or its role under concurrent insertion. */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO membership (workspace_id, account_id, role, hidden, review_selected, public_activity_seen, joined_at)
        VALUES (:workspaceId, :accountId, :role, :hidden, false, false, CURRENT_TIMESTAMP)
        ON CONFLICT (workspace_id, account_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("workspaceId") Long workspaceId,
            @Param("accountId") Long accountId,
            @Param("role") String role,
            @Param("hidden") boolean hidden);

    @Modifying
    @Transactional
    @Query("DELETE FROM Membership wm WHERE wm.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);

    @Modifying
    @WorkspaceAgnostic("Account erasure or verified absorption removes memberships across workspaces")
    @Query("DELETE FROM Membership m WHERE m.id.accountId = :accountId")
    void deleteByAccountId(@Param("accountId") long accountId);
}

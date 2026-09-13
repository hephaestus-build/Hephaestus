package de.tum.cit.aet.hephaestus.core.auth.domain;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Cross-module read-only handle on {@link Account}. Concrete mutations live behind
 * {@code AccountService} in the auth module.
 */
@Repository
@WorkspaceAgnostic(
        "Account is the Hephaestus-native principal; it spans workspaces (membership lives on WorkspaceMembership)")
public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findAllByIdInAndStatusNot(Collection<Long> ids, Account.Status status);

    @Query("""
        SELECT a.id FROM Account a
         WHERE a.appRole = de.tum.cit.aet.hephaestus.core.auth.domain.Account.AppRole.APP_ADMIN
           AND a.status = de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.ACTIVE
           AND a.primaryEmail IS NOT NULL AND a.primaryEmailVerifiedAt IS NOT NULL
         ORDER BY a.id
        """)
    List<Long> findActiveVerifiedAdministratorIds();

    @Query("""
        SELECT a.primaryEmail FROM Account a
         WHERE a.id = :accountId
           AND a.appRole = de.tum.cit.aet.hephaestus.core.auth.domain.Account.AppRole.APP_ADMIN
           AND a.status = de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.ACTIVE
           AND a.primaryEmail IS NOT NULL AND a.primaryEmailVerifiedAt IS NOT NULL
        """)
    Optional<String> findActiveVerifiedAdministratorEmail(@Param("accountId") long accountId);

    /**
     * Ids of accounts whose GDPR soft-delete cooldown has elapsed: {@code status = DELETING} and
     * {@code deleted_at} strictly older than {@code cutoff}, oldest first. Paged so a large erasure
     * backlog is purged one bounded page at a time. Backs {@code AccountHardDeleteSweeper}.
     */
    @Query("""
        SELECT a.id
          FROM Account a
         WHERE a.status = de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.DELETING
           AND a.deletedAt IS NOT NULL
           AND a.deletedAt < :cutoff
         ORDER BY a.deletedAt
        """)
    List<Long> findDeletingPastCooldown(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Resolve an account by {@code primary_email} — dev-login only ({@code DevLoginService}, for
     * idempotent repeat logins). The production login path NEVER resolves by email (nOAuth defence —
     * email is forensic-only).
     */
    Optional<Account> findByPrimaryEmail(String primaryEmail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * Usable (ACTIVE) accounts in the given role, write-locked for the surrounding transaction. Backs
     * the last-admin guard. Selects the entity (not a scalar) so Hibernate emits {@code FOR UPDATE} —
     * as {@code findActiveByAccountIdForUpdate} does — letting concurrent demotions serialize rather
     * than both passing a stale count. ACTIVE-only: a suspended/deleting admin can't sign in, so it
     * must not count as the last admin.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.appRole = :role AND a.status = :status")
    List<Account> findByAppRoleAndStatusForUpdate(
            @Param("role") Account.AppRole role, @Param("status") Account.Status status);

    /**
     * Break-glass first-admin promotion (backs {@code POST /auth/bootstrap-admin}). Promotes the
     * given account to {@code APP_ADMIN} in a single atomic statement, but ONLY while no ACTIVE
     * {@code APP_ADMIN} exists — so it self-disables the instant a real admin appears (the
     * {@code NOT EXISTS} is the gate). Returns the number of rows updated: {@code 1} on success,
     * {@code 0} if an admin already exists or the account is already {@code APP_ADMIN}.
     *
     * <p><b>Concurrency:</b> the single statement removes the read-modify-write count TOCTOU within a
     * node, but under READ COMMITTED the {@code NOT EXISTS} subquery takes no predicate lock on the
     * (empty) admin set, so two concurrent break-glass calls promoting DIFFERENT accounts can both see
     * {@code NOT EXISTS} as true and each promote one — yielding two simultaneous first-admins. That is
     * acceptable for this one-time operator-gated path (the demotion guard at
     * {@link #findByAppRoleAndStatusForUpdate} still serialises with {@code FOR UPDATE}); it is NOT a
     * hard single-winner gate.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Account a
           SET a.appRole = de.tum.cit.aet.hephaestus.core.auth.domain.Account.AppRole.APP_ADMIN
         WHERE a.id = :id
           AND a.appRole <> de.tum.cit.aet.hephaestus.core.auth.domain.Account.AppRole.APP_ADMIN
           AND NOT EXISTS (
               SELECT 1 FROM Account b
                WHERE b.appRole = de.tum.cit.aet.hephaestus.core.auth.domain.Account.AppRole.APP_ADMIN
                  AND b.status = de.tum.cit.aet.hephaestus.core.auth.domain.Account.Status.ACTIVE
           )
        """)
    int promoteToFirstAdminIfNoneExists(@Param("id") Long id);
}

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

/** Cross-module handle on {@link Account}. The auth module owns account mutations. */
@Repository
@WorkspaceAgnostic(
        "Account is the Hephaestus-native principal; it spans workspaces (membership lives on" + " Membership)")
public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByPasskeyUserHandle(String handle);

    @Modifying
    @Query(value = """
        INSERT INTO identity_restriction (id, provider_id, provider_user_key, team_key, kind)
        SELECT gen_random_uuid(), l.provider_id, l.provider_user_key, l.team_key, 'HIDE_PUBLIC' FROM identity l
        JOIN account a ON a.id = l.account_id
        JOIN identity_provider p ON p.id = l.provider_id
        WHERE a.id = :accountId AND p.type IN ('GITHUB', 'GITLAB')
          AND (NOT a.public_activity_visible OR a.status IN ('DELETING', 'DELETED'))
        ON CONFLICT (provider_id, provider_user_key, team_key, kind) DO NOTHING
        """, nativeQuery = true)
    int retainPublicActivityObjections(@Param("accountId") long accountId);

    @Modifying
    @Query(value = """
        DELETE FROM identity_restriction o USING identity l
        WHERE l.account_id = :accountId AND l.provider_id = o.provider_id AND l.provider_user_key = o.provider_user_key AND l.team_key = o.team_key AND o.kind = 'HIDE_PUBLIC'
        """, nativeQuery = true)
    int clearLinkedPublicActivityObjections(@Param("accountId") long accountId);

    /**
     * Forgets that the upgrade hid this account only because it never answered: from now on its value
     * is its own choice, which a rollback of that upgrade must not overwrite.
     */
    @Modifying
    @Query(
            value = "DELETE FROM account_public_activity_hidden_by_default WHERE account_id = :accountId",
            nativeQuery = true)
    int forgetPublicActivityHiddenByDefault(@Param("accountId") long accountId);

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
     * idempotent repeat logins). The production login path NEVER resolves by email (nOAuth defence
     * — email is forensic-only).
     */
    Optional<Account> findByPrimaryEmail(String primaryEmail);

    boolean existsByPrimaryEmailIgnoreCase(String primaryEmail);

    /** An unassigned contact email has no row to lock. */
    @Query(
            value =
                    "SELECT count(pg_advisory_xact_lock(hashtextextended('account-contact-email:' || lower(CAST(:email AS text)),0)))",
            nativeQuery = true)
    long lockContactEmail(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * The account's status, with the row share-locked for the rest of the transaction. Every token
     * issuance takes this lock, and account-wide revocation takes the conflicting {@link
     * #lockStatusForUpdate}, so a revocation that commits is never outlived by a token minted
     * concurrently. Scalar and native, so it never hands back a stale managed entity.
     */
    @Query(value = "SELECT status FROM account WHERE id = :id FOR SHARE", nativeQuery = true)
    Optional<String> lockStatusForShare(@Param("id") Long id);

    /**
     * Status changes conflict with issuance share locks but allow audit foreign-key key-share locks.
     */
    @Query(value = "SELECT status FROM account WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<String> lockStatusForUpdate(@Param("id") Long id);

    /**
     * What a token is minted from, read from the row as it stands once share-locked: status,
     * instance role and display name. Native and projected, so a managed {@link Account} loaded
     * earlier in the transaction can never supply a stale role or status.
     */
    interface IssuanceAuthority {
        String getStatus();

        String getAppRole();

        String getDisplayName();
    }

    @Query(
            value = "SELECT status AS \"status\", app_role AS \"appRole\", display_name AS"
                    + " \"displayName\" FROM account WHERE id = :id FOR SHARE",
            nativeQuery = true)
    Optional<IssuanceAuthority> lockAuthorityForShare(@Param("id") Long id);

    interface AccountStatusRow {
        Long getId();

        String getStatus();
    }

    /** Status per account as last committed, for a cohort read; takes no lock. */
    @Query(value = "SELECT id AS \"id\", status AS \"status\" FROM account WHERE id IN (:ids)", nativeQuery = true)
    List<AccountStatusRow> findStatuses(@Param("ids") Collection<Long> ids);

    /**
     * {@link #findStatuses} with every row share-locked in id order, so two cohort locks never wait on
     * each other in opposite orders. Conflicts with {@link #findByIdForUpdate}, which every consent
     * write takes first, and with {@link #lockStatusForUpdate}, which account purge takes first.
     */
    @Query(
            value = "SELECT id AS \"id\", status AS \"status\" FROM account WHERE id IN (:ids) ORDER BY id FOR SHARE",
            nativeQuery = true)
    List<AccountStatusRow> lockStatusesForShare(@Param("ids") Collection<Long> ids);

    /** Share-locks the account for a token issuance; whether it may be issued one. */
    default boolean lockForIssuance(Long id) {
        return lockStatusForShare(id).map(Account.Status.ACTIVE.name()::equals).orElse(false);
    }

    /** Locks active administrators so concurrent demotions cannot both pass the last-admin guard. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.appRole = :role AND a.status = :status")
    List<Account> findByAppRoleAndStatusForUpdate(
            @Param("role") Account.AppRole role, @Param("status") Account.Status status);

    /**
     * Break-glass first-admin promotion (backs {@code POST /auth/bootstrap-admin}). Promotes the
     * given account to {@code APP_ADMIN} in a single atomic statement, but ONLY while no ACTIVE
     * {@code APP_ADMIN} exists — so it self-disables the instant a real admin appears (the {@code
     * NOT EXISTS} is the gate). Returns the number of rows updated: {@code 1} on success, {@code 0}
     * if an admin already exists or the account is already {@code APP_ADMIN}.
     *
     * <p><b>Concurrency:</b> the single statement removes the read-modify-write count TOCTOU within
     * a node, but under READ COMMITTED the {@code NOT EXISTS} subquery takes no predicate lock on
     * the (empty) admin set, so two concurrent break-glass calls promoting DIFFERENT accounts can
     * both see {@code NOT EXISTS} as true and each promote one — yielding two simultaneous
     * first-admins. That is acceptable for this one-time operator-gated path (the demotion guard at
     * {@link #findByAppRoleAndStatusForUpdate} still serialises with {@code FOR UPDATE}); it is NOT
     * a hard single-winner gate.
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

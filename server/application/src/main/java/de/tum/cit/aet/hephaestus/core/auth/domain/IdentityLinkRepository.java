package de.tum.cit.aet.hephaestus.core.auth.domain;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Auth-path lookups for {@link IdentityLink}.
 *
 * <p><strong>nOAuth defence:</strong> there is no {@code findByEmail} here, by design.
 * Account resolution at login is always {@code (provider, subject, team_id)}. Any future
 * email-based search method belongs on a contact-only repository, not this one — and the
 * ArchUnit suite enforces that constraint once it lands.
 */
@Repository
@WorkspaceAgnostic("Federated-login associations are user-scoped, resolved by (provider, subject) at login")
public interface IdentityLinkRepository extends JpaRepository<IdentityLink, Long> {
    /**
     * Resolve an IdentityLink by the immutable {@code (provider, subject, team_id)} triple.
     * Active-only — disabled rows are not considered (a refresh-failed or admin-disabled
     * link must require explicit re-link, not silent re-bind).
     */
    @Query("""
        SELECT il
          FROM IdentityLink il
         WHERE il.providerId = :gitProviderId
           AND il.subject = :subject
           AND COALESCE(il.teamId, '') = COALESCE(:teamId, '')
           AND il.disabledAt IS NULL
        """)
    Optional<IdentityLink> findActiveByProviderSubject(
            @Param("gitProviderId") Long gitProviderId,
            @Param("subject") String subject,
            @Param("teamId") @Nullable String teamId);

    @Modifying
    @Query("""
        UPDATE IdentityLink il
           SET il.lastLoginAt = :now
         WHERE il.id = :id
        """)
    int touchLastLogin(@Param("id") Long id, @Param("now") Instant now);

    /** Active (non-disabled) identity links for an account. */
    @Query("""
        SELECT il
          FROM IdentityLink il
         WHERE il.account.id = :accountId
           AND il.disabledAt IS NULL
        """)
    List<IdentityLink> findActiveByAccountId(@Param("accountId") Long accountId);

    /**
     * Like {@link #findActiveByAccountId} but takes a {@code PESSIMISTIC_WRITE} (SELECT … FOR UPDATE)
     * lock on the account's active links. Unlink uses this to serialize the last-identity guard:
     * without the lock, two concurrent unlinks of DIFFERENT identities each read count 2, both pass
     * {@code size() <= 1}, and both disjoint-row UPDATEs commit under READ COMMITTED (neither blocks on
     * the other) → 0 active identities, a full sign-in lockout. With the lock the second unlink waits,
     * re-reads count 1, and is rejected.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT il
          FROM IdentityLink il
         WHERE il.account.id = :accountId
           AND il.disabledAt IS NULL
        """)
    List<IdentityLink> findActiveByAccountIdForUpdate(@Param("accountId") Long accountId);

    /**
     * Unlink: remove the link, but only if owned by the given account (so a caller can never unlink
     * someone else's identity). A row delete — not a soft delete — chosen deliberately: the
     * {@code uq_identity_link_provider_subject_team} index is global (spans disabled rows), so a
     * soft-deleted row would keep its {@code (provider, subject)} key and re-linking the SAME IdP
     * identity later would hit a unique violation. Deleting also avoids retaining the disconnected
     * identity's PII and keeps {@code disabled_at} reserved for admin/system disabling. The append-only
     * {@code auth_event} keeps the audit trail; its {@code identity_link_id} FK is {@code ON DELETE SET
     * NULL}.
     *
     * @return number of rows deleted (0 when the link is missing or not owned — e.g. a lost race)
     */
    @Modifying
    @Query("DELETE FROM IdentityLink il WHERE il.id = :id AND il.account.id = :accountId")
    int deleteByIdAndAccountId(@Param("id") Long id, @Param("accountId") Long accountId);

    /**
     * Account ids owning an active link wired to {@code externalActorId} — the reverse of
     * {@link #linkExternalActorIfAbsent}. Ordered by link id so the caller's first pick is deterministic.
     */
    @Query("""
        SELECT il.account.id
          FROM IdentityLink il
         WHERE il.externalActorId = :externalActorId
           AND il.disabledAt IS NULL
         ORDER BY il.id
        """)
    List<Long> findActiveAccountIdsByExternalActorId(@Param("externalActorId") Long externalActorId);

    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.core.auth.domain.LinkedAccountRow(
                   il.externalActorId, il.account.id, il.account.status)
          FROM IdentityLink il
         WHERE il.externalActorId IN :externalActorIds
           AND il.disabledAt IS NULL
        """)
    List<LinkedAccountRow> findLinkedAccountsByExternalActorIds(
            @Param("externalActorIds") Collection<Long> externalActorIds);

    /**
     * Wire an {@link IdentityLink} to its git-provider actor mirror, but only when it is not already
     * set — idempotent and never clobbers an existing association. Backs
     * {@code AccountIdentityQuery.linkExternalActor} so the SCM-side provisioner can close the
     * {@code IdentityLink → ExternalActor} gap on first login.
     *
     * @return number of rows updated (0 when the link is missing or already wired)
     */
    @Modifying
    @Query("""
        UPDATE IdentityLink il
           SET il.externalActorId = :externalActorId
         WHERE il.id = :id
           AND il.externalActorId IS NULL
        """)
    int linkExternalActorIfAbsent(@Param("id") Long id, @Param("externalActorId") Long externalActorId);
}

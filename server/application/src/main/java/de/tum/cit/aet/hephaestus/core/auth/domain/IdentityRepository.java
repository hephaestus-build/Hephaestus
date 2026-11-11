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
 * Identity keys are provider, subject and team—not email. Email lookup belongs to contact data, not
 * authentication.
 */
@Repository
@WorkspaceAgnostic("Federated-login associations are user-scoped, resolved by (provider, subject) at login")
public interface IdentityRepository extends JpaRepository<Identity, Long> {
    @Query(value = "SELECT account_id FROM identity WHERE id=:identityId", nativeQuery = true)
    Optional<Long> findOwnerId(@Param("identityId") long identityId);

    @Query(value = """
        SELECT count(pg_advisory_xact_lock(k)) FROM (
          SELECT DISTINCT hashtextextended(jsonb_build_array(p,s,t)::text,0) k
          FROM unnest(CAST(:providers AS bigint[]),CAST(:subjects AS text[]),CAST(:teams AS text[])) AS keys(p,s,t)
          ORDER BY k
        ) ordered_keys
        """, nativeQuery = true)
    long lockOwnership(
            @Param("providers") Long[] providers, @Param("subjects") String[] subjects, @Param("teams") String[] teams);

    @Query("""
        SELECT i FROM Identity i JOIN FETCH i.account
        WHERE i.providerId = :providerId AND i.providerUserKey = :key AND i.teamKey = :teamKey
        ORDER BY CASE WHEN i.disabledAt IS NULL THEN 0 ELSE 1 END, i.id DESC
        """)
    List<Identity> findByExactKey(
            @Param("providerId") long providerId, @Param("key") String key, @Param("teamKey") String teamKey);

    List<Identity> findByAccount_Id(Long accountId);

    /**
     * Resolves a provider identity only while its link is enabled; does not imply the account is
     * active.
     */
    @Query("""
        SELECT il
          FROM Identity il
         WHERE il.providerId = :gitProviderId
           AND il.providerUserKey = :subject
           AND COALESCE(il.teamKey, '') = COALESCE(:teamId, '')
           AND il.disabledAt IS NULL
        """)
    Optional<Identity> findActiveByProviderSubject(
            @Param("gitProviderId") Long gitProviderId,
            @Param("subject") String subject,
            @Param("teamId") @Nullable String teamId);

    @Modifying
    @Query("""
        UPDATE Identity il
           SET il.lastLoginAt = :now
         WHERE il.id = :id
        """)
    int touchLastLogin(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Active identity links in linking order, keeping the representative actor stable when another
     * is linked.
     */
    @Query("""
        SELECT il
          FROM Identity il
         WHERE il.account.id = :accountId
           AND il.disabledAt IS NULL
         ORDER BY il.id
        """)
    List<Identity> findActiveByAccountId(@Param("accountId") Long accountId);

    @Query("""
        SELECT i FROM Identity i JOIN FETCH i.account
        WHERE i.account.id IN :accountIds AND i.disabledAt IS NULL ORDER BY i.id
        """)
    List<Identity> findActiveByAccountIds(@Param("accountIds") Collection<Long> accountIds);

    @Modifying
    @Query("UPDATE Identity i SET i.account.id = :target WHERE i.account.id = :source")
    int absorbAccount(@Param("target") long target, @Param("source") long source);

    /**
     * Serializes the last-identity guard across concurrent unlinks, preventing both from removing
     * their links after independently observing another active link.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT il
          FROM Identity il
         WHERE il.account.id = :accountId
           AND il.disabledAt IS NULL
        """)
    List<Identity> findActiveByAccountIdForUpdate(@Param("accountId") Long accountId);
}

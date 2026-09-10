package de.tum.cit.aet.hephaestus.core.auth.domain;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Identity keys are provider, subject and team—not email. Email lookup belongs to contact data, not authentication. */
@Repository
@WorkspaceAgnostic("Federated-login associations are user-scoped, resolved by (provider, subject) at login")
public interface IdentityLinkRepository extends JpaRepository<IdentityLink, Long> {
    /** Resolves a provider identity only while its link is enabled; does not imply the account is active. */
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

    /** Active identity links in linking order, keeping the representative actor stable when another is linked. */
    @Query("""
        SELECT il
          FROM IdentityLink il
         WHERE il.account.id = :accountId
           AND il.disabledAt IS NULL
         ORDER BY il.id
        """)
    List<IdentityLink> findActiveByAccountId(@Param("accountId") Long accountId);

    /**
     * Serializes the last-identity guard across concurrent unlinks, preventing both from removing
     * their links after independently observing another active link.
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
     * Deletes only an account-owned link. Soft deletion would reserve its globally unique provider identity
     * and prevent re-linking. The audit trail remains; its link foreign key is set to null.
     */
    @Modifying
    @Query("DELETE FROM IdentityLink il WHERE il.id = :id AND il.account.id = :accountId")
    int deleteByIdAndAccountId(@Param("id") Long id, @Param("accountId") Long accountId);

    /**
     * Fills an absent cached actor reference; an existing reference is never replaced.
     * The cache is not authoritative identity evidence. Returns the number of rows updated.
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

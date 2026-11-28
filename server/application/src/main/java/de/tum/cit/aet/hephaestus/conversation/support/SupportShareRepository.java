package de.tum.cit.aet.hephaestus.conversation.support;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Every read names its reader: the person who shared, or the one instance admin who receives the share. */
@Repository
@WorkspaceAgnostic("A support share belongs to the account that shares it and to its one recipient, not to a workspace")
interface SupportShareRepository extends JpaRepository<SupportShare, UUID> {

    /** Every share of the person that its recipient can still read, newest first: each needs its Revoke. */
    @Query("""
        SELECT s FROM SupportShare s
         WHERE s.accountId = :accountId AND s.revokedAt IS NULL AND s.expiresAt > :now
         ORDER BY s.createdAt DESC, s.id DESC
        """)
    List<SupportShare> findActiveOfAccount(@Param("accountId") long accountId, @Param("now") Instant now);

    /** The person's latest ended shares, newest first, as the record of who read them. */
    @Query("""
        SELECT s FROM SupportShare s
         WHERE s.accountId = :accountId AND (s.revokedAt IS NOT NULL OR s.expiresAt <= :now)
         ORDER BY s.createdAt DESC, s.id DESC LIMIT 50
        """)
    List<SupportShare> findRecentEndedOfAccount(@Param("accountId") long accountId, @Param("now") Instant now);

    Optional<SupportShare> findByIdAndAccountId(UUID id, Long accountId);

    /** The shares that a recipient can read now, newest first. */
    @Query("""
        SELECT s FROM SupportShare s
         WHERE s.recipientAccountId = :recipient AND s.revokedAt IS NULL AND s.expiresAt > :now
         ORDER BY s.createdAt DESC, s.id DESC LIMIT 100
        """)
    List<SupportShare> findReadableBy(@Param("recipient") long recipient, @Param("now") Instant now);

    /**
     * Counts one read, in the statement that checks the recipient, the revocation and the expiry. It is the last check
     * before the share is served, so a share revoked a moment earlier is not read.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE SupportShare s SET s.readCount = s.readCount + 1, s.lastReadAt = :now
         WHERE s.id = :id AND s.recipientAccountId = :recipient AND s.revokedAt IS NULL AND s.expiresAt > :now
        """)
    int markRead(@Param("id") UUID id, @Param("recipient") long recipient, @Param("now") Instant now);

    /**
     * Ends the share in one statement that writes only its end, so a read that the recipient counts meanwhile stays
     * counted. The persistence context is cleared, so the share is read again after it.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE SupportShare s SET s.revokedAt = :now
         WHERE s.id = :id AND s.accountId = :accountId AND s.revokedAt IS NULL
        """)
    int revoke(@Param("id") UUID id, @Param("accountId") long accountId, @Param("now") Instant now);
}

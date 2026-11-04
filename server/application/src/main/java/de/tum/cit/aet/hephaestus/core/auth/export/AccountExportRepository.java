package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link AccountExport}. All ownership-sensitive reads go through
 * {@link #findByIdAndAccountId} so a caller can never observe another account's export (the
 * service maps the empty result to 404, defeating id enumeration).
 */
@Repository
@WorkspaceAgnostic("GDPR data exports are account-scoped, not workspace-scoped")
public interface AccountExportRepository extends JpaRepository<AccountExport, Long> {
    /** Ownership-scoped lookup. Empty if the id doesn't exist OR belongs to another account. */
    Optional<AccountExport> findByIdAndAccountId(Long id, Long accountId);

    /** The ownership-scoped row, locked until the transaction ends, so one generation at a time decides its state. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM AccountExport e WHERE e.id = :id AND e.accountId = :accountId")
    Optional<AccountExport> lockByIdAndAccountId(@Param("id") Long id, @Param("accountId") Long accountId);

    /**
     * True if the account already has an in-flight export (PENDING or PROCESSING). Backs the
     * one-in-flight-per-account cap that stops a session from queueing unbounded async full-bundle
     * assemblies, each persisting a BYTEA blob (DoS / storage-amplification guard).
     */
    boolean existsByAccountIdAndStatusIn(Long accountId, Collection<AccountExport.Status> statuses);

    /**
     * Bulk-expire READY exports past their retention window: flip to EXPIRED and free the payload in
     * one UPDATE, without loading the (large) BYTEA blobs into the persistence context. Returns the
     * number of rows affected.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AccountExport e
           SET e.status = de.tum.cit.aet.hephaestus.core.auth.export.AccountExport.Status.EXPIRED,
               e.payload = NULL
         WHERE e.status = de.tum.cit.aet.hephaestus.core.auth.export.AccountExport.Status.READY
           AND e.expiresAt < :now
        """)
    int expireReadyBefore(@Param("now") Instant now);
}

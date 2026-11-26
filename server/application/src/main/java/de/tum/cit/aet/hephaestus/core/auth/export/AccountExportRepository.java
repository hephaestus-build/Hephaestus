package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    @Query("SELECT e.status FROM AccountExport e WHERE e.id = :id AND e.accountId = :accountId")
    Optional<AccountExport.Status> findStatus(@Param("id") Long id, @Param("accountId") Long accountId);

    /** Serializes duplicate deliveries without holding a lock on the account or the export during assembly. */
    @Query(
            value =
                    "SELECT pg_try_advisory_xact_lock(hashtext('account-export-generation'), hashtext(CAST(:id AS text)))",
            nativeQuery = true)
    boolean tryLockGeneration(@Param("id") Long id);

    /**
     * The owner's export fence, alone, until the transaction ends. An erasure or an edit of the owner's conversation
     * messages or received feedback shares it ({@code person_row_sweep('EXPORT_SOURCES_CHANGED', …)}), so the worker
     * reads those sources either after such a change commits or before it starts, and only a session that holds it may
     * publish a ready export ({@code account_export_published_fenced}). The key is the one that rule uses.
     */
    @Query(
            value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtext('account-export-sources'),"
                    + " hashtext(CAST(:accountId AS text)) & 63)) fence",
            nativeQuery = true)
    long lockSources(@Param("accountId") Long accountId);

    /** The ownership-scoped row, locked through the terminal state change. */
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
     * Expires one batch of READY exports past their retention window: flips them to EXPIRED and frees the payload in one
     * UPDATE, without loading the BYTEA blobs into the persistence context. The owner function takes at most
     * {@link ExportRetentionSweeper#BATCH} exports and skips one that another transaction holds. Returns the number of
     * exports it expired.
     */
    @Query(
            value =
                    "SELECT public.person_row_sweep('EXPIRED_EXPORTS', CAST(NULL AS bigint), CAST(NULL AS bigint), :now)",
            nativeQuery = true)
    int expireReadyBefore(@Param("now") Instant now);
}

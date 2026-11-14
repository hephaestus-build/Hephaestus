package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Product feedback filings belong to instance-level reports, not to a workspace, which the type-level marker states. The
 * methods that join a report by its account reach every workspace's reports and carry their own marker, which the
 * tenancy check honours (ADR 0077).
 */
@WorkspaceAgnostic("Product feedback filings belong to instance-level reports, not to a workspace")
interface ProductFeedbackFilingRepository extends JpaRepository<ProductFeedbackFiling, UUID> {

    /** One filer for the whole instance at a time; released when the transaction ends. */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryPaceLock(@Param("key") long key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM ProductFeedbackFiling f WHERE f.feedbackId = :id")
    Optional<ProductFeedbackFiling> lock(@Param("id") UUID id);

    /**
     * A report whose draft answer, or lack of one, waits for the server to route it. Its {@code draft_report} action's
     * end put it there, whether the action drafted it, failed or expired.
     */
    @Query(value = """
                    SELECT feedback_id FROM product_feedback_filing
                    WHERE state = 'ANSWERED' AND next_attempt_at <= :now
                    ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, nativeQuery = true)
    Optional<UUID> claimableAnswer(@Param("now") Instant now);

    /** A routed report that goes to no repository, so the hourly GitHub limit does not hold it back. */
    @Query(value = """
                    SELECT feedback_id FROM product_feedback_filing
                    WHERE state = 'QUEUED' AND route = 'ADMINS' AND next_attempt_at <= :now
                    ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, nativeQuery = true)
    Optional<UUID> claimableForAdmins(@Param("now") Instant now);

    /** A routed report due for filing, or a held one whose hold ended. */
    @Query(value = """
                    SELECT feedback_id FROM product_feedback_filing
                    WHERE (state = 'QUEUED' AND next_attempt_at <= :now)
                       OR (state = 'HELD' AND filed_after <= :now AND next_attempt_at <= :now)
                    ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, nativeQuery = true)
    Optional<UUID> claimableFiling(@Param("now") Instant now);

    /** Filed issues whose follow-up is due, oldest first. A closed issue stays followed, so a reopen is seen. */
    @Query(value = """
                    SELECT * FROM product_feedback_filing
                    WHERE state = 'FILED' AND issue_node_id IS NOT NULL
                      AND (followed_at IS NULL
                           OR (route = 'PRIVATE' AND followed_at < :privateBefore)
                           OR (route = 'PUBLIC' AND followed_at < :publicBefore))
                    ORDER BY followed_at NULLS FIRST LIMIT 100
                    """, nativeQuery = true)
    List<ProductFeedbackFiling> dueForFollowUp(
            @Param("privateBefore") Instant privateBefore, @Param("publicBefore") Instant publicBefore);

    @Query(
            value =
                    "SELECT count(*) FROM product_feedback_filing WHERE state IN ('FILING','FILED') AND first_attempt_at >= :since",
            nativeQuery = true)
    long countFiledSince(@Param("since") Instant since);

    @Query(
            value =
                    "SELECT count(*) FROM product_feedback_filing WHERE drafted_at >= :since OR draft_admitted_at >= :since",
            nativeQuery = true)
    long countDraftedSince(@Param("since") Instant since);

    @WorkspaceAgnostic(
            "Limits the product feedback reports one account drafts, across every workspace it sends feedback from")
    @Query(value = """
                    SELECT count(*) FROM product_feedback_filing f JOIN product_feedback p ON p.id = f.feedback_id
                    WHERE p.account_id = :accountId AND (f.drafted_at >= :since OR f.draft_admitted_at >= :since)
                    """, nativeQuery = true)
    long countDraftedByAccountSince(@Param("accountId") long accountId, @Param("since") Instant since);

    @WorkspaceAgnostic("Limits the public issues one account files, across every workspace it sends feedback from")
    @Query(value = """
                    SELECT count(*) FROM product_feedback_filing f JOIN product_feedback p ON p.id = f.feedback_id
                    WHERE p.account_id = :accountId AND f.route = 'PUBLIC' AND f.state IN ('FILING','FILED')
                      AND f.first_attempt_at >= :since
                    """, nativeQuery = true)
    long countPublicFiledByAccountSince(@Param("accountId") long accountId, @Param("since") Instant since);

    /** Open reports that wait for the admins, oldest first. */
    @WorkspaceAgnostic("Instance administrators read the open reports of every workspace")
    @Query(value = """
                    SELECT f.feedback_id FROM product_feedback_filing f JOIN product_feedback p ON p.id = f.feedback_id
                    WHERE f.state = 'SAVED' AND p.resolved_at IS NULL
                    ORDER BY p.created_at LIMIT :limit
                    """, nativeQuery = true)
    List<UUID> savedForAdmins(@Param("limit") int limit);

    @WorkspaceAgnostic("Instance administrators count the open reports of every workspace")
    @Query(value = """
                    SELECT count(*) FROM product_feedback_filing f JOIN product_feedback p ON p.id = f.feedback_id
                    WHERE f.state = 'SAVED' AND p.resolved_at IS NULL
                    """, nativeQuery = true)
    long countSavedForAdmins();

    long countByState(FilingState state);

    long countByStateAndRoute(FilingState state, FilingRoute route);

    @Query(
            value = "SELECT max(first_attempt_at) FROM product_feedback_filing WHERE state = 'FILED'",
            nativeQuery = true)
    Optional<Instant> lastFiledAt();

    List<ProductFeedbackFiling> findAllByStateOrderByUpdatedAtDesc(FilingState state, Pageable pageable);

    @Query(
            value =
                    "SELECT * FROM product_feedback_filing WHERE state IN ('DRAFT_QUEUED','DRAFTING','DRAFT_WAITING','ANSWERED','HELD','QUEUED','FILING')"
                            + " ORDER BY created_at LIMIT 1",
            nativeQuery = true)
    Optional<ProductFeedbackFiling> oldestWaiting();

    @WorkspaceAgnostic("A person's own product feedback reports, from every workspace they sent one from")
    @Query(value = """
                    SELECT f.* FROM product_feedback_filing f JOIN product_feedback p ON p.id = f.feedback_id
                    WHERE p.account_id = :accountId
                    """, nativeQuery = true)
    List<ProductFeedbackFiling> findAllByAccount(@Param("accountId") long accountId);

    /** Erasure holds these locks until it deletes the rows, so a promotion cannot freeze a recipient in between. */
    @WorkspaceAgnostic("Erasure locks every product feedback filing of one person, in every workspace")
    @Query(value = """
                    SELECT f.* FROM product_feedback_filing f JOIN product_feedback p ON p.id = f.feedback_id
                    WHERE p.account_id = :accountId
                    ORDER BY f.feedback_id
                    FOR UPDATE OF f
                    """, nativeQuery = true)
    List<ProductFeedbackFiling> lockAllByAccount(@Param("accountId") long accountId);

    /** Erasure holds these locks until it deletes the rows, so a promotion cannot freeze a recipient in between. */
    @Query(value = """
                    SELECT f.* FROM product_feedback_filing f JOIN product_feedback p ON p.id = f.feedback_id
                    WHERE p.workspace_id = :workspaceId
                    ORDER BY f.feedback_id
                    FOR UPDATE OF f
                    """, nativeQuery = true)
    List<ProductFeedbackFiling> lockAllByWorkspace(@Param("workspaceId") long workspaceId);
}

package de.tum.cit.aet.hephaestus.practices.advice;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** One decision per review and advice entry. A receipt is written once and never changed. */
public interface PracticeAdviceReceiptRepository
        extends JpaRepository<PracticeAdviceReceipt, PracticeAdviceReceipt.Key> {

    Optional<PracticeAdviceReceipt> findByAdviceIdAndWorkspaceIdAndReviewId(
            UUID adviceId, long workspaceId, UUID reviewId);

    /** The receipt takes its owner and workspace from the locked entry, so it can name no other. */
    @Modifying
    @Query(
            value = "INSERT INTO practice_advice_receipt (advice_id, workspace_id, person_account_id, review_id, kind,"
                    + " content_digest, version, written_at) SELECT a.id, a.workspace_id, a.person_account_id,"
                    + " :reviewId, :kind, :digest, :version, clock_timestamp() FROM practice_advice a"
                    + " WHERE a.id = :adviceId AND a.workspace_id = :workspaceId",
            nativeQuery = true)
    int insertReceipt(
            @Param("adviceId") UUID adviceId,
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") UUID reviewId,
            @Param("kind") String kind,
            @Param("digest") byte[] digest,
            @Param("version") long version);
}

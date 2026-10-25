package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeDeliveryStatus;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeMode;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public interface FeedbackBasisRepository extends JpaRepository<FeedbackBasis, UUID> {

    List<FeedbackBasis> findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(long workspaceId, UUID feedbackId);

    List<FeedbackBasis> findByWorkspaceIdAndResultId(long workspaceId, UUID resultId);

    @Query(value = """
        SELECT EXISTS(SELECT 1 FROM feedback_basis b JOIN result_mark m
          ON m.result_id = b.result_id AND m.workspace_id = b.workspace_id
         WHERE b.feedback_id = :feedbackId AND b.workspace_id = :workspaceId
           AND m.kind = 'INCORRECT' AND m.removed_at IS NULL)
        """, nativeQuery = true)
    boolean hasIncorrectBasis(@Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    /** The pieces among {@code feedbackIds} with a result marked incorrect: {@link #hasIncorrectBasis} for many. */
    @Query(value = """
        SELECT DISTINCT b.feedback_id FROM feedback_basis b JOIN result_mark m
          ON m.result_id = b.result_id AND m.workspace_id = b.workspace_id
         WHERE b.feedback_id IN (:feedbackIds) AND b.workspace_id = :workspaceId
           AND m.kind = 'INCORRECT' AND m.removed_at IS NULL
        """, nativeQuery = true)
    List<UUID> incorrectBasisFeedbackIds(
            @Param("workspaceId") long workspaceId, @Param("feedbackIds") Collection<UUID> feedbackIds);

    @Query(value = """
        SELECT EXISTS(SELECT 1 FROM result_mark m WHERE m.workspace_id=:workspaceId
          AND m.result_id IN (:resultIds) AND m.kind='INCORRECT' AND m.removed_at IS NULL)
        """, nativeQuery = true)
    boolean hasIncorrectResults(@Param("workspaceId") long workspaceId, @Param("resultIds") Collection<UUID> resultIds);

    // The immutable trace keeps delivery intent after a later withholding. Missing traces conservatively use an
    // allowance.
    @Query(value = """
        SELECT f.id AS feedbackId, f.destination AS destination, b.result_id AS resultId,
          COALESCE(jsonb_array_length(r.checks -> 'feedbackComposition' -> (f.id::text) -> 'copies') > 0, true)
            AS delivering
        FROM feedback f JOIN feedback_basis b ON b.feedback_id=f.id AND b.workspace_id=f.workspace_id
          JOIN review r ON r.id=f.review_id AND r.workspace_id=f.workspace_id
        WHERE f.workspace_id=:workspaceId AND f.review_id=:reviewId AND f.developer_id=:developerId
          AND f.destination IN ('PRACTICE_PAGE','CONVERSATION') AND b.result_id IN (:resultIds)
        """, nativeQuery = true)
    List<PrivateDecisionRow> findPrivateDecisions(
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") UUID reviewId,
            @Param("developerId") long developerId,
            @Param("resultIds") Collection<UUID> resultIds);

    interface PrivateDecisionRow {
        UUID getFeedbackId();

        FeedbackDestination getDestination();

        UUID getResultId();

        boolean getDelivering();
    }

    List<FeedbackBasis> findByWorkspaceIdAndFeedbackIdInOrderByOrdinalAsc(
            long workspaceId, Collection<UUID> feedbackIds);

    @Query(value = """
        SELECT r.* FROM result r WHERE r.workspace_id = :workspaceId AND r.developer_id IN (:developerIds)
           AND r.recorded_at > :from AND r.recorded_at < :to AND r.ended_at IS NULL
        ORDER BY r.recorded_at, r.id
        """, nativeQuery = true)
    List<Result> findLaterResults(
            @Param("workspaceId") long workspaceId,
            @Param("developerIds") Collection<Long> developerIds,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query(value = """
        SELECT i.id AS id, i.kind AS kind, i.source_id AS sourceId, i.parent_id AS parentId,
            i.path AS path, i.removed_at AS removedAt
        FROM item i WHERE i.workspace_id = :workspaceId AND i.id IN (:itemIds)
        """, nativeQuery = true)
    List<ItemRow> findItems(@Param("workspaceId") long workspaceId, @Param("itemIds") Collection<Long> itemIds);

    interface ItemRow {
        long getId();

        String getKind();

        @Nullable
        Long getSourceId();

        @Nullable
        Long getParentId();

        @Nullable
        String getPath();

        @Nullable
        Instant getRemovedAt();
    }

    @WorkspaceAgnostic("Verified account absorption moves the same person across all workspaces")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE feedback_basis SET developer_id = :claimedAccountId WHERE developer_id"
                    + " = :unclaimedAccountId",
            nativeQuery = true)
    int absorbDeveloper(
            @Param("claimedAccountId") long claimedAccountId, @Param("unclaimedAccountId") long unclaimedAccountId);

    @Query(value = """
        SELECT w.status AS workspaceStatus,w.practices_enabled AS enabled,
          w.practice_delivery_status AS deliveryStatus,w.practice_rollout_revision AS rolloutRevision,
          w.review_scope_people AS peopleScope,m.review_selected AS selected,m.left_at AS leftAt,
          a.status AS accountStatus,a.ai_choice AS aiChoice,a.feedback_delivery_enabled AS deliveryEnabled
        FROM workspace w JOIN membership m ON m.workspace_id=w.id JOIN account a ON a.id=m.account_id
        WHERE w.id=:workspaceId AND a.id=:developerId
        """, nativeQuery = true)
    Optional<PolicyRow> policy(@Param("workspaceId") long workspaceId, @Param("developerId") long developerId);

    interface PolicyRow {
        WorkspaceStatus getWorkspaceStatus();

        boolean getEnabled();

        PracticeDeliveryStatus getDeliveryStatus();

        long getRolloutRevision();

        ReviewScopeMode getPeopleScope();

        boolean getSelected();

        @Nullable
        Instant getLeftAt();

        Account.Status getAccountStatus();

        @Nullable
        AiChoice getAiChoice();

        boolean getDeliveryEnabled();
    }

    @Query(value = """
        SELECT DISTINCT fresh.id AS id,answer.resolution AS resolution FROM problem fresh JOIN result current_result ON current_result.id=fresh.result_id AND current_result.workspace_id=fresh.workspace_id
         JOIN result prior ON prior.workspace_id=current_result.workspace_id AND prior.practice_id=current_result.practice_id
           AND prior.item_id=current_result.item_id AND prior.developer_id=current_result.developer_id
         JOIN problem old ON old.result_id=prior.id AND old.workspace_id=prior.workspace_id AND old.key=fresh.key
         JOIN feedback_basis b ON b.result_id=prior.id AND b.workspace_id=prior.workspace_id AND (b.problem_id IS NULL OR b.problem_id=old.id)
         JOIN feedback f ON f.id=b.feedback_id AND f.workspace_id=b.workspace_id AND f.developer_id=current_result.developer_id AND f.delivered_at IS NOT NULL
         JOIN LATERAL (SELECT r.resolution FROM feedback_response r WHERE r.feedback_id=f.id AND r.workspace_id=f.workspace_id
           ORDER BY r.created_at DESC,r.id::text DESC LIMIT 1) answer ON true
        WHERE fresh.workspace_id=:workspaceId AND fresh.id IN (:problemIds) AND fresh.key IS NOT NULL
          AND answer.resolution IN ('DISAGREE','NOT_APPLICABLE')
        """, nativeQuery = true)
    List<SuppressedProblem> findSuppressedProblems(
            @Param("workspaceId") long workspaceId, @Param("problemIds") Collection<UUID> problemIds);

    interface SuppressedProblem {
        UUID getId();

        FeedbackResolution getResolution();
    }

    @WorkspaceAgnostic("Verified account absorption defers both owner-specific person-equality constraints")
    @Modifying
    @Query(
            value = "SET CONSTRAINTS"
                    + " sfk_feedback_basis_result_person,sfk_feedback_basis_feedback_person"
                    + " DEFERRED",
            nativeQuery = true)
    void deferDeveloperConstraints();

    @WorkspaceAgnostic(
            "Verified account absorption validates both person-equality constraints before source" + " cleanup")
    @Modifying
    @Query(
            value = "SET CONSTRAINTS"
                    + " sfk_feedback_basis_result_person,sfk_feedback_basis_feedback_person"
                    + " IMMEDIATE",
            nativeQuery = true)
    void validateDeveloperConstraints();
}

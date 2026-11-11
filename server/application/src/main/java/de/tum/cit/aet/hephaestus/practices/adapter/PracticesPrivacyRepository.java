package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface PracticesPrivacyRepository extends Repository<Result, UUID> {
    String CONVERSATION_RESULTS =
            "SELECT r.id FROM result r JOIN item i ON i.id=r.item_id AND i.workspace_id=r.workspace_id"
                    + " WHERE r.workspace_id=:ws AND i.kind='chat.thread'";

    @Query(value = CONVERSATION_RESULTS, nativeQuery = true)
    List<UUID> conversationResultIds(long ws);

    @Query(value = CONVERSATION_RESULTS + " AND i.source_id IN (:threads)", nativeQuery = true)
    List<UUID> conversationResultIdsForThreads(long ws, Collection<Long> threads);

    @Query(value = CONVERSATION_RESULTS + " AND r.developer_id=:account", nativeQuery = true)
    List<UUID> conversationResultIdsForDeveloper(long ws, long account);

    @Query(
            value = "SELECT r.id FROM result r JOIN item i ON i.id=r.item_id AND i.workspace_id=r.workspace_id"
                    + " WHERE r.workspace_id=:ws AND i.kind LIKE 'scm.%'",
            nativeQuery = true)
    List<UUID> scmResultIds(long ws);

    @Query(
            value = "SELECT r.id FROM result r JOIN item i ON i.id=r.item_id AND i.workspace_id=r.workspace_id"
                    + " WHERE r.workspace_id=:ws AND i.kind LIKE 'docs.%'",
            nativeQuery = true)
    List<UUID> documentResultIds(long ws);

    @Query(
            value = "SELECT DISTINCT f.id FROM feedback f JOIN feedback_basis b ON b.feedback_id=f.id AND"
                    + " b.workspace_id=f.workspace_id WHERE f.workspace_id=:ws AND b.result_id=ANY(CAST(:ids AS"
                    + " uuid[]))",
            nativeQuery = true)
    List<UUID> feedbackForResults(long ws, UUID[] ids);

    @Modifying
    @Query(
            value = "DELETE FROM feedback f WHERE f.workspace_id=:ws AND EXISTS(SELECT 1 FROM feedback_basis b WHERE"
                    + " b.workspace_id=f.workspace_id AND b.feedback_id=f.id AND b.result_id=ANY(CAST(:ids AS"
                    + " uuid[])))",
            nativeQuery = true)
    int deleteFeedbackForResults(long ws, UUID[] ids);

    @Modifying
    @Query(value = "DELETE FROM result WHERE workspace_id=:ws AND id=ANY(CAST(:ids AS uuid[]))", nativeQuery = true)
    int deleteResults(long ws, UUID[] ids);

    @Query(value = "SELECT id FROM feedback WHERE workspace_id=:ws ORDER BY id", nativeQuery = true)
    List<UUID> workspaceFeedbackIds(long ws);

    @Modifying
    @Query(value = "DELETE FROM feedback WHERE workspace_id=:ws", nativeQuery = true)
    int deleteWorkspaceFeedback(long ws);

    @Modifying
    @Query(value = "DELETE FROM review_item WHERE workspace_id=:ws", nativeQuery = true)
    int deleteWorkspaceMembers(long ws);

    @Modifying
    @Query(value = "DELETE FROM result WHERE workspace_id=:ws", nativeQuery = true)
    int deleteWorkspaceResults(long ws);

    @Modifying
    @Query(value = "DELETE FROM review WHERE workspace_id=:ws", nativeQuery = true)
    int deleteWorkspaceReviews(long ws);

    @Modifying
    @Query(value = "DELETE FROM review_batch WHERE workspace_id=:ws", nativeQuery = true)
    int deleteWorkspaceBatches(long ws);

    @Modifying
    @Query(value = "DELETE FROM review_schedule WHERE workspace_id=:ws", nativeQuery = true)
    int deleteWorkspaceSchedules(long ws);

    @Modifying
    @Query(value = "DELETE FROM practice_version WHERE workspace_id=:ws", nativeQuery = true)
    int deleteWorkspaceVersions(long ws);

    @Modifying
    @Query(value = "DELETE FROM practice WHERE workspace_id=:ws", nativeQuery = true)
    int deleteWorkspacePractices(long ws);

    @Modifying
    @Query(value = "DELETE FROM practice_group WHERE workspace_id=:ws", nativeQuery = true)
    int deleteWorkspaceGroups(long ws);

    @WorkspaceAgnostic("Transaction-local deferral permits deletion of mutually referenced practice/version rows")
    @Modifying
    @Query(value = "SET CONSTRAINTS ALL DEFERRED", nativeQuery = true)
    void deferConstraints();

    @WorkspaceAgnostic("Locks attribution parents for two globally locked, verified accounts during absorption")
    @Query(
            value = "SELECT id FROM review WHERE id IN(SELECT review_id FROM result WHERE developer_id IN"
                    + " (:claimed,:unclaimed)) ORDER BY id::text FOR UPDATE",
            nativeQuery = true)
    List<UUID> lockAccountReviews(long claimed, long unclaimed);

    @WorkspaceAgnostic("Locks attribution for two globally locked, verified accounts during absorption")
    @Query(
            value = "SELECT id FROM result WHERE developer_id IN (:claimed,:unclaimed) ORDER BY id::text FOR UPDATE",
            nativeQuery = true)
    List<UUID> lockAccountResults(long claimed, long unclaimed);

    @WorkspaceAgnostic("Resolves overlapping current claims before verified account attribution is combined")
    @Modifying
    @Query(value = """
        WITH ranked AS (
         SELECT r.id,ROW_NUMBER() OVER(PARTITION BY r.workspace_id,r.practice_id,r.item_id ORDER BY v.ordered_at DESC,r.recorded_at DESC,v.id::text DESC) AS ordinal
         FROM result r JOIN review v ON v.id=r.review_id AND v.workspace_id=r.workspace_id
         WHERE r.developer_id IN (:claimed,:unclaimed) AND r.ended_at IS NULL
        ) UPDATE result r SET ended_at=GREATEST(:at,r.recorded_at),end_reason='OUTRANKED'
        FROM ranked WHERE ranked.id=r.id AND ranked.ordinal>1
        """, nativeQuery = true)
    int endOverlappingClaims(long claimed, long unclaimed, Instant at);

    @WorkspaceAgnostic("Moves immutable result attribution between two globally locked, verified accounts")
    @Modifying
    @Query(value = "UPDATE result SET developer_id=:claimed WHERE developer_id=:unclaimed", nativeQuery = true)
    int reassignResults(long claimed, long unclaimed);

    @WorkspaceAgnostic("Moves frozen member attribution between two globally locked, verified accounts")
    @Modifying
    @Query(
            value = "UPDATE review_item SET detail=jsonb_set(detail,'{developerId}',to_jsonb(CAST(:claimed AS bigint)))"
                    + " WHERE detail->>'developerId'=CAST(:unclaimed AS text)",
            nativeQuery = true)
    int reassignMembers(long claimed, long unclaimed);

    @WorkspaceAgnostic("Verifies that the absorbed global account no longer owns result attribution")
    @Query(value = "SELECT EXISTS(SELECT 1 FROM result WHERE developer_id=:account)", nativeQuery = true)
    boolean hasAccountResults(long account);
}

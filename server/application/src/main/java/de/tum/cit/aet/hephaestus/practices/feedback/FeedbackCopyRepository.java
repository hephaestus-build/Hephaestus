package de.tum.cit.aet.hephaestus.practices.feedback;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public interface FeedbackCopyRepository extends JpaRepository<FeedbackCopy, UUID> {

    /**
     * Whether copy {@code c} rests on a result marked incorrect. The composition trace names each copy's exact support.
     * A copy that it does not name rests on every basis of its feedback.
     */
    String RESTS_ON_INCORRECT_RESULT = " " + """
        EXISTS (SELECT 1 FROM result_mark m
         WHERE m.workspace_id = c.workspace_id AND m.kind = 'INCORRECT' AND m.removed_at IS NULL
           AND m.result_id IN (
             SELECT (based.value ->> 'resultId')::uuid
               FROM feedback f JOIN review r ON r.id = f.review_id AND r.workspace_id = f.workspace_id,
                 jsonb_array_elements(r.checks -> 'feedbackComposition' -> (f.id::text) -> 'copies') traced,
                 jsonb_array_elements(traced.value -> 'basedOn') based
              WHERE f.id = c.feedback_id AND f.workspace_id = c.workspace_id
                AND traced.value ->> 'kind' = c.kind AND (traced.value ->> 'position')::int = c.position
             UNION ALL
             SELECT b.result_id FROM feedback_basis b
              WHERE b.workspace_id = c.workspace_id AND b.feedback_id = c.feedback_id
                AND NOT EXISTS (SELECT 1
                  FROM feedback f JOIN review r ON r.id = f.review_id AND r.workspace_id = f.workspace_id,
                    jsonb_array_elements(r.checks -> 'feedbackComposition' -> (f.id::text) -> 'copies') traced
                 WHERE f.id = c.feedback_id AND f.workspace_id = c.workspace_id
                   AND traced.value ->> 'kind' = c.kind AND (traced.value ->> 'position')::int = c.position)))
        """;

    @Query(
            value = "SELECT c.id FROM feedback_copy c"
                    + " WHERE c.workspace_id = :workspaceId AND c.feedback_id = :feedbackId AND"
                    + RESTS_ON_INCORRECT_RESULT,
            nativeQuery = true)
    Set<UUID> incorrectCopyIds(@Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    List<FeedbackCopy> findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(long workspaceId, UUID feedbackId);

    Optional<FeedbackCopy> findByIdAndWorkspaceId(UUID id, long workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM FeedbackCopy c WHERE c.feedbackId = :feedbackId AND c.workspaceId ="
            + " :workspaceId ORDER BY c.id")
    List<FeedbackCopy> lockByFeedbackId(@Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    @Query(value = """
        SELECT EXISTS(SELECT 1 FROM feedback_copy WHERE workspace_id = :workspaceId
         AND feedback_id = :feedbackId AND (write_started_at IS NOT NULL OR posted_at IS NOT NULL
           OR external_ref IS NOT NULL OR state IN ('WRITE_STARTED','POSTED','RETRACTED')))
        """, nativeQuery = true)
    boolean hasWriteFence(@Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    /** The pieces among {@code feedbackIds} with a write fence: {@link #hasWriteFence} for many. */
    @Query(value = """
        SELECT DISTINCT feedback_id FROM feedback_copy WHERE workspace_id = :workspaceId
         AND feedback_id IN (:feedbackIds) AND (write_started_at IS NOT NULL OR posted_at IS NOT NULL
           OR external_ref IS NOT NULL OR state IN ('WRITE_STARTED','POSTED','RETRACTED'))
        """, nativeQuery = true)
    List<UUID> writeFencedFeedbackIds(
            @Param("workspaceId") long workspaceId, @Param("feedbackIds") Collection<UUID> feedbackIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET state = 'WRITE_STARTED', write_started_at = COALESCE(write_started_at,:startedAt), stop_reason='DELIVERY_UNCONFIRMED',last_error=NULL,readback='UNKNOWN'
         WHERE workspace_id = :workspaceId AND feedback_id = :feedbackId AND id = :copyId
           AND state = 'PLANNED' AND (write_started_at IS NULL OR readback = 'ABSENT')
           AND external_ref IS NULL AND posted_at IS NULL AND write_key IS NOT NULL
        """, nativeQuery = true)
    int beginWrite(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("copyId") UUID copyId,
            @Param("startedAt") Instant startedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET state = 'POSTED', posted_at = :postedAt,
            external_ref = :externalRef, external_url = :externalUrl, kind = :kind, thread_ref = :threadRef,
            stop_reason = NULL, last_error = NULL, readback=NULL
         WHERE workspace_id = :workspaceId AND feedback_id = :feedbackId AND id = :copyId
           AND state = 'WRITE_STARTED' AND write_started_at IS NOT NULL
           AND external_ref IS NULL AND posted_at IS NULL
        """, nativeQuery = true)
    int confirmPosted(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("copyId") UUID copyId,
            @Param("postedAt") Instant postedAt,
            @Param("externalRef") @Nullable String externalRef,
            @Param("externalUrl") @Nullable String externalUrl,
            @Param("kind") String kind,
            @Param("threadRef") @Nullable String threadRef);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET state = 'FAILED', stop_reason = NULL, last_error = :error, readback='ABSENT'
         WHERE workspace_id = :workspaceId AND feedback_id = :feedbackId AND id = :copyId
           AND state = 'WRITE_STARTED' AND external_ref IS NULL AND posted_at IS NULL
        """, nativeQuery = true)
    int recordAbsent(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("copyId") UUID copyId,
            @Param("error") String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET state='WITHHELD',stop_reason=:reason
        WHERE workspace_id=:workspaceId AND feedback_id=:feedbackId AND state='PLANNED' AND (write_started_at IS NULL OR readback = 'ABSENT')
        """, nativeQuery = true)
    int withholdPlanned(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy c SET state='WITHHELD',stop_reason='RESULT_MARKED_INCORRECT'
        WHERE c.workspace_id=:workspaceId AND c.feedback_id=:feedbackId AND c.state='PLANNED'
          AND (c.write_started_at IS NULL OR c.readback='ABSENT') AND""" + RESTS_ON_INCORRECT_RESULT, nativeQuery = true)
    int withholdIncorrect(@Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    /** Restores the unsent copies that a mark withheld. A copy that still rests on another mark stays withheld. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy c SET state='PLANNED',stop_reason=NULL,last_error=NULL
        WHERE c.workspace_id=:workspaceId AND c.feedback_id=:feedbackId AND c.state='WITHHELD'
          AND c.stop_reason='RESULT_MARKED_INCORRECT' AND (c.write_started_at IS NULL OR c.readback='ABSENT')
          AND c.posted_at IS NULL AND c.external_ref IS NULL AND c.mentor_turn_id IS NULL AND NOT""" + RESTS_ON_INCORRECT_RESULT, nativeQuery = true)
    int restoreUnsent(@Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET state='PLANNED' WHERE workspace_id=:workspaceId AND feedback_id=:feedbackId
          AND state='FAILED' AND external_ref IS NULL AND posted_at IS NULL
          AND (write_started_at IS NULL OR readback = 'ABSENT')
        """, nativeQuery = true)
    int retryAbsent(@Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET last_error=:error,stop_reason=:reason,readback=:readback
        WHERE workspace_id=:workspaceId AND feedback_id=:feedbackId AND id=:copyId
        """, nativeQuery = true)
    int recordError(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("copyId") UUID copyId,
            @Param("reason") @Nullable String reason,
            @Param("readback") @Nullable String readback,
            @Param("error") @Nullable String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET state=:state,last_error=NULL,stop_reason=NULL,readback=NULL
        WHERE workspace_id=:workspaceId AND feedback_id=:feedbackId AND id=:copyId
          AND state IN ('POSTED','RETRACTED') AND posted_at IS NOT NULL
        """, nativeQuery = true)
    int confirmCorrection(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("copyId") UUID copyId,
            @Param("state") String state);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET state='WITHHELD',stop_reason=:reason WHERE workspace_id=:workspaceId AND feedback_id=:feedbackId
          AND id=:copyId AND state='PLANNED' AND (write_started_at IS NULL OR readback = 'ABSENT')
        """, nativeQuery = true)
    int withholdCopy(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("copyId") UUID copyId,
            @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET state='FAILED',readback='ABSENT',last_error='No provider request was admitted.'
        WHERE workspace_id=:workspaceId AND feedback_id=:feedbackId AND state='PLANNED' AND (write_started_at IS NULL OR readback = 'ABSENT')
        """, nativeQuery = true)
    int failUnstarted(@Param("workspaceId") long workspaceId, @Param("feedbackId") UUID feedbackId);

    /**
     * When the running attempt of a Heph turn of {@code developerId} started, or empty when the turn is not running
     * with that attempt. Shares the turn row, so a claim waits for an end that holds it and then sees the turn ended:
     * an ended attempt claims nothing.
     */
    @Query(value = """
        SELECT t.started_at FROM mentor_turn t
        JOIN mentor_conversation mc ON mc.id=t.conversation_id AND mc.workspace_id=t.workspace_id
        WHERE t.id=:turnId AND t.workspace_id=:workspaceId AND mc.account_id=:developerId
          AND t.status='RUNNING' AND t.attempt=:attempt AND t.started_at IS NOT NULL
        FOR SHARE OF t
        """, nativeQuery = true)
    Optional<Instant> runningAttemptStartedAt(
            @Param("workspaceId") long workspaceId,
            @Param("developerId") long developerId,
            @Param("turnId") UUID turnId,
            @Param("attempt") int attempt);

    /**
     * Claims the conversation copy for a Heph turn. The database clock stamps the claim, as it stamps the start of the
     * turn's attempt, so a claim compares with the attempt that made it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET state='WRITE_STARTED',write_started_at=now(),mentor_turn_id=:turnId,
          stop_reason='DELIVERY_UNCONFIRMED',last_error=NULL,readback='UNKNOWN'
        WHERE workspace_id=:workspaceId AND feedback_id=:feedbackId AND id=:copyId AND kind='CHAT_TURN'
          AND state='PLANNED' AND write_started_at IS NULL AND posted_at IS NULL AND external_ref IS NULL
          AND mentor_turn_id IS NULL AND mentor_entry_id IS NULL AND body IS NULL AND write_key IS NOT NULL
        """, nativeQuery = true)
    int claimConversation(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("copyId") UUID copyId,
            @Param("turnId") UUID turnId);

    /**
     * The words a completed Heph answer showed for one piece of feedback, by the database function
     * {@code conversation_receipt_words}: the one rule that the receipt guard on this table applies too.
     */
    @Query(
            value = "SELECT conversation_receipt_words(:workspaceId, :feedbackId, :entryId, :turnId)",
            nativeQuery = true)
    Optional<String> completedConversationText(
            @Param("workspaceId") long workspaceId,
            @Param("turnId") UUID turnId,
            @Param("entryId") UUID entryId,
            @Param("feedbackId") UUID feedbackId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET state='POSTED',posted_at=:at,body=:body,mentor_entry_id=:entryId,
          stop_reason=NULL,last_error=NULL,readback=NULL
        WHERE workspace_id=:workspaceId AND feedback_id=:feedbackId AND id=:copyId AND kind='CHAT_TURN'
          AND mentor_turn_id=:turnId AND write_started_at IS NOT NULL AND posted_at IS NULL
          AND state IN ('WRITE_STARTED','FAILED') AND body IS NULL
        """, nativeQuery = true)
    int recordConversationDelivered(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("copyId") UUID copyId,
            @Param("turnId") UUID turnId,
            @Param("entryId") UUID entryId,
            @Param("body") String body,
            @Param("at") Instant at);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE feedback_copy SET state=:state,stop_reason=:reason,last_error=:detail,readback=:readback,
          mentor_entry_id=COALESCE(CAST(:entryId AS uuid),mentor_entry_id)
        WHERE workspace_id=:workspaceId AND feedback_id=:feedbackId AND id=:copyId AND kind='CHAT_TURN'
          AND mentor_turn_id=:turnId AND write_started_at IS NOT NULL AND posted_at IS NULL
          AND state IN ('WRITE_STARTED','FAILED')
        """, nativeQuery = true)
    int recordConversationOutcome(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("copyId") UUID copyId,
            @Param("turnId") UUID turnId,
            @Param("entryId") @Nullable UUID entryId,
            @Param("state") String state,
            @Param("reason") @Nullable String reason,
            @Param("readback") String readback,
            @Param("detail") @Nullable String detail);

    /**
     * The conversation copies a Heph turn claimed from {@code since} to {@code until} that have no receipt yet. A
     * receipt sets
     * {@code posted_at}, the state FAILED, or for an unconfirmed write its reason in {@code last_error}.
     */
    @Query(value = """
        SELECT c.* FROM feedback_copy c
        WHERE c.workspace_id=:workspaceId AND c.mentor_turn_id=:turnId AND c.kind='CHAT_TURN'
          AND c.posted_at IS NULL AND c.state='WRITE_STARTED' AND c.last_error IS NULL
          AND c.write_started_at>=:since AND c.write_started_at<=:until
        ORDER BY c.write_started_at, c.id
        """, nativeQuery = true)
    List<FeedbackCopy> findUnsettledConversationClaims(
            @Param("workspaceId") long workspaceId,
            @Param("turnId") UUID turnId,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query(value = """
        SELECT EXISTS(SELECT 1 FROM feedback_copy newer JOIN feedback latest ON latest.id=newer.feedback_id AND latest.workspace_id=newer.workspace_id
          JOIN feedback original ON original.id=:feedbackId AND original.workspace_id=:workspaceId
          WHERE newer.workspace_id=:workspaceId AND newer.feedback_id<>original.id
            AND newer.external_ref=:externalRef AND newer.posted_at IS NOT NULL AND latest.item_id=original.item_id
            AND (latest.created_at>original.created_at OR (latest.created_at=original.created_at AND latest.id::text>original.id::text)))
        """, nativeQuery = true)
    boolean hasNewerSummaryReceipt(
            @Param("workspaceId") long workspaceId,
            @Param("feedbackId") UUID feedbackId,
            @Param("externalRef") String externalRef);
}

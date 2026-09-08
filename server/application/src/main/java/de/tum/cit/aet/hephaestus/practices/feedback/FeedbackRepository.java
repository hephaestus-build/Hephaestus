package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@WorkspaceAgnostic("Feedback is scoped by a raw workspace_id scalar (cross-module FK), not a Workspace association")
public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Feedback f WHERE f.id = :id AND f.workspaceId = :workspaceId")
    Optional<Feedback> lockByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            value = "UPDATE feedback SET delivery_state = :targetState WHERE id = :id AND workspace_id = :workspaceId "
                    + "AND delivery_state = 'AWAITING_APPROVAL'",
            nativeQuery = true)
    int decideProposal(
            @Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("targetState") String targetState);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE feedback SET delivery_state = 'SUPPRESSED', suppression_reason = :reason "
                    + "WHERE id = :id AND workspace_id = :workspaceId AND delivery_state = 'AWAITING_APPROVAL'",
            nativeQuery = true)
    int suppressProposal(@Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("reason") String reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            value =
                    "UPDATE feedback SET delivery_state = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP, suppression_reason = NULL "
                            + "WHERE id = :id AND workspace_id = :workspaceId AND delivery_state IN ('PREPARED', 'PARTIALLY_DELIVERED')",
            nativeQuery = true)
    int markApprovedDelivered(@Param("workspaceId") Long workspaceId, @Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            value =
                    "UPDATE feedback SET delivery_state = 'PARTIALLY_DELIVERED', suppression_reason = :reason "
                            + "WHERE id = :id AND workspace_id = :workspaceId AND delivery_state IN ('PREPARED', 'PARTIALLY_DELIVERED')",
            nativeQuery = true)
    int markApprovedPartiallyDelivered(
            @Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("reason") @Nullable String reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            value = "UPDATE feedback SET delivery_state = 'SUPPRESSED', suppression_reason = :reason "
                    + "WHERE id = :id AND workspace_id = :workspaceId AND delivery_state = 'PREPARED'",
            nativeQuery = true)
    int markApprovedSuppressed(
            @Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            value =
                    "UPDATE feedback SET delivery_state = 'FAILED', suppression_reason = NULL "
                            + "WHERE id = :id AND workspace_id = :workspaceId AND delivery_state IN ('PREPARED', 'PARTIALLY_DELIVERED')",
            nativeQuery = true)
    int markApprovedFailed(@Param("workspaceId") Long workspaceId, @Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
            value =
                    "UPDATE feedback SET delivery_state = 'PARTIALLY_FAILED', suppression_reason = NULL "
                            + "WHERE id = :id AND workspace_id = :workspaceId AND delivery_state IN ('PREPARED', 'PARTIALLY_DELIVERED')",
            nativeQuery = true)
    int markApprovedPartiallyFailed(@Param("workspaceId") Long workspaceId, @Param("id") UUID id);

    /** Idempotency guard for the ledger recorder: has this job already recorded this unit? */
    boolean existsByAgentJobIdAndPosition(UUID agentJobId, Integer position);

    Optional<Feedback> findByIdAndWorkspaceId(UUID id, Long workspaceId);

    Optional<Feedback> findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
            UUID id, Long workspaceId, Long recipientUserId, FeedbackDeliveryState deliveryState);

    @Query(value = """
        SELECT f.agent_job_id AS "jobId",
               COUNT(*) FILTER (WHERE f.delivery_state = 'PREPARED' OR
                   (f.delivery_state = 'PARTIALLY_DELIVERED' AND f.suppression_reason IS NULL)) AS "prepared",
               COUNT(*) FILTER (WHERE f.delivery_state = 'DELIVERED') AS "delivered",
               COUNT(*) FILTER (WHERE f.delivery_state = 'SUPERSEDED') AS "superseded",
               COUNT(*) FILTER (WHERE f.delivery_state = 'SUPPRESSED' OR
                   (f.delivery_state = 'PARTIALLY_DELIVERED' AND f.suppression_reason IS NOT NULL)) AS "suppressed",
               COUNT(*) FILTER (WHERE f.delivery_state IN ('FAILED', 'PARTIALLY_FAILED')) AS "failed"
        FROM feedback f
        WHERE f.workspace_id = :workspaceId
          AND f.agent_job_id IN :jobIds
        GROUP BY f.agent_job_id
        """, nativeQuery = true)
    List<ReviewFeedbackCounts> summarizeReviewFeedback(
            @Param("workspaceId") Long workspaceId, @Param("jobIds") Collection<UUID> jobIds);

    interface ReviewFeedbackCounts {
        UUID getJobId();

        Long getPrepared();

        Long getDelivered();

        Long getSuperseded();

        Long getSuppressed();

        Long getFailed();
    }

    /** Delivered summary and inline-only feedback for a recipient, newest first. */
    @Query("""
        SELECT f FROM Feedback f
        WHERE f.workspaceId = :workspaceId
          AND f.recipientUserId = :recipientUserId
          AND f.deliveryState = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.DELIVERED
          AND f.createdAt >= :since
        ORDER BY f.createdAt DESC
        """)
    List<Feedback> findRecentDeliveredForRecipient(
            @Param("workspaceId") Long workspaceId,
            @Param("recipientUserId") Long recipientUserId,
            @Param("since") Instant since,
            Pageable pageable);

    /**
     * Everything already composed for a recipient that they have not received yet — every lane, newest
     * first.
     *
     * <p>Deliberately not filtered by {@code createdAt}: a queued message is queued however long ago it
     * was written, and a window that hid the old ones would let composition write a second message about
     * a habit whose first message is still waiting to be read.
     */
    @Query("""
        SELECT f FROM Feedback f
        WHERE f.workspaceId = :workspaceId
          AND f.recipientUserId = :recipientUserId
          AND f.deliveryState = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.PREPARED
        ORDER BY f.createdAt DESC
        """)
    List<Feedback> findPreparedForRecipient(
            @Param("workspaceId") Long workspaceId, @Param("recipientUserId") Long recipientUserId, Pageable pageable);

    /**
     * Marks a prior DELIVERED summary superseded when a new one replaces it; inline-only deliveries stay
     * DELIVERED on the same thread. The state predicate makes concurrent retries idempotent.
     */
    @Modifying
    @Transactional
    @Query(
            value = "UPDATE feedback SET delivery_state = :state WHERE id = :id AND delivery_state = 'DELIVERED'",
            nativeQuery = true)
    int updateState(@Param("id") UUID id, @Param("state") String state);

    /**
     * Finds the newest row regardless of delivery state so callers can distinguish a delivered row
     * from one already superseded. Thread keys group related feedback, not unique PREPARED items.
     * Recipient and workspace predicates enforce isolation independently of the key's hash.
     */
    @Query(value = """
        SELECT f.id FROM feedback f
        WHERE f.workspace_id = :workspaceId
          AND f.recipient_user_id = :recipientUserId
          AND f.channel = :channel
          AND f.thread_key = :threadKey
        ORDER BY f.created_at DESC, f.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<UUID> findLatestOnThread(
            @Param("workspaceId") Long workspaceId,
            @Param("recipientUserId") Long recipientUserId,
            @Param("channel") String channel,
            @Param("threadKey") String threadKey);

    /**
     * Atomically retires a selected PREPARED row without overwriting a concurrent delivery.
     * Native because {@link Feedback} is {@code @Immutable}.
     *
     * @return 1 if retired, 0 if absent or no longer PREPARED
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(
            value = "UPDATE feedback SET delivery_state = 'SUPERSEDED' "
                    + "WHERE id = :id AND workspace_id = :workspaceId AND delivery_state = 'PREPARED'",
            nativeQuery = true)
    int markSuperseded(@Param("workspaceId") Long workspaceId, @Param("id") UUID id);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(
            value = "UPDATE feedback SET delivery_state = 'SUPERSEDED' "
                    + "WHERE workspace_id = :workspaceId AND thread_key = :threadKey AND id <> :keepId "
                    + "AND delivery_state = 'AWAITING_APPROVAL'",
            nativeQuery = true)
    int supersedeUndecidedProposals(
            @Param("workspaceId") Long workspaceId, @Param("threadKey") String threadKey, @Param("keepId") UUID keepId);

    /**
     * Whether a unit has been received. Asked only after a supersession compare-and-set matched nothing,
     * to tell "the recipient read it first" — a thread that continues, so the replacement still points
     * back at what it follows — from "the thread already moved on", where pointing back would fork the
     * chain onto a row some other run has already claimed.
     *
     * <p>Native and by primary key so it reads the row rather than the persistence context, which still
     * holds the pre-CAS state.
     */
    @Query(
            value = "SELECT EXISTS (SELECT 1 FROM feedback f "
                    + "WHERE f.id = :id AND f.workspace_id = :workspaceId AND f.delivery_state = 'DELIVERED')",
            nativeQuery = true)
    boolean isDelivered(@Param("workspaceId") Long workspaceId, @Param("id") UUID id);

    /**
     * The practice each of these units is about, read off its headline observation.
     *
     * <p>Staged onto {@code prepared.json} so a composer choosing to replace a queued message can tell
     * <em>which habit</em> each queued message is about. Without it the thread keys on that file are
     * opaque digests and the composer would be picking one blind — and on the conversation lane, whose
     * body is composed at the turn, there is not even a body to guess from.
     *
     * <p>Both sides of the join carry {@code workspaceId}: the unit's own scalar and the practice behind
     * the observation. One predicate is the tenancy boundary for one table, and this query spans two.
     */
    @Query("""
        SELECT fo.feedback.id AS feedbackId, fo.observation.practice.slug AS practiceSlug
        FROM FeedbackObservation fo
        WHERE fo.feedback.id IN :feedbackIds
          AND fo.feedback.workspaceId = :workspaceId
          AND fo.observation.practice.workspace.id = :workspaceId
          AND fo.role = de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole.PRIMARY
          AND fo.ordinal = 0
        """)
    List<HeadlinePracticeRow> findHeadlinePractices(
            @Param("workspaceId") Long workspaceId, @Param("feedbackIds") Collection<UUID> feedbackIds);

    interface HeadlinePracticeRow {
        UUID getFeedbackId();

        String getPracticeSlug();
    }

    /**
     * A workspace purge soft-deletes rather than dropping rows, so it never fires the RESTRICT FK on
     * {@code feedback}; without this, feedback and its CASCADE children would persist indefinitely.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM Feedback f WHERE f.workspaceId = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);

    // Each erasure method below hard-deletes feedback for one workspace + artifact-kind scope. DB
    // ON DELETE CASCADE clears feedback_observation/feedback_placement/feedback_reaction; the predicates keep
    // every other tenant's and kind's rows untouched. Bulk JPQL delete, since the @Immutable entity forbids
    // an ORM remove.

    /**
     * Erases {@code chat.conversation_thread} feedback by {@code artifact_id}, invoked through
     * {@link de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure} when a channel's consent is
     * withdrawn.
     *
     * @return the number of pieces of feedback deleted
     */
    @Modifying
    @Transactional
    @Query("""
        DELETE FROM Feedback f
        WHERE f.workspaceId = :workspaceId
          AND f.artifactKind = :artifactKind
          AND f.artifactId IN :artifactIds
        """)
    int deleteFeedbackOfKind(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactKind") ArtifactKind artifactKind,
            @Param("artifactIds") Collection<Long> artifactIds);

    default int deleteConversationThreadFeedback(Long workspaceId, Collection<Long> artifactIds) {
        return deleteFeedbackOfKind(workspaceId, ArtifactKinds.CONVERSATION_THREAD, artifactIds);
    }

    /**
     * Erases every {@code chat.conversation_thread} piece of feedback for a workspace, invoked on app-uninstall /
     * workspace-purge.
     *
     * @return the number of pieces of feedback deleted
     */
    @Modifying
    @Transactional
    @Query("""
        DELETE FROM Feedback f
        WHERE f.workspaceId = :workspaceId
          AND f.artifactKind IN :artifactKinds
        """)
    int deleteAllFeedbackOfKinds(
            @Param("workspaceId") Long workspaceId, @Param("artifactKinds") Collection<ArtifactKind> artifactKinds);

    default int deleteAllConversationThreadFeedback(Long workspaceId) {
        return deleteAllFeedbackOfKinds(workspaceId, List.of(ArtifactKinds.CONVERSATION_THREAD));
    }

    /**
     * Erases every {@code scm.pull_request} / {@code scm.issue} piece of feedback for a workspace, invoked when
     * the SCM mirror is erased. These units hold mirrored third-party content directly, so they neither
     * cascade with the repository delete nor survive it meaningfully.
     *
     * @return the number of pieces of feedback deleted
     */
    @Modifying
    @Transactional
    @Query("""
        DELETE FROM Feedback f
        WHERE f.workspaceId = :workspaceId
          AND f.artifactKind IN :artifactKinds
        """)
    int deleteAllScmFeedbackOfKinds(
            @Param("workspaceId") Long workspaceId, @Param("artifactKinds") Collection<ArtifactKind> artifactKinds);

    default int deleteAllScmArtifactFeedback(Long workspaceId) {
        return deleteAllScmFeedbackOfKinds(workspaceId, List.of(ArtifactKinds.PULL_REQUEST, ArtifactKinds.ISSUE));
    }

    /**
     * Erases the {@code chat.conversation_thread} feedback a person is the subject of ({@code about_user_id}),
     * invoked through
     * {@link de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure#eraseConversationFeedbackAboutUser}
     * for a person opt-out / account hard-delete.
     *
     * @return the number of pieces of feedback deleted
     */
    @Modifying
    @Transactional
    @Query("""
        DELETE FROM Feedback f
        WHERE f.workspaceId = :workspaceId
          AND f.artifactKind = :artifactKind
          AND f.aboutUserId = :aboutUserId
        """)
    int deleteFeedbackOfKindAboutUser(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactKind") ArtifactKind artifactKind,
            @Param("aboutUserId") Long aboutUserId);

    default int deleteConversationThreadFeedbackAboutUser(Long workspaceId, Long aboutUserId) {
        return deleteFeedbackOfKindAboutUser(workspaceId, ArtifactKinds.CONVERSATION_THREAD, aboutUserId);
    }

    // --- conversational feedback delivery loop ---

    /**
     * Flips a PREPARED conversational unit to DELIVERED (compare-and-set): the {@code delivery_state='PREPARED'}
     * predicate lets exactly one of N racing mentor turns win the flip, the rest see rowcount 0.
     *
     * @return {@code 1} on a clean flip, {@code 0} if the unit was no longer PREPARED
     */
    @Modifying
    @Transactional
    @Query(
            value = "UPDATE feedback SET delivery_state = 'DELIVERED', delivered_at = :at "
                    + "WHERE id = :id AND delivery_state = 'PREPARED'",
            nativeQuery = true)
    int markConversationDelivered(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Flips a PREPARED conversational unit to SUPPRESSED when instance Silent Mode blocked its transport
     * attempt; the state predicate avoids overwriting a unit another transaction already delivered.
     */
    @Modifying
    @Transactional
    @Query(
            value = "UPDATE feedback SET delivery_state = 'SUPPRESSED', suppression_reason = 'INSTANCE_SILENCED' "
                    + "WHERE id = :id AND delivery_state = 'PREPARED'",
            nativeQuery = true)
    int markConversationSuppressedBySilentMode(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query(
            value = "UPDATE feedback SET delivery_state = 'SUPPRESSED', suppression_reason = :reason "
                    + "WHERE id = :id AND workspace_id = :workspaceId AND delivery_state = 'PREPARED'",
            nativeQuery = true)
    int markPreparedSuppressed(
            @Param("id") UUID id, @Param("workspaceId") Long workspaceId, @Param("reason") String reason);

    /**
     * Newest PREPARED conversational units for a developer (as recipient) — the mentor's queue. The body on
     * these rows is never the mentor's script: it is null, or the composer's notes to the mentor
     * ({@link ConversationBriefBody}), and the words of the turn are composed at delivery either way.
     */
    @Query("""
        SELECT f FROM Feedback f
        WHERE f.workspaceId = :workspaceId
          AND f.recipientUserId = :recipientUserId
          AND f.channel = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel.IN_CHAT
          AND f.deliveryState = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.PREPARED
        ORDER BY f.createdAt DESC
        """)
    List<Feedback> findRecentPreparedConversationForRecipient(
            @Param("workspaceId") Long workspaceId, @Param("recipientUserId") Long recipientUserId, Pageable pageable);

    /**
     * Whether a DELIVERED IN_CONTEXT unit already exists for this recipient bound to an observation carrying
     * {@code recurrenceKey}, so the router can avoid re-raising a locus already received inline.
     */
    @Query("""
        SELECT (COUNT(f) > 0) FROM Feedback f, FeedbackObservation fo
        WHERE fo.feedback = f
          AND fo.observation.recurrenceKey = :recurrenceKey
          AND f.workspaceId = :workspaceId
          AND f.recipientUserId = :recipientUserId
          AND f.channel = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel.IN_CONTEXT
          AND f.deliveryState = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.DELIVERED
        """)
    boolean existsDeliveredInContextForRecurrenceKey(
            @Param("workspaceId") Long workspaceId,
            @Param("recipientUserId") Long recipientUserId,
            @Param("recurrenceKey") String recurrenceKey);

    /** Distinct workspaces holding at least one PREPARED conversational unit (TTL sweep enumeration). */
    @Query("""
        SELECT DISTINCT f.workspaceId FROM Feedback f
        WHERE f.channel = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel.IN_CHAT
          AND f.deliveryState = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.PREPARED
        """)
    List<Long> findWorkspaceIdsWithPreparedConversation();

    /**
     * Ages out every PREPARED conversational unit created before {@code cutoff} to SUPPRESSED /
     * CONVERSATION_EXPIRED (native, since the {@code @Immutable} entity forbids an ORM update).
     *
     * @return the number of units expired
     */
    @Modifying
    @Transactional
    @Query(
            value = "UPDATE feedback SET delivery_state = 'SUPPRESSED', suppression_reason = 'CONVERSATION_EXPIRED' "
                    + "WHERE workspace_id = :workspaceId AND channel = 'IN_CHAT' "
                    + "AND delivery_state = 'PREPARED' AND created_at < :cutoff",
            nativeQuery = true)
    int expirePreparedConversationBefore(@Param("workspaceId") Long workspaceId, @Param("cutoff") Instant cutoff);

    int BODY_PREVIEW_LENGTH = 320;

    String OPERATOR_PREDICATES = """
          AND (CAST(:#{#f.deliveryStateNames()} AS text[]) IS NULL OR f.delivery_state = ANY(CAST(:#{#f.deliveryStateNames()} AS text[])))
          AND (CAST(:#{#f.suppressionReasonNames()} AS text[]) IS NULL OR f.suppression_reason = ANY(CAST(:#{#f.suppressionReasonNames()} AS text[])))
          AND (CAST(:#{#f.channelNames()} AS text[]) IS NULL OR f.channel = ANY(CAST(:#{#f.channelNames()} AS text[])))
          AND (CAST(:#{#f.agentJobId()} AS uuid) IS NULL OR f.agent_job_id = CAST(:#{#f.agentJobId()} AS uuid))
          AND (CAST(:#{#f.artifactKindValue()} AS text) IS NULL OR f.artifact_kind = CAST(:#{#f.artifactKindValue()} AS text))
          AND (CAST(:#{#f.artifactId()} AS bigint) IS NULL OR f.artifact_id = CAST(:#{#f.artifactId()} AS bigint))
          AND (CAST(:#{#f.recipientUserId()} AS bigint) IS NULL OR f.recipient_user_id = CAST(:#{#f.recipientUserId()} AS bigint))
          AND (CAST(:#{#f.from()} AS timestamptz) IS NULL OR f.created_at >= CAST(:#{#f.from()} AS timestamptz))
          AND (CAST(:#{#f.to()} AS timestamptz) IS NULL OR f.created_at < CAST(:#{#f.to()} AS timestamptz))
        """;

    /**
     * The operator's page of feedback.
     *
     * <p><b>IN_APP bodies are never returned here.</b> {@code IN_CONTEXT} bodies are already public on
     * the pull request and {@code IN_CHAT} bodies are NULL by construction, so until now "operators
     * can read feedback bodies" exposed nothing private. A {@code IN_APP} body is the first
     * system-authored text about a named person that lives nowhere else — and in the course deployment
     * the workspace admin is the instructor. {@link FeedbackChannel}'s own contract says every channel is
     * developer-facing, "never to a mentor, instructor, or grader"; handing this one to an admin would
     * make that statement false.
     *
     * <p>Operators still see that the unit exists, its channel, its state, its suppression reason, its
     * recipient and how many observations fed it — everything needed to audit whether the pipeline
     * behaved. They do not see what it said. Withheld in the projection rather than in a mapper so a
     * second caller cannot forget, and mirrored in {@code ReviewFeedbackQueryService#get} for the detail
     * route. This is the reversible direction: opening it up later is a decision somebody can take;
     * un-publishing a body an instructor has already read is not a decision at all.
     */
    @Query(
            value = "SELECT f.id AS \"id\"," + " f.agent_job_id AS \"agentJobId\","
                    + " f.artifact_kind AS \"artifactKind\","
                    + " f.artifact_id AS \"artifactId\","
                    + " f.recipient_user_id AS \"recipientUserId\","
                    + " f.about_user_id AS \"aboutUserId\","
                    + " f.channel AS \"channel\","
                    + " f.delivery_state AS \"deliveryState\","
                    + " f.suppression_reason AS \"suppressionReason\","
                    + " f.replaces_id AS \"replacesId\","
                    + " f.created_at AS \"createdAt\","
                    + " f.delivered_at AS \"deliveredAt\","
                    +
                    // IN_APP and IN_CHAT bodies are withheld from the operator surface in SQL rather than in
                    // a mapper, so a second projection cannot forget. One is the developer's private page, the other
                    // is the mentor's unspoken coaching move about them; see
                    // ReviewFeedbackQueryService#bodyVisibleToOperator. Executed against a real database by
                    // PracticeReviewOutputControllerIntegrationTest#withholdsAnInAppBodyFromEveryOperatorRoute.
                    " CASE WHEN f.channel IN ('IN_APP', 'IN_CHAT') THEN NULL ELSE left(f.body, "
                    + BODY_PREVIEW_LENGTH
                    + ") END AS \"bodyPreview\","
                    + " (f.channel NOT IN ('IN_APP', 'IN_CHAT') AND f.body IS NOT NULL AND length(f.body) > "
                    + BODY_PREVIEW_LENGTH
                    + ") AS \"bodyTruncated\","
                    + " (SELECT count(*) FROM feedback_observation fo"
                    + " JOIN observation o ON o.id = fo.observation_id"
                    + " JOIN practice p ON p.id = o.practice_id"
                    + " WHERE fo.feedback_id = f.id AND p.workspace_id = f.workspace_id) AS \"observationCount\""
                    + " FROM feedback f WHERE f.workspace_id = :workspaceId"
                    + OPERATOR_PREDICATES
                    + " ORDER BY f.created_at DESC, f.id DESC",
            countQuery = "SELECT count(*) FROM feedback f WHERE f.workspace_id = :workspaceId" + OPERATOR_PREDICATES,
            nativeQuery = true)
    Page<OperatorFeedbackRow> findForWorkspace(
            @Param("workspaceId") Long workspaceId, @Param("f") FeedbackQueryFilter filter, Pageable pageable);

    interface OperatorFeedbackRow {
        UUID getId();

        UUID getAgentJobId();

        /** The raw column: a native-query projection is mapped from JDBC types, with no converter run. */
        @Nullable
        String getArtifactKind();

        Long getArtifactId();

        Long getRecipientUserId();

        Long getAboutUserId();

        FeedbackChannel getChannel();

        FeedbackDeliveryState getDeliveryState();

        @Nullable
        FeedbackSuppressionReason getSuppressionReason();

        @Nullable
        UUID getReplacesId();

        Instant getCreatedAt();

        @Nullable
        Instant getDeliveredAt();

        @Nullable
        String getBodyPreview();

        Boolean getBodyTruncated();

        Long getObservationCount();
    }

    /**
     * How much of what was measured on one artifact actually reached a person, by practice.
     * {@code COUNT(DISTINCT f.id)} because one piece of feedback routinely fuses several observations of the
     * same practice; counting join rows would multiply it.
     */
    @Query("""
        SELECT o.practice.id AS practiceId, f.deliveryState AS deliveryState,
               f.suppressionReason AS suppressionReason, COUNT(DISTINCT f.id) AS units
        FROM FeedbackObservation fo JOIN fo.feedback f JOIN fo.observation o
        WHERE f.workspaceId = :workspaceId
          AND o.artifactKind = :artifactKind
          AND o.artifactId = :artifactId
        GROUP BY o.practice.id, f.deliveryState, f.suppressionReason
        """)
    List<ArtifactFeedbackRow> summarizeForArtifact(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactKind") ArtifactKind artifactKind,
            @Param("artifactId") Long artifactId);

    interface ArtifactFeedbackRow {
        Long getPracticeId();

        FeedbackDeliveryState getDeliveryState();

        @Nullable
        FeedbackSuppressionReason getSuppressionReason();

        long getUnits();
    }

    // --- the in-app lane ---
    //
    // Every query below carries `workspace_id` by hand. `feedback` is scoped by a raw scalar with no
    // Hibernate tenancy filter (see the @WorkspaceAgnostic reason on this interface), so on this table
    // the predicate IS the tenancy boundary — and these rows are the first genuinely private,
    // system-authored text about a named person, so a missing one leaks more than a count.

    /**
     * What the recipient may read on their own practice pages: their IN_APP units that were prepared or
     * already read, newest first. Suppressed and superseded rows are excluded — the operator surface is
     * where "we withheld this, and here is why" is answered; the developer's own surface shows what was
     * actually said to them.
     */
    @Query("""
        SELECT f FROM Feedback f
        WHERE f.workspaceId = :workspaceId
          AND f.recipientUserId = :recipientUserId
          AND f.channel = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel.IN_APP
          AND f.deliveryState IN (
              de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.PREPARED,
              de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.DELIVERED
          )
        ORDER BY f.createdAt DESC, f.id DESC
        """)
    List<Feedback> findReadableInAppForRecipient(
            @Param("workspaceId") Long workspaceId, @Param("recipientUserId") Long recipientUserId, Pageable pageable);

    /**
     * Flips a PREPARED in-app unit to DELIVERED at the moment its recipient actually reads it
     * (compare-and-set, so two concurrent page loads cannot both claim the flip and the second sees
     * rowcount 0).
     *
     * <p>This lane is the only one where "delivered" is a fact we can observe rather than infer: we own
     * the surface. Recording it at write time instead would enter text nobody opened into the ledger as
     * received, which would quietly corrupt the one delivery measurement the system can make honestly.
     *
     * <p>Native because {@link Feedback} is {@code @Immutable} — the ORM cannot update it.
     *
     * @return {@code 1} on a clean flip, {@code 0} if the unit was no longer PREPARED
     */
    @Modifying
    @Transactional
    @Query(
            value =
                    "UPDATE feedback SET delivery_state = 'DELIVERED', delivered_at = :at "
                            + "WHERE id = :id AND workspace_id = :workspaceId AND channel = 'IN_APP' AND delivery_state = 'PREPARED'",
            nativeQuery = true)
    int markInAppDelivered(@Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("at") Instant at);

    /**
     * When an IN_APP unit about this practice was last written for this recipient, whatever became of it
     * — the cooldown that stops one habit being restated on every pull request.
     *
     * <p>Deliberately unfiltered by delivery state: the question is when we last said this, and a unit
     * that was written and then superseded still said it.
     */
    @Query("""
        SELECT MAX(f.createdAt) FROM Feedback f, FeedbackObservation fo
        WHERE fo.feedback = f
          AND fo.observation.practice.slug = :practiceSlug
          AND fo.observation.practice.workspace.id = :workspaceId
          AND f.workspaceId = :workspaceId
          AND f.recipientUserId = :recipientUserId
          AND f.channel = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel.IN_APP
        """)
    Optional<Instant> lastInAppSurfacedAt(
            @Param("workspaceId") Long workspaceId,
            @Param("recipientUserId") Long recipientUserId,
            @Param("practiceSlug") String practiceSlug);
}

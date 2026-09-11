package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@WorkspaceAgnostic("FeedbackObservation is a join row scoped through its parent Feedback's workspace_id, not its own")
public interface FeedbackObservationRepository extends JpaRepository<FeedbackObservation, FeedbackObservation.Id> {
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO feedback_observation (feedback_id, observation_id, role, ordinal)
        VALUES (:feedbackId, :observationId, :evidenceRole, :ordinal)
        ON CONFLICT (feedback_id, observation_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("feedbackId") UUID feedbackId,
            @Param("observationId") UUID observationId,
            @Param("evidenceRole") String evidenceRole,
            @Param("ordinal") int ordinal);

    /**
     * The observations behind a batch of delivered feedback, carrying what decides their visibility.
     *
     * <p>Both revisions are fetch-joined because every caller compares them the moment it has a row:
     * an observation is only shown if the rules it was evaluated under are still the practice's current
     * ones. Fetched rather than joined — a plain join narrows the result and preloads nothing, so the
     * comparison would lazy-load its way through the batch one row at a time.
     */
    @Query("""
        SELECT fo.feedback.id AS feedbackId, observation AS observation
        FROM FeedbackObservation fo
        JOIN fo.observation observation
        JOIN FETCH observation.practice practice
        LEFT JOIN FETCH practice.currentRevision
        LEFT JOIN FETCH observation.practiceRevision
        WHERE fo.feedback.workspaceId = :workspaceId
          AND fo.feedback.id IN :feedbackIds
        """)
    List<FeedbackObservationVisibility> findForVisibility(
            @Param("workspaceId") Long workspaceId, @Param("feedbackIds") Collection<UUID> feedbackIds);

    interface FeedbackObservationVisibility {
        UUID getFeedbackId();

        Observation getObservation();
    }

    /**
     * Observation ids already bound to a SUPPRESSED unit of this job (reaction suppression writes its
     * {@code REACTED_*} units before the DELIVERED unit is recorded). The DELIVERED binding excludes
     * these so a withheld observation is never also counted as delivered.
     */
    @Query(value = """
        SELECT ff.observation_id FROM feedback_observation ff
        JOIN feedback f ON f.id = ff.feedback_id
        WHERE f.agent_job_id = :agentJobId AND f.delivery_state = 'SUPPRESSED'
        """, nativeQuery = true)
    List<UUID> findObservationIdsSuppressedForJob(@Param("agentJobId") UUID agentJobId);

    /**
     * The newest body said to this observation's subject <em>about this observation</em>, per observation,
     * on the lanes the caller names. {@code FAILED} counts: the words were composed and the developer may
     * have seen them on the artifact; only states where nothing was ever said are ineligible.
     *
     * <p><b>{@code channels} is required, and deliberately has no default.</b> A {@link Feedback} body is
     * "what we told them", but three lanes can tell them something and they are not interchangeable: an
     * IN_CONTEXT note and a mentor turn are about the one observation they are bound to, while an IN_APP
     * unit is a cross-artifact message that binds every problem behind it as PRIMARY evidence and is
     * about none of them individually. With no channel predicate this query's newest-first tie-break
     * hands a per-observation surface the process message instead — so the lane a caller means is a fact only
     * the caller holds, and it has to state it.
     *
     * <p>Bound as names rather than as {@link FeedbackChannel} values because this is a native query, where
     * an enum parameter's JDBC mapping is not the string the column stores.
     *
     * <p><b>Scoped by {@code workspace_id}, like every other read here.</b> An observation id is a UUID
     * and looks unguessable, but this join row is written by native SQL with no tenancy check of its own,
     * so the only thing standing between one tenant's private advice and another tenant's detail page is
     * this predicate. Without it the newest-first tie-break decides which tenant's words a reader gets.
     */
    @Query(value = """
        SELECT DISTINCT ON (fo.observation_id)
               fo.observation_id AS observationId,
               f.body AS body
        FROM feedback_observation fo
        JOIN feedback f ON f.id = fo.feedback_id
        WHERE fo.observation_id IN (:observationIds)
          AND f.workspace_id = :workspaceId
          AND f.channel IN (:channels)
          AND f.delivery_state IN ('DELIVERED', 'FAILED')
          AND f.body IS NOT NULL
        ORDER BY fo.observation_id, f.created_at DESC, f.id DESC
        """, nativeQuery = true)
    List<ObservationFeedbackBody> findLatestFeedbackBodiesByObservationIds(
            @Param("workspaceId") Long workspaceId,
            @Param("observationIds") Collection<UUID> observationIds,
            @Param("channels") Collection<String> channels);

    interface ObservationFeedbackBody {
        UUID getObservationId();

        String getBody();
    }

    /** Newest delivered feedback that carried each observation to this developer. */
    @Query(value = """
                SELECT DISTINCT ON (fo.observation_id)
                       fo.observation_id AS "observationId",
                       f.id AS "feedbackId",
                       response.usefulness AS "responseUsefulness",
                       response.action AS "responseResolution",
                       response.explanation AS "responseComment"
                FROM feedback_observation fo
                JOIN feedback f ON f.id = fo.feedback_id
                LEFT JOIN LATERAL (
                    SELECT r.usefulness, r.action, r.explanation
                    FROM reaction r
                    WHERE r.feedback_id = f.id AND r.reactor_user_id = :recipientUserId
                    ORDER BY r.created_at DESC, r.id DESC LIMIT 1
        ) response ON TRUE
        WHERE fo.observation_id IN (:observationIds)
          AND f.workspace_id = :workspaceId
          AND f.recipient_user_id = :recipientUserId
          AND f.delivery_state = 'DELIVERED'
        ORDER BY fo.observation_id, f.delivered_at DESC NULLS LAST, f.created_at DESC, f.id DESC
        """, nativeQuery = true)
    List<DeliveredFeedbackBinding> findDeliveredFeedbackBindings(
            @Param("workspaceId") Long workspaceId,
            @Param("recipientUserId") Long recipientUserId,
            @Param("observationIds") Collection<UUID> observationIds);

    interface DeliveredFeedbackBinding {
        UUID getObservationId();

        UUID getFeedbackId();

        @Nullable
        String getResponseUsefulness();

        @Nullable
        String getResponseResolution();

        @Nullable
        String getResponseComment();
    }

    // --- conversational feedback delivery loop ---

    /**
     * The id(s) of the PREPARED IN_CHAT piece of feedback(s) for this recipient/workspace bound (as PRIMARY) to the
     * given observation. Maps a mentor {@code link_observation} id back to the unit to flip to DELIVERED.
     * Ordered newest-first so a caller can take the first; the reconciler's CAS makes any duplicate flip a no-op.
     */
    @Query("""
        SELECT fo.feedback.id
        FROM FeedbackObservation fo
        WHERE fo.observation.id = :observationId
          AND fo.role = de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole.PRIMARY
          AND fo.feedback.workspaceId = :workspaceId
          AND fo.feedback.recipientUserId = :recipientUserId
          AND fo.feedback.channel = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel.IN_CHAT
          AND fo.feedback.deliveryState = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.PREPARED
        ORDER BY fo.feedback.createdAt DESC
        """)
    List<UUID> findPreparedConversationFeedbackIdsByObservation(
            @Param("workspaceId") Long workspaceId,
            @Param("recipientUserId") Long recipientUserId,
            @Param("observationId") UUID observationId);

    /** Newest prepared conversation facts and optional {@link ConversationBriefBody} bodies for a recipient. */
    @Query("""
        SELECT fo.feedback.id AS feedbackId,
               fo.feedback.agentJobId AS agentJobId,
               o.id AS observationId,
               p.slug AS practiceSlug,
               p.name AS practiceName,
               o.summary AS summary,
               o.evidenceRationale AS evidenceRationale,
               o.severity AS severity,
               fo.feedback.body AS body,
               fo.feedback.artifactKind AS artifactKind,
               fo.feedback.artifactId AS artifactId,
               fo.feedback.createdAt AS preparedAt
        FROM FeedbackObservation fo
        JOIN fo.observation o
        JOIN o.practice p
        WHERE fo.role = de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole.PRIMARY
          AND fo.feedback.workspaceId = :workspaceId
          AND fo.feedback.recipientUserId = :recipientUserId
          AND fo.feedback.channel = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel.IN_CHAT
          AND fo.feedback.deliveryState = de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState.PREPARED
        ORDER BY fo.feedback.createdAt DESC
        """)
    List<PreparedConversationFact> findPreparedConversationFactsForRecipient(
            @Param("workspaceId") Long workspaceId, @Param("recipientUserId") Long recipientUserId, Pageable pageable);

    @Query("""
        SELECT fo.observation.id AS observationId, fo.role AS role, fo.ordinal AS ordinal,
               p.slug AS practiceSlug, p.name AS practiceName, o.summary AS summary,
               pa.slug AS groupSlug, pa.name AS groupName, pa.icon AS groupIcon, pa.color AS groupColor,
               o.assessmentStatus AS assessmentStatus, o.presence AS presence, o.assessment AS assessment, o.severity AS severity,
               evaluatedRevision.id AS practiceRevisionId,
               evaluatedRevision.reviewRuleFingerprint AS practiceRevisionFingerprint,
               currentRevision.reviewRuleFingerprint AS currentPracticeRevisionFingerprint,
               o.observedAt AS observedAt
        FROM FeedbackObservation fo
        JOIN fo.observation o
        JOIN o.practice p
        LEFT JOIN o.practiceRevision evaluatedRevision
        LEFT JOIN p.currentRevision currentRevision
        LEFT JOIN p.group pa
        WHERE fo.feedback.id = :feedbackId
          AND fo.feedback.workspaceId = :workspaceId
          AND p.workspace.id = :workspaceId
        ORDER BY fo.ordinal ASC
        """)
    List<BoundObservation> findBoundObservations(
            @Param("workspaceId") Long workspaceId, @Param("feedbackId") UUID feedbackId);

    @Query("""
        SELECT fo.feedback.id AS feedbackId, fo.role AS role,
               fo.feedback.agentJobId AS agentJobId, fo.feedback.channel AS channel,
               fo.feedback.deliveryState AS deliveryState, fo.feedback.suppressionReason AS suppressionReason,
               fo.feedback.createdAt AS createdAt, fo.feedback.deliveredAt AS deliveredAt
        FROM FeedbackObservation fo
        WHERE fo.observation.id = :observationId AND fo.feedback.workspaceId = :workspaceId
        ORDER BY fo.feedback.createdAt DESC, fo.feedback.id DESC
        """)
    List<BoundFeedbackUnit> findBoundFeedbackUnits(
            @Param("workspaceId") Long workspaceId, @Param("observationId") UUID observationId);

    interface BoundObservation {
        UUID getObservationId();

        EvidenceRole getRole();

        Integer getOrdinal();

        String getPracticeSlug();

        String getPracticeName();

        @Nullable
        String getGroupSlug();

        @Nullable
        String getGroupName();

        @Nullable
        String getGroupIcon();

        @Nullable
        String getGroupColor();

        String getSummary();

        AssessmentStatus getAssessmentStatus();

        @Nullable
        Presence getPresence();

        @Nullable
        Assessment getAssessment();

        @Nullable
        Severity getSeverity();

        @Nullable
        Long getPracticeRevisionId();

        @Nullable
        String getPracticeRevisionFingerprint();

        @Nullable
        String getCurrentPracticeRevisionFingerprint();

        Instant getObservedAt();
    }

    interface BoundFeedbackUnit {
        UUID getFeedbackId();

        EvidenceRole getRole();

        UUID getAgentJobId();

        FeedbackChannel getChannel();

        FeedbackDeliveryState getDeliveryState();

        @Nullable
        FeedbackSuppressionReason getSuppressionReason();

        Instant getCreatedAt();

        @Nullable
        Instant getDeliveredAt();
    }

    /** Projection: facts + practice for one PREPARED conversational unit (no body - composed at delivery). */
    interface PreparedConversationFact {
        UUID getFeedbackId();

        UUID getAgentJobId();

        UUID getObservationId();

        String getPracticeSlug();

        String getPracticeName();

        String getSummary();

        @Nullable
        String getEvidenceRationale();

        /**
         * The composer's notes to the mentor, or null when nothing was composed for this unit. See
         * {@link ConversationBriefBody}.
         */
        String getBody();

        Severity getSeverity();

        @Nullable
        ArtifactKind getArtifactKind();

        Long getArtifactId();

        Instant getPreparedAt();
    }
}

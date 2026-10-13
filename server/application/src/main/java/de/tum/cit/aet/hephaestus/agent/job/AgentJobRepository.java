package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.handler.LinkedRepairFollowupRequest;
import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.MonitoredRepositoryScope;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import jakarta.persistence.LockModeType;
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
import tools.jackson.databind.JsonNode;

@Repository
public interface AgentJobRepository extends JpaRepository<AgentJob, UUID> {

    /** Provenance survives replacement or withdrawal: those comments remain Hephaestus-authored. */
    @Query(value = """
        SELECT p.posted_comment_ref AS "externalRef", p.posted_comment_url AS "url"
          FROM feedback_placement p JOIN feedback f ON f.id = p.feedback_id
         WHERE f.artifact_kind = 'scm.issue' AND f.artifact_id = :issueId
           AND p.placement_type = 'SUMMARY' AND p.posted_comment_ref IS NOT NULL
        UNION
        SELECT d.delivered_external_ref AS "externalRef", d.delivered_external_url AS "url"
          FROM feedback_dispatch d JOIN agent_job j ON j.id = d.agent_job_id AND j.workspace_id = d.workspace_id
         WHERE j.artifact_kind = 'scm.issue' AND j.metadata ->> 'issue_id' = CAST(:issueId AS text)
           AND d.delivered_external_ref IS NOT NULL
        UNION
        SELECT j.delivery_comment_id AS "externalRef", NULL AS "url"
          FROM agent_job j
         WHERE j.artifact_kind = 'scm.issue' AND j.metadata ->> 'issue_id' = CAST(:issueId AS text)
           AND j.delivery_comment_id IS NOT NULL
        """, nativeQuery = true)
    List<DeliveredIssueCommentRow> findDeliveredIssueComments(@Param("issueId") long issueId);

    /**
     * {@link #findDeliveredIssueComments} for every issue of one repository that the workspace monitors and still
     * holds, in one read. The scope limits which issues are asked about, not which workspace's delivery counts.
     */
    @Query(value = """
        WITH work AS (
            SELECT i.id FROM issue i
             WHERE i.repository_id = :repositoryId AND i.deleted_at IS NULL
               AND i.issue_type IS DISTINCT FROM 'PULL_REQUEST'
               AND EXISTS (SELECT 1 FROM repository_to_monitor rtm JOIN repository r
                           ON r.name_with_owner = rtm.name_with_owner AND\s""" + MonitoredRepositoryScope.NATIVE_RTM_R + """

                           WHERE rtm.workspace_id = :workspaceId AND r.id = i.repository_id)
        )
        SELECT w.id AS "issueId", p.posted_comment_ref AS "externalRef", p.posted_comment_url AS "url"
          FROM work w JOIN feedback f ON f.artifact_kind = 'scm.issue' AND f.artifact_id = w.id
          JOIN feedback_placement p ON p.feedback_id = f.id
         WHERE p.placement_type = 'SUMMARY' AND p.posted_comment_ref IS NOT NULL
        UNION
        SELECT w.id AS "issueId", d.delivered_external_ref AS "externalRef", d.delivered_external_url AS "url"
          FROM work w
          JOIN agent_job j ON j.artifact_kind = 'scm.issue' AND j.metadata ->> 'issue_id' = CAST(w.id AS text)
          JOIN feedback_dispatch d ON d.agent_job_id = j.id AND d.workspace_id = j.workspace_id
         WHERE d.delivered_external_ref IS NOT NULL
        UNION
        SELECT w.id AS "issueId", j.delivery_comment_id AS "externalRef", NULL AS "url"
          FROM work w
          JOIN agent_job j ON j.artifact_kind = 'scm.issue' AND j.metadata ->> 'issue_id' = CAST(w.id AS text)
         WHERE j.delivery_comment_id IS NOT NULL
        """, nativeQuery = true)
    List<RepositoryDeliveredIssueCommentRow> findDeliveredIssueCommentsOfRepository(
            @Param("workspaceId") long workspaceId, @Param("repositoryId") long repositoryId);

    interface DeliveredIssueCommentRow {
        String getExternalRef();

        @Nullable
        String getUrl();
    }

    interface RepositoryDeliveredIssueCommentRow extends DeliveredIssueCommentRow {
        Long getIssueId();
    }

    /**
     * The mirrored comments of one pull request that this workspace's delivery recorded posting, whatever became of
     * the feedback since. Each recorded identity is compared with a comment of this pull request on its own
     * provider, so it cannot name a comment on other work or another provider: GitLab records the note's global or
     * legacy numeric id, GitHub an opaque node id whose permalink carries the native id.
     */
    @Query(value = """
        WITH receipt AS (
            SELECT p.posted_comment_ref AS ref, p.posted_comment_url AS url
              FROM feedback_placement p JOIN feedback f ON f.id = p.feedback_id
             WHERE f.workspace_id = :workspaceId
               AND f.artifact_kind = 'scm.pull_request' AND f.artifact_id = :pullRequestId
               AND p.placement_type IN ('SUMMARY', 'LOCATION_COMMENT', 'INLINE')
               AND p.posted_comment_ref IS NOT NULL
            UNION
            SELECT d.delivered_external_ref, d.delivered_external_url
              FROM feedback_dispatch d JOIN agent_job j ON j.id = d.agent_job_id AND j.workspace_id = d.workspace_id
             WHERE d.workspace_id = :workspaceId AND j.artifact_kind = 'scm.pull_request'
               AND j.metadata ->> 'pull_request_id' = CAST(:pullRequestId AS text)
               AND d.delivered_external_ref IS NOT NULL
            UNION
            SELECT e ->> 'externalRef', e ->> 'externalUrl'
              FROM feedback_dispatch d JOIN agent_job j ON j.id = d.agent_job_id AND j.workspace_id = d.workspace_id
                   CROSS JOIN jsonb_array_elements(d.delivered_placements) e
             WHERE d.workspace_id = :workspaceId AND j.artifact_kind = 'scm.pull_request'
               AND j.metadata ->> 'pull_request_id' = CAST(:pullRequestId AS text)
               AND e ->> 'externalRef' IS NOT NULL
            UNION
            SELECT j.delivery_comment_id, NULL
              FROM agent_job j
             WHERE j.workspace_id = :workspaceId AND j.artifact_kind = 'scm.pull_request'
               AND j.metadata ->> 'pull_request_id' = CAST(:pullRequestId AS text)
               AND j.delivery_comment_id IS NOT NULL
        ),
        mirrored AS (
            SELECT FALSE AS is_inline, c.native_id, 'issuecomment-' AS anchor, ip.type AS provider
              FROM issue_comment c JOIN identity_provider ip ON ip.id = c.provider_id
              JOIN issue pr ON pr.id = c.issue_id AND pr.provider_id = c.provider_id
             WHERE pr.id = :pullRequestId AND pr.issue_type = 'PULL_REQUEST'
            UNION ALL
            SELECT TRUE, c.native_id, 'discussion_r', ip.type
              FROM pull_request_review_comment c JOIN identity_provider ip ON ip.id = c.provider_id
              JOIN issue pr ON pr.id = c.pull_request_id AND pr.provider_id = c.provider_id
             WHERE pr.id = :pullRequestId AND pr.issue_type = 'PULL_REQUEST'
        )
        SELECT m.is_inline AS "inline", m.native_id AS "nativeId"
          FROM mirrored m
         WHERE EXISTS (
                SELECT 1 FROM receipt r
                 WHERE (m.provider = 'GITLAB' AND r.ref IN (
                            'gid://gitlab/Note/' || m.native_id,
                            'gid://gitlab/DiffNote/' || m.native_id,
                            CAST(m.native_id AS text)))
                    OR (m.provider = 'GITHUB' AND split_part(r.url, '#', 2) = m.anchor || m.native_id))
        """, nativeQuery = true)
    List<DeliveredPullRequestCommentRow> findDeliveredPullRequestComments(
            @Param("workspaceId") long workspaceId, @Param("pullRequestId") long pullRequestId);

    interface DeliveredPullRequestCommentRow {
        boolean getInline();

        Long getNativeId();
    }

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM AgentJob j WHERE j.workspace.id = :workspaceId")
    int deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);

    /**
     * One page of a workspace's jobs, with an optional status filter, selecting only what the listing
     * renders. An entity page would carry {@code container_logs} — a whole review transcript per row,
     * which no listing reads — into heap and throw it away. {@code output} stays: the listing renders
     * it and derives each run's outcome from it, and it is bounded by the agent's result contract
     * rather than by how much a review had to say.
     */
    @Query("SELECT j.id AS id, j.jobType AS jobType, j.status AS status, j.integrationKind AS integrationKind, "
            + "j.metadata AS metadata, j.output AS output, j.configSnapshot AS configSnapshot, "
            + "j.exitCode AS exitCode, j.errorMessage AS errorMessage, j.deliveryStatus AS deliveryStatus, "
            + "j.deliveryCommentId AS deliveryCommentId, j.retryCount AS retryCount, "
            + "j.availableAt AS availableAt, j.holdReason AS holdReason, j.createdAt AS createdAt, "
            + "j.startedAt AS startedAt, j.executionStartedAt AS executionStartedAt, "
            + "j.completedAt AS completedAt, j.llmModel AS llmModel, "
            + "j.llmModelVersion AS llmModelVersion, j.llmTotalCalls AS llmTotalCalls, "
            + "j.llmTotalInputTokens AS llmTotalInputTokens, j.llmTotalOutputTokens AS llmTotalOutputTokens, "
            + "j.llmTotalReasoningTokens AS llmTotalReasoningTokens, j.llmCacheReadTokens AS llmCacheReadTokens, "
            + "j.llmCacheWriteTokens AS llmCacheWriteTokens "
            + "FROM AgentJob j WHERE j.workspace.id = :workspaceId "
            + "AND (:status IS NULL OR j.status = :status)")
    Page<AgentJobListRow> findListRows(
            @Param("workspaceId") Long workspaceId,
            @Param("status") @Nullable AgentJobStatus status,
            Pageable pageable);

    @Query("SELECT j.id AS id, j.jobType AS jobType, j.integrationKind AS integrationKind, j.metadata AS metadata "
            + "FROM AgentJob j WHERE j.workspace.id = :workspaceId AND j.id IN :ids")
    List<ReviewRunTargetRow> findReviewRunTargets(
            @Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    /**
     * What these runs decided, for the trace view. {@code reviewReadiness} is the per-practice record
     * and is deliberately fetched with them: a run that ends without measuring a practice looks the
     * same from {@code status} and {@code output} whatever the cause, and only readiness says which.
     */
    @Query("SELECT j.id AS id, j.status AS status, j.output AS output, j.reviewReadiness AS reviewReadiness, "
            + "j.completedAt AS completedAt FROM AgentJob j WHERE j.workspace.id = :workspaceId AND j.id IN :ids")
    List<ReviewOutcomeRow> findReviewOutcomes(
            @Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    /**
     * The practices each of these runs left to an earlier review's answer, as preparation recorded them. Only that
     * key is returned; runs that recorded none yield no row.
     */
    @Query(value = """
        SELECT j.id AS "id", CAST(j.evidence_snapshot -> 'answeredPractices' AS text) AS "answeredPractices"
        FROM agent_job j
        WHERE j.id IN :ids AND j.workspace_id = :workspaceId
          AND j.evidence_snapshot -> 'answeredPractices' IS NOT NULL
        """, nativeQuery = true)
    List<AnsweredPracticesRow> findAnsweredPractices(
            @Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    interface AnsweredPracticesRow {
        UUID getId();

        String getAnsweredPractices();
    }

    /** What these runs wrote about themselves: the composed next steps live in {@code output}. */
    List<ReviewRunNarrativeRow> findReviewRunNarrativesByWorkspaceIdAndIdIn(Long workspaceId, Collection<UUID> ids);

    /**
     * What these runs record about themselves; {@code output} holds the coverage ledger, and {@code feedbackUrl}
     * is the address the provider returned for the run's summary comment. A projection, so a listing does not
     * load each run's whole entity.
     */
    @Query("SELECT j.id AS id, j.jobType AS jobType, j.integrationKind AS integrationKind, j.metadata AS metadata, "
            + "j.status AS status, j.practiceTriggerMode AS triggerMode, j.output AS output, "
            + "j.createdAt AS createdAt, j.llmModel AS llmModel, j.completedAt AS completedAt, "
            + "j.startedAt AS startedAt, j.holdReason AS holdReason, "
            + "(SELECT MAX(d.deliveredExternalUrl) FROM FeedbackDispatch d WHERE d.workspaceId = j.workspace.id "
            + "AND d.agentJobId = j.id AND d.deliveredExternalRef = j.deliveryCommentId) AS feedbackUrl "
            + "FROM AgentJob j WHERE j.workspace.id = :workspaceId AND j.id IN :ids")
    List<ReviewRunFactsRow> findReviewRunFacts(
            @Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    interface ReviewRunFactsRow extends ReviewRunTargetRow {
        AgentJobStatus getStatus();

        TriggerMode getTriggerMode();

        @Nullable
        JsonNode getOutput();

        @Nullable
        String getFeedbackUrl();

        Instant getCreatedAt();

        @Nullable
        String getLlmModel();

        @Nullable
        Instant getCompletedAt();

        @Nullable
        Instant getStartedAt();

        @Nullable
        String getHoldReason();
    }

    /**
     * The work a retained-attempt read may name: a pull request or issue the developer authored, not tombstoned, in a
     * repository this workspace monitors on its connected provider and no team here hides from contributions.
     * Requires the aliases {@code i} (the work) and {@code r} (its repository).
     */
    String OWN_SCM_WORK = "\n" + """
              AND i.author_id = :developerId
              AND i.deleted_at IS NULL
              AND r.provider_id = :providerId
              AND EXISTS (
                  SELECT 1 FROM repository_to_monitor rtm
                  WHERE rtm.workspace_id = :workspaceId AND rtm.name_with_owner = r.name_with_owner
                    AND\s""" + MonitoredRepositoryScope.NATIVE_RTM_R + """
            )
              AND NOT EXISTS (
                  SELECT 1 FROM workspace_team_repository_settings wtrs
                  WHERE wtrs.workspace_id = :workspaceId
                    AND wtrs.repository_id = r.id
                    AND wtrs.hidden_from_contributions = true)
        """;

    /**
     * A practice review of exactly the work {@code i}, created since {@code :since}. The job type picks both the work's
     * discriminator and the metadata key naming it, and the key must hold that id as a JSON number, so a string or
     * malformed id matches nothing and an issue's id never answers for a pull request. Requires the aliases {@code j}
     * (the job) and {@code i} (the work).
     */
    String PRACTICE_REVIEW_OF_WORK = """
              j.workspace_id = :workspaceId
              AND j.purpose = 'PRACTICE_REVIEW'
              AND j.job_type IN ('PULL_REQUEST_REVIEW', 'ISSUE_REVIEW')
              AND j.created_at >= :since
              AND i.issue_type = CASE j.job_type WHEN 'ISSUE_REVIEW' THEN 'ISSUE' ELSE 'PULL_REQUEST' END
              AND j.metadata -> (CASE j.job_type WHEN 'ISSUE_REVIEW' THEN 'issue_id' ELSE 'pull_request_id' END)
                  = to_jsonb(i.id)
        """;

    /**
     * The developer's newest retained practice reviews of their own pull requests and issues, newest first; a caller
     * passes its cap plus one to learn whether more exist. Identities and timestamps only: what a run concluded is
     * not read here.
     */
    @Query(value = """
        SELECT j.id AS "reviewId", i.issue_type AS "workType", i.id AS "artifactId", i.number AS "number",
               i.title AS "title", i.html_url AS "url", i.state AS "state", j.created_at AS "createdAt",
               j.completed_at AS "completedAt"
        FROM agent_job j
        JOIN issue i ON
        """ + PRACTICE_REVIEW_OF_WORK + """
        JOIN repository r ON r.id = i.repository_id
        WHERE TRUE
        """ + OWN_SCM_WORK + """
        ORDER BY j.created_at DESC, j.id DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<ReviewAttemptRow> findOwnScmReviewAttempts(
            @Param("workspaceId") long workspaceId,
            @Param("developerId") long developerId,
            @Param("providerId") long providerId,
            @Param("since") Instant since,
            @Param("limit") int limit);

    /** One pull request or issue, by the practice review type that reviews it. */
    record ScmWork(AgentJobType jobType, long artifactId) {}

    /**
     * {@link #findOwnScmReviewAttempts} for one work of its review type's kind. No row means the work is not one the
     * developer may read here; an eligible work with no retained review is one row whose review columns are null.
     */
    @Query(value = """
        SELECT attempt.id AS "reviewId", i.issue_type AS "workType", i.id AS "artifactId", i.number AS "number",
               i.title AS "title", i.html_url AS "url", i.state AS "state", attempt.created_at AS "createdAt",
               attempt.completed_at AS "completedAt"
        FROM issue i
        JOIN repository r ON r.id = i.repository_id
        LEFT JOIN LATERAL (
            SELECT j.id, j.created_at, j.completed_at
            FROM agent_job j
            WHERE j.job_type = :#{#work.jobType().name()}
              AND
        """ + PRACTICE_REVIEW_OF_WORK + """
            ORDER BY j.created_at DESC, j.id DESC
            LIMIT :limit
        ) attempt ON TRUE
        WHERE i.id = :#{#work.artifactId()}
          AND i.issue_type = CASE CAST(:#{#work.jobType().name()} AS varchar)
                                 WHEN 'ISSUE_REVIEW' THEN 'ISSUE'
                                 WHEN 'PULL_REQUEST_REVIEW' THEN 'PULL_REQUEST'
                             END
        """ + OWN_SCM_WORK + """
        ORDER BY attempt.created_at DESC NULLS LAST, attempt.id DESC NULLS LAST
        """, nativeQuery = true)
    List<ReviewAttemptRow> findOwnScmReviewAttemptsOfWork(
            @Param("workspaceId") long workspaceId,
            @Param("developerId") long developerId,
            @Param("providerId") long providerId,
            @Param("work") ScmWork work,
            @Param("since") Instant since,
            @Param("limit") int limit);

    interface ReviewAttemptRow {
        /** Null only for an eligible work {@link #findOwnScmReviewAttemptsOfWork} found no retained review of. */
        @Nullable
        UUID getReviewId();

        /** The work's discriminator: {@code ISSUE} or {@code PULL_REQUEST}. */
        String getWorkType();

        Long getArtifactId();

        Integer getNumber();

        @Nullable
        String getTitle();

        @Nullable
        String getUrl();

        @Nullable
        String getState();

        @Nullable
        Instant getCreatedAt();

        @Nullable
        Instant getCompletedAt();
    }

    interface ReviewRunNarrativeRow {
        UUID getId();

        @Nullable
        JsonNode getOutput();
    }

    interface ReviewOutcomeRow {
        UUID getId();

        AgentJobStatus getStatus();

        @Nullable
        JsonNode getOutput();

        @Nullable
        JsonNode getReviewReadiness();

        @Nullable
        Instant getCompletedAt();
    }

    /**
     * One page of practice review runs narrowed by {@code filter}.
     *
     * <p>The window is inclusive at {@code from} and exclusive at {@code to}, the same half-open convention the
     * observation and feedback listings use, so a day picked in both surfaces means the same day; a null bound drops
     * out of the predicate. The bounds are bound on their own rather than read from {@code filter}: {@code CAST(:from
     * AS Instant)} types a null bound only when Hibernate sees an {@code Instant} parameter. A run whose results have
     * no processing status yet matches only when the filter leaves result processing open. A review matches the status
     * filter when it has a listed status, with {@code onHold} when it is queued on a hold, as its row's
     * {@code holdReason} says, or with {@code waiting} when it waits for a worker now by the predicate of the queue's
     * {@code waiting} count ({@link #findReviewQueue}): queued, not held for the budget and due at {@code now}, read
     * from the same app clock. The observation filters keep a run when one of its standing observations matches all
     * of them at once: one an admin marked incorrect
     * counts as never made, as the run's own practices and developers read it. The work filter reads the work from the
     * metadata field that {@link ReviewRunTargetMapper} names it by, so it matches the work the run lists. The merge
     * filter keeps the pull request reviews that completed after the work was merged, as {@link #findTimingSignals}
     * counts them: a review cancelled, failed or timed out after the merge produced no feedback that came late.
     */
    @Query("SELECT j.id AS id, j.status AS status, j.deliveryStatus AS deliveryStatus, j.jobType AS jobType, "
            + "j.integrationKind AS integrationKind, j.metadata AS metadata, j.createdAt AS createdAt, "
            + "function('jsonb_extract_path_text', j.output, 'outcome') AS reviewOutcome FROM AgentJob j "
            + "WHERE j.workspace.id = :workspaceId "
            + "AND j.purpose = de.tum.cit.aet.hephaestus.agent.config.AgentPurpose.PRACTICE_REVIEW "
            + "AND (:#{#filter.anyStatus()} = TRUE "
            + "OR (:#{#filter.hasListedStatus()} = TRUE AND j.status IN :#{#filter.statuses()}) "
            + "OR (:#{#filter.heldOnly()} = TRUE AND j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.QUEUED "
            + "AND j.holdReason IS NOT NULL) "
            + "OR (:#{#filter.waitingOnly()} = TRUE "
            + "AND j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.QUEUED "
            + "AND (j.holdReason IS NULL "
            + "OR j.holdReason <> '" + AgentJob.HOLD_REASON_BUDGET + "') "
            + "AND j.availableAt <= :now)) "
            + "AND (:#{#filter.anyResultProcessing()} = TRUE "
            + "OR j.deliveryStatus IN :#{#filter.resultProcessingStates()}) "
            + "AND (CAST(:from AS Instant) IS NULL OR j.createdAt >= :from) "
            + "AND (CAST(:to AS Instant) IS NULL OR j.createdAt < :to) "
            + "AND (:#{#filter.anyReview()} = TRUE OR j.id = :#{#filter.agentJobIdValue()}) "
            + "AND :#{#filter.reviewableWork()} = TRUE "
            + "AND (:#{#filter.anyWork()} = TRUE OR (j.jobType = :#{#filter.workJobType()} "
            + "AND (:#{#filter.anyWorkItem()} = TRUE OR FUNCTION('jsonb_extract_path_text', j.metadata, "
            + ":#{#filter.workIdField()}) = :#{#filter.workIdText()}))) "
            + "AND (:#{#filter.anyObservation()} = TRUE OR EXISTS (SELECT 1 FROM Observation o "
            + "JOIN o.practice p LEFT JOIN p.group g WHERE o.agentJobId = j.id AND o.workspaceId = :workspaceId "
            + "AND NOT EXISTS (SELECT 1 FROM ObservationInvalidation oi WHERE oi.observationId = o.id "
            + "AND oi.restoredAt IS NULL) "
            + "AND (:#{#filter.anySubject()} = TRUE OR o.aboutUserId = :#{#filter.subjectUserIdValue()}) "
            + "AND (:#{#filter.anyPractice()} = TRUE OR p.slug IN :#{#filter.practiceSlugs()}) "
            + "AND (:#{#filter.anyGroup()} = TRUE OR g.slug IN :#{#filter.groupSlugs()}))) "
            + "AND (:#{#filter.anyMergeTiming()} = TRUE OR (j.jobType = :#{#filter.mergedWorkJobType()} "
            + "AND EXISTS (SELECT 1 FROM PullRequest pr WHERE pr.id = CAST(FUNCTION('jsonb_extract_path_text', "
            + "j.metadata, 'pull_request_id') AS Long) AND pr.mergedAt < j.completedAt) "
            + "AND j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.COMPLETED))")
    Page<ReviewRunSummaryRow> findReviewRunSummaries(
            @Param("workspaceId") Long workspaceId,
            @Param("filter") ReviewRunFilterParams filter,
            @Param("from") @Nullable Instant from,
            @Param("to") @Nullable Instant to,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * The pull request {@code pr} that the pull request review {@code j} reviewed. A native join condition, so every
     * query that asks whether a review came after its merge reads the same pull request.
     */
    String REVIEWED_PULL_REQUEST = "j.job_type = 'PULL_REQUEST_REVIEW' AND pr.issue_type = 'PULL_REQUEST'"
            + " AND pr.id = CAST(j.metadata ->> '" + ReviewRunTargetMapper.PULL_REQUEST_ID_FIELD + "' AS bigint)";

    /**
     * Whether review {@code j} finished after its pull request {@code pr} was merged: it completed after the merge. A
     * review cancelled, failed or timed out after the merge produced no feedback that came late. False, never null,
     * when {@code pr} is absent or not merged. The JPQL merge filter of {@link #findReviewRunSummaries} states the
     * same test; {@code PracticeReviewTimingIntegrationTest} pins that the list, its rows and the count agree.
     */
    String FINISHED_AFTER_MERGE =
            "(j.status = 'COMPLETED' AND pr.merged_at IS NOT NULL AND pr.merged_at < j.completed_at)";

    /** Whether the merge of {@code pr} started review {@code j}. Binds {@code :mergedSignal}. */
    String STARTED_BY_MERGE = """
        EXISTS (SELECT 1 FROM artifact_signal s
                WHERE s.workspace_id = j.workspace_id AND s.artifact_kind = 'scm.pull_request'
                  AND s.artifact_id = pr.id AND s.signal_name = :mergedSignal AND s.job_id = j.id)
        """;

    /**
     * What the admin review list says of each run beyond its counts: how many practices it measured, from the
     * coverage ledger in its output, and its place among the practice reviews of the same work, counted from the
     * oldest in the list order. {@code evaluated} is null when the run wrote no whole-number count, and
     * {@code position} and {@code reviewsOnWork} are 0 when the run names no work. {@code holdReason} is set only
     * while the run is queued; the merge columns say whether a completed pull request review finished after its work
     * was merged, as {@link #findTimingSignals} counts it, and whether the merge itself started it. The count states the work with
     * {@link ReviewRunTargetMapper#WORK_ID_SQL}, exactly as the {@code idx_agent_job_review_work} index holds it, so it reads one index range per listed run over the reviews
     * of its own work, never the workspace's other jobs.
     */
    @Query(
            value = """
        SELECT j.id AS "id",
               CASE WHEN jsonb_typeof(j.output -> 'practiceCoverage' -> 'evaluated') = 'number'
                         AND (j.output -> 'practiceCoverage' ->> 'evaluated') ~ '^[0-9]+$'
                    THEN CAST(j.output -> 'practiceCoverage' ->> 'evaluated' AS integer) END AS "evaluated",
               on_work.position AS "position",
               on_work.reviews AS "reviewsOnWork",
               CASE WHEN j.status = 'QUEUED' THEN j.hold_reason END AS "holdReason",
        """ + FINISHED_AFTER_MERGE + """
                   AS "finishedAfterMerge",
               (
        """ + FINISHED_AFTER_MERGE + " AND " + STARTED_BY_MERGE + """
               ) AS "startedByMerge"
        FROM agent_job j
        CROSS JOIN LATERAL (SELECT
        """
                    + ReviewRunTargetMapper.WORK_ID_SQL + """
                AS id) work
        CROSS JOIN LATERAL (
            SELECT COUNT(*) FILTER (WHERE (e.created_at, e.id) <= (j.created_at, j.id)) AS position,
                   COUNT(*) AS reviews
            FROM agent_job e
            WHERE work.id IS NOT NULL
              AND e.workspace_id = j.workspace_id
              AND e.purpose = j.purpose
              AND e.job_type = j.job_type
              AND (
        """ + ReviewRunTargetMapper.WORK_ID_SQL + """
                  ) = work.id) on_work
        LEFT JOIN issue pr ON
        """
                    + REVIEWED_PULL_REQUEST + """
        WHERE j.workspace_id = :workspaceId AND j.id IN :ids
        """,
            nativeQuery = true)
    List<ReviewRunListFactsRow> findReviewRunListFacts(
            @Param("workspaceId") Long workspaceId,
            @Param("ids") Collection<UUID> ids,
            @Param("mergedSignal") String mergedSignal);

    interface ReviewRunListFactsRow {
        UUID getId();

        @Nullable
        Integer getEvaluated();

        Long getPosition();

        Long getReviewsOnWork();

        @Nullable
        String getHoldReason();

        Boolean getFinishedAfterMerge();

        Boolean getStartedByMerge();
    }

    /**
     * Jobs of {@code purpose} created in {@code [from, to)}, the window {@link #findReviewRunSummaries} filters by, by
     * the time bucket they were created in and their status. Buckets are numbered from 1 as
     * {@link de.tum.cit.aet.hephaestus.core.time.TimeBuckets#epochSeconds()} describes; a bucket and status without
     * jobs has no row.
     */
    @Query(value = """
        SELECT width_bucket(extract(epoch from j.created_at), CAST(:starts AS bigint[])) AS "bucket",
               j.status AS "status",
               COUNT(*) AS "count"
        FROM agent_job j
        WHERE j.workspace_id = :workspaceId
          AND j.purpose = :#{#purpose.name()}
          AND j.created_at >= :from
          AND j.created_at < :to
        GROUP BY 1, 2
        """, nativeQuery = true)
    List<StatusBucketCount> countByBucketAndStatus(
            @Param("workspaceId") Long workspaceId,
            @Param("purpose") AgentPurpose purpose,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("starts") Long[] starts);

    /**
     * When the workspace's first job of {@code purpose} was created: where "all time" starts, so a range over all of
     * a workspace's reviews needs no lower bound from the caller. Empty when the workspace has none.
     */
    @Query(value = """
        SELECT MIN(j.created_at) FROM agent_job j
        WHERE j.workspace_id = :workspaceId AND j.purpose = :#{#purpose.name()}
        """, nativeQuery = true)
    Optional<Instant> findFirstCreatedAt(
            @Param("workspaceId") Long workspaceId, @Param("purpose") AgentPurpose purpose);

    interface StatusBucketCount {
        Integer getBucket();

        AgentJobStatus getStatus();

        Long getCount();
    }

    /**
     * Whether feedback {@code f} was posted on the work by the review itself: in context, without an admin's approval
     * it waited for. A person's wait is not the review's time. A native predicate over {@code f}.
     */
    String POSTED_BY_ITSELF = """
        f.channel = 'IN_CONTEXT' AND NOT EXISTS (SELECT 1 FROM feedback_approval a
                                                 WHERE a.feedback_id = f.id AND a.workspace_id = f.workspace_id)
        """;

    /**
     * Whether feedback {@code f} reaches the developer to read: on their practice pages or in a conversation, so its
     * delivery is when they first read it. A native predicate over {@code f}.
     */
    String READ_BY_DEVELOPER = "f.channel IN ('IN_APP', 'IN_CHAT')";

    /**
     * The jobs of {@code purpose} created in {@code [from, to)} as {@code jobs}, the window
     * {@link #findReviewRunSummaries} filters by, with what each delivered as {@code delivered}, how long each waited
     * to be asked for as {@code asked}, and two sets of whole reviews, each stage of a review clamped at zero since a
     * retry can move a start past the end before it: {@code commented}, the reviews that posted a comment on the work
     * by themselves, with all five stages, and {@code finished}, the completed reviews, with the four stages until
     * their end. A run splits into preparation and model at {@code execution_started_at}; a review without it, such
     * as one recorded before that moment was, counts its whole run as {@code unsplit}, so it stays in both sets and
     * its stages still add up to its time. {@code asked} runs from the first moment on the work that names the review, the one that started
     * it as the review's trace reads it, to the request; it is zero for a review no moment started, such as one an
     * admin or developer asked for (its signal is recorded just before the request) or a backfill or sweep (their
     * signal carries the work's last update, not a moment that asked for the review), and for a moment recorded
     * after the request. A comment that an admin approved first waited for a person, so it is left out. A native
     * fragment to follow {@code WITH}; it binds
     * {@code :workspaceId}, {@code :purpose}, {@code :from} and {@code :to}.
     */
    String REVIEW_SPANS = """
        jobs AS (
            SELECT j.id, j.status, j.created_at, j.started_at, j.execution_started_at, j.completed_at
            FROM agent_job j
            WHERE j.workspace_id = :workspaceId
              AND j.purpose = :#{#purpose.name()}
              AND j.created_at >= :from
              AND j.created_at < :to
        ), moments AS (
            SELECT s.job_id, MIN(s.occurred_at) AS moment_at
            FROM artifact_signal s
            JOIN jobs ON jobs.id = s.job_id
            WHERE s.workspace_id = :workspaceId
              AND s.discovered_via IN ('EVENT', 'SYNC')
            GROUP BY s.job_id
        ), asked AS (
            SELECT jobs.id,
                   COALESCE(GREATEST(extract(epoch from jobs.created_at - m.moment_at), 0), 0) AS asked
            FROM jobs LEFT JOIN moments m ON m.job_id = jobs.id
        ), delivered AS (
            SELECT f.agent_job_id,
                   MIN(f.delivered_at) FILTER (WHERE
        """ + POSTED_BY_ITSELF + """
                   ) AS first_posted_at,
                   MIN(f.delivered_at) FILTER (WHERE
        """ + READ_BY_DEVELOPER + """
                   ) AS first_read_at
            FROM feedback f
            JOIN jobs ON jobs.id = f.agent_job_id
            WHERE f.workspace_id = :workspaceId AND f.delivered_at IS NOT NULL
            GROUP BY f.agent_job_id
        ), runs AS (
            SELECT jobs.id,
                   COALESCE(GREATEST(extract(epoch from jobs.execution_started_at - jobs.started_at), 0), 0)
                       AS preparation,
                   COALESCE(GREATEST(extract(epoch from jobs.completed_at - jobs.execution_started_at), 0), 0)
                       AS model,
                   CASE WHEN jobs.execution_started_at IS NULL
                        THEN GREATEST(extract(epoch from jobs.completed_at - jobs.started_at), 0)
                        ELSE 0 END AS unsplit
            FROM jobs
            WHERE jobs.started_at IS NOT NULL AND jobs.completed_at IS NOT NULL
        ), commented AS (
            SELECT a.asked,
                   GREATEST(extract(epoch from jobs.started_at - jobs.created_at), 0) AS queue,
                   r.preparation, r.model, r.unsplit,
                   GREATEST(extract(epoch from d.first_posted_at - jobs.completed_at), 0) AS posted
            FROM jobs JOIN delivered d ON d.agent_job_id = jobs.id JOIN asked a ON a.id = jobs.id
            JOIN runs r ON r.id = jobs.id
            WHERE d.first_posted_at IS NOT NULL
        ), finished AS (
            SELECT a.asked,
                   GREATEST(extract(epoch from jobs.started_at - jobs.created_at), 0) AS queue,
                   r.preparation, r.model, r.unsplit
            FROM jobs JOIN asked a ON a.id = jobs.id JOIN runs r ON r.id = jobs.id
            WHERE jobs.status = 'COMPLETED'
        )
        """;

    /**
     * How long each stage took for the jobs of {@code purpose} created in {@code [from, to)}, as
     * {@link #REVIEW_SPANS} reads them: one row per stage that measured at least one job. A stage measures a job once
     * both of its ends are recorded, whatever the job's status: {@code QUEUE} counts a retried job's earlier attempt
     * as waiting, and {@code MODEL} counts failed and timed-out runs. {@code POSTED} ends at the first comment the job
     * posted on the work by itself. {@code ASKED} measures the reviews a moment started before their request, from that
     * moment to the request. {@code TOTAL} is one figure per review over {@code commented}, from the moment that
     * started it, or the request where none did, to that comment, and {@code FINISHED} one per review over
     * {@code finished}, from the same start to the end of the review, so each median and p90 is of whole reviews. {@code READ} is not a pipeline stage: it ends when a
     * developer first opened the job's feedback on their practice pages or was shown it in a conversation. Every row
     * also carries the same {@link StageSecondsRow} sums, so the figures and the shares come from one snapshot.
     */
    @Query(value = "WITH " + REVIEW_SPANS + """
        , spans AS (
            SELECT 'ASKED' AS stage, extract(epoch from jobs.created_at - m.moment_at) AS seconds
            FROM jobs JOIN moments m ON m.job_id = jobs.id
            WHERE m.moment_at < jobs.created_at
            UNION ALL
            SELECT 'QUEUE', extract(epoch from started_at - created_at)
            FROM jobs WHERE started_at IS NOT NULL
            UNION ALL
            SELECT 'PREPARATION', extract(epoch from execution_started_at - started_at)
            FROM jobs WHERE started_at IS NOT NULL AND execution_started_at IS NOT NULL
            UNION ALL
            SELECT 'MODEL', extract(epoch from completed_at - execution_started_at)
            FROM jobs WHERE execution_started_at IS NOT NULL AND completed_at IS NOT NULL
            UNION ALL
            SELECT 'POSTED', extract(epoch from d.first_posted_at - jobs.completed_at)
            FROM jobs JOIN delivered d ON d.agent_job_id = jobs.id
            WHERE jobs.completed_at IS NOT NULL AND d.first_posted_at IS NOT NULL
            UNION ALL
            SELECT 'TOTAL', asked + queue + preparation + model + unsplit + posted FROM commented
            UNION ALL
            SELECT 'FINISHED', asked + queue + preparation + model + unsplit FROM finished
            UNION ALL
            SELECT 'READ', extract(epoch from d.first_read_at - jobs.completed_at)
            FROM jobs JOIN delivered d ON d.agent_job_id = jobs.id
            WHERE jobs.completed_at IS NOT NULL AND d.first_read_at IS NOT NULL
        ), stages AS (
            SELECT stage,
                   COUNT(*) AS count,
                   CAST(round(percentile_cont(0.5) WITHIN GROUP (ORDER BY GREATEST(seconds, 0))) AS bigint) AS median,
                   CAST(round(percentile_cont(0.9) WITHIN GROUP (ORDER BY GREATEST(seconds, 0))) AS bigint) AS p90
            FROM spans
            GROUP BY stage
        )
        SELECT s.stage AS "stage", s.count AS "count", s.median AS "median", s.p90 AS "p90",
               c.asked AS "commentedAsked", c.queue AS "commentedQueue",
               c.preparation AS "commentedPreparation", c.model AS "commentedModel",
               c.unsplit AS "commentedUnsplit", c.posted AS "commentedPosted",
               f.asked AS "finishedAsked", f.queue AS "finishedQueue", f.preparation AS "finishedPreparation",
               f.model AS "finishedModel", f.unsplit AS "finishedUnsplit"
        FROM stages s
        CROSS JOIN (SELECT CAST(SUM(asked) AS double precision) AS asked,
                           CAST(SUM(queue) AS double precision) AS queue,
                           CAST(SUM(preparation) AS double precision) AS preparation,
                           CAST(SUM(model) AS double precision) AS model,
                           CAST(SUM(unsplit) AS double precision) AS unsplit,
                           CAST(SUM(posted) AS double precision) AS posted
                    FROM commented) c
        CROSS JOIN (SELECT CAST(SUM(asked) AS double precision) AS asked,
                           CAST(SUM(queue) AS double precision) AS queue,
                           CAST(SUM(preparation) AS double precision) AS preparation,
                           CAST(SUM(model) AS double precision) AS model,
                           CAST(SUM(unsplit) AS double precision) AS unsplit
                    FROM finished) f
        """, nativeQuery = true)
    List<StageTimingRow> findStageTimings(
            @Param("workspaceId") Long workspaceId,
            @Param("purpose") AgentPurpose purpose,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * Where the time of whole reviews went, over the same reviews as {@code TOTAL} and {@code FINISHED} in
     * {@link #findStageTimings}: each stage's seconds summed over {@code commented} and over {@code finished}, so a
     * stage's part of a set is its sum over the set's. Every sum is null when its set is empty.
     */
    interface StageSecondsRow {
        @Nullable
        Double getCommentedAsked();

        @Nullable
        Double getCommentedQueue();

        @Nullable
        Double getCommentedPreparation();

        @Nullable
        Double getCommentedModel();

        /** The run of the reviews without an execution start, which cannot be split. */
        @Nullable
        Double getCommentedUnsplit();

        @Nullable
        Double getCommentedPosted();

        @Nullable
        Double getFinishedAsked();

        @Nullable
        Double getFinishedQueue();

        @Nullable
        Double getFinishedPreparation();

        @Nullable
        Double getFinishedModel();

        @Nullable
        Double getFinishedUnsplit();
    }

    interface StageTimingRow extends StageSecondsRow {
        String getStage();

        Long getCount();

        Long getMedian();

        Long getP90();
    }

    /**
     * The reviews that need a look: those waiting for a worker since before {@code waitingSince} other than budget
     * holds, counted from when they became available so a requeued review waits from its requeue, whenever it was
     * requested, and, among the jobs created in {@code [from, to)}, the pull request reviews that
     * completed after their work was merged, the ones of those that the merge started, and the
     * feedback created in the range that was withheld because the work was already merged. The merge test is
     * {@link #FINISHED_AFTER_MERGE}, as the review list reads it.
     */
    @Query(
            value = """
        SELECT
          (SELECT COUNT(*) FROM agent_job j
           WHERE j.workspace_id = :#{#query.workspaceId()} AND j.purpose = :#{#query.purpose().name()} AND j.status = 'QUEUED'
             AND j.available_at < :#{#query.waitingSince()} AND j.hold_reason IS DISTINCT FROM :#{#query.budget()}) AS "waitingLong",
          late.finished AS "finishedAfterMerge",
          late.started_by_merge AS "finishedAfterMergeStartedByMerge",
          (SELECT COUNT(*) FROM feedback f
           WHERE f.workspace_id = :#{#query.workspaceId()} AND f.suppression_reason = 'ARTIFACT_MERGED'
             AND f.created_at >= :#{#query.from()} AND f.created_at < :#{#query.to()}) AS "feedbackWithheldAsMerged"
        FROM (
          SELECT COUNT(*) AS finished,
                 COUNT(*) FILTER (WHERE
        """ + STARTED_BY_MERGE + """
                 ) AS started_by_merge
          FROM agent_job j
          JOIN issue pr ON
        """ + REVIEWED_PULL_REQUEST + """
          WHERE j.workspace_id = :#{#query.workspaceId()}
            AND j.purpose = :#{#query.purpose().name()}
            AND j.created_at >= :#{#query.from()}
            AND j.created_at < :#{#query.to()}
            AND
        """ + FINISHED_AFTER_MERGE + """
        ) late
        """,
            nativeQuery = true)
    ReviewTimingSignalsRow findTimingSignals(
            @Param("query") ReviewTimingSignalsQuery query, @Param("mergedSignal") String mergedSignal);

    /**
     * What {@link #findTimingSignals} counts over: the jobs of one purpose created in {@code [from, to)}, a wait
     * longer than since {@code waitingSince} and the budget hold reason.
     */
    record ReviewTimingSignalsQuery(
            Long workspaceId, AgentPurpose purpose, Instant from, Instant to, Instant waitingSince, String budget) {}

    interface ReviewTimingSignalsRow {
        Long getWaitingLong();

        Long getFinishedAfterMerge();

        Long getFinishedAfterMergeStartedByMerge();

        Long getFeedbackWithheldAsMerged();
    }

    /**
     * When one run first posted a comment on the work by itself, as {@link #findStageTimings} times it; when a
     * developer first read its feedback on their practice pages or in a conversation; and when the pull request it
     * reviewed was merged. Each is null when it has not happened, or for a run that reviewed other work. Empty when
     * the workspace has no such run.
     */
    @Query(
            value = """
        SELECT (SELECT MIN(f.delivered_at) FROM feedback f
                WHERE f.agent_job_id = j.id AND f.workspace_id = j.workspace_id AND
        """ + POSTED_BY_ITSELF + """
               ) AS "firstFeedbackPostedAt",
               (SELECT MIN(f.delivered_at) FROM feedback f
                WHERE f.agent_job_id = j.id AND f.workspace_id = j.workspace_id AND
        """ + READ_BY_DEVELOPER + """
               ) AS "firstFeedbackReadAt",
               (SELECT pr.merged_at FROM issue pr WHERE
        """ + REVIEWED_PULL_REQUEST + """
               ) AS "workMergedAt"
        FROM agent_job j
        WHERE j.id = :id AND j.workspace_id = :workspaceId
        """,
            nativeQuery = true)
    Optional<ReviewRunMomentsRow> findReviewRunMoments(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    interface ReviewRunMomentsRow {
        @Nullable
        Instant getFirstFeedbackPostedAt();

        @Nullable
        Instant getFirstFeedbackReadAt();

        @Nullable
        Instant getWorkMergedAt();
    }

    @Query("SELECT j FROM AgentJob j JOIN FETCH j.workspace WHERE j.id = :id AND j.workspace.id = :workspaceId")
    Optional<AgentJob> findByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /**
     * Which evidence contract governed a run, without reading the snapshot it is recorded in.
     *
     * <p>A snapshot carries one entry per staged file, so a repository-tree capture makes it megabytes,
     * and Postgres has no partial read for a TOASTed jsonb. Loading the job to take one string out of it
     * therefore detoasts, ships and parses the whole document — and evidence authorization asks this
     * question once per observation, on surfaces that list hundreds. Extracting the key in SQL keeps the
     * detoast on the server and the answer to a few bytes.
     *
     * @return the contract version, or empty when this workspace has no such run or the run recorded no
     *     evidence — both of which mean nothing may be cited from it
     */
    @Query(
            value = "SELECT jsonb_extract_path_text(j.evidence_snapshot, 'manifest', 'contractVersion') "
                    + "FROM agent_job j WHERE j.id = :id AND j.workspace_id = :workspaceId",
            nativeQuery = true)
    Optional<String> findEvidenceContractVersion(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /**
     * The same answer as {@link #findEvidenceContractVersion} for a whole set of runs, in one round trip.
     *
     * <p>Evidence authorization asks the question once per observation, and the surfaces that ask it list
     * hundreds — a developer with forty pull requests across ten practices costs four hundred sequential
     * round trips on one dashboard read. The per-row query is cheap; the latency is the count.
     *
     * <p>A run this workspace does not own yields no row, and a run that recorded no evidence yields a row
     * whose value is {@code null}. Both mean nothing may be cited from it, exactly as the empty
     * {@link Optional} does on the single-row query.
     */
    @Query(value = """
        SELECT j.id AS "id", j.retry_count AS "attempt",
               jsonb_extract_path_text(j.evidence_snapshot, 'manifest', 'contractVersion') AS "contractVersion"
        FROM agent_job j
        WHERE j.id IN :ids
          AND j.workspace_id = :workspaceId
        """, nativeQuery = true)
    List<EvidenceContractVersionRow> findEvidenceContractVersions(
            @Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    /**
     * What each run captured of the work it reviewed: the artifact it was about, its source contract, its
     * {@code reviewedWork}, and for a run from before that was recorded, the archived description digest and pinned
     * change range the jsonpaths select.
     * Extracted in SQL for the reason {@link #findEvidenceContractVersion} gives; a run outside this workspace yields
     * no row.
     */
    @Query(value = """
        SELECT j.id AS "id",
               j.job_type AS "jobType",
               j.status AS "status",
               COALESCE(j.metadata ->> 'pull_request_id', j.metadata ->> 'issue_id') AS "reviewedArtifactId",
               jsonb_extract_path_text(j.evidence_snapshot, 'manifest', 'contractVersion') AS "contractVersion",
               jsonb_extract_path_text(j.evidence_snapshot, 'manifest', 'capturedAt') AS "capturedAt",
               CAST(j.evidence_snapshot -> 'reviewedWork' AS text) AS "reviewedWork",
               jsonb_path_query_first(j.evidence_snapshot, CAST(:descriptionPath AS jsonpath)) #>> '{}'
                   AS "descriptionSha256",
               jsonb_path_query_first(j.evidence_snapshot, CAST(:changePath AS jsonpath)) #>> '{}' AS "changeRange"
        FROM agent_job j
        WHERE j.id IN :ids
          AND j.workspace_id = :workspaceId
        """, nativeQuery = true)
    List<ReviewedWorkRow> findReviewedWork(
            @Param("workspaceId") Long workspaceId,
            @Param("ids") Collection<UUID> ids,
            @Param("descriptionPath") String descriptionPath,
            @Param("changePath") String changePath);

    /**
     * The staged identity and source contract for material repair admission, and the generated-path policy the run
     * staged. Source states outlive the retired artifact inventories, so they stay readable after admission.
     */
    @Query(value = """
        SELECT j.id AS "id", j.retry_count AS "attempt", j.status AS "status",
               CAST(j.evidence_snapshot -> 'reviewedWork' AS text) AS "reviewedWork",
               jsonb_extract_path_text(j.evidence_snapshot, 'manifest', 'contractVersion') AS "contractVersion",
               CAST(j.evidence_snapshot -> 'manifest' AS text) AS "manifest",
               CAST(j.evidence_snapshot -> 'generatedPaths' AS text) AS "generatedPaths"
        FROM agent_job j
        WHERE j.id IN :ids AND j.workspace_id = :workspaceId
        """, nativeQuery = true)
    List<CapturedReviewedWorkRow> findCapturedReviewedWork(
            @Param("workspaceId") long workspaceId, @Param("ids") Collection<UUID> ids);

    /**
     * Whether a review of this pull request submitted at exactly this head, title and description, under an occasion
     * other than {@code excludedSignals}, is still queued or running in the workspace.
     */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM agent_job j
            WHERE j.workspace_id = :workspaceId
              AND j.job_type = 'PULL_REQUEST_REVIEW'
              AND j.status IN ('QUEUED', 'RUNNING')
              AND j.metadata -> 'pull_request_id' = to_jsonb(CAST(:pullRequestId AS bigint))
              AND j.metadata ->> 'commit_sha' = :head
              AND j.metadata ->> 'title' IS NOT DISTINCT FROM CAST(:title AS text)
              AND j.metadata ->> 'body' IS NOT DISTINCT FROM CAST(:body AS text)
              AND COALESCE(j.metadata ->> 'signal', '') NOT IN (:excludedSignals)
        )
        """, nativeQuery = true)
    boolean existsActivePullRequestReviewOf(
            @Param("workspaceId") long workspaceId,
            @Param("pullRequestId") long pullRequestId,
            @Param("head") String head,
            @Param("title") @Nullable String title,
            @Param("body") @Nullable String body,
            @Param("excludedSignals") Collection<String> excludedSignals);

    /**
     * The newest review of this pull request's author under one of {@code signals}, share-locked so that a claim or
     * cancel of it still in progress commits before a decision reads its state.
     */
    @Query(value = """
        SELECT j.* FROM agent_job j
        WHERE j.workspace_id = :workspaceId
          AND j.job_type = 'PULL_REQUEST_REVIEW'
          AND j.metadata -> 'pull_request_id' = to_jsonb(CAST(:pullRequestId AS bigint))
          AND j.metadata ->> 'signal' IN (:signals)
          AND j.metadata -> 'about_user_id' IS NULL
          AND j.practice_trigger_mode = 'AUTO'
        ORDER BY j.created_at DESC, j.id DESC
        LIMIT 1
        FOR SHARE
        """, nativeQuery = true)
    Optional<AgentJob> lockLatestAuthorReviewOf(
            @Param("workspaceId") long workspaceId,
            @Param("pullRequestId") long pullRequestId,
            @Param("signals") Collection<String> signals);

    /**
     * Queued reviews of this pull request's author under one of {@code signals} created before {@code before}, locked
     * as a claim locks them. A row a claim already holds is skipped: that claim decides it.
     */
    @Query(value = """
        SELECT j.* FROM agent_job j
        WHERE j.workspace_id = :workspaceId
          AND j.job_type = 'PULL_REQUEST_REVIEW'
          AND j.status = 'QUEUED'
          AND j.metadata -> 'pull_request_id' = to_jsonb(CAST(:pullRequestId AS bigint))
          AND j.metadata ->> 'signal' IN (:signals)
          AND j.metadata -> 'about_user_id' IS NULL
          AND j.practice_trigger_mode = 'AUTO'
          AND j.created_at < :before
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<AgentJob> lockQueuedAuthorReviewsBefore(
            @Param("workspaceId") long workspaceId,
            @Param("pullRequestId") long pullRequestId,
            @Param("signals") Collection<String> signals,
            @Param("before") Instant before);

    /**
     * Candidates only, in key order after the given pull request, so a sweep pages through every candidate rather than
     * revisiting the same first batch. The caller locks each pull request and decides again.
     */
    @WorkspaceAgnostic("Finds queued reviews with a later one across workspaces; each decision is workspace-scoped")
    @Query(value = """
        SELECT queued.workspace_id AS "workspaceId",
               CAST(queued.metadata ->> 'pull_request_id' AS bigint) AS "pullRequestId"
        FROM agent_job queued
        WHERE queued.status = 'QUEUED'
          AND queued.job_type = 'PULL_REQUEST_REVIEW'
          AND queued.metadata ->> 'signal' IN (:signals)
          AND queued.metadata -> 'about_user_id' IS NULL
          AND queued.practice_trigger_mode = 'AUTO'
          AND (queued.workspace_id, CAST(queued.metadata ->> 'pull_request_id' AS bigint))
              > (:afterWorkspaceId, :afterPullRequestId)
          AND EXISTS (
              SELECT 1 FROM agent_job later
              WHERE later.workspace_id = queued.workspace_id
                AND later.job_type = 'PULL_REQUEST_REVIEW'
                AND later.metadata -> 'pull_request_id' = queued.metadata -> 'pull_request_id'
                AND later.metadata ->> 'signal' IN (:signals)
                AND later.metadata -> 'about_user_id' IS NULL
                AND later.practice_trigger_mode = 'AUTO'
                AND later.created_at > queued.created_at)
        GROUP BY 1, 2
        ORDER BY 1, 2
        LIMIT :batchSize
        """, nativeQuery = true)
    List<QueuedAuthorReviewRow> findQueuedAuthorReviewsWithALaterOne(
            @Param("signals") Collection<String> signals,
            @Param("afterWorkspaceId") long afterWorkspaceId,
            @Param("afterPullRequestId") long afterPullRequestId,
            @Param("batchSize") int batchSize);

    interface QueuedAuthorReviewRow {
        Long getWorkspaceId();

        Long getPullRequestId();
    }

    public interface CapturedReviewedWorkRow {
        UUID getId();

        int getAttempt();

        AgentJobStatus getStatus();

        @Nullable
        String getContractVersion();

        @Nullable
        String getReviewedWork();

        @Nullable
        String getManifest();

        @Nullable
        String getGeneratedPaths();
    }

    interface ReviewedWorkRow {
        UUID getId();

        AgentJobStatus getStatus();

        @Nullable
        String getJobType();

        @Nullable
        String getReviewedArtifactId();

        @Nullable
        String getContractVersion();

        @Nullable
        String getCapturedAt();

        @Nullable
        String getReviewedWork();

        @Nullable
        String getDescriptionSha256();

        @Nullable
        String getChangeRange();
    }

    interface EvidenceContractVersionRow {
        UUID getId();

        int getAttempt();

        @Nullable
        String getContractVersion();
    }

    @Query("SELECT CASE WHEN COUNT(j) > 0 THEN true ELSE false END FROM AgentJob j "
            + "WHERE j.workspace.id = :workspaceId AND ("
            + "j.status IN ("
            + "de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.QUEUED, "
            + "de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.RUNNING) OR "
            + "(j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.COMPLETED AND "
            + "j.deliveryStatus = de.tum.cit.aet.hephaestus.agent.job.DeliveryStatus.PENDING))")
    boolean existsPurgeBlockingWork(@Param("workspaceId") Long workspaceId);

    @Query(value = """
            SELECT COUNT(*) FROM agent_job
            WHERE workspace_id = :workspaceId AND purpose = :#{#purpose.name()} AND status = 'RUNNING'
              AND COALESCE(config_snapshot ->> 'dataHandlingTier', 'UNDECLARED') = :#{#tier.name()}
            """, nativeQuery = true)
    long countRunningByWorkspaceIdAndPurposeAndDataHandlingTier(
            Long workspaceId, AgentPurpose purpose, DataHandlingTier tier);

    long countByWorkspaceIdAndPurposeAndCreatedAtGreaterThanEqual(
            Long workspaceId, AgentPurpose purpose, Instant createdAt);

    List<AgentJob> findByStatus(AgentJobStatus status);

    List<AgentJob> findByStatusIn(Collection<AgentJobStatus> statuses);

    Optional<AgentJob> findByJobTokenHashAndStatus(String jobTokenHash, AgentJobStatus status);

    /**
     * Clears the hold a budget block placed on this workspace's queued jobs, so raising the cap takes
     * effect immediately. Scoped to {@code hold_reason = 'BUDGET'} rather than "any future
     * {@code available_at}" so it cannot fast-forward a crash-retry backoff.
     *
     * @return how many held jobs were released
     */
    @WorkspaceAgnostic("Workspace-scoped release; caller is the budget writer for that workspace")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.availableAt = :now, j.holdReason = null "
            + "WHERE j.workspace.id = :workspaceId AND j.status = 'QUEUED' AND j.holdReason = 'BUDGET'")
    int releaseBudgetHolds(@Param("workspaceId") Long workspaceId, @Param("now") Instant now);

    Optional<AgentJob> findByWorkspaceIdAndIdempotencyKeyAndStatusIn(
            Long workspaceId, String idempotencyKey, Collection<AgentJobStatus> statuses);

    Optional<AgentJob> findByWorkspaceIdAndIdempotencyKey(Long workspaceId, String idempotencyKey);

    /**
     * Matches on an idempotency-key prefix, so a caller can look across the varying tail of the key
     * (head SHA, revision, timestamp) for an earlier review of the same subject.
     */
    @Query("SELECT j FROM AgentJob j WHERE j.workspace.id = :workspaceId"
            + " AND j.idempotencyKey LIKE :keyPrefix ESCAPE '\\'"
            + " AND j.createdAt > :cutoff"
            + " ORDER BY j.createdAt DESC"
            + " LIMIT 1")
    Optional<AgentJob> findRecentJobByKeyPrefix(
            @Param("workspaceId") Long workspaceId,
            @Param("keyPrefix") String keyPrefix,
            @Param("cutoff") Instant cutoff);

    /**
     * Empty if a concurrent poller holds the row, or it is no longer eligible. Eligibility is
     * re-checked here and not only in the candidate poll, because a concurrent backoff-requeue can push
     * {@code available_at} into the future in between. {@code :now} is bound rather than read from the
     * DB clock so eligibility is judged against the same app clock that computed {@code available_at}.
     */
    @WorkspaceAgnostic("ID-based claim; job ID from a workspace-scoped candidate poll")
    @Query(
            value = "SELECT j.* FROM agent_job j JOIN workspace w ON w.id = j.workspace_id "
                    + "WHERE j.id = :id AND j.status = 'QUEUED' AND j.available_at <= :now AND w.status = 'ACTIVE' "
                    + "FOR UPDATE OF j SKIP LOCKED",
            nativeQuery = true)
    Optional<AgentJob> findByIdQueuedForUpdateSkipLocked(@Param("id") UUID id, @Param("now") Instant now);

    @WorkspaceAgnostic("ID-based reload; job ID from workspace-scoped claim context")
    @Query("SELECT j FROM AgentJob j LEFT JOIN FETCH j.workspace WHERE j.id = :id")
    Optional<AgentJob> findByIdWithWorkspace(@Param("id") UUID id);

    /**
     * The row lock makes this read and the caller's following status transition one decision: a
     * concurrent execution-start CAS either commits before this read or waits and then loses because
     * the job is no longer RUNNING.
     */
    @WorkspaceAgnostic("ID-based locked recovery read; caller performs a fenced status transition")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM AgentJob j LEFT JOIN FETCH j.workspace WHERE j.id = :id")
    Optional<AgentJob> findByIdWithWorkspaceForUpdate(@Param("id") UUID id);

    /** Keeps source-use and readiness verdicts, not the uncited workspace inventory, after its lifetime. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE agent_job j SET evidence_snapshot = jsonb_set(
              jsonb_set(jsonb_set(j.evidence_snapshot, '{manifest,artifacts}', '[]'::jsonb), '{manifest,refusals}', '[]'::jsonb),
              '{manifest,sources}', (SELECT COALESCE(jsonb_agg(jsonb_set(source, '{artifacts}', '[]'::jsonb)), '[]'::jsonb)
                FROM jsonb_array_elements(j.evidence_snapshot #> '{manifest,sources}') source))
            WHERE j.id = :jobId AND j.workspace_id = :workspaceId
              AND j.retry_count = :attempt AND j.worker_id = :workerId
              AND jsonb_typeof(j.evidence_snapshot #> '{manifest,sources}') = 'array'
              AND (j.status <> 'RUNNING' OR COALESCE(j.metadata ->> '""" + ObservationAdmissionService.DIGEST_METADATA_KEY + "', '') <> '')", nativeQuery = true)
    int discardRetiredArtifactInventory(
            @Param("jobId") UUID jobId,
            @Param("workspaceId") Long workspaceId,
            @Param("attempt") int attempt,
            @Param("workerId") String workerId);

    /** @return rows updated (0 or 1); 0 means a concurrent transition won. */
    @WorkspaceAgnostic("ID-based status transition; job ID from workspace-scoped context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = :newStatus, j.completedAt = :now, j.errorMessage = :error "
            + "WHERE j.id = :id AND j.status IN :fromStatuses")
    int transitionStatus(
            @Param("id") UUID id,
            @Param("newStatus") AgentJobStatus newStatus,
            @Param("now") Instant now,
            @Param("error") @Nullable String error,
            @Param("fromStatuses") Collection<AgentJobStatus> fromStatuses);

    /**
     * Like {@link #transitionStatus}, but a worker whose job was orphan-requeued to a sibling cannot
     * clobber the sibling's run with its own late terminal write.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based fenced transition; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = :newStatus, j.completedAt = :now, j.errorMessage = :error "
            + "WHERE j.id = :id AND j.status IN :fromStatuses AND j.workerId = :workerId")
    int transitionStatusOwnedBy(
            @Param("id") UUID id,
            @Param("newStatus") AgentJobStatus newStatus,
            @Param("now") Instant now,
            @Param("error") @Nullable String error,
            @Param("fromStatuses") Collection<AgentJobStatus> fromStatuses,
            @Param("workerId") String workerId);

    /**
     * Unfenced by worker; callers that know the owning worker should prefer
     * {@link #transitionToCancelledOwnedBy}.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based cancel; job ID from worker-local drain or user-scoped admin call")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.CANCELLED, "
            + "j.completedAt = :now, j.errorMessage = :error, j.cancellationReason = :reason "
            + "WHERE j.id = :id AND j.status IN :fromStatuses")
    int transitionToCancelled(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("error") String error,
            @Param("reason") AgentJobCancellationReason reason,
            @Param("fromStatuses") Collection<AgentJobStatus> fromStatuses);

    /**
     * Adds one proxied call's tokens to the running totals of ONE attempt, so a job that crashes
     * mid-run still has the calls it made on record. A clean finish overwrites these with the runner's
     * authoritative totals.
     *
     * <p>Fenced on {@code retry_count} and {@code RUNNING} because a provider call can outlive the
     * attempt that issued it: {@link #requeueOrphan} may hand the row to a new attempt (zeroing these
     * columns) while the proxy is still waiting on the provider, and a late write would otherwise bill
     * one attempt's tokens at another attempt's frozen price and funding source.
     *
     * @param attempt the {@code retry_count} the caller observed when it authenticated the call
     * @return 1 if the attempt still owns the row, 0 if it has been superseded (a safe no-op)
     */
    @WorkspaceAgnostic("ID-based per-call usage accumulation from the worker-local proxy")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET " + "j.llmTotalCalls = COALESCE(j.llmTotalCalls, 0) + 1, "
            + "j.llmTotalInputTokens = COALESCE(j.llmTotalInputTokens, 0) + :#{#usage.inputTokens()}, "
            + "j.llmTotalOutputTokens = COALESCE(j.llmTotalOutputTokens, 0) + :#{#usage.outputTokens()}, "
            + "j.llmTotalReasoningTokens = COALESCE(j.llmTotalReasoningTokens, 0) + :#{#usage.reasoningTokens()}, "
            + "j.llmCacheReadTokens = COALESCE(j.llmCacheReadTokens, 0) + :#{#usage.cacheReadTokens()}, "
            + "j.llmCacheWriteTokens = COALESCE(j.llmCacheWriteTokens, 0) + :#{#usage.cacheWriteTokens()} "
            + "WHERE j.id = :id AND j.retryCount = :attempt AND j.status = 'RUNNING'")
    int accumulateLlmUsage(
            @Param("id") UUID id, @Param("attempt") int attempt, @Param("usage") AgentJobLlmUsageDelta usage);

    /**
     * Reads the totals straight from the row rather than from a possibly stale in-memory entity, so
     * committed proxy accumulations are included.
     */
    @WorkspaceAgnostic("ID-based usage read; job ID from worker-local terminal accounting")
    @Query("SELECT new de.tum.cit.aet.hephaestus.agent.job.AgentJobLlmUsage("
            + "COALESCE(j.llmTotalCalls, 0), COALESCE(j.llmTotalInputTokens, 0), "
            + "COALESCE(j.llmTotalOutputTokens, 0), COALESCE(j.llmTotalReasoningTokens, 0), "
            + "COALESCE(j.llmCacheReadTokens, 0), COALESCE(j.llmCacheWriteTokens, 0)) "
            + "FROM AgentJob j WHERE j.id = :id")
    Optional<AgentJobLlmUsage> findLlmUsageById(@Param("id") UUID id);

    /**
     * Completed pull-request and issue reviews with unfinished preparation in {@code [from, until)}.
     * A successful empty result has a completion mark and is excluded.
     */
    @WorkspaceAgnostic("Cross-tenant recovery sweep over jobs whose feedback lanes have no completion mark")
    @Query("SELECT new de.tum.cit.aet.hephaestus.agent.job.UnpreparedFeedbackLanes("
            + "j.id, j.workspace.id, j.inChatPreparedAt, j.inAppPreparedAt) FROM AgentJob j "
            + "WHERE j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.COMPLETED "
            + "AND j.jobType IN (de.tum.cit.aet.hephaestus.agent.AgentJobType.PULL_REQUEST_REVIEW, "
            + "de.tum.cit.aet.hephaestus.agent.AgentJobType.ISSUE_REVIEW) "
            + "AND j.completedAt >= :from AND j.completedAt < :until "
            + "AND (j.inChatPreparedAt IS NULL OR j.inAppPreparedAt IS NULL) "
            + "ORDER BY j.completedAt")
    List<UnpreparedFeedbackLanes> findUnpreparedFeedbackLanes(
            @Param("from") Instant from, @Param("until") Instant until, Pageable pageable);

    /**
     * Records successful preparation, including an empty result, without overwriting its first completion time.
     *
     * @return 1 if recorded, 0 if already recorded or the job no longer exists
     */
    @WorkspaceAgnostic("ID-based lane completion mark; job ID from the lane's own event or the recovery sweep")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.inChatPreparedAt = :at WHERE j.id = :id AND j.inChatPreparedAt IS NULL")
    int markInChatPrepared(@Param("id") UUID id, @Param("at") Instant at);

    /** The in-app lane's half of {@link #markInChatPrepared}. */
    @WorkspaceAgnostic("ID-based lane completion mark; job ID from the lane's own event or the recovery sweep")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.inAppPreparedAt = :at WHERE j.id = :id AND j.inAppPreparedAt IS NULL")
    int markInAppPrepared(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Like {@link #transitionToCancelled}, fenced to the owning worker: a draining worker must not
     * cancel a sibling's run if the job was orphan-requeued out from under it.
     *
     * @return rows updated (0 or 1)
     */
    @WorkspaceAgnostic("ID-based fenced cancel; job ID + owner from worker-local drain context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.CANCELLED, "
            + "j.completedAt = :now, j.errorMessage = :error, j.cancellationReason = :reason "
            + "WHERE j.id = :id AND j.status IN :fromStatuses AND j.workerId = :workerId")
    int transitionToCancelledOwnedBy(
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("error") String error,
            @Param("reason") AgentJobCancellationReason reason,
            @Param("fromStatuses") Collection<AgentJobStatus> fromStatuses,
            @Param("workerId") String workerId);

    /** Persists the accounting boundary immediately before sandbox/provider execution. */
    @WorkspaceAgnostic("ID-based execution-start fence; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.executionStartedAt = :now "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.executionStartedAt IS NULL "
            + "AND ((:workerId IS NULL AND j.workerId IS NULL) OR j.workerId = :workerId)")
    int markExecutionStarted(
            @Param("id") UUID id, @Param("workerId") @Nullable String workerId, @Param("now") Instant now);

    /** Written before the sandbox starts, so a failed or cancelled run still records what it consumed. */
    @WorkspaceAgnostic("ID-based provenance stamp; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.promptDigest = :#{#stamp.promptDigest}, "
            + "j.inputsDigest = :#{#stamp.inputsDigest}, "
            + "j.evidenceSnapshot = :#{#stamp.evidenceSnapshot}, "
            + "j.reviewReadiness = :#{#stamp.reviewReadiness} "
            + "WHERE j.id = :id AND j.status = 'RUNNING' "
            + "AND ((:workerId IS NULL AND j.workerId IS NULL) OR j.workerId = :workerId) "
            + "AND j.retryCount = :retryCount")
    int updateProvenanceDigests(
            @Param("id") UUID id,
            @Param("workerId") @Nullable String workerId,
            @Param("retryCount") int retryCount,
            @Param("stamp") ProvenanceStamp stamp);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.metadata = :metadata WHERE j.id = :id AND j.workspace.id = :workspaceId "
            + "AND j.status = 'RUNNING' AND j.retryCount = :retryCount "
            + "AND ((:workerId IS NULL AND j.workerId IS NULL) OR j.workerId = :workerId) "
            + "AND j.executionStartedAt IS NULL AND " + NOT_ADMITTED)
    int recordLinkedRepairSuccessor(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("workerId") @Nullable String workerId,
            @Param("retryCount") int retryCount,
            @Param("metadata") JsonNode metadata);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE agent_job SET metadata = jsonb_set(metadata, '{"
                    + LinkedRepairFollowupRequest.SUCCESSOR_REVISION + "}', to_jsonb(CAST(:nextRevision AS text))) "
                    + "WHERE id = :id AND workspace_id = :workspaceId AND status = 'COMPLETED' "
                    + "AND metadata ->> '" + LinkedRepairFollowupRequest.SUCCESSOR_REVISION + "' = :previousRevision "
                    + "AND metadata ->> '" + LinkedRepairFollowupRequest.ROOT_JOB + "' = CAST(:rootJobId AS text) "
                    + "AND COALESCE(metadata ->> '" + ObservationAdmissionService.DIGEST_METADATA_KEY + "', '') = ''",
            nativeQuery = true)
    int advanceLinkedRepairSuccessor(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("rootJobId") UUID rootJobId,
            @Param("previousRevision") String previousRevision,
            @Param("nextRevision") String nextRevision);

    @Query(
            value =
                    "SELECT j.* FROM agent_job j WHERE j.workspace_id = :workspaceId "
                            + "AND j.job_type = 'PULL_REQUEST_REVIEW' AND j.purpose = 'PRACTICE_REVIEW' "
                            + "AND j.status = 'COMPLETED' AND (" + ReviewRunTargetMapper.WORK_ID_SQL
                            + ") = CAST(:artifactId AS text) "
                            + "AND COALESCE(j.metadata ->> '" + ObservationAdmissionService.DIGEST_METADATA_KEY
                            + "', '') = '' "
                            + "AND j.metadata ->> 'signal' = 'scm.pull_request.linked_issue_updated' "
                            + "AND j.metadata ->> '" + LinkedRepairFollowupRequest.SUCCESSOR_REVISION + "' = :revision "
                            + "AND j.created_at >= :since AND j.created_at <= :until ORDER BY j.created_at DESC, j.id DESC LIMIT 1",
            nativeQuery = true)
    Optional<AgentJob> findLinkedRepairPredecessor(
            @Param("workspaceId") long workspaceId,
            @Param("artifactId") long artifactId,
            @Param("revision") String revision,
            @Param("since") Instant since,
            @Param("until") Instant until);

    /** Written as a unit so the evidence snapshot and the readiness decisions over it cannot diverge. */
    record ProvenanceStamp(
            @Nullable String promptDigest,
            @Nullable String inputsDigest,
            @Nullable JsonNode evidenceSnapshot,
            @Nullable JsonNode reviewReadiness) {}

    String EVIDENCE_REFUSAL_TRANSITION =
            "UPDATE AgentJob j SET j.status = 'COMPLETED', j.completedAt = :now, j.output = :output, "
                    + "j.deliveryStatus = 'DELIVERED', j.errorMessage = NULL WHERE j.id = :id AND j.status = 'RUNNING' "
                    + "AND ((:workerId IS NULL AND j.workerId IS NULL) OR j.workerId = :workerId) "
                    + "AND j.retryCount = :retryCount";

    // DELIVERED settles result processing here: no model output exists to process, and no recipient delivery is
    // claimed.
    @WorkspaceAgnostic("ID-based evidence-refusal transition; job ID + owner from worker-local execution context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(EVIDENCE_REFUSAL_TRANSITION)
    int transitionToEvidenceRefused(
            @Param("id") UUID id,
            @Param("workerId") @Nullable String workerId,
            @Param("retryCount") int retryCount,
            @Param("now") Instant now,
            @Param("output") JsonNode output);

    @WorkspaceAgnostic("ID-based measurement refusal; admitted writers retain their original owner")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(EVIDENCE_REFUSAL_TRANSITION + " AND " + NOT_ADMITTED)
    int transitionToStaleLinkedRepairRefused(
            @Param("id") UUID id,
            @Param("workerId") @Nullable String workerId,
            @Param("retryCount") int retryCount,
            @Param("now") Instant now,
            @Param("output") JsonNode output);

    /**
     * Poll-loop candidates, id-only because {@link #findByIdQueuedForUpdateSkipLocked} re-checks and
     * locks each one; a stale read here costs at most a skipped candidate.
     *
     * <p>Candidates whose slot is already at its {@code max_concurrent_jobs} cap are excluded. A slot is a
     * {@code (workspace, purpose, AI tier)} binding, the tier being the one the job's snapshot froze and
     * {@code UNDECLARED} where it names none, as {@link #countRunningByWorkspaceIdAndPurposeAndDataHandlingTier}
     * and the claim count it. Without the exclusion, one saturated slot with a deep backlog fills every LIMIT
     * window with jobs nobody can claim, and a younger runnable job elsewhere never reaches the batch. A
     * candidate whose slot has no binding row is still fetched — the claim's admission re-check is the
     * authoritative gate.
     */
    @WorkspaceAgnostic("Cross-workspace poll candidates; caller is the @WorkspaceAgnostic job poller")
    @Query(
            value = "SELECT j.id FROM agent_job j JOIN workspace w ON w.id = j.workspace_id "
                    + "WHERE j.status = 'QUEUED' "
                    + "AND w.status = 'ACTIVE' "
                    + "AND j.available_at <= now() "
                    + "AND ("
                    + "  (SELECT count(*) FROM agent_job r "
                    + "     WHERE r.workspace_id = j.workspace_id AND r.purpose = j.purpose AND r.status = 'RUNNING' "
                    + "       AND COALESCE(r.config_snapshot ->> 'dataHandlingTier', 'UNDECLARED') "
                    + "         = COALESCE(j.config_snapshot ->> 'dataHandlingTier', 'UNDECLARED')) "
                    + "  < COALESCE((SELECT b.max_concurrent_jobs FROM workspace_agent_binding b "
                    + "     WHERE b.workspace_id = j.workspace_id AND b.purpose = j.purpose "
                    + "       AND b.data_handling_tier = COALESCE(j.config_snapshot ->> 'dataHandlingTier', 'UNDECLARED')), "
                    + "     2147483647)"
                    + ") "
                    + "ORDER BY j.available_at ASC, j.id ASC LIMIT :limit",
            nativeQuery = true)
    List<UUID> findQueuedIdsOldestFirst(@Param("limit") int limit);

    @WorkspaceAgnostic("Cross-workspace stale job reaper; caller is @WorkspaceAgnostic sweeper")
    @Query("SELECT j FROM AgentJob j WHERE j.status = 'RUNNING' AND j.startedAt < :cutoff")
    List<AgentJob> findStaleRunningJobs(@Param("cutoff") Instant cutoff);

    /**
     * RUNNING jobs whose owning worker has no fresh heartbeat. Native so the liveness comparison stays
     * on the DB clock on both sides ({@code last_heartbeat} is written with the DB {@code now()}), and
     * no app/DB skew can produce a false orphan.
     */
    @WorkspaceAgnostic("Cross-workspace orphan recovery; caller is @WorkspaceAgnostic sweeper")
    @Query(
            value = "SELECT j.id AS jobId, j.workspace_id AS workspaceId, j.retry_count AS retryCount, "
                    + "j.worker_id AS workerId "
                    + "FROM agent_job j WHERE j.status = 'RUNNING' AND j.worker_id IS NOT NULL "
                    + "AND j.started_at < :graceCutoff "
                    + "AND NOT EXISTS (SELECT 1 FROM worker_registry w WHERE w.worker_id = j.worker_id "
                    + "AND w.last_heartbeat >= now() - make_interval(secs => :leaseTtlSeconds))",
            nativeQuery = true)
    List<OrphanedJobRef> findOrphanedRunningJobs(
            @Param("graceCutoff") Instant graceCutoff, @Param("leaseTtlSeconds") long leaseTtlSeconds);

    /**
     * CAS requeue of an orphaned or draining job: RUNNING → QUEUED, ownership cleared, retry_count
     * bumped.
     *
     * <p>Fenced on {@code worker_id}: status alone would let a belated requeue steal a job that a
     * different worker has since legitimately re-claimed. The retry cap is in the WHERE clause too, so
     * a caller that forgets to check it cannot requeue past the cap.
     *
     * <p>Rotates the job token so a merely partitioned zombie sandbox cannot keep authenticating
     * against the LLM proxy once a sibling re-claims the row, and zeroes the per-attempt LLM
     * accumulators: the caller bills the dead attempt before requeuing, so leaving the totals would
     * make the next attempt's terminal billing record the overlap a second time.
     *
     * @param availableAt when the requeued job becomes claimable again (now + backoff, so a
     *     crash-looping job cannot burn its whole retry budget in seconds)
     * @param newJobToken freshly generated plaintext token (encrypted at rest by the entity's converter)
     * @param newJobTokenHash {@code AgentJob.computeTokenHash(newJobToken)} — indexed lookup hash
     * @return 1 if this caller won the race, 0 otherwise
     */
    @WorkspaceAgnostic("ID-based orphan/drain requeue; caller is @WorkspaceAgnostic sweeper or worker-local drain")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = 'QUEUED', j.workerId = null, "
            + "j.startedAt = null, j.executionStartedAt = null, "
            + "j.retryCount = j.retryCount + 1, j.availableAt = :availableAt, "
            + "j.jobToken = :newJobToken, j.jobTokenHash = :newJobTokenHash, "
            + "j.llmTotalCalls = 0, j.llmTotalInputTokens = 0, j.llmTotalOutputTokens = 0, "
            + "j.llmTotalReasoningTokens = 0, j.llmCacheReadTokens = 0, j.llmCacheWriteTokens = 0 "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.workerId = :workerId AND j.retryCount < :maxRetries "
            + "AND " + NOT_ADMITTED)
    int requeueOrphan(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("maxRetries") int maxRetries,
            @Param("availableAt") Instant availableAt,
            @Param("newJobToken") String newJobToken,
            @Param("newJobTokenHash") String newJobTokenHash);

    /**
     * The admission's digest is absent. Requeuing an admitted attempt would discard the observations its
     * citations were verified for: the next attempt captures again and cannot submit against the digest
     * the job carries. The predicate sits in the UPDATE, so it is evaluated under the row lock the
     * admission takes, whatever the caller read before.
     */
    String NOT_ADMITTED = "COALESCE(FUNCTION('jsonb_extract_path_text', j.metadata, '"
            + ObservationAdmissionService.DIGEST_METADATA_KEY + "'), '') = ''";

    /**
     * Locks this exact owned attempt, as erasure's cancellation locks it before marking receipts, and finds it only
     * while it can be continued: no saved output, an admission recorded with its attempt, and that admission's kept
     * inputs current, unexpired and vouched for by the admitting attempt's READY receipt that indexes every person
     * they copied. A receipt erased, purged, unfinished or of another attempt finds nothing. Erasure that begins after
     * this lock cancels the requeued attempt; one that marked the receipt before it leaves the attempt to fail.
     */
    @WorkspaceAgnostic("ID-based fenced lookup; the basis and receipt joins pin the job's own workspace")
    @Query(
            value = """
            SELECT j.id FROM agent_job j
            JOIN review_composition_basis b ON b.job_id = j.id AND b.workspace_id = j.workspace_id
            JOIN person_evidence_copy c
              ON c.id = b.copy_id AND c.job_id = j.id AND c.workspace_id = j.workspace_id
            WHERE j.id = :id AND j.status = 'RUNNING' AND j.worker_id = :workerId AND j.retry_count = :attempt
              AND j.output IS NULL
              AND b.basis_version = :version AND b.expires_at > :now AND b.admission_attempt <= j.retry_count
              AND b.admission_digest = j.metadata ->> '""" + ObservationAdmissionService.DIGEST_METADATA_KEY + """
            '
              AND j.metadata ->> '"""
                    + ObservationAdmissionService.ATTEMPT_METADATA_KEY + """
            ' = CAST(b.admission_attempt AS text)
              AND c.state = 'READY' AND c.payload ->> 'dependencies' = 'COMPLETE'
              AND c.payload ->> 'attempt' = CAST(b.admission_attempt AS text)
            FOR NO KEY UPDATE OF j
            """,
            nativeQuery = true)
    List<UUID> lockContinuableAttempt(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("attempt") int attempt,
            @Param("version") int version,
            @Param("now") Instant now);

    /** The transition of {@link #lockContinuableAttempt}, once its attempt is locked and found continuable. */
    @WorkspaceAgnostic("ID-based fenced requeue; caller is @WorkspaceAgnostic sweeper or worker-local drain")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = 'QUEUED', j.workerId = null, "
            + "j.startedAt = null, j.executionStartedAt = null, "
            + "j.retryCount = j.retryCount + 1, j.availableAt = :#{#request.availableAt}, "
            + "j.jobToken = :#{#request.newJobToken}, j.jobTokenHash = :#{#request.newJobTokenHash}, "
            + "j.llmTotalCalls = 0, j.llmTotalInputTokens = 0, j.llmTotalOutputTokens = 0, "
            + "j.llmTotalReasoningTokens = 0, j.llmCacheReadTokens = 0, j.llmCacheWriteTokens = 0 "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.workerId = :workerId AND j.retryCount = :#{#request.attempt} "
            + "AND j.retryCount < :#{#request.maxRetries} AND j.output IS NULL AND NOT (" + NOT_ADMITTED + ") "
            + "AND EXISTS (SELECT 1 FROM ReviewCompositionBasis b WHERE b.jobId = j.id "
            + "AND b.workspaceId = j.workspace.id AND b.expiresAt > :#{#request.now} "
            + "AND b.admissionDigest = FUNCTION('jsonb_extract_path_text', j.metadata, '"
            + ObservationAdmissionService.DIGEST_METADATA_KEY + "'))")
    int requeueLockedForComposition(
            @Param("id") UUID id, @Param("workerId") String workerId, @Param("request") CompositionRequeue request);

    record CompositionRequeue(
            int attempt,
            int maxRetries,
            Instant now,
            Instant availableAt,
            String newJobToken,
            String newJobTokenHash) {}

    /**
     * Ends a RUNNING attempt whose observations were admitted but whose run was lost before it finished:
     * it fails with what is known, and its observations, capture and digest stay as recorded. Fenced on
     * the owning worker and the attempt, so a stale caller cannot end a run another claim started;
     * {@link #requeueOrphan} refuses exactly these rows.
     *
     * @return 1 if this caller ended the attempt, 0 if the row is not admitted or no longer that attempt
     */
    @WorkspaceAgnostic("ID-based fenced terminal write; caller is @WorkspaceAgnostic sweeper or worker-local drain")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.FAILED, "
            + "j.completedAt = :now, j.errorMessage = :error "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND j.workerId = :workerId AND j.retryCount = :attempt "
            + "AND NOT (" + NOT_ADMITTED + ")")
    int failAdmittedOwnedBy(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("attempt") int attempt,
            @Param("now") Instant now,
            @Param("error") String error);

    /**
     * Requeue of a claim this same worker just won but could not dispatch (sandbox executor pool
     * rejection). The job never started, so {@code retry_count} is left untouched — otherwise an
     * undersized sandbox pool would exhaust {@code max-retries} on jobs that never ran. {@code
     * available_at} is left untouched too: the row could only have been claimed because it was already
     * in the past, so it stays immediately reclaimable. No token rotation — no sandbox ever saw it.
     * {@code :workerId} is null only when the worker role runs with no identity configured.
     *
     * @return 1 if requeued, 0 if the row is no longer RUNNING-and-ours; the caller treats both the
     *     same, since either way the claim is gone
     */
    @WorkspaceAgnostic("ID-based self-fenced requeue; caller is the claiming worker itself")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.status = 'QUEUED', j.workerId = null, j.startedAt = null, "
            + "j.executionStartedAt = null "
            + "WHERE j.id = :id AND j.status = 'RUNNING' AND (:workerId IS NULL OR j.workerId = :workerId)")
    int requeueRejectedClaim(@Param("id") UUID id, @Param("workerId") @Nullable String workerId);

    @WorkspaceAgnostic("ID-based delivery update; job ID from workspace-scoped delivery context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.deliveryStatus = :status, j.deliveryCommentId = :commentId " + "WHERE j.id = :id")
    void updateDeliveryStatus(
            @Param("id") UUID id,
            @Param("status") DeliveryStatus status,
            @Param("commentId") @Nullable String commentId);

    @WorkspaceAgnostic("Dispatch recovery carries both the tenant id and job id")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE AgentJob j SET j.deliveryStatus = :status, j.deliveryCommentId = :commentId "
            + "WHERE j.id = :id AND j.workspace.id = :workspaceId")
    int reconcileDispatchDeliveryStatus(
            @Param("id") UUID id,
            @Param("workspaceId") Long workspaceId,
            @Param("status") DeliveryStatus status,
            @Param("commentId") @Nullable String commentId);

    /** @return 1 if transitioned, 0 if the row no longer matches the expected job/delivery statuses. */
    @WorkspaceAgnostic("ID-based delivery transition; job ID from workspace-scoped context")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.deliveryStatus = :newStatus "
            + "WHERE j.id = :id AND j.status = 'COMPLETED' AND j.deliveryStatus IN :fromStatuses")
    int transitionDeliveryStatus(
            @Param("id") UUID id,
            @Param("newStatus") DeliveryStatus newStatus,
            @Param("fromStatuses") Collection<DeliveryStatus> fromStatuses);

    /**
     * Bounded by {@code pageable} so one sweep pass never loads an unbounded backlog, and narrowed to
     * what the sweep decides on: most candidates are skipped or exhausted, and only the one that wins
     * its attempt is worth reading whole.
     */
    @WorkspaceAgnostic("Cross-workspace delivery-recovery sweep; caller is @WorkspaceAgnostic sweeper")
    @Query("SELECT j.id AS id, j.deliveryAttempts AS deliveryAttempts, j.deliveryCommentId AS deliveryCommentId "
            + "FROM AgentJob j WHERE j.status = 'COMPLETED' AND j.deliveryStatus = 'PENDING' "
            + "AND j.completedAt < :cutoff ORDER BY j.completedAt ASC")
    List<StuckDeliveryRow> findStuckPendingDeliveries(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * The one candidate a sweep pass won its attempt on, read whole because the delivery needs the
     * output the review produced. The status and delivery predicates repeat the sweep's own, so a row
     * that finished or failed in between is simply not there.
     */
    @WorkspaceAgnostic("ID-based read of a delivery-recovery candidate; job ID from the @WorkspaceAgnostic sweep")
    @Query("SELECT j FROM AgentJob j WHERE j.id = :id AND j.status = 'COMPLETED' " + "AND j.deliveryStatus = 'PENDING'")
    Optional<AgentJob> findDeliveryRecoveryCandidate(@Param("id") UUID id);

    /**
     * Increments {@code delivery_attempts} only if it still matches {@code expectedAttempts}, so two
     * concurrent sweeper passes cannot both re-post the same stuck delivery. The winner (1) proceeds
     * with the redelivery; a loser (0) skips this pass.
     */
    @WorkspaceAgnostic("ID-based delivery-recovery CAS; job ID from workspace-scoped sweep candidate")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.deliveryAttempts = j.deliveryAttempts + 1 "
            + "WHERE j.id = :id AND j.status = 'COMPLETED' AND j.deliveryStatus = 'PENDING' "
            + "AND j.deliveryAttempts = :expectedAttempts")
    int claimDeliveryRecoveryAttempt(@Param("id") UUID id, @Param("expectedAttempts") short expectedAttempts);

    /**
     * Terminal write for a delivery-recovery attempt, fenced on the {@code delivery_attempts} value the
     * caller's own {@link #claimDeliveryRecoveryAttempt} claimed. That counter is not a lease, so a slow
     * attempt spanning several sweep passes can be superseded by a later one; without the fence,
     * whichever finished last would win and a stale FAILED could clobber a DELIVERED.
     *
     * @return 1 if this attempt's result was recorded; 0 if a later attempt superseded it — treat as
     *     lost and do not retry the write
     */
    @WorkspaceAgnostic("ID-based fenced delivery-recovery terminal write; job ID from workspace-scoped sweep candidate")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AgentJob j SET j.deliveryStatus = :newStatus, j.deliveryCommentId = :commentId "
            + "WHERE j.id = :id AND j.status = 'COMPLETED' AND j.deliveryStatus IN :fromStatuses "
            + "AND j.deliveryAttempts = :expectedAttempts")
    int transitionDeliveryStatusFenced(
            @Param("id") UUID id,
            @Param("newStatus") DeliveryStatus newStatus,
            @Param("commentId") @Nullable String commentId,
            @Param("fromStatuses") Collection<DeliveryStatus> fromStatuses,
            @Param("expectedAttempts") short expectedAttempts);

    /**
     * Strips the heavy payload columns from terminal rows, batched so a large backlog is worked off in
     * many short transactions. Idempotent.
     *
     * <p>Excludes {@code delivery_status = 'PENDING'}: the delivery-recovery retry reads
     * {@code output}, so stripping it first would make a stuck delivery permanently undeliverable.
     */
    @WorkspaceAgnostic("Cross-workspace retention batch; caller is @WorkspaceAgnostic retention service")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "UPDATE agent_job SET container_logs = NULL, output = NULL " + "WHERE id IN ("
                    + "  SELECT id FROM agent_job "
                    + "  WHERE status IN ('COMPLETED','FAILED','TIMED_OUT','CANCELLED') "
                    + "  AND completed_at < :cutoff "
                    + "  AND delivery_status <> 'PENDING' "
                    + "  AND (container_logs IS NOT NULL OR output IS NOT NULL) "
                    + "  LIMIT :batchSize"
                    + ")",
            nativeQuery = true)
    int stripTerminalPayloads(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @WorkspaceAgnostic("Cross-workspace retention batch; caller is @WorkspaceAgnostic retention service")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "DELETE FROM agent_job WHERE id IN (" + "  SELECT j.id FROM agent_job j "
                    + "  WHERE j.status IN ('COMPLETED','FAILED','TIMED_OUT','CANCELLED') "
                    + "  AND j.completed_at < :cutoff "
                    + "  AND j.delivery_status <> 'PENDING' "
                    + "  AND NOT EXISTS (SELECT 1 FROM feedback f WHERE f.agent_job_id = j.id) "
                    + "  AND NOT EXISTS (SELECT 1 FROM observation o WHERE o.agent_job_id = j.id) "
                    + "  LIMIT :batchSize"
                    + ")",
            nativeQuery = true)
    int deleteUnreferencedTerminalRowsOlderThan(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * Depth, oldest-eligible-age, held count and running count in a single pass, so the scan cost does
     * not multiply exactly when an incident has inflated the backlog. {@code :now} is bound rather than
     * read from the DB clock, matching {@link #findByIdQueuedForUpdateSkipLocked}.
     *
     * <p>{@code depth} counts only jobs a worker could claim right now, which is what "are we keeping
     * up?" means — a job on a retry backoff is not work the fleet is behind on. {@code held} is counted
     * separately for the case that distinction hides: a workspace over its LLM cap has its whole backlog
     * pushed out of {@code depth}, so depth alone reads identically to an idle instance. Held is the
     * number that separates paused from idle.
     */
    @WorkspaceAgnostic("Fleet-wide queue-health snapshot; caller is @WorkspaceAgnostic health sampler")
    @Query(
            value = "SELECT " + "  COUNT(*) FILTER (WHERE status = 'QUEUED' AND available_at <= :now) AS depth, "
                    + "  MIN(available_at) FILTER (WHERE status = 'QUEUED' AND available_at <= :now) AS oldestAvailableAt, "
                    + "  COUNT(*) FILTER (WHERE status = 'QUEUED' AND hold_reason IS NOT NULL) AS held, "
                    + "  COUNT(*) FILTER (WHERE status = 'RUNNING') AS running "
                    + "FROM agent_job WHERE status IN ('QUEUED', 'RUNNING')",
            nativeQuery = true)
    QueueHealthSnapshot queueHealthSnapshot(@Param("now") Instant now);

    /** {@code oldestAvailableAt} is null when nothing is eligible. */
    interface QueueHealthSnapshot {
        long getDepth();

        @Nullable
        Instant getOldestAvailableAt();

        /** QUEUED jobs parked on a hold an admin can lift, as opposed to a retry backoff. */
        long getHeld();

        long getRunning();
    }

    /**
     * Per-practice readiness outcomes over the most recent reviews of a workspace.
     *
     * <p>One statement, because two would take two snapshots under {@code READ COMMITTED} and a review
     * completing between them shifts the window: counts and blocking reasons would then describe
     * different sets of jobs. The {@code id} tiebreaker keeps the window stable when a sync enqueues
     * several jobs in the same microsecond.
     *
     * <p>Native because the decisions live in a JSONB column and the useful shape is one row per
     * decision, which needs {@code jsonb_array_elements}.
     */
    @Query(
            value = "WITH recent AS (" + "  SELECT j.id, j.review_readiness FROM agent_job j"
                    + "   WHERE j.workspace_id = :workspaceId AND j.review_readiness IS NOT NULL"
                    + "   ORDER BY j.created_at DESC, j.id DESC LIMIT :window"
                    + "), decision AS ("
                    + "  SELECT d FROM recent,"
                    + "   jsonb_array_elements(recent.review_readiness -> 'decisions') d"
                    + "), counted AS ("
                    + "  SELECT d ->> 'practiceSlug' AS practice_slug,"
                    + "         count(*) AS considered,"
                    + "         count(*) FILTER (WHERE (d ->> 'ready')::boolean) AS reviewed"
                    + "    FROM decision GROUP BY 1"
                    + "), blocked AS ("
                    +
                    // A decision can be skipped without any source failing: the author set the practice to a
                    // mode that runs no model. Those carry a decision-level reason and no failing check, so
                    // reporting only source failures would leave the skip with no stated cause at all.
                    "  SELECT d ->> 'practiceSlug' AS practice_slug,"
                    + "         c ->> 'sourceKind' AS source_kind,"
                    + "         reason AS reason_code,"
                    + "         count(*) AS reviews"
                    + "    FROM decision,"
                    + "         jsonb_array_elements(d -> 'sourceChecks') c,"
                    + "         jsonb_array_elements_text(c -> 'reasonCodes') reason"
                    + "   WHERE NOT (d ->> 'ready')::boolean AND NOT (c ->> 'meetsRequirements')::boolean"
                    + "   GROUP BY 1, 2, 3"
                    + "   UNION ALL"
                    + "  SELECT d ->> 'practiceSlug', NULL, reason, count(*)"
                    + "    FROM decision, jsonb_array_elements_text(d -> 'reasonCodes') reason"
                    + "   WHERE NOT (d ->> 'ready')::boolean"
                    + "   GROUP BY 1, 2, 3"
                    + "), aggregated AS ("
                    + "  SELECT practice_slug, jsonb_agg(jsonb_build_object("
                    + "           'sourceKind', source_kind, 'reasonCode', reason_code, 'reviewsAffected', reviews"
                    + "         ) ORDER BY reviews DESC, source_kind NULLS FIRST, reason_code) AS blockers"
                    + "    FROM blocked GROUP BY 1"
                    + ")"
                    + " SELECT counted.practice_slug AS practiceSlug,"
                    + "        counted.considered AS consideredReviews,"
                    + "        counted.reviewed AS reviewedCount,"
                    + "        coalesce(aggregated.blockers, '[]'::jsonb)::text AS blockersObserved"
                    + "   FROM counted LEFT JOIN aggregated USING (practice_slug)"
                    + "  ORDER BY counted.practice_slug",
            nativeQuery = true)
    List<PracticeReadinessRow> findReadinessOutcomes(
            @Param("workspaceId") Long workspaceId, @Param("window") int window);

    interface PracticeReadinessRow {
        String getPracticeSlug();

        int getConsideredReviews();

        int getReviewedCount();

        /** A JSON array of blockers, aggregated by the query so one row is one practice. */
        String getBlockersObserved();
    }

    /**
     * One row of the workspace job listing: every column {@code AgentJobDTO} renders, and no other.
     * Each getter carries the nullness {@link AgentJob} declares for the same field, so a row and an
     * entity say the same thing about what may be absent.
     */
    interface AgentJobListRow extends ReviewRunTargetRow {
        AgentJobStatus getStatus();

        @Nullable
        JsonNode getOutput();

        JsonNode getConfigSnapshot();

        @Nullable
        Integer getExitCode();

        @Nullable
        String getErrorMessage();

        @Nullable
        DeliveryStatus getDeliveryStatus();

        @Nullable
        String getDeliveryCommentId();

        int getRetryCount();

        Instant getAvailableAt();

        @Nullable
        String getHoldReason();

        Instant getCreatedAt();

        @Nullable
        Instant getStartedAt();

        @Nullable
        Instant getExecutionStartedAt();

        @Nullable
        Instant getCompletedAt();

        @Nullable
        String getLlmModel();

        @Nullable
        String getLlmModelVersion();

        @Nullable
        Integer getLlmTotalCalls();

        @Nullable
        Integer getLlmTotalInputTokens();

        @Nullable
        Integer getLlmTotalOutputTokens();

        @Nullable
        Integer getLlmTotalReasoningTokens();

        @Nullable
        Integer getLlmCacheReadTokens();

        @Nullable
        Integer getLlmCacheWriteTokens();
    }

    /** What one delivery-recovery pass decides on before it reads a job whole. */
    interface StuckDeliveryRow {
        UUID getId();

        short getDeliveryAttempts();

        @Nullable
        String getDeliveryCommentId();
    }

    interface ReviewRunTargetRow {
        UUID getId();

        AgentJobType getJobType();

        @Nullable
        IntegrationKind getIntegrationKind();

        @Nullable
        JsonNode getMetadata();
    }

    interface ReviewRunSummaryRow extends ReviewRunTargetRow {
        AgentJobStatus getStatus();

        @Nullable
        String getReviewOutcome();

        @Nullable
        DeliveryStatus getDeliveryStatus();

        Instant getCreatedAt();
    }

    /**
     * The practice review queue of one workspace as it stands now, in one pass over its queued and running jobs:
     * how many run, how many distinct workers run them, how many wait other than
     * budget holds and since when the oldest of those became available to a worker, so a requeued review waits from
     * its requeue and one still backing off before a retry does not wait yet, and how many are held for budget. The reviews that finished
     * since {@code dayAgo}, and of those since {@code hourAgo}, are counted apart over the terminal jobs; a cancelled
     * review is not counted in these completion figures.
     */
    @Query(value = """
        SELECT
          active.running AS "running",
          active.workers AS "activeWorkers",
          active.waiting AS "waiting",
          active.oldest_waiting AS "oldestWaitingSince",
          active.held AS "heldForBudget",
          done.last_hour AS "finishedLastHour",
          done.last_day AS "finishedLastDay"
        FROM (
          SELECT COUNT(*) FILTER (WHERE j.status = 'RUNNING') AS running,
                 COUNT(DISTINCT j.worker_id) FILTER (WHERE j.status = 'RUNNING') AS workers,
                 COUNT(*) FILTER (WHERE j.status = 'QUEUED' AND j.hold_reason IS DISTINCT FROM :budget
                                    AND j.available_at <= :now) AS waiting,
                 MIN(j.available_at) FILTER (WHERE j.status = 'QUEUED' AND j.hold_reason IS DISTINCT FROM :budget
                                             AND j.available_at <= :now) AS oldest_waiting,
                 COUNT(*) FILTER (WHERE j.status = 'QUEUED' AND j.hold_reason = :budget) AS held
          FROM agent_job j
          WHERE j.workspace_id = :workspaceId AND j.purpose = :#{#purpose.name()}
            AND j.status IN ('QUEUED', 'RUNNING')
        ) active
        CROSS JOIN (
          SELECT COUNT(*) FILTER (WHERE j.completed_at >= :hourAgo) AS last_hour,
                 COUNT(*) AS last_day
          FROM agent_job j
          WHERE j.workspace_id = :workspaceId AND j.purpose = :#{#purpose.name()}
            AND j.status IN ('COMPLETED', 'FAILED', 'TIMED_OUT')
            AND j.completed_at >= :dayAgo
        ) done
        """, nativeQuery = true)
    ReviewQueueRow findReviewQueue(
            @Param("workspaceId") Long workspaceId,
            @Param("purpose") AgentPurpose purpose,
            @Param("budget") String budget,
            @Param("now") Instant now,
            @Param("hourAgo") Instant hourAgo,
            @Param("dayAgo") Instant dayAgo);

    interface ReviewQueueRow {
        long getRunning();

        long getActiveWorkers();

        long getWaiting();

        @Nullable
        Instant getOldestWaitingSince();

        long getHeldForBudget();

        long getFinishedLastHour();

        long getFinishedLastDay();
    }

    /**
     * The practice reviews at the head of one workspace's queue: the running ones first, longest running first,
     * then the queued ones, longest available first, and the budget holds last, since nothing runs them until the cap
     * is lifted. A review still backing off before a retry is left out until it is available, as in
     * {@link #findReviewQueue}. The page bounds how many are read.
     */
    @Query("SELECT j.id AS id, j.status AS status, j.jobType AS jobType, j.integrationKind AS integrationKind, "
            + "j.metadata AS metadata, j.holdReason AS holdReason, j.createdAt AS createdAt, "
            + "j.startedAt AS startedAt, j.availableAt AS availableAt FROM AgentJob j "
            + "WHERE j.workspace.id = :workspaceId AND j.purpose = :purpose "
            + "AND j.status IN (de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.RUNNING, "
            + "de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.QUEUED) "
            + "AND (j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.RUNNING "
            + "OR j.holdReason = :budget OR j.availableAt <= :now) "
            + "ORDER BY CASE WHEN j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.RUNNING THEN 0 "
            + "WHEN j.holdReason IS NULL OR j.holdReason <> :budget THEN 1 ELSE 2 END, "
            + "CASE WHEN j.status = de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus.RUNNING "
            + "THEN COALESCE(j.startedAt, j.createdAt) ELSE j.availableAt END, j.id")
    List<ReviewQueueItemRow> findReviewQueueHead(
            @Param("workspaceId") Long workspaceId,
            @Param("purpose") AgentPurpose purpose,
            @Param("budget") String budget,
            @Param("now") Instant now,
            Pageable pageable);

    interface ReviewQueueItemRow extends ReviewRunTargetRow {
        AgentJobStatus getStatus();

        @Nullable
        String getHoldReason();

        Instant getCreatedAt();

        @Nullable
        Instant getStartedAt();

        Instant getAvailableAt();
    }
}

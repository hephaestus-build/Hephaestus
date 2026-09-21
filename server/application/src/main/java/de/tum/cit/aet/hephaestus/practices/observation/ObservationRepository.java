package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.dto.DeveloperPracticeSummaryProjection;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for immutable practice observations with idempotent insertion.
 */
@Repository
public interface ObservationRepository extends JpaRepository<Observation, UUID> {
    /**
     * Excludes observations about an artifact in a repository a workspace team has hidden from contributions.
     *
     * <p>One text, concatenated into every native query that needs it, because five hand-kept copies is five
     * chances for one of them to be forgotten — which is exactly what happened to the review-runs queries.
     * Adding a surface now means adding this constant to it, and a surface that omits it omits it visibly.
     *
     * <p>Binds to the alias {@code f} (the observation), so every query using it must name it that way. Native
     * rather than JPQL: it reaches the integration module's
     * {@code issue} table and the workspace module's team settings, neither of which the practices module may
     * hold an entity reference to.
     */
    String HIDDEN_REPOSITORY_GUARD = """
                  AND NOT EXISTS (
                      SELECT 1
                      FROM issue target_artifact
                      JOIN workspace_team_repository_settings wtrs
                        ON wtrs.workspace_id = f.workspace_id
                       AND wtrs.repository_id = target_artifact.repository_id
                       AND wtrs.hidden_from_contributions = true
                      WHERE f.artifact_kind IN ('scm.pull_request', 'scm.issue')
                        AND target_artifact.id = f.artifact_id
                  )
        """;

    @EntityGraph(attributePaths = {"practice.currentRevision", "practiceRevision"})
    @Query("SELECT f FROM Observation f WHERE f.id = :id AND f.workspaceId = :workspaceId")
    Optional<Observation> findByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /**
     * The {@link #findByIdAndWorkspaceId} answer for a whole set of ids, in one round trip.
     *
     * <p>Same workspace predicate and the same entity graph as the single-id form. The graph is the point
     * as much as the batching is: {@code ReviewClaimCurrentness} compares the evaluated
     * {@code practiceRevision} against {@code practice.currentRevision}, so a batch that dropped it would
     * trade one N+1 for a lazier one.
     *
     * <p>An id with no row in the result is an id the caller may not read — an observation that does not
     * exist and one belonging to another workspace collapse into the same absence the single-id
     * {@link Optional} reports empty. Callers guard an empty {@code ids}.
     */
    @EntityGraph(attributePaths = {"practice.currentRevision", "practiceRevision"})
    @Query("SELECT f FROM Observation f WHERE f.id IN :ids AND f.workspaceId = :workspaceId")
    List<Observation> findAllByIdInAndWorkspaceId(
            @Param("ids") Collection<UUID> ids, @Param("workspaceId") Long workspaceId);

    /**
     * All observations a given agent job produced — the source set the feedback ledger recorder binds to.
     * Ordered by id so {@code get(0)} is deterministic across retries: the recorder derives the recipient,
     * artifact, and thread key from the first row, and an unordered read could re-source them differently on
     * a re-run of a multi-subject / multi-artifact job.
     */
    @EntityGraph(attributePaths = {"practice", "practiceRevision"})
    @Query(
            "SELECT f FROM Observation f WHERE f.agentJobId = :agentJobId AND f.workspaceId = :workspaceId ORDER BY f.id ASC")
    List<Observation> findByAgentJobId(@Param("agentJobId") UUID agentJobId, @Param("workspaceId") Long workspaceId);

    @Query(value = """
        SELECT o.agent_job_id AS "jobId",
               COUNT(*) FILTER (WHERE o.assessment = 'GOOD') AS "strengths",
               COUNT(*) FILTER (WHERE o.assessment = 'BAD') AS "problems",
               COUNT(*) FILTER (WHERE o.presence = 'NOT_APPLICABLE') AS "notApplicable",
               COUNT(*) FILTER (WHERE o.presence = 'INCONCLUSIVE') AS "inconclusive"
        FROM observation o
        WHERE o.workspace_id = :workspaceId
          AND o.agent_job_id IN :jobIds
        GROUP BY o.agent_job_id
        """, nativeQuery = true)
    List<ReviewObservationCounts> summarizeReviewObservations(
            @Param("workspaceId") Long workspaceId, @Param("jobIds") Collection<UUID> jobIds);

    interface ReviewObservationCounts {
        UUID getJobId();

        Long getStrengths();

        Long getProblems();

        Long getNotApplicable();
        /**
         * Runs where the practice looked and could not settle the question. Counted apart from
         * {@link #getNotApplicable()}: both are silence on the artifact, but an operator reading a review
         * summary needs "nothing here to judge" told apart from "we could not tell".
         */
        Long getInconclusive();
    }

    /**
     * Atomically inserts a practice observation if absent (race-condition safe).
     *
     * <p>Uses PostgreSQL's ON CONFLICT DO NOTHING to handle concurrent inserts.
     * This avoids the race condition where exists() check passes but save() fails
     * with DataIntegrityViolationException at transaction commit time.
     *
     * @return 1 if inserted, 0 if duplicate (conflict on occurrence_key)
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO observation (
            id, occurrence_key, agent_job_id, workspace_id, practice_id, practice_revision_id,
            artifact_kind, artifact_id, about_user_id,
            summary, presence, assessment, severity,
            evidence, evidence_rationale,
            recurrence_key, observed_at, origin
        )
        SELECT
            :id, :idempotencyKey, :agentJobId,
            p.workspace_id, p.id, COALESCE(:practiceRevisionId, p.current_revision_id),
            :artifactKind, :artifactId, :aboutUserId,
            :summary, :presence, :assessment, :severity,
            CAST(:evidence AS jsonb), :evidenceRationale,
            :recurrenceKey, :observedAt, :origin
        FROM practice p
        WHERE p.id = :practiceId AND p.workspace_id = :workspaceId
        ON CONFLICT (occurrence_key) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("agentJobId") UUID agentJobId,
            @Param("workspaceId") Long workspaceId,
            @Param("practiceId") Long practiceId,
            @Param("practiceRevisionId") @Nullable Long practiceRevisionId,
            @Param("artifactKind") String artifactKind,
            @Param("artifactId") Long artifactId,
            @Param("aboutUserId") @Nullable Long aboutUserId,
            @Param("summary") String summary,
            @Param("presence") String presence,
            @Param("assessment") @Nullable String assessment,
            @Param("severity") @Nullable String severity,
            @Param("evidence") @Nullable String evidence,
            @Param("evidenceRationale") @Nullable String evidenceRationale,
            @Param("recurrenceKey") @Nullable String recurrenceKey,
            @Param("observedAt") Instant observedAt,
            @Param("origin") String origin);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM observation WHERE workspace_id = :workspaceId", nativeQuery = true)
    void deleteAllByPracticeWorkspaceId(@Param("workspaceId") Long workspaceId);

    /**
     * Hard-delete the {@code chat.conversation_thread} observations for a workspace whose {@code artifact_id} (the
     * {@code slack_thread} id) is one of {@code artifactIds} — the derived-content erasure the Slack module invokes
     * through {@link de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure} when a channel's consent is
     * withdrawn. the {@code artifactKind} + {@code artifactId} predicates keep PR/ISSUE observations
     * and other tenants' rows untouched. DB {@code ON DELETE CASCADE} clears any bound {@code feedback_observation} /
     * {@code reaction} children. Callers guard an empty {@code artifactIds}.
     *
     * @return the number of observations deleted
     */
    @Modifying
    @Transactional
    @Query("""
        DELETE FROM Observation o
        WHERE o.artifactKind = :artifactKind
          AND o.artifactId IN :artifactIds
          AND o.workspaceId = :workspaceId
        """)
    int deleteObservationsOfKind(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactKind") ArtifactKind artifactKind,
            @Param("artifactIds") Collection<Long> artifactIds);

    default int deleteConversationThreadObservations(Long workspaceId, Collection<Long> artifactIds) {
        return deleteObservationsOfKind(workspaceId, ArtifactKinds.CONVERSATION_THREAD, artifactIds);
    }

    /**
     * Hard-delete <em>every</em> {@code chat.conversation_thread} observation for a workspace — the whole-tenant erasure
     * the Slack module invokes through
     * {@link de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure#eraseAllConversationForWorkspace} on
     * app-uninstall / workspace-purge. Scoping and cascade behaviour match {@link #deleteObservationsOfKind}. Idempotent.
     *
     * @return the number of observations deleted
     */
    @Modifying
    @Transactional
    @Query("""
        DELETE FROM Observation o
        WHERE o.artifactKind IN :artifactKinds
          AND o.workspaceId = :workspaceId
        """)
    int deleteAllObservationsOfKinds(
            @Param("workspaceId") Long workspaceId, @Param("artifactKinds") Collection<ArtifactKind> artifactKinds);

    default int deleteAllConversationThreadObservations(Long workspaceId) {
        return deleteAllObservationsOfKinds(workspaceId, List.of(ArtifactKinds.CONVERSATION_THREAD));
    }

    /**
     * Hard-delete every {@code scm.pull_request} / {@code scm.issue} observation for a workspace — the
     * SCM-derived counterpart of {@link #deleteAllConversationThreadObservations}, invoked when the
     * workspace's SCM mirror is erased on connection-disconnect or workspace-purge. The
     * {@code evidence} jsonb quotes mirrored diff/comment content verbatim and {@code artifact_id} is
     * a soft reference (no FK to {@code issue}/{@code pull_request}), so these rows would otherwise
     * outlive the artifacts they describe. Scoping and cascade behaviour match
     * {@link #deleteObservationsOfKind}. Idempotent.
     *
     * @return the number of observations deleted
     */
    @Modifying
    @Transactional
    @Query("""
        DELETE FROM Observation o
        WHERE o.artifactKind IN :artifactKinds
          AND o.workspaceId = :workspaceId
        """)
    int deleteAllScmObservationsOfKinds(
            @Param("workspaceId") Long workspaceId, @Param("artifactKinds") Collection<ArtifactKind> artifactKinds);

    default int deleteAllScmArtifactObservations(Long workspaceId) {
        return deleteAllScmObservationsOfKinds(workspaceId, List.of(ArtifactKinds.PULL_REQUEST, ArtifactKinds.ISSUE));
    }

    /**
     * Hard-delete the {@code chat.conversation_thread} observations a single person is the <em>subject</em> of
     * ({@code about_user_id = :aboutUserId}) within a workspace — the derived-content half of a person opt-out /
     * account hard-delete, invoked through
     * {@link de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure#eraseConversationFeedbackAboutUser}.
     * Scoping and cascade behaviour match {@link #deleteObservationsOfKind}. Idempotent.
     *
     * @return the number of observations deleted
     */
    @Modifying
    @Transactional
    @Query("""
        DELETE FROM Observation o
        WHERE o.artifactKind = :artifactKind
          AND o.aboutUserId = :aboutUserId
          AND o.workspaceId = :workspaceId
        """)
    int deleteObservationsOfKindAboutUser(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactKind") ArtifactKind artifactKind,
            @Param("aboutUserId") Long aboutUserId);

    default int deleteConversationThreadObservationsAboutUser(Long workspaceId, Long aboutUserId) {
        return deleteObservationsOfKindAboutUser(workspaceId, ArtifactKinds.CONVERSATION_THREAD, aboutUserId);
    }

    // Read queries for the developer dashboard.

    /**
     * Paginated observations for an about-user within a workspace, with optional filters.
     *
     * <p>Workspace scoping is done via the {@code Practice.workspace} join. The about-user is the
     * {@code about_user_id} subject the observation is filed against (ADR 0022).
     * Uses a separate {@code countQuery} because {@code JOIN FETCH} is incompatible
     * with count projections in Hibernate.
     *
     * <p>The group join is a LEFT JOIN on purpose: an implicit {@code p.group.slug} path would
     * inner-join and silently drop observations of group-less practices even when no group filter
     * is set. {@code displayableOnly} drops NOT_APPLICABLE rows — the activity-feed surface shows
     * what the reviewer actually observed, not the practices that did not apply to an artifact.
     *
     * <p>{@code hasArtifactKinds} guards the artifact-kind filter separately because JPQL cannot
     * test a collection parameter for null, and {@code IN} over an empty list is invalid SQL —
     * callers pass {@code false} plus any non-empty placeholder collection to disable the filter.
     */
    @EntityGraph(attributePaths = {"practice.currentRevision", "practiceRevision"})
    @Query(value = """
        SELECT f FROM Observation f
        JOIN FETCH f.practice p
        LEFT JOIN p.group a
        WHERE f.aboutUserId = :aboutUserId
        AND f.workspaceId = :workspaceId
        AND (:practiceSlug IS NULL OR p.slug = :practiceSlug)
        AND (:groupSlug IS NULL OR a.slug = :groupSlug)
        AND (:presence IS NULL OR f.presence = :presence)
        AND (:hasArtifactKinds = FALSE OR f.artifactKind IN :artifactKinds)
        AND (:hasSeverities = FALSE OR f.severity IS NULL OR f.severity IN :severities)
        AND (:displayableOnly = FALSE OR f.presence <> de.tum.cit.aet.hephaestus.practices.model.Presence.NOT_APPLICABLE)
        """, countQuery = """
        SELECT COUNT(f) FROM Observation f
        JOIN f.practice p
        LEFT JOIN p.group a
        WHERE f.aboutUserId = :aboutUserId
        AND f.workspaceId = :workspaceId
        AND (:practiceSlug IS NULL OR p.slug = :practiceSlug)
        AND (:groupSlug IS NULL OR a.slug = :groupSlug)
        AND (:presence IS NULL OR f.presence = :presence)
        AND (:hasArtifactKinds = FALSE OR f.artifactKind IN :artifactKinds)
        AND (:hasSeverities = FALSE OR f.severity IS NULL OR f.severity IN :severities)
        AND (:displayableOnly = FALSE OR f.presence <> de.tum.cit.aet.hephaestus.practices.model.Presence.NOT_APPLICABLE)
        """)
    Page<Observation> findByAboutUserAndWorkspace(
            @Param("aboutUserId") Long aboutUserId,
            @Param("workspaceId") Long workspaceId,
            @Param("practiceSlug") @Nullable String practiceSlug,
            @Param("groupSlug") @Nullable String groupSlug,
            @Param("presence") @Nullable Presence presence,
            @Param("hasArtifactKinds") boolean hasArtifactKinds,
            @Param("artifactKinds") Collection<ArtifactKind> artifactKinds,
            @Param("hasSeverities") boolean hasSeverities,
            @Param("severities") Collection<Severity> severities,
            @Param("displayableOnly") boolean displayableOnly,
            Pageable pageable);

    /**
     * Same filter set as {@link #findByAboutUserAndWorkspace}, ordered by severity. The severity rank
     * is a fixed CASE (CRITICAL &gt; MAJOR &gt; MINOR &gt; INFO, severity-less strengths last, ties
     * broken newest-first) because a {@code Pageable} sort on the enum column would order
     * alphabetically; {@code severitySign} {@code +1} puts the most severe first, {@code -1} the
     * least severe (then strengths lead). Callers pass an UNSORTED pageable.
     *
     * <p>Same entity graph as the date-ordered form, and for the same reason: the row's DTO reports whether
     * the claim was measured against the practice's current rules, which reads both revisions. Without the
     * graph that read happens after the session closed and the whole page fails.
     */
    @EntityGraph(attributePaths = {"practice.currentRevision", "practiceRevision"})
    @Query(value = """
        SELECT f FROM Observation f
        JOIN FETCH f.practice p
        LEFT JOIN p.group a
        WHERE f.aboutUserId = :aboutUserId
        AND f.workspaceId = :workspaceId
        AND (:practiceSlug IS NULL OR p.slug = :practiceSlug)
        AND (:groupSlug IS NULL OR a.slug = :groupSlug)
        AND (:presence IS NULL OR f.presence = :presence)
        AND (:hasArtifactKinds = FALSE OR f.artifactKind IN :artifactKinds)
        AND (:hasSeverities = FALSE OR f.severity IS NULL OR f.severity IN :severities)
        AND (:displayableOnly = FALSE OR f.presence <> de.tum.cit.aet.hephaestus.practices.model.Presence.NOT_APPLICABLE)
        ORDER BY (CASE
            WHEN f.severity = de.tum.cit.aet.hephaestus.practices.model.Severity.CRITICAL THEN 0
            WHEN f.severity = de.tum.cit.aet.hephaestus.practices.model.Severity.MAJOR THEN 1
            WHEN f.severity = de.tum.cit.aet.hephaestus.practices.model.Severity.MINOR THEN 2
            WHEN f.severity = de.tum.cit.aet.hephaestus.practices.model.Severity.INFO THEN 3
            ELSE 4
        END) * :severitySign, f.observedAt DESC
        """, countQuery = """
        SELECT COUNT(f) FROM Observation f
        JOIN f.practice p
        LEFT JOIN p.group a
        WHERE f.aboutUserId = :aboutUserId
        AND f.workspaceId = :workspaceId
        AND (:practiceSlug IS NULL OR p.slug = :practiceSlug)
        AND (:groupSlug IS NULL OR a.slug = :groupSlug)
        AND (:presence IS NULL OR f.presence = :presence)
        AND (:hasArtifactKinds = FALSE OR f.artifactKind IN :artifactKinds)
        AND (:hasSeverities = FALSE OR f.severity IS NULL OR f.severity IN :severities)
        AND (:displayableOnly = FALSE OR f.presence <> de.tum.cit.aet.hephaestus.practices.model.Presence.NOT_APPLICABLE)
        """)
    Page<Observation> findByAboutUserAndWorkspaceSeverityFirst(
            @Param("aboutUserId") Long aboutUserId,
            @Param("workspaceId") Long workspaceId,
            @Param("practiceSlug") @Nullable String practiceSlug,
            @Param("groupSlug") @Nullable String groupSlug,
            @Param("presence") @Nullable Presence presence,
            @Param("hasArtifactKinds") boolean hasArtifactKinds,
            @Param("artifactKinds") Collection<ArtifactKind> artifactKinds,
            @Param("hasSeverities") boolean hasSeverities,
            @Param("severities") Collection<Severity> severities,
            @Param("displayableOnly") boolean displayableOnly,
            @Param("severitySign") int severitySign,
            Pageable pageable);

    /**
     * Review-history page at the run grain. Paging observations directly can split one review across pages,
     * which leaves the developer with an incomplete explanation of what the reviewer saw. This projection first
     * selects complete agent-job runs; {@link #findPracticeGroupReviewRunObservations} loads their observations next.
     *
     * <p>Carries {@link #HIDDEN_REPOSITORY_GUARD}, which is what keeps a hidden repository out of the whole
     * surface: the second query reads only the job ids this one returns, and an agent job reviews one
     * artifact, so a run dropped here takes its observations with it.
     */
    @Query(value = """
                    SELECT f.agent_job_id AS "jobId",
                           MAX(f.observed_at) AS "reviewedAt"
                    FROM observation f
                    JOIN practice p ON p.id = f.practice_id
                    JOIN practice_group a ON a.id = p.practice_group_id
                    WHERE f.about_user_id = :aboutUserId
                      AND f.workspace_id = :workspaceId
                      AND a.slug = :groupSlug
                      AND (:practiceSlug IS NULL OR p.slug = :practiceSlug)
                      AND (:artifactKinds IS NULL OR f.artifact_kind = ANY(string_to_array(:artifactKinds, ',')))
                      AND (:severities IS NULL OR f.severity = ANY(string_to_array(:severities, ',')))
                      AND f.presence <> 'NOT_APPLICABLE'
            """ + HIDDEN_REPOSITORY_GUARD + """
            GROUP BY f.agent_job_id
            ORDER BY MAX(f.observed_at) DESC, f.agent_job_id DESC
            """, nativeQuery = true)
    Slice<ReviewRunRow> findPracticeGroupReviewRuns(
            @Param("aboutUserId") Long aboutUserId,
            @Param("workspaceId") Long workspaceId,
            @Param("groupSlug") @Nullable String groupSlug,
            @Param("practiceSlug") @Nullable String practiceSlug,
            @Param("artifactKinds") @Nullable String artifactKinds,
            @Param("severities") @Nullable String severities,
            Pageable pageable);

    /**
     * Every observation in the group for the runs {@link #findPracticeGroupReviewRuns} returned. Filters select
     * matching runs; they do not truncate a selected review run.
     *
     * <p>Fetches both revisions because every row is handed straight to {@code ObservationVisibilityPolicy},
     * which reads the evaluated revision and the practice's current one to decide whether the claim still
     * speaks for the practice — lazily, that is one round trip per practice and revision on the page.
     */
    @EntityGraph(attributePaths = {"practice.currentRevision", "practiceRevision"})
    @Query("""
        SELECT o FROM Observation o
        JOIN FETCH o.practice p
        JOIN p.group a
        WHERE o.agentJobId IN :jobIds
          AND o.aboutUserId = :aboutUserId
          AND o.workspaceId = :workspaceId
          AND a.slug = :groupSlug
          AND o.presence <> de.tum.cit.aet.hephaestus.practices.model.Presence.NOT_APPLICABLE
        ORDER BY o.observedAt DESC, o.id ASC
        """)
    List<Observation> findPracticeGroupReviewRunObservations(
            @Param("jobIds") Collection<UUID> jobIds,
            @Param("aboutUserId") Long aboutUserId,
            @Param("workspaceId") Long workspaceId,
            @Param("groupSlug") @Nullable String groupSlug);

    interface ReviewRunRow {
        UUID getJobId();

        Instant getReviewedAt();
    }

    /**
     * Per-practice aggregation for the developer dashboard: present/good and bad counts, and last observation date.
     *
     * <p>Aggregates represent each target's current state: within the workspace, only the run with the newest
     * {@code (observed_at, agent_job_id)} tuple contributes.
     *
     * <p>Aggregate views have no team context, so a repository hidden by any workspace team is excluded. Raw
     * per-artifact fetches remain unfiltered.
     *
     * <p>Native (not JPQL) because the latest-run-per-target selection needs {@code ORDER BY ... LIMIT 1} in a
     * correlated subquery, which JPQL cannot express. Aliases are quoted so the JDBC column labels match the
     * {@link DeveloperPracticeSummaryProjection} getters exactly (Postgres folds unquoted identifiers to
     * lower-case). Enum columns compare against their {@code STRING} storage form. {@code goodCount} is
     * the strengths ({@code assessment='GOOD'}); {@code badCount} is the problems ({@code assessment='BAD'}).
     */
    @Query(value = """
                    SELECT p.slug AS "practiceSlug",
                           p.name AS "practiceName",
                           COUNT(f.id) AS "totalObservations",
                           SUM(CASE WHEN f.assessment = 'GOOD' THEN 1 ELSE 0 END) AS "goodCount",
                           SUM(CASE WHEN f.assessment = 'BAD' THEN 1 ELSE 0 END) AS "badCount",
                           MAX(f.observed_at) AS "lastObservedAt"
                    FROM observation f
                    JOIN practice p ON p.id = f.practice_id
                    WHERE f.about_user_id = :aboutUserId
                      AND f.workspace_id = :workspaceId
            """ + HIDDEN_REPOSITORY_GUARD + """
              AND f.origin <> 'BACKFILL'
              AND f.agent_job_id = (
                  SELECT f2.agent_job_id FROM observation f2
                  JOIN practice p2 ON p2.id = f2.practice_id
                  WHERE f2.workspace_id = f.workspace_id
                    AND f2.practice_id = f.practice_id
                    AND f2.about_user_id = f.about_user_id
                    AND f2.artifact_kind = f.artifact_kind
                    AND f2.artifact_id = f.artifact_id
                    AND f2.origin <> 'BACKFILL'
                  ORDER BY f2.observed_at DESC, f2.agent_job_id DESC
                  LIMIT 1
              )
            GROUP BY p.slug, p.name
            ORDER BY p.name ASC
            """, nativeQuery = true)
    List<DeveloperPracticeSummaryProjection> findSummaryByDeveloperAndWorkspace(
            @Param("aboutUserId") Long aboutUserId, @Param("workspaceId") Long workspaceId);

    /**
     * Single observation by ID within a workspace, restricted to a specific about-user.
     *
     * <p>Ownership is enforced in the query (not in Java) to avoid lazy-load
     * fragility and to keep the auth check atomic with the fetch.
     */
    @EntityGraph(attributePaths = {"practice.currentRevision", "practiceRevision"})
    @Query("""
        SELECT f FROM Observation f
        JOIN FETCH f.practice p
        WHERE f.id = :observationId
        AND f.aboutUserId = :aboutUserId
        AND f.workspaceId = :workspaceId
        """)
    Optional<Observation> findByIdAndDeveloperAndWorkspace(
            @Param("observationId") UUID observationId,
            @Param("aboutUserId") Long aboutUserId,
            @Param("workspaceId") Long workspaceId);

    @EntityGraph(attributePaths = {"practice.currentRevision", "practiceRevision"})
    @Query("""
        SELECT f FROM Observation f
        JOIN FETCH f.practice p
        WHERE f.artifactKind = :artifactKind
        AND f.artifactId = :pullRequestId
        AND f.workspaceId = :workspaceId
        ORDER BY f.observedAt DESC
        """)
    List<Observation> findByPullRequestAndWorkspace(
            @Param("artifactKind") ArtifactKind artifactKind,
            @Param("pullRequestId") Long pullRequestId,
            @Param("workspaceId") Long workspaceId);

    /**
     * A developer's recent observations, newest first, each piece of work answering with its latest run.
     *
     * <p>Re-review deduped (same grain as {@link #findSummaryByDeveloperAndWorkspace}): a re-pushed pull
     * request's observations do not repeat across the list. "Latest run" means the latest run that said
     * something about THIS claim — the subquery correlates on practice, subject, artifact and origin class
     * together, the rule {@link LatestRun#perClaim} states for a window already in memory. Native because the
     * latest-run selection needs {@code ORDER BY ... LIMIT 1} in a correlated subquery; the practice is loaded
     * lazily per observation rather than JOIN-fetched.
     *
     * <p>{@code verdictsOnly} decides whether a presence that does not {@link Presence#carriesValence() carry
     * valence} is listed. The context providers pass {@code true}: {@code NOT_APPLICABLE} would bury the
     * actionable {@code BAD}/{@code GOOD} rows within their page budget, and coaching on {@code INCONCLUSIVE}
     * would invite the mentor to invent a direction the measurement declined to take — both totals still reach
     * it via the presence-count summary. The work resolution passes {@code false}: an opportunity that produced
     * no verdict is skipped rather than counted, and only the row itself can say so.
     *
     * <p>Backfilled observations are included: a campaign's {@code BAD} observation on a developer's own work
     * is exactly what "what should I work on" is asking for. {@code PracticeStandingObservationDTO.origin()}
     * carries the class through so a surface can label a backfilled item rather than pass it off as live.
     */
    @Query(value = """
                    SELECT f.* FROM observation f
                    JOIN practice p ON p.id = f.practice_id
                    WHERE f.about_user_id = :aboutUserId
                      AND f.workspace_id = :workspaceId
            """ + HIDDEN_REPOSITORY_GUARD + """
              AND f.observed_at >= :since
              AND (:verdictsOnly = FALSE OR f.presence IN ('PRESENT', 'ABSENT'))
              AND f.agent_job_id = (
                  SELECT f2.agent_job_id FROM observation f2
                  WHERE f2.practice_id = f.practice_id
                    AND f2.about_user_id = f.about_user_id
                    AND f2.artifact_kind = f.artifact_kind AND f2.artifact_id = f.artifact_id
                    AND (f2.origin = 'BACKFILL') = (f.origin = 'BACKFILL')
                  ORDER BY f2.observed_at DESC, f2.agent_job_id DESC LIMIT 1
              )
            ORDER BY f.observed_at DESC
            """, nativeQuery = true)
    List<Observation> findRecentByDeveloperAndWorkspace(
            @Param("aboutUserId") Long aboutUserId,
            @Param("workspaceId") Long workspaceId,
            @Param("since") Instant since,
            @Param("verdictsOnly") boolean verdictsOnly,
            Pageable pageable);

    /**
     * Every observation about a developer inside a span, newest first, every run's rows and every presence:
     * the practice standing's one load, which it narrows in memory to each claim's latest run as of each
     * moment it is read at ({@link LatestRun#perClaim}), so that two standings of one developer come from one
     * query. Carries {@link #HIDDEN_REPOSITORY_GUARD} like every developer surface.
     */
    @Query(value = """
                    SELECT f.* FROM observation f
                    WHERE f.about_user_id = :aboutUserId
                      AND f.workspace_id = :workspaceId
            """ + HIDDEN_REPOSITORY_GUARD + """
              AND f.observed_at >= :since
              AND f.observed_at <= :until
            ORDER BY f.observed_at DESC
            """, nativeQuery = true)
    List<Observation> findByDeveloperAndWorkspaceBetween(
            @Param("aboutUserId") Long aboutUserId,
            @Param("workspaceId") Long workspaceId,
            @Param("since") Instant since,
            @Param("until") Instant until);

    /**
     * The developer's review runs, newest first: one row per agent job that recorded an observation about
     * them, dated by its newest observation and naming the one piece of work the job reviewed. Either bound
     * may be null; a run is inside the bounds when its date is after {@code since} and at or before
     * {@code until}. The lower bound is a row filter, since dropping rows at or before it leaves a later run's
     * newest observation as it was; the upper bound has to wait for the aggregate.
     *
     * <p>Every presence counts, including {@code NOT_APPLICABLE}: the question is when a review last ran on
     * this person's work, and a run that found nothing to judge still ran. The visibility gate is not applied
     * either, for the same reason. Carries {@link #HIDDEN_REPOSITORY_GUARD} like every developer surface.
     * Not folded into {@link #findPracticeGroupReviewRuns}: that one answers "which runs judged something in
     * this group" and drops {@code NOT_APPLICABLE}, so one query would need a presence switch on top of a
     * nullable group, which hides the difference instead of stating it.
     */
    @Query(value = """
                    SELECT f.agent_job_id AS "jobId",
                           MAX(f.observed_at) AS "reviewedAt",
                           MIN(f.artifact_kind) AS "artifactKind",
                           MIN(f.artifact_id) AS "artifactId"
                    FROM observation f
                    WHERE f.about_user_id = :aboutUserId
                      AND f.workspace_id = :workspaceId
            """ + HIDDEN_REPOSITORY_GUARD + """
              AND (CAST(:since AS timestamptz) IS NULL OR f.observed_at > CAST(:since AS timestamptz))
            GROUP BY f.agent_job_id
            HAVING (CAST(:until AS timestamptz) IS NULL OR MAX(f.observed_at) <= CAST(:until AS timestamptz))
            ORDER BY MAX(f.observed_at) DESC, f.agent_job_id DESC
            """, nativeQuery = true)
    List<DeveloperReviewRunRow> findDeveloperReviewRuns(
            @Param("aboutUserId") Long aboutUserId,
            @Param("workspaceId") Long workspaceId,
            @Param("since") @Nullable Instant since,
            @Param("until") @Nullable Instant until,
            Pageable pageable);

    interface DeveloperReviewRunRow extends ReviewRunRow {
        /** The raw column: a native-query projection is mapped from JDBC types, with no converter run. */
        String getArtifactKind();

        Long getArtifactId();
    }

    /**
     * When each practice first recorded an observation about the developer, keyed by practice slug — the
     * fact behind "first observed", which the look-back-bounded lists above cannot answer.
     */
    @Query(value = """
                    SELECT p.slug AS "practiceSlug", MIN(f.observed_at) AS "firstObservedAt"
                    FROM observation f
                    JOIN practice p ON p.id = f.practice_id
                    WHERE f.about_user_id = :aboutUserId
                      AND f.workspace_id = :workspaceId
            """ + HIDDEN_REPOSITORY_GUARD + """
            GROUP BY p.slug
            """, nativeQuery = true)
    List<FirstObservedRow> findFirstObservedAtByPractice(
            @Param("aboutUserId") Long aboutUserId, @Param("workspaceId") Long workspaceId);

    interface FirstObservedRow {
        String getPracticeSlug();

        Instant getFirstObservedAt();
    }

    /**
     * Severity histogram for a developer's observations within a workspace.
     * Returns {@code [severityName, count]} rows — caller maps to a name→count map.
     *
     * <p>Re-review deduped to each target's latest run (see {@link #findRecentByDeveloperAndWorkspace}) so
     * the mentor's "how am I doing" histogram reflects current state, not the re-push multiplier. Only
     * {@code BAD} observations carry a non-null severity, so the histogram is over problems.
     */
    @Query(value = """
                    SELECT f.severity AS severity, COUNT(f.id) AS count
                    FROM observation f
                    JOIN practice p ON p.id = f.practice_id
                    WHERE f.about_user_id = :aboutUserId
                      AND f.workspace_id = :workspaceId
            """ + HIDDEN_REPOSITORY_GUARD + """
              AND f.observed_at >= :since
              AND f.severity IS NOT NULL
              AND f.origin <> 'BACKFILL'
              AND f.agent_job_id = (
                  SELECT f2.agent_job_id FROM observation f2
                  JOIN practice p2 ON p2.id = f2.practice_id
                  WHERE f2.workspace_id = f.workspace_id
                    AND f2.practice_id = f.practice_id
                    AND f2.about_user_id = f.about_user_id
                    AND f2.artifact_kind = f.artifact_kind AND f2.artifact_id = f.artifact_id
                    AND f2.origin <> 'BACKFILL'
                  ORDER BY f2.observed_at DESC, f2.agent_job_id DESC LIMIT 1
              )
            GROUP BY f.severity
            """, nativeQuery = true)
    List<SeverityCount> countBySeverityForDeveloper(
            @Param("aboutUserId") Long aboutUserId,
            @Param("workspaceId") Long workspaceId,
            @Param("since") Instant since);

    /**
     * Presence histogram for a developer's observations within a workspace.
     *
     * <p>Aggregate policy matches {@link #findSummaryByDeveloperAndWorkspace}.
     */
    @Query(value = """
                    SELECT f.presence AS presence, COUNT(f.id) AS count
                    FROM observation f
                    JOIN practice p ON p.id = f.practice_id
                    WHERE f.about_user_id = :aboutUserId
                      AND f.workspace_id = :workspaceId
            """ + HIDDEN_REPOSITORY_GUARD + """
              AND f.observed_at >= :since
              AND f.origin <> 'BACKFILL'
              AND f.agent_job_id = (
                  SELECT f2.agent_job_id FROM observation f2
                  JOIN practice p2 ON p2.id = f2.practice_id
                  WHERE f2.workspace_id = f.workspace_id
                    AND f2.practice_id = f.practice_id
                    AND f2.about_user_id = f.about_user_id
                    AND f2.artifact_kind = f.artifact_kind AND f2.artifact_id = f.artifact_id
                    AND f2.origin <> 'BACKFILL'
                  ORDER BY f2.observed_at DESC, f2.agent_job_id DESC LIMIT 1
              )
            GROUP BY f.presence
            """, nativeQuery = true)
    List<PresenceCount> countByPresenceForDeveloper(
            @Param("aboutUserId") Long aboutUserId,
            @Param("workspaceId") Long workspaceId,
            @Param("since") Instant since);

    // Cross-run trend read path (ADR 0021) — the measurement substrate ObservationTrendService classifies.

    /**
     * The runs (agent jobs) that produced ≥1 correlation-keyed observation for a target, newest first by the
     * run's latest detection. Pass {@code PageRequest.of(0, 2)} to get the two most-recent runs to diff.
     * Workspace-scoped via {@code Practice.workspace}.
     *
     * <p>A {@code BACKFILL} run is never one of the two. The diff is read as "did this get fixed between
     * the two times we looked", and a sweep that looked at the artifact long after the fact would answer
     * that question with a difference in sampling rather than a difference in the work.
     */
    @Query("""
        SELECT f.agentJobId AS agentJobId, MAX(f.observedAt) AS runAt
        FROM Observation f JOIN f.practice p
        WHERE f.artifactKind = :artifactKind AND f.artifactId = :artifactId AND f.workspaceId = :workspaceId
          AND f.recurrenceKey IS NOT NULL
          AND f.origin <> de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin.BACKFILL
        GROUP BY f.agentJobId
        ORDER BY MAX(f.observedAt) DESC, f.agentJobId DESC
        """)
    List<RunRef> findRecentRunRefsForTarget(
            @Param("artifactKind") ArtifactKind artifactKind,
            @Param("artifactId") Long artifactId,
            @Param("workspaceId") Long workspaceId,
            Pageable pageable);

    /** All correlation-keyed observations for the given (already-resolved) run job-ids, with the trend fields. */
    @Query("""
        SELECT f.agentJobId AS agentJobId, f.recurrenceKey AS recurrenceKey, f.presence AS presence,
               f.assessment AS assessment, f.severity AS severity, p.slug AS practiceSlug,
               f.summary AS summary, f.observedAt AS observedAt
        FROM Observation f JOIN f.practice p
        WHERE f.agentJobId IN :agentJobIds AND f.workspaceId = :workspaceId AND f.recurrenceKey IS NOT NULL
          AND f.presence IN (de.tum.cit.aet.hephaestus.practices.model.Presence.PRESENT,
                             de.tum.cit.aet.hephaestus.practices.model.Presence.ABSENT)
        ORDER BY f.observedAt DESC
        """)
    List<LocusObservation> findLociByAgentJobs(
            @Param("agentJobIds") Collection<UUID> agentJobIds, @Param("workspaceId") Long workspaceId);

    /** Projection: one run (agent job) with its latest detection timestamp. */
    interface RunRef {
        UUID getAgentJobId();

        Instant getRunAt();
    }

    /**
     * Projection: the locus-identity + sign fields the trend classifier keys on (which run, which
     * recurrence locus, present-or-not, good-or-bad). Split out from {@link LocusObservation} so each
     * projection stays focused (ISP); {@link LocusObservation} extends it with the presentation fields.
     */
    interface LocusKey {
        UUID getAgentJobId();

        String getRecurrenceKey();

        Presence getPresence();

        @Nullable
        Assessment getAssessment();
    }

    /** Projection: a correlation-keyed observation reduced to the fields the trend classifier needs. */
    interface LocusObservation extends LocusKey {
        @Nullable
        Severity getSeverity();

        String getPracticeSlug();

        String getSummary();

        Instant getObservedAt();
    }

    /** Projection: severity → count. */
    interface SeverityCount {
        Severity getSeverity();

        Long getCount();
    }

    /** Projection: presence → count. */
    interface PresenceCount {
        Presence getPresence();

        Long getCount();
    }

    String OPERATOR_PREDICATES = """
          AND (CAST(:#{#f.practiceSlugArray()} AS text[]) IS NULL OR p.slug = ANY(CAST(:#{#f.practiceSlugArray()} AS text[])))
          AND (CAST(:#{#f.groupSlugArray()} AS text[]) IS NULL OR pa.slug = ANY(CAST(:#{#f.groupSlugArray()} AS text[])))
          AND (CAST(:#{#f.presenceNames()} AS text[]) IS NULL OR o.presence = ANY(CAST(:#{#f.presenceNames()} AS text[])))
          AND (CAST(:#{#f.assessmentNames()} AS text[]) IS NULL OR o.assessment = ANY(CAST(:#{#f.assessmentNames()} AS text[])))
          AND (CAST(:#{#f.severityNames()} AS text[]) IS NULL OR o.severity = ANY(CAST(:#{#f.severityNames()} AS text[])))
          AND (CAST(:#{#f.agentJobId()} AS uuid) IS NULL OR o.agent_job_id = CAST(:#{#f.agentJobId()} AS uuid))
          AND (CAST(:#{#f.artifactKindValue()} AS text) IS NULL OR o.artifact_kind = CAST(:#{#f.artifactKindValue()} AS text))
          AND (CAST(:#{#f.artifactId()} AS bigint) IS NULL OR o.artifact_id = CAST(:#{#f.artifactId()} AS bigint))
          AND (CAST(:#{#f.aboutUserId()} AS bigint) IS NULL OR o.about_user_id = CAST(:#{#f.aboutUserId()} AS bigint))
          AND (CAST(:#{#f.originNames()} AS text[]) IS NULL OR o.origin = ANY(CAST(:#{#f.originNames()} AS text[])))
          AND (CAST(:#{#f.from()} AS timestamptz) IS NULL OR o.observed_at >= CAST(:#{#f.from()} AS timestamptz))
          AND (CAST(:#{#f.to()} AS timestamptz) IS NULL OR o.observed_at < CAST(:#{#f.to()} AS timestamptz))
        """;

    @Query(value = """
            SELECT o.id AS "id",
                   o.agent_job_id AS "agentJobId",
                   p.slug AS "practiceSlug",
                   p.name AS "practiceName",
                   pa.slug AS "groupSlug",
                   pa.name AS "groupName",
                   pa.icon AS "groupIcon",
                   pa.color AS "groupColor",
                   o.artifact_kind AS "artifactKind",
                   o.artifact_id AS "artifactId",
                   o.about_user_id AS "aboutUserId",
                   o.summary AS "summary",
                   o.presence AS "presence",
                   o.assessment AS "assessment",
                   o.severity AS "severity",
                   o.recurrence_key AS "recurrenceKey",
                   o.origin AS "origin",
                   o.practice_revision_id AS "practiceRevisionId",
                   evaluated_revision.review_rule_fingerprint AS "practiceRevisionFingerprint",
                   current_revision.review_rule_fingerprint AS "currentPracticeRevisionFingerprint",
                   o.observed_at AS "observedAt"
            FROM observation o
            JOIN practice p ON p.id = o.practice_id
            LEFT JOIN practice_revision evaluated_revision ON evaluated_revision.id = o.practice_revision_id
            LEFT JOIN practice_revision current_revision ON current_revision.id = p.current_revision_id
            LEFT JOIN practice_group pa ON pa.id = p.practice_group_id
            WHERE o.workspace_id = :workspaceId
            """ + OPERATOR_PREDICATES + """
             ORDER BY
               CASE WHEN :prioritizeActionable THEN
                 CASE o.assessment WHEN 'BAD' THEN 0 WHEN 'GOOD' THEN 1 ELSE 2 END
               ELSE 0 END,
               CASE WHEN :prioritizeActionable AND o.assessment = 'BAD' THEN
                 CASE o.severity
                   WHEN 'CRITICAL' THEN 0
                   WHEN 'MAJOR' THEN 1
                   WHEN 'MINOR' THEN 2
                   WHEN 'INFO' THEN 3
                   ELSE 4
                 END
               ELSE 0 END,
               o.observed_at DESC,
               o.id DESC
            """, countQuery = """
            SELECT count(*)
            FROM observation o
            JOIN practice p ON p.id = o.practice_id
            LEFT JOIN practice_group pa ON pa.id = p.practice_group_id
            WHERE o.workspace_id = :workspaceId
            """ + OPERATOR_PREDICATES, nativeQuery = true)
    Page<OperatorObservationRow> findForWorkspace(
            @Param("workspaceId") Long workspaceId,
            @Param("f") ObservationQueryFilter filter,
            @Param("prioritizeActionable") boolean prioritizeActionable,
            Pageable pageable);

    interface OperatorObservationRow {
        UUID getId();

        UUID getAgentJobId();

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

        /** The raw column: a native-query projection is mapped from JDBC types, with no converter run. */
        String getArtifactKind();

        Long getArtifactId();

        @Nullable
        Long getAboutUserId();

        String getSummary();

        Presence getPresence();

        @Nullable
        Assessment getAssessment();

        @Nullable
        Severity getSeverity();

        @Nullable
        String getRecurrenceKey();

        /**
         * What occasioned the measurement. Without it the operator surface cannot tell a campaign's observations
         * from live ones, which is a population-mixing hazard in exactly the surface used to judge whether a
         * campaign was worth its cost.
         */
        ObservationOrigin getOrigin();

        @Nullable
        Long getPracticeRevisionId();

        @Nullable
        String getPracticeRevisionFingerprint();

        @Nullable
        String getCurrentPracticeRevisionFingerprint();

        Instant getObservedAt();
    }

    @Query(value = """
        SELECT fo.observation_id AS "observationId",
               COUNT(*) FILTER (WHERE f.delivery_state = 'PREPARED' OR
                   (f.delivery_state = 'PARTIALLY_DELIVERED' AND f.suppression_reason IS NULL)) AS "prepared",
               COUNT(*) FILTER (WHERE f.delivery_state = 'DELIVERED') AS "delivered",
               COUNT(*) FILTER (WHERE f.delivery_state = 'SUPERSEDED') AS "superseded",
               COUNT(*) FILTER (WHERE f.delivery_state = 'SUPPRESSED' OR
                   (f.delivery_state = 'PARTIALLY_DELIVERED' AND f.suppression_reason IS NOT NULL)) AS "suppressed",
               COUNT(*) FILTER (WHERE f.delivery_state IN ('FAILED', 'PARTIALLY_FAILED')) AS "failed"
        FROM feedback_observation fo
        JOIN feedback f ON f.id = fo.feedback_id
        WHERE fo.observation_id IN :observationIds
          AND f.workspace_id = :workspaceId
        GROUP BY fo.observation_id
        """, nativeQuery = true)
    List<ObservationFeedbackDisposition> findFeedbackDispositions(
            @Param("workspaceId") Long workspaceId, @Param("observationIds") Collection<UUID> observationIds);

    interface ObservationFeedbackDisposition {
        UUID getObservationId();

        Long getPrepared();

        Long getDelivered();

        Long getSuperseded();

        Long getSuppressed();

        Long getFailed();
    }

    @Query("""
        SELECT o.id AS observationId, p.autonomy AS practiceAutonomy, a.autonomy AS groupAutonomy
        FROM Observation o
        JOIN o.practice p
        LEFT JOIN p.group a
        WHERE o.id IN :observationIds AND o.workspaceId = :workspaceId
        """)
    List<ObservationPracticeAutonomy> findPracticeAutonomyFor(
            @Param("observationIds") Collection<UUID> observationIds, @Param("workspaceId") Long workspaceId);

    /**
     * The practice each of {@code observationIds} measures, by slug.
     *
     * <p>Projected for the same reason as {@link #findPracticeAutonomyFor}: the composition stage names a
     * practice and nothing else about the evidence, so this join is the whole of the match between what the
     * composer wrote and what was measured — and the producer that needs it is handed observations that may
     * already be detached. A lazy {@code o.practice.slug} there would make a composed message reach the
     * developer or not depending on whether the caller happened to hold a session.
     */
    @Query("""
        SELECT o.id AS observationId, p.slug AS practiceSlug
        FROM Observation o
        JOIN o.practice p
        WHERE o.id IN :observationIds AND o.workspaceId = :workspaceId
        """)
    List<ObservationPracticeSlug> practiceSlugsFor(
            @Param("observationIds") Collection<UUID> observationIds, @Param("workspaceId") Long workspaceId);

    /** One observation's practice slug, without loading either entity. */
    interface ObservationPracticeSlug {
        UUID getObservationId();

        @Nullable
        String getPracticeSlug();
    }

    interface ObservationPracticeAutonomy {
        UUID getObservationId();

        @Nullable
        PracticeAutonomy getPracticeAutonomy();

        @Nullable
        PracticeAutonomy getGroupAutonomy();
    }

    /**
     * Every measurement taken on one artifact, by practice — the trace view's evidence that a practice
     * ran at all.
     *
     * <p>Keyed off the artifact rather than off a run: a practice that produced a measurement must never
     * be reported as silent, including when its run predates the signal ledger or was never linked back.
     */
    @Query("""
        SELECT o.practice.id AS practiceId, o.agentJobId AS reviewId, o.observedAt AS observedAt
        FROM Observation o
        WHERE o.workspaceId = :workspaceId
          AND o.artifactKind = :artifactKind
          AND o.artifactId = :artifactId
        """)
    List<ArtifactObservationRow> findForArtifact(
            @Param("workspaceId") Long workspaceId,
            @Param("artifactKind") ArtifactKind artifactKind,
            @Param("artifactId") Long artifactId);

    interface ArtifactObservationRow {
        Long getPracticeId();

        UUID getReviewId();

        Instant getObservedAt();
    }

    /**
     * One person's own measurements of one practice inside a window — the evidence a process-level
     * message about that practice stands on.
     *
     * <p>Workspace-scoped through the practice, exactly as every other read here: {@code observation}
     * carries no workspace column of its own, so the join IS the tenancy predicate and dropping it would
     * make a pattern about one workspace citable in another.
     *
     * <p>Every run's rows, not only each artifact's latest: the window is bounded and the caller narrows it
     * to each piece of work's newest review through {@link LatestRun}, the one home of that rule, so that a
     * re-review of the same pull request is one occurrence and a problem it no longer found is none.
     */
    @EntityGraph(attributePaths = {"practice.currentRevision", "practiceRevision"})
    @Query("""
        SELECT o FROM Observation o
        JOIN o.practice p
        WHERE o.workspaceId = :workspaceId
          AND p.slug = :practiceSlug
          AND o.aboutUserId = :aboutUserId
          AND o.observedAt >= :since
        ORDER BY o.observedAt DESC
        """)
    List<Observation> findRecentForSubjectAndPractice(
            @Param("workspaceId") Long workspaceId,
            @Param("aboutUserId") Long aboutUserId,
            @Param("practiceSlug") @Nullable String practiceSlug,
            @Param("since") Instant since,
            Pageable pageable);

    /**
     * The distinct people this job filed measurements against — the recipients a cycle can compose for.
     *
     * <p>Ordered, because callers hand each recipient a slice of a fixed ordinal band and a re-run has to
     * assign the same slices for its idempotency guard to recognise what it already wrote.
     */
    @Query(
            "SELECT DISTINCT o.aboutUserId FROM Observation o WHERE o.agentJobId = :agentJobId AND o.workspaceId = :workspaceId ORDER BY o.aboutUserId")
    List<Long> findSubjectUserIdsByAgentJobId(
            @Param("agentJobId") UUID agentJobId, @Param("workspaceId") Long workspaceId);
}

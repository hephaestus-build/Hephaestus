package de.tum.cit.aet.hephaestus.practices.research;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Workspace-qualified metadata projections for one repeatable-read research snapshot. */
interface ResearchDatasetRepository extends Repository<Result, UUID> {
    String RESULTS =
            "SELECT r.id FROM result r WHERE r.workspace_id = :workspaceId AND r.person_account_id = ANY(CAST(:accounts AS bigint[])) AND r.recorded_at >= :from AND r.recorded_at < :to";

    String FEEDBACK =
            "SELECT f.id FROM feedback f WHERE f.workspace_id = :workspaceId AND f.person_account_id = ANY(CAST(:accounts AS bigint[])) AND f.created_at >= :from AND f.created_at < :to";

    /** Reviews that are parents of a candidate result or feedback piece. */
    String REVIEWS =
            "SELECT r.review_id FROM result r WHERE r.workspace_id = :workspaceId AND r.id IN (SELECT r.id FROM result r WHERE r.workspace_id = :workspaceId AND r.person_account_id = ANY(CAST(:accounts AS bigint[])) AND r.recorded_at >= :from AND r.recorded_at < :to) UNION SELECT f.review_id FROM feedback f WHERE f.workspace_id = :workspaceId AND f.id IN (SELECT f.id FROM feedback f WHERE f.workspace_id = :workspaceId AND f.person_account_id = ANY(CAST(:accounts AS bigint[])) AND f.created_at >= :from AND f.created_at < :to)";

    @WorkspaceAgnostic("The current transaction time reads no workspace-owned table")
    @Query(value = "SELECT now()", nativeQuery = true)
    Instant cutoff();

    @Query(value = "SELECT account_id FROM membership WHERE workspace_id = :workspaceId", nativeQuery = true)
    List<Long> members(long workspaceId);

    @Query(value = "SELECT count(*) FROM (" + RESULTS + ") x", nativeQuery = true)
    long countResults(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = "SELECT count(*) FROM (" + FEEDBACK + ") x", nativeQuery = true)
    long countFeedback(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = """
        SELECT r.id, r.review_id AS reviewId, r.practice_id AS practiceId, r.practice_version_id AS practiceVersionId, r.item_id AS itemId, r.person_account_id AS developerId,
               r.outcome, r.severity, r.evidence_version AS evidenceVersion, r.item_version IS NOT NULL AS itemVersionRecorded, r.coverage IS NOT NULL AS coverageRecorded,
               r.recorded_at AS recordedAt, r.ended_at AS endedAt, r.end_reason AS endReason,
               (SELECT m.kind FROM result_mark m
                 WHERE m.workspace_id = :workspaceId AND m.result_id = r.id AND m.removed_at IS NULL
                 ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS activeMark
          FROM result r
         WHERE r.workspace_id = :workspaceId AND r.id IN (
        """ + RESULTS + """
        )
        """, nativeQuery = true)
    List<ResultRow> results(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = """
        SELECT p.id, p.result_id AS resultId, p.position, p.severity FROM problem p
         WHERE p.workspace_id = :workspaceId AND p.result_id IN (
        """ + RESULTS + """
        )
        """, nativeQuery = true)
    List<ProblemRow> problems(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = """
        SELECT f.id, f.review_id AS reviewId, f.item_id AS itemId, f.person_account_id AS developerId, f.destination, f.state, f.stop_reason AS stopReason,
               f.created_at AS createdAt, f.delivered_at AS deliveredAt, f.replaces_id AS replacesId
          FROM feedback f
         WHERE f.workspace_id = :workspaceId AND f.id IN (
        """ + FEEDBACK + """
        )
        """, nativeQuery = true)
    List<FeedbackRow> feedback(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = """
        SELECT b.feedback_id AS feedbackId, b.result_id AS resultId, b.problem_id AS problemId, b.ordinal FROM feedback_basis b
         WHERE b.workspace_id = :workspaceId AND b.feedback_id IN (
        """ + FEEDBACK + """
        )
        """, nativeQuery = true)
    List<BasisRow> basis(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = """
        SELECT d.feedback_id AS feedbackId, d.action, d.decided_at AS decidedAt FROM feedback_decision d
         WHERE d.workspace_id = :workspaceId AND d.feedback_id IN (
        """ + FEEDBACK + """
        )
        """, nativeQuery = true)
    List<DecisionRow> decisions(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = """
        SELECT s.feedback_id AS feedbackId, s.resolution, s.usefulness, s.created_at AS createdAt FROM feedback_response s
         WHERE s.workspace_id = :workspaceId AND s.feedback_id IN (
        """ + FEEDBACK + """
        )
        """, nativeQuery = true)
    List<ResponseRow> responses(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = """
        SELECT v.id, v.kind, v.origin, v.status, v.ordered_at AS orderedAt, v.completed_at AS completedAt FROM review v
         WHERE v.workspace_id = :workspaceId AND v.id IN (
        """ + REVIEWS + """
        )
        """, nativeQuery = true)
    List<ReviewRow> reviews(long workspaceId, Long[] accounts, Instant from, Instant to);

    // Who each whole review is about, from every fact that names a subject, not only the rows in the
    // window: the review's own results (in or out of the window), the results its assignments judged
    // or reused, and for an unjudged ITEM assignment the developer the assignment recorded. That is
    // the same account-id field ReviewAssignmentQueryService.find reads. A SCAN assignment records
    // none, and an absent, null or non-integral value is not guessed at: it counts as unattributed.
    @Query(value = """
        WITH reviewed AS (
            SELECT v.id, v.kind FROM review v WHERE v.workspace_id = :workspaceId AND v.id IN (
        """ + REVIEWS + """
        )),
        assigned AS (
            SELECT a.review_id, a.result_id,
                   CASE WHEN x.kind = 'ITEM' AND jsonb_typeof(a.detail -> 'developerId') = 'number'
                             AND (a.detail ->> 'developerId') ~ '^[1-9][0-9]{0,17}$'
                        THEN (a.detail ->> 'developerId')::bigint END AS recorded_developer
              FROM review_item a JOIN reviewed x ON x.id = a.review_id
             WHERE a.workspace_id = :workspaceId),
        linked AS (
            SELECT r.review_id, r.person_account_id FROM result r
             WHERE r.workspace_id = :workspaceId AND r.review_id IN (SELECT id FROM reviewed)
            UNION ALL
            SELECT s.review_id, r.person_account_id FROM assigned s
              JOIN result r ON r.workspace_id = :workspaceId AND r.id = s.result_id
            UNION ALL
            SELECT s.review_id, s.recorded_developer FROM assigned s
             WHERE s.result_id IS NULL AND s.recorded_developer IS NOT NULL)
        SELECT x.id AS reviewId,
               (SELECT count(*) FROM assigned s WHERE s.review_id = x.id) AS assignments,
               (SELECT count(*) FROM assigned s
                 WHERE s.review_id = x.id AND s.result_id IS NULL AND s.recorded_developer IS NULL) AS unattributedAssignments,
               (SELECT count(*) FROM linked l WHERE l.review_id = x.id AND l.person_account_id IS NULL) AS unattributedResults,
               ARRAY(SELECT DISTINCT l.person_account_id FROM linked l
                      WHERE l.review_id = x.id AND l.person_account_id IS NOT NULL) AS developers
          FROM reviewed x
        """, nativeQuery = true)
    List<ReviewSubjectRow> reviewSubjects(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = """
        SELECT rp.review_id AS reviewId, rp.practice_id AS practiceId, rp.practice_version_id AS practiceVersionId, rp.status, rp.reason,
               rp.items_listed AS itemsListed, rp.listing_complete AS listingComplete,
               count(a.item_id) AS assignments,
               count(a.item_id) FILTER (WHERE a.state = 'LISTED') AS listed,
               count(a.item_id) FILTER (WHERE a.state = 'JUDGED') AS judged,
               count(a.item_id) FILTER (WHERE a.state = 'REUSED') AS reused,
               count(a.item_id) FILTER (WHERE a.state = 'WITHHELD') AS withheld
          FROM review_practice rp
          LEFT JOIN review_item a ON a.workspace_id = rp.workspace_id AND a.review_id = rp.review_id
                                 AND a.practice_id = rp.practice_id
         WHERE rp.workspace_id = :workspaceId AND rp.review_id IN (
        """ + REVIEWS + """
        )
         GROUP BY rp.review_id, rp.practice_id, rp.practice_version_id, rp.status, rp.reason,
                  rp.items_listed, rp.listing_complete
        """, nativeQuery = true)
    List<ReviewPracticeRow> reviewPractices(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = """
        SELECT i.id, i.kind FROM item i
         WHERE i.workspace_id = :workspaceId
           AND (i.id IN (SELECT r.item_id FROM result r WHERE r.workspace_id = :workspaceId AND r.id IN (
        """ + RESULTS + """
        ))
                OR i.id IN (SELECT f.item_id FROM feedback f WHERE f.workspace_id = :workspaceId AND f.id IN (
        """ + FEEDBACK + """
        )))
        """, nativeQuery = true)
    List<ItemRow> items(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = """
        SELECT pr.id, pr.catalog_slug AS catalogSlug FROM practice pr
         WHERE pr.workspace_id = :workspaceId
           AND (pr.id IN (SELECT r.practice_id FROM result r WHERE r.workspace_id = :workspaceId AND r.id IN (
        """ + RESULTS + """
        ))
                OR pr.id IN (SELECT rp.practice_id FROM review_practice rp
                              WHERE rp.workspace_id = :workspaceId AND rp.review_id IN (
        """ + REVIEWS + """
        )))
        """, nativeQuery = true)
    List<PracticeRow> practices(long workspaceId, Long[] accounts, Instant from, Instant to);

    @Query(value = """
        SELECT v.id, v.practice_id AS practiceId, v.number, v.review_digest AS reviewDigest FROM practice_version v
         WHERE v.workspace_id = :workspaceId
           AND (v.id IN (SELECT r.practice_version_id FROM result r
                          WHERE r.workspace_id = :workspaceId AND r.id IN (
        """ + RESULTS + """
        ))
                OR v.id IN (SELECT rp.practice_version_id FROM review_practice rp
                             WHERE rp.workspace_id = :workspaceId AND rp.review_id IN (
        """ + REVIEWS + """
        )))
        """, nativeQuery = true)
    List<PracticeVersionRow> versions(long workspaceId, Long[] accounts, Instant from, Instant to);

    interface ResultRow {
        UUID getId();

        UUID getReviewId();

        long getPracticeId();

        @Nullable
        Long getPracticeVersionId();

        long getItemId();

        long getDeveloperId();

        String getOutcome();

        @Nullable
        String getSeverity();

        short getEvidenceVersion();

        boolean getItemVersionRecorded();

        boolean getCoverageRecorded();

        Instant getRecordedAt();

        @Nullable
        Instant getEndedAt();

        @Nullable
        String getEndReason();

        @Nullable
        String getActiveMark();
    }

    interface ProblemRow {
        UUID getId();

        UUID getResultId();

        int getPosition();

        String getSeverity();
    }

    interface FeedbackRow {
        UUID getId();

        UUID getReviewId();

        @Nullable
        Long getItemId();

        long getDeveloperId();

        String getDestination();

        String getState();

        @Nullable
        String getStopReason();

        Instant getCreatedAt();

        @Nullable
        Instant getDeliveredAt();

        @Nullable
        UUID getReplacesId();
    }

    interface BasisRow {
        UUID getFeedbackId();

        UUID getResultId();

        @Nullable
        UUID getProblemId();

        int getOrdinal();
    }

    interface DecisionRow {
        UUID getFeedbackId();

        String getAction();

        Instant getDecidedAt();
    }

    interface ResponseRow {
        UUID getFeedbackId();

        @Nullable
        String getResolution();

        @Nullable
        String getUsefulness();

        Instant getCreatedAt();
    }

    interface ReviewRow {
        UUID getId();

        String getKind();

        String getOrigin();

        String getStatus();

        Instant getOrderedAt();

        @Nullable
        Instant getCompletedAt();
    }

    interface ReviewSubjectRow {
        UUID getReviewId();

        int getAssignments();

        int getUnattributedAssignments();

        int getUnattributedResults();

        long[] getDevelopers();
    }

    interface ReviewPracticeRow {
        UUID getReviewId();

        long getPracticeId();

        @Nullable
        Long getPracticeVersionId();

        String getStatus();

        @Nullable
        String getReason();

        @Nullable
        Integer getItemsListed();

        @Nullable
        Boolean getListingComplete();

        int getAssignments();

        int getListed();

        int getJudged();

        int getReused();

        int getWithheld();
    }

    interface ItemRow {
        long getId();

        String getKind();
    }

    interface PracticeRow {
        long getId();

        @Nullable
        String getCatalogSlug();
    }

    interface PracticeVersionRow {
        long getId();

        long getPracticeId();

        int getNumber();

        String getReviewDigest();
    }
}

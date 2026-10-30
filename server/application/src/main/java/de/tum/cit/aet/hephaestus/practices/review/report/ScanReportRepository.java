package de.tum.cit.aet.hephaestus.practices.review.report;

import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ScanReportRepository extends Repository<Review, UUID> {
    @Query(value = """
        SELECT mark.kind,COUNT(DISTINCT mark.result_id) AS count FROM result_mark mark JOIN
        review_item member ON member.result_id=mark.result_id AND member.workspace_id=mark.workspace_id
        WHERE member.workspace_id=:ws AND member.review_id=:scan AND mark.removed_at IS NULL GROUP BY mark.kind
        """, nativeQuery = true)
    List<KindCount> markedCounts(long ws, UUID scan);

    @Query(value = """
        SELECT m.review_id AS reviewId,count(*) AS count FROM review_item m JOIN problem p ON
        p.workspace_id=m.workspace_id AND p.result_id=m.result_id WHERE m.workspace_id=:ws
        AND m.review_id IN (:scans) AND m.result_id IN (:results) GROUP BY m.review_id
        """, nativeQuery = true)
    List<ReviewCount> openCounts(long ws, Collection<UUID> scans, Collection<UUID> results);

    @Query(value = "SELECT COUNT(*) FROM problem WHERE workspace_id=:ws AND result_id IN (:ids)", nativeQuery = true)
    long problemCount(long ws, Collection<UUID> ids);

    @Query(value = """
        SELECT COUNT(DISTINCT m.item_id) AS files,COUNT(*) AS pairs,
         COUNT(*) FILTER(WHERE m.state IN ('JUDGED','REUSED')) AS reviewed,
         COUNT(*) FILTER(WHERE m.state='REUSED') AS reused,
         COUNT(*) FILTER(WHERE r.outcome='MET') AS met,
         COUNT(*) FILTER(WHERE r.outcome='NOT_MET') AS notMet,
         COUNT(*) FILTER(WHERE r.outcome='DOES_NOT_APPLY') AS doesNotApply,
         COUNT(*) FILTER(WHERE r.outcome='COULD_NOT_DETERMINE') AS couldNotDetermine,
         COUNT(*) FILTER(WHERE m.state='WITHHELD') AS withheld,
         COUNT(*) FILTER(WHERE m.state IN ('LISTED','STAGED')) AS pending
        FROM review_item m LEFT JOIN result r ON r.id=m.result_id AND r.workspace_id=m.workspace_id
        WHERE m.workspace_id=:ws AND m.review_id=:scan
          AND (CAST(:practice AS bigint) IS NULL OR m.practice_id=:practice)
        """, nativeQuery = true)
    MemberCounts counts(long ws, UUID scan, @Nullable Long practice);

    @Query(value = """
        SELECT COUNT(*) FROM review_item m JOIN problem p ON p.result_id=m.result_id
        AND p.workspace_id=m.workspace_id WHERE m.workspace_id=:ws AND m.review_id=:scan
          AND (CAST(:practice AS bigint) IS NULL OR m.practice_id=:practice)
        """, nativeQuery = true)
    long childCount(long ws, UUID scan, @Nullable Long practice);

    @Query(value = """
        SELECT COALESCE(reason,:fallback) AS reason,COUNT(*) AS count FROM review_item
        WHERE workspace_id=:ws AND review_id=:scan AND (state=:state OR (:state='LISTED' AND state='STAGED')) GROUP BY 1 ORDER BY 1
        """, nativeQuery = true)
    List<ReasonCount> reasons(long ws, UUID scan, String state, String fallback);

    @Modifying
    @Query(value = "DELETE FROM review_item WHERE workspace_id=:ws AND review_id=:scan", nativeQuery = true)
    int deleteReviewMembers(long ws, UUID scan);

    @Query(value = """
        SELECT review_id AS reviewId,COUNT(*) AS count FROM review_item WHERE workspace_id=:ws
        AND result_id=ANY(CAST(:ids AS uuid[])) GROUP BY review_id ORDER BY review_id
        """, nativeQuery = true)
    List<ReviewCount> affectedReviewCounts(long ws, UUID[] ids);

    @Modifying
    @Query(value = """
        UPDATE review_item m SET detail=jsonb_set(m.detail,'{problemComparison}',COALESCE((SELECT
        jsonb_agg(CASE WHEN (c->>'previousProblemId')::uuid IN (SELECT id FROM problem WHERE
        workspace_id=:ws AND result_id=ANY(CAST(:ids AS uuid[]))) THEN c-'previousProblemId' ELSE c END) FROM
        jsonb_array_elements(COALESCE(m.detail->'problemComparison','[]'::jsonb)) c WHERE
        (c->>'problemId')::uuid NOT IN (SELECT id FROM problem WHERE workspace_id=:ws AND
        result_id=ANY(CAST(:ids AS uuid[])))),'[]'::jsonb)) WHERE m.workspace_id=:ws
        AND jsonb_exists(m.detail,'problemComparison')
        """, nativeQuery = true)
    int eraseProblemComparisons(long ws, UUID[] ids);

    @Modifying
    @Query(
            value = "DELETE FROM review_item WHERE workspace_id=:ws AND result_id=ANY(CAST(:ids AS uuid[]))",
            nativeQuery = true)
    int deleteResultMembers(long ws, UUID[] ids);

    interface KindCount {
        String getKind();

        long getCount();
    }

    interface ReviewCount {
        UUID getReviewId();

        long getCount();
    }

    interface ReasonCount {
        String getReason();

        long getCount();
    }

    interface MemberCounts {
        long getFiles();

        long getPairs();

        long getReviewed();

        long getReused();

        long getMet();

        long getNotMet();

        long getDoesNotApply();

        long getCouldNotDetermine();

        long getWithheld();

        long getPending();
    }
}

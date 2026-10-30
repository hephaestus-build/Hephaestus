package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewShownStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ReviewOverviewRepository extends Repository<Review, UUID> {
    @Query("SELECT min(r.createdAt) FROM Review r WHERE r.workspaceId=:ws")
    Optional<Instant> first(long ws);

    @Query(value = """
        SELECT practice_id AS practiceId, outcome, count(*) AS count FROM result
        WHERE workspace_id=:ws AND recorded_at>=:from AND recorded_at<:to
        GROUP BY GROUPING SETS ((practice_id,outcome),(outcome))
        """, nativeQuery = true)
    List<OutcomeCount> outcomes(long ws, Instant from, Instant to);

    @Query(value = """
        WITH pieces AS (SELECT DISTINCT f.id,f.state,r.practice_id FROM feedback f
          JOIN feedback_basis b ON b.workspace_id=f.workspace_id AND b.feedback_id=f.id
          JOIN result r ON r.workspace_id=b.workspace_id AND r.id=b.result_id
          WHERE f.workspace_id=:ws AND f.created_at>=:from AND f.created_at<:to)
        SELECT practice_id AS practiceId,state,count(*) AS count FROM pieces GROUP BY practice_id,state
        UNION ALL
        SELECT NULL AS practiceId,state,count(*) AS count FROM feedback
        WHERE workspace_id=:ws AND created_at>=:from AND created_at<:to GROUP BY state
        """, nativeQuery = true)
    List<FeedbackCount> feedback(long ws, Instant from, Instant to);

    @Query(value = """
        SELECT r.practice_id AS practiceId,count(*) AS count FROM result r
        WHERE r.workspace_id=:ws AND r.recorded_at>=:from AND r.recorded_at<:to
          AND EXISTS(SELECT 1 FROM result_mark m WHERE m.workspace_id=r.workspace_id AND m.result_id=r.id
                     AND m.kind='INCORRECT' AND m.removed_at IS NULL)
        GROUP BY GROUPING SETS ((r.practice_id),())
        """, nativeQuery = true)
    List<MarkedCount> incorrect(long ws, Instant from, Instant to);

    @Query(
            value = "SELECT " + ReviewShownStatus.SQL + " AS status,count(*) AS count FROM review q"
                    + " WHERE q.workspace_id=:ws AND q.created_at>=:from AND q.created_at<:to GROUP BY 1",
            nativeQuery = true)
    List<StatusCount> statuses(long ws, Instant from, Instant to);

    @Query(value = """
        WITH dates AS (
          SELECT 'REVIEW' AS kind,created_at AS at FROM review WHERE workspace_id=:ws AND created_at>=:from AND created_at<:to
          UNION ALL SELECT 'RESULT',recorded_at FROM result WHERE workspace_id=:ws AND recorded_at>=:from AND recorded_at<:to
          UNION ALL SELECT 'FEEDBACK',created_at FROM feedback WHERE workspace_id=:ws AND created_at>=:from AND created_at<:to)
        SELECT width_bucket(CAST(extract(epoch FROM at) AS float8), CAST(:starts AS float8[])) AS bucket,
          count(*) FILTER(WHERE kind='REVIEW') AS reviews,count(*) FILTER(WHERE kind='RESULT') AS results,
          count(*) FILTER(WHERE kind='FEEDBACK') AS feedback FROM dates GROUP BY bucket
        """, nativeQuery = true)
    List<BucketCount> buckets(long ws, Instant from, Instant to, Long[] starts);

    interface OutcomeCount {
        @Nullable
        Long getPracticeId();

        ResultOutcome getOutcome();

        int getCount();
    }

    interface FeedbackCount {
        @Nullable
        Long getPracticeId();

        FeedbackState getState();

        int getCount();
    }

    interface MarkedCount {
        @Nullable
        Long getPracticeId();

        int getCount();
    }

    interface StatusCount {
        ReviewStatus getStatus();

        int getCount();
    }

    interface BucketCount {
        int getBucket();

        int getReviews();

        int getResults();

        int getFeedback();
    }
}

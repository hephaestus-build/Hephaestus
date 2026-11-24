package de.tum.cit.aet.hephaestus.practices.usage;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("usage_release is global; only count() below opens a bypass, for the instance totals")
interface UsageReleaseRepository extends Repository<UsageRelease, LocalDate> {

    /** One stored release as a reader needs it. */
    record ReleasedRow(LocalDate periodStart, Instant computedAt, short schemaVersion, String figures) {}

    @Query("""
            SELECT new de.tum.cit.aet.hephaestus.practices.usage.UsageReleaseRepository$ReleasedRow(
                u.periodStart, u.computedAt, u.schemaVersion, u.figures)
            FROM UsageRelease u
            ORDER BY u.periodStart DESC
            """)
    List<ReleasedRow> findReleased();

    @Query("""
            SELECT new de.tum.cit.aet.hephaestus.practices.usage.UsageReleaseRepository$ReleasedRow(
                u.periodStart, u.computedAt, u.schemaVersion, u.figures)
            FROM UsageRelease u
            WHERE u.periodStart = :start
            """)
    Optional<ReleasedRow> findReleased(@Param("start") LocalDate start);

    @Query("SELECT count(u) > 0 FROM UsageRelease u WHERE u.periodStart = :start")
    boolean isReleased(@Param("start") LocalDate start);

    @Query("SELECT max(u.periodStart) FROM UsageRelease u")
    Optional<LocalDate> findLatestPeriodStart();

    /**
     * Releases one period once. A second release of the same period, from this or another replica, changes nothing, and
     * the database refuses any update or delete of a released row.
     */
    @Modifying
    @NativeQuery("""
            INSERT INTO usage_release (period_start, period_end, computed_at, schema_version, figures)
            VALUES (:start, :end, :computedAt, :version, CAST(:figures AS jsonb))
            ON CONFLICT (period_start) DO NOTHING
            """)
    int insertOnce(
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("computedAt") Instant computedAt,
            @Param("version") short version,
            @Param("figures") String figures);

    /**
     * The raw counts of each half-year from {@code from} through the half-year that starts at {@code through}, from
     * event time in UTC. Trials count nowhere. Each figure comes with the number of workspaces that add to it and the
     * largest part of one workspace, so that the dominance rule can apply; no workspace leaves the query.
     */
    @WorkspaceAgnostic("Instance totals over every workspace for the public usage page; only sanitized figures leave"
            + " the release (ADR 0153)")
    @NativeQuery("""
            WITH periods AS (
                SELECT to_char(s, 'YYYY-MM') AS period,
                       s AT TIME ZONE 'UTC' AS starts,
                       (s + INTERVAL '6 months') AT TIME ZONE 'UTC' AS ends
                FROM generate_series(CAST(:from AS timestamp), CAST(:through AS timestamp), INTERVAL '6 months') AS s
            ),
            reviews AS (
                SELECT p.period, r.id, r.workspace_id
                FROM review r
                JOIN periods p ON r.created_at >= p.starts AND r.created_at < p.ends
                WHERE r.origin <> 'TRIAL'
            ),
            counts AS (
                SELECT r.period, 'PRACTICE_REVIEWS' AS figure, r.workspace_id, count(*) AS n
                FROM reviews r
                JOIN review_practice rp ON rp.review_id = r.id AND rp.workspace_id = r.workspace_id
                GROUP BY r.period, r.workspace_id
                UNION ALL
                SELECT r.period, 'REVIEWED_WORK', r.workspace_id, count(DISTINCT ri.item_id)
                FROM reviews r
                JOIN review_item ri ON ri.review_id = r.id AND ri.workspace_id = r.workspace_id
                JOIN practice pr ON pr.id = ri.practice_id AND pr.workspace_id = ri.workspace_id
                WHERE ri.state IN ('JUDGED', 'REUSED') AND pr.kind = 'scm.pull_request'
                GROUP BY r.period, r.workspace_id
                UNION ALL
                SELECT p.period, 'REVIEWED_PEOPLE', res.workspace_id, count(DISTINCT res.person_account_id)
                FROM result res
                JOIN periods p ON res.recorded_at >= p.starts AND res.recorded_at < p.ends
                JOIN review v ON v.id = res.review_id AND v.workspace_id = res.workspace_id
                WHERE v.origin <> 'TRIAL' AND res.person_account_id IS NOT NULL
                GROUP BY p.period, res.workspace_id
                UNION ALL
                SELECT p.period, 'FEEDBACK_DELIVERED', f.workspace_id, count(*)
                FROM feedback f
                JOIN periods p ON f.delivered_at >= p.starts AND f.delivered_at < p.ends
                JOIN review v ON v.id = f.review_id AND v.workspace_id = f.workspace_id
                WHERE v.origin <> 'TRIAL' AND f.state = 'DELIVERED'
                GROUP BY p.period, f.workspace_id
            )
            SELECT period, figure, count(*) AS workspaces, CAST(sum(n) AS bigint) AS total, max(n) AS largest
            FROM counts
            GROUP BY period, figure
            UNION ALL
            SELECT p.period, 'DISTINCT_PEOPLE', 0, count(DISTINCT res.person_account_id), 0
            FROM result res
            JOIN periods p ON res.recorded_at >= p.starts AND res.recorded_at < p.ends
            JOIN review v ON v.id = res.review_id AND v.workspace_id = res.workspace_id
            WHERE v.origin <> 'TRIAL' AND res.person_account_id IS NOT NULL
            GROUP BY p.period
            """)
    List<UsageCount> count(@Param("from") LocalDateTime from, @Param("through") LocalDateTime through);
}

package de.tum.cit.aet.hephaestus.practices.review.report;

import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemBaselineState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class ScanMembershipQueriesImpl implements ScanMembershipQueries {
    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private Session session() {
        return Objects.requireNonNull(entityManager).unwrap(Session.class);
    }

    @Override
    public List<ItemKey> items(Review review, @Nullable ScanReportCursor after, int size, ItemFilter filter) {
        var where = new StringBuilder(" WHERE m.workspace_id=:ws AND m.review_id=:scan ");
        var params = params(review, size);
        add(where, params, "p.slug=:practice", "practice", filter.practiceSlug());
        add(
                where,
                params,
                "r.outcome=:outcome",
                "outcome",
                filter.outcome() == null ? null : filter.outcome().name());
        add(where, params, "i.path=:path", "path", filter.path());
        add(where, params, "(m.state='REUSED')=:reused", "reused", filter.reused());
        add(
                where,
                params,
                "(CASE WHEN m.state IN ('LISTED','STAGED') AND v.status NOT IN ('QUEUED','RUNNING') THEN 'NOT_REVIEWED'"
                        + " WHEN m.state='STAGED' THEN 'LISTED' ELSE m.state"
                        + " END)=:status",
                "status",
                filter.status());
        if (after != null) {
            where.append(" AND (COALESCE(i.path,''),m.practice_id,m.item_id)>(:lastPath,:lastPractice,:lastItem)");
            params.put("lastPath", after.path());
            params.put("lastPractice", after.practiceId());
            params.put("lastItem", after.itemId());
        }
        return session()
                .createNativeQuery(
                        "SELECT COALESCE(i.path,'') AS path,m.practice_id,m.item_id,m.result_id, (SELECT count(*) FROM"
                                + " problem child WHERE child.workspace_id=m.workspace_id AND child.result_id=m.result_id)"
                                + " AS problem_count FROM review_item m JOIN review v ON v.id=m.review_id AND"
                                + " v.workspace_id=m.workspace_id JOIN item i ON i.id=m.item_id AND"
                                + " i.workspace_id=m.workspace_id JOIN practice p ON p.id=m.practice_id AND"
                                + " p.workspace_id=m.workspace_id LEFT JOIN result r ON r.id=m.result_id AND"
                                + " r.workspace_id=m.workspace_id"
                                + where + " ORDER BY COALESCE(i.path,''),m.practice_id,m.item_id LIMIT :limit",
                        Tuple.class)
                .setProperties(params)
                .getResultStream()
                .map(row -> new ItemKey(
                        new ScanReportCursor(
                                review.getId(),
                                row.get("path", String.class),
                                row.get("practice_id", Long.class),
                                row.get("item_id", Long.class),
                                0,
                                null),
                        row.get("result_id", UUID.class),
                        Math.toIntExact(row.get("problem_count", Long.class))))
                .toList();
    }

    private static final String CURRENT_PROBLEMS = """
        SELECT m.practice_id,m.item_id,p.position,p.id AS problem_id,r.id AS result_id,COALESCE(i.path,'') AS path,
          COALESCE((SELECT c->>'state' FROM jsonb_array_elements(COALESCE(m.detail->'problemComparison','[]'::jsonb)) c WHERE c->>'problemId'=p.id::text LIMIT 1),CASE WHEN v.report IS NOT NULL AND v.report->>'baselineScanId' IS NULL THEN 'NEW' ELSE NULL END) AS baseline_state,
          (SELECT (c->>'previousProblemId')::uuid FROM jsonb_array_elements(COALESCE(m.detail->'problemComparison','[]'::jsonb)) c WHERE c->>'problemId'=p.id::text LIMIT 1) AS previous_problem_id
        FROM review_item m JOIN review v ON v.id=m.review_id AND v.workspace_id=m.workspace_id
        JOIN result r ON r.id=m.result_id AND r.workspace_id=m.workspace_id
        JOIN problem p ON p.result_id=r.id AND p.workspace_id=r.workspace_id
        JOIN item i ON i.id=m.item_id AND i.workspace_id=m.workspace_id
        WHERE m.workspace_id=:ws AND m.review_id=:scan
        """;
    private static final String ABSENT_PROBLEMS = """
        SELECT m.practice_id,m.item_id,p.position,p.id AS problem_id,r.id AS result_id,COALESCE(i.path,'') AS path,c->>'state' AS baseline_state,(c->>'previousProblemId')::uuid AS previous_problem_id
        FROM review_item m JOIN review v ON v.id=m.review_id AND v.workspace_id=m.workspace_id
        CROSS JOIN LATERAL jsonb_array_elements(COALESCE(m.detail->'problemComparison','[]'::jsonb)) c
        JOIN problem p ON p.id=(c->>'problemId')::uuid AND p.workspace_id=m.workspace_id
        JOIN result r ON r.id=p.result_id AND r.workspace_id=p.workspace_id
        JOIN item i ON i.id=m.item_id AND i.workspace_id=m.workspace_id
        WHERE m.workspace_id=:ws AND m.review_id=:scan AND c->>'state'='ABSENT'
        """;

    @Override
    public List<ProblemKey> problems(Review review, @Nullable ScanReportCursor after, int size, ProblemFilter filter) {
        var params = params(review, size);
        var where = new StringBuilder(" WHERE true ");
        add(where, params, "owner.slug=:practice", "practice", filter.practiceSlug());
        add(
                where,
                params,
                "p.severity=:severity",
                "severity",
                filter.severity() == null ? null : filter.severity().name());
        add(where, params, "x.path=:path", "path", filter.path());
        add(
                where,
                params,
                "x.baseline_state=:baseline",
                "baseline",
                filter.baselineState() == null ? null : filter.baselineState().name());
        if (filter.openOnly())
            where.append(
                    " AND COALESCE(x.baseline_state,'')<>'ABSENT' AND r.outcome='NOT_MET' AND r.ended_at IS NULL AND"
                            + " pinned.review_digest=current.review_digest AND NOT EXISTS(SELECT 1 FROM result_mark mark"
                            + " WHERE mark.workspace_id=r.workspace_id AND mark.result_id=r.id AND mark.removed_at IS"
                            + " NULL)");
        if (after != null) {
            where.append(
                    " AND (x.path,x.practice_id,x.item_id,x.position,x.problem_id::text)>(:lastPath,:lastPractice,:lastItem,:lastPosition,:lastProblem)");
            params.put("lastPath", after.path());
            params.put("lastPractice", after.practiceId());
            params.put("lastItem", after.itemId());
            params.put("lastPosition", after.position());
            params.put("lastProblem", Objects.requireNonNull(after.problemId()).toString());
        }
        return session()
                .createNativeQuery(
                        "SELECT x.* FROM (" + CURRENT_PROBLEMS + " UNION ALL " + ABSENT_PROBLEMS
                                + ") x JOIN problem p ON p.id=x.problem_id AND p.workspace_id=:ws JOIN result r ON"
                                + " r.id=x.result_id AND r.workspace_id=:ws JOIN practice owner ON"
                                + " owner.id=x.practice_id AND owner.workspace_id=:ws LEFT JOIN practice_version"
                                + " pinned ON pinned.id=r.practice_version_id AND pinned.workspace_id=:ws JOIN"
                                + " practice_version current ON current.id=owner.current_version_id AND"
                                + " current.workspace_id=:ws"
                                + where
                                + " ORDER BY x.path,x.practice_id,x.item_id,x.position,x.problem_id::text LIMIT :limit",
                        Tuple.class)
                .setProperties(params)
                .getResultStream()
                .map(row -> {
                    var state = row.get("baseline_state", String.class);
                    return new ProblemKey(
                            new ScanReportCursor(
                                    review.getId(),
                                    row.get("path", String.class),
                                    row.get("practice_id", Long.class),
                                    row.get("item_id", Long.class),
                                    row.get("position", Integer.class),
                                    row.get("problem_id", UUID.class)),
                            row.get("result_id", UUID.class),
                            state == null ? null : ProblemBaselineState.valueOf(state),
                            row.get("previous_problem_id", UUID.class));
                })
                .toList();
    }

    @Override
    public List<UUID> proofIds(
            long workspaceId,
            List<UUID> reviewIds,
            @Nullable UUID after,
            int limit,
            @Nullable Long practiceId,
            boolean openOnly) {
        if (reviewIds.isEmpty()) return List.of();
        var params = new LinkedHashMap<String, @Nullable Object>();
        params.put("ws", workspaceId);
        params.put("scans", reviewIds);
        params.put("limit", limit);
        var where =
                new StringBuilder(" WHERE m.workspace_id=:ws AND m.review_id IN (:scans) AND m.result_id IS NOT NULL ");
        if (after != null) {
            where.append(" AND r.id::text>:last");
            params.put("last", after.toString());
        }
        add(where, params, "m.practice_id=:practice", "practice", practiceId);
        if (openOnly)
            where.append(
                    " AND r.outcome='NOT_MET' AND r.ended_at IS NULL AND pinned.review_digest=current.review_digest"
                            + " AND NOT EXISTS(SELECT 1 FROM result_mark mark WHERE mark.workspace_id=r.workspace_id AND"
                            + " mark.result_id=r.id AND mark.removed_at IS NULL)");
        return session()
                .createNativeQuery(
                        "SELECT DISTINCT r.id,r.id::text AS ordering FROM review_item m JOIN result r ON"
                                + " r.id=m.result_id AND r.workspace_id=m.workspace_id JOIN practice p ON"
                                + " p.id=r.practice_id AND p.workspace_id=r.workspace_id LEFT JOIN practice_version pinned"
                                + " ON pinned.id=r.practice_version_id AND pinned.workspace_id=r.workspace_id JOIN"
                                + " practice_version current ON current.id=p.current_version_id AND"
                                + " current.workspace_id=p.workspace_id"
                                + where + " ORDER BY ordering LIMIT :limit",
                        Tuple.class)
                .setProperties(params)
                .getResultStream()
                .map(row -> row.get("id", UUID.class))
                .toList();
    }

    @Override
    public List<ScanReportCursor> comparisonPairs(
            Review review, @Nullable UUID baseline, @Nullable ScanReportCursor after, int limit) {
        var params = params(review, limit);
        var where = new StringBuilder(
                " WHERE m.workspace_id=:ws AND m.review_id=:scan AND (r.outcome='NOT_MET' OR (r.outcome='MET' AND"
                        + " m.state='JUDGED' AND old.outcome='NOT_MET')) ");
        if (after != null) {
            where.append(" AND (m.practice_id,m.item_id)>(:lastPractice,:lastItem)");
            params.put("lastPractice", after.practiceId());
            params.put("lastItem", after.itemId());
        }
        return session()
                .createNativeQuery(
                        "SELECT m.practice_id,m.item_id,COALESCE(i.path,'') AS path FROM review_item m JOIN result r"
                                + " ON r.id=m.result_id AND r.workspace_id=m.workspace_id JOIN item i ON i.id=m.item_id"
                                + " AND i.workspace_id=m.workspace_id LEFT JOIN review_item prior ON"
                                + " prior.review_id=CAST(:baseline AS uuid) AND prior.workspace_id=m.workspace_id AND"
                                + " prior.practice_id=m.practice_id AND prior.item_id=m.item_id LEFT JOIN result old ON"
                                + " old.id=prior.result_id AND old.workspace_id=prior.workspace_id"
                                + where + " ORDER BY m.practice_id,m.item_id LIMIT :limit",
                        Tuple.class)
                .setProperties(params)
                .setParameter("baseline", baseline, UUID.class)
                .getResultStream()
                .map(row -> new ScanReportCursor(
                        review.getId(),
                        row.get("path", String.class),
                        row.get("practice_id", Long.class),
                        row.get("item_id", Long.class),
                        0,
                        null))
                .toList();
    }

    private static Map<String, @Nullable Object> params(Review review, int limit) {
        var p = new LinkedHashMap<String, @Nullable Object>();
        p.put("ws", review.getWorkspaceId());
        p.put("scan", review.getId());
        p.put("limit", limit);
        return p;
    }

    private static void add(
            StringBuilder where,
            Map<String, @Nullable Object> params,
            String condition,
            String key,
            @Nullable Object value) {
        if (value != null) {
            where.append(" AND ").append(condition);
            params.put(key, value);
        }
    }
}

package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.ResultSort;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewListSort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class ResultQueriesImpl implements ResultQueries {
    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private Session session() {
        return Objects.requireNonNull(entityManager).unwrap(Session.class);
    }

    private static final String JOIN =
            " FROM result r JOIN practice p ON p.id=r.practice_id AND p.workspace_id=r.workspace_id LEFT JOIN"
                    + " practice_group g ON g.id=p.group_id AND g.workspace_id=p.workspace_id JOIN item i ON"
                    + " i.id=r.item_id AND i.workspace_id=r.workspace_id LEFT JOIN practice_version v ON"
                    + " v.id=r.practice_version_id AND v.workspace_id=r.workspace_id JOIN practice_version cv ON"
                    + " cv.id=p.current_version_id AND cv.workspace_id=p.workspace_id ";
    private static final String INCORRECT =
            "EXISTS(SELECT 1 FROM result_mark m WHERE m.workspace_id=r.workspace_id AND m.result_id=r.id AND"
                    + " m.kind='INCORRECT' AND m.removed_at IS NULL)";
    private static final String ACCEPTED =
            "EXISTS(SELECT 1 FROM result_mark m WHERE m.workspace_id=r.workspace_id AND m.result_id=r.id AND"
                    + " m.kind='ACCEPTED' AND m.removed_at IS NULL)";
    private static final String DISAGREED =
            "EXISTS(SELECT 1 FROM feedback_basis b JOIN feedback_response s ON s.feedback_id=b.feedback_id AND"
                    + " s.workspace_id=b.workspace_id WHERE b.workspace_id=r.workspace_id AND b.result_id=r.id AND"
                    + " s.resolution='DISAGREE' AND s.id=(SELECT latest.id FROM feedback_response latest WHERE"
                    + " latest.workspace_id=s.workspace_id AND latest.feedback_id=s.feedback_id ORDER BY latest.created_at"
                    + " DESC,latest.id::text DESC LIMIT 1))";
    private static final String CURRENTNESS =
            "CASE WHEN r.evidence_version=0 THEN 'UNVERIFIABLE' WHEN r.ended_at IS NOT NULL THEN 'STALE' WHEN v.id IS NULL OR r.summary IS"
                    + " NULL OR r.explanation IS NULL OR r.evidence IS NULL OR r.coverage IS NULL THEN 'UNVERIFIABLE' WHEN"
                    + " v.review_digest=cv.review_digest THEN 'CURRENT' ELSE 'STALE' END";

    @Override
    public Page<Result> list(
            long workspaceId, ResultQueryFilter filter, ResultSort sort, boolean displayableOnly, Pageable page) {
        var selection = selection(workspaceId, filter);
        String where = selection.where() + (displayableOnly ? " AND r.outcome <> 'DOES_NOT_APPLY'" : "");
        String order = switch (sort) {
            case ACTIONABILITY ->
                "CASE r.severity WHEN 'CRITICAL' THEN 0 WHEN 'MAJOR' THEN 1 WHEN 'MINOR' THEN 2 WHEN 'TRIVIAL'"
                        + " THEN 3 ELSE 4 END,r.recorded_at DESC,r.id::text DESC";
            case OLDEST -> "r.recorded_at ASC,r.id::text ASC";
            case NEWEST -> "r.recorded_at DESC,r.id::text DESC";
        };
        long total = session()
                .createNativeQuery("SELECT count(*)" + JOIN + where, Long.class)
                .setProperties(selection.params())
                .getSingleResult();
        var rows = session()
                .createNativeQuery(
                        "SELECT r.*" + JOIN + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset",
                        Result.class)
                .setProperties(selection.params())
                .setParameter("limit", page.getPageSize())
                .setParameter("offset", page.getOffset())
                .getResultList();
        return new PageImpl<>(rows, page, total);
    }

    @Override
    public Page<Problem> problems(long workspaceId, ResultQueryFilter filter, ProblemQueryFilter child, Pageable page) {
        var s = selection(workspaceId, filter);
        var where = new StringBuilder(s.where());
        var params = new LinkedHashMap<>(s.params());
        add(where, params, "child.result_id=:resultId", "resultId", child.resultId());
        add(
                where,
                params,
                "EXISTS(SELECT 1 FROM jsonb_array_elements(child.citations) c WHERE c->>'path'=:path)",
                "path",
                child.path());
        many(
                where,
                params,
                "child.severity",
                "problemSeverity",
                child.severities() == null
                        ? null
                        : child.severities().stream().map(Enum::name).toList());
        if (child.openOnly())
            where.append(
                    " AND r.ended_at IS NULL AND r.outcome='NOT_MET' AND NOT " + INCORRECT + " AND NOT " + ACCEPTED);
        String joined = JOIN + " JOIN problem child ON child.result_id=r.id AND child.workspace_id=r.workspace_id ";
        long total = session()
                .createNativeQuery("SELECT count(*)" + joined + where, Long.class)
                .setProperties(params)
                .getSingleResult();
        var rows = session()
                .createNativeQuery(
                        "SELECT child.*" + joined + where
                                + " ORDER BY "
                                + (child.sort() == ReviewListSort.OLDEST
                                        ? "r.recorded_at ASC,r.id::text ASC"
                                        : "r.recorded_at DESC,r.id::text DESC")
                                + ",child.position ASC LIMIT :limit OFFSET :offset",
                        Problem.class)
                .setProperties(params)
                .setParameter("limit", page.getPageSize())
                .setParameter("offset", page.getOffset())
                .getResultList();
        return new PageImpl<>(rows, page, total);
    }

    @Override
    public List<Result> forDevelopersSince(
            long workspaceId, Collection<Long> developers, Instant since, boolean currentOnly) {
        if (developers.isEmpty()) return List.of();
        return session()
                .createNativeQuery(
                        "SELECT r.*" + JOIN
                                + " WHERE r.workspace_id=:ws AND r.developer_id IN (:developers) AND"
                                + " r.recorded_at>=:since"
                                + (currentOnly ? " AND r.ended_at IS NULL AND NOT " + INCORRECT : "")
                                + " ORDER BY r.recorded_at DESC,r.id::text DESC",
                        Result.class)
                .setParameter("ws", workspaceId)
                .setParameterList("developers", developers)
                .setParameter("since", since)
                .getResultList();
    }

    @Override
    public Map<UUID, PlaceOnWork> placesOnWork(long workspaceId, long developerId, Collection<UUID> reviewIds) {
        if (reviewIds.isEmpty()) return Map.of();
        // Numbered over the developer's reviews of the listed reviews' works only, in the order the
        // list sorts reviews by, so a page reads its own works and nothing else.
        List<Object[]> rows = session()
                .createNativeQuery(
                        "SELECT placed.id,placed.position,placed.total FROM (SELECT rv.id,"
                                + " row_number() OVER (PARTITION BY rv.item_id ORDER BY rv.created_at,rv.id::text)"
                                + " AS position,count(*) OVER (PARTITION BY rv.item_id) AS total FROM review rv"
                                + " WHERE rv.workspace_id=:ws AND rv.item_id IN (SELECT listed.item_id FROM review"
                                + " listed WHERE listed.workspace_id=:ws AND listed.id IN (:reviews)) AND EXISTS(SELECT 1"
                                + JOIN
                                + "WHERE r.workspace_id=:ws AND r.review_id=rv.id AND r.developer_id=:developer))"
                                + " placed WHERE placed.id IN (:reviews)",
                        Object[].class)
                .setParameter("ws", workspaceId)
                .setParameter("developer", developerId)
                .setParameterList("reviews", reviewIds)
                .getResultList();
        return rows.stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> new PlaceOnWork(((Number) row[1]).intValue(), ((Number) row[2]).intValue())));
    }

    @Override
    public List<Long> developersSince(long workspaceId, Instant since) {
        return session()
                .createNativeQuery(
                        "SELECT DISTINCT r.developer_id FROM result r WHERE r.workspace_id=:ws"
                                + " AND r.developer_id IS NOT NULL AND r.recorded_at>=:since",
                        Long.class)
                .setParameter("ws", workspaceId)
                .setParameter("since", since)
                .getResultList();
    }

    @Override
    public List<Result> ordered(long workspaceId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var loaded = session()
                .createNativeQuery("SELECT * FROM result WHERE workspace_id=:ws AND id IN (:ids)", Result.class)
                .setParameter("ws", workspaceId)
                .setParameterList("ids", ids)
                .getResultStream()
                .collect(Collectors.toMap(Result::getId, r -> r));
        return ids.stream().map(loaded::get).filter(Objects::nonNull).toList();
    }

    private static Selection selection(long ws, ResultQueryFilter f) {
        var where = new StringBuilder(" WHERE r.workspace_id=:ws");
        var params = new LinkedHashMap<String, Object>();
        params.put("ws", ws);
        add(where, params, "r.review_id=:reviewId", "reviewId", f.reviewId());
        add(where, params, "r.item_id=:itemId", "itemId", f.itemId());
        add(where, params, "i.kind=:itemKind", "itemKind", f.itemKind());
        add(where, params, "p.slug=:practiceSlug", "practiceSlug", f.practiceSlug());
        add(where, params, "g.slug=:groupSlug", "groupSlug", f.groupSlug());
        add(where, params, "r.developer_id=:developerId", "developerId", f.developerId());
        add(
                where,
                params,
                CURRENTNESS + "=:currentness",
                "currentness",
                f.currentness() == null ? null : f.currentness().name());
        add(where, params, "r.recorded_at>=:from", "from", f.from());
        add(where, params, "r.recorded_at<:to", "to", f.to());
        many(
                where,
                params,
                "r.outcome",
                "outcomes",
                f.outcomes() == null
                        ? null
                        : f.outcomes().stream().map(Enum::name).toList());
        many(
                where,
                params,
                "r.severity",
                "severities",
                f.severities() == null
                        ? null
                        : f.severities().stream().map(Enum::name).toList());
        if (f.markedIncorrect() != null) where.append(" AND " + (f.markedIncorrect() ? "" : "NOT ") + INCORRECT);
        if (f.accepted() != null) where.append(" AND " + (f.accepted() ? "" : "NOT ") + ACCEPTED);
        if (f.disagreed() != null) where.append(" AND " + (f.disagreed() ? "" : "NOT ") + DISAGREED);
        return new Selection(where.toString(), params);
    }

    private static void add(
            StringBuilder where, Map<String, Object> params, String clause, String name, @Nullable Object value) {
        if (value != null) {
            where.append(" AND " + clause);
            params.put(name, value);
        }
    }

    private static void many(
            StringBuilder where, Map<String, Object> params, String column, String name, @Nullable List<?> values) {
        if (values != null && !values.isEmpty()) add(where, params, column + " IN (:" + name + ")", name, values);
    }

    private record Selection(String where, Map<String, Object> params) {}
}

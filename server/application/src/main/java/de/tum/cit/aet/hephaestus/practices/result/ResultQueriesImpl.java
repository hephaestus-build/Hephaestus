package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultSort;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewListSort;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResultQueriesImpl implements ResultQueries {
    private final ObjectProvider<ResultFreshness> freshness;

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
    private static final String CURRENTNESS = "CASE WHEN r.evidence_version=" + Result.MIGRATED_EVIDENCE_VERSION
            + " THEN 'UNVERIFIABLE' WHEN r.ended_at IS NOT NULL THEN 'STALE' WHEN v.id IS NULL OR NOT ("
            + Result.CAPTURED_PROOF_SQL
            + ") THEN 'UNVERIFIABLE' WHEN v.review_digest=cv.review_digest THEN 'CURRENT' ELSE 'STALE' END";

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
        if (child.openOnly()) sourceCurrentness(workspaceId, joined, where, params, Currentness.CURRENT);
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
    public List<ResultFacts> forDevelopersSince(
            long workspaceId, Collection<Long> developers, Instant since, boolean currentOnly) {
        if (developers.isEmpty()) return List.of();
        var where = new StringBuilder(
                " WHERE r.workspace_id=:ws AND r.developer_id IN (:developers) AND r.recorded_at>=:since");
        var params = new LinkedHashMap<String, Object>();
        params.put("ws", workspaceId);
        params.put("developers", developers);
        params.put("since", since);
        if (currentOnly) {
            where.append(" AND r.ended_at IS NULL AND NOT ").append(INCORRECT);
            sourceCurrentness(workspaceId, JOIN, where, params, Currentness.CURRENT);
        }
        // Only the facts a history needs, with coverage, explanation and evidence as presence flags and the source
        // kinds that the result recorded: a recorded row loads no evidence. UUIDs compare as their text does, so the
        // order of the earlier id::text sort is unchanged.
        List<Object[]> rows = session()
                .createNativeQuery(
                        "SELECT r.id,r.workspace_id,r.review_id,r.practice_id,"
                                + "r.practice_version_id,r.item_id,r.item_version,r.developer_id,r.outcome,r.severity,"
                                + "r.summary,r.evidence_version,r.recorded_at,r.ended_at,r.end_reason,"
                                + "r.explanation IS NOT NULL,r.evidence IS NOT NULL,r.coverage IS NOT NULL,"
                                + "r.source_kinds"
                                + JOIN + where + " ORDER BY r.recorded_at DESC,r.id DESC",
                        Object[].class)
                .setProperties(params)
                .getResultList();
        // A row without recorded kinds (an older server wrote it during an upgrade, or the upgrade has not reached it)
        // is read whole, so its kinds come from its evidence as an insert records them.
        var unrecorded = rows.stream()
                .filter(row -> row[18] == null)
                .map(row -> (UUID) row[0])
                .toList();
        Map<UUID, @Nullable List<String>> extracted = new HashMap<>();
        if (!unrecorded.isEmpty())
            session()
                    .createSelectionQuery("FROM Result r WHERE r.workspaceId = :ws AND r.id IN :ids", Result.class)
                    .setParameter("ws", workspaceId)
                    .setParameterList("ids", unrecorded)
                    .getResultList()
                    .forEach(result -> extracted.put(result.getId(), result.sourceKinds()));
        return rows.stream()
                .map(row -> facts(row, row[18] != null ? kinds(row[18]) : extracted.get((UUID) row[0])))
                .toList();
    }

    private static List<String> kinds(Object array) {
        if (array instanceof String[] values) return List.of(values);
        try {
            return List.of((String[]) ((Array) array).getArray());
        } catch (SQLException unreadable) {
            throw new IllegalStateException("The recorded source kinds cannot be read", unreadable);
        }
    }

    private ResultFacts facts(Object[] row, @Nullable List<String> sourceKinds) {
        return new ResultFacts(
                (UUID) row[0],
                ((Number) row[1]).longValue(),
                (UUID) row[2],
                ((Number) row[3]).longValue(),
                row[4] == null ? null : ((Number) row[4]).longValue(),
                ((Number) row[5]).longValue(),
                (String) row[6],
                row[7] == null ? null : ((Number) row[7]).longValue(),
                ResultOutcome.valueOf((String) row[8]),
                row[9] == null ? null : Severity.valueOf((String) row[9]),
                (String) row[10],
                ((Number) row[11]).shortValue() == Result.MIGRATED_EVIDENCE_VERSION,
                instant(row[12]),
                row[13] == null ? null : instant(row[13]),
                row[14] == null ? null : ResultEndReason.valueOf((String) row[14]),
                (Boolean) row[15],
                (Boolean) row[16],
                (Boolean) row[17],
                sourceKinds);
    }

    private static Instant instant(Object value) {
        return value instanceof Instant instant ? instant : ((Timestamp) value).toInstant();
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

    /** Freshness is read before LIMIT and count, so a filtered page agrees with every row it renders. */
    private void sourceCurrentness(
            long workspaceId, String joined, StringBuilder where, Map<String, Object> params, Currentness wanted) {
        var candidates = session()
                .createNativeQuery(
                        "SELECT DISTINCT r.id,r.workspace_id,r.review_id,r.practice_id,r.item_id,r.ended_at" + joined
                                + where + " AND (" + CURRENTNESS + ")='CURRENT'",
                        ResultFreshness.Claim.class)
                .setProperties(params)
                .getResultList();
        var matching = freshness.getObject().currentness(workspaceId, candidates).entrySet().stream()
                .filter(entry -> entry.getValue() == wanted)
                .map(Map.Entry::getKey)
                .toList();
        String source = "false";
        if (!matching.isEmpty()) {
            // Open-only can add a second predicate beside an explicit currentness filter.
            String key = "source" + wanted.name();
            params.put(key, matching.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}")));
            source = "r.id = ANY(CAST(:" + key + " AS uuid[]))";
        }
        where.append(" AND (((")
                .append(CURRENTNESS)
                .append(")='")
                .append(wanted.name())
                .append("' AND (")
                .append(CURRENTNESS)
                .append(")<>'CURRENT') OR ((")
                .append(CURRENTNESS)
                .append(")='CURRENT' AND ")
                .append(source)
                .append("))");
    }

    private Selection selection(long ws, ResultQueryFilter f) {
        var where = new StringBuilder(" WHERE r.workspace_id=:ws");
        var params = new LinkedHashMap<String, Object>();
        params.put("ws", ws);
        add(where, params, "r.review_id=:reviewId", "reviewId", f.reviewId());
        add(where, params, "r.item_id=:itemId", "itemId", f.itemId());
        add(where, params, "i.kind=:itemKind", "itemKind", f.itemKind());
        add(where, params, "p.slug=:practiceSlug", "practiceSlug", f.practiceSlug());
        add(where, params, "g.slug=:groupSlug", "groupSlug", f.groupSlug());
        add(where, params, "r.developer_id=:developerId", "developerId", f.developerId());
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
        if (f.currentness() != null) sourceCurrentness(ws, JOIN, where, params, f.currentness());
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

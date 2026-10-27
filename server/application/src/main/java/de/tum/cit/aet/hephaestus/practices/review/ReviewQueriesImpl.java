package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewListSort;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewQueueState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewShownStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class ReviewQueriesImpl implements ReviewQueries {
    @PersistenceContext
    private @Nullable EntityManager entityManager;

    @Override
    public Page<Review> search(long ws, ReviewFilterParams filter, Pageable page) {
        var where = new StringBuilder(" WHERE q.workspace_id=:ws");
        var params = new LinkedHashMap<String, Object>();
        params.put("ws", ws);
        add(where, params, "q.id=:id", "id", filter.reviewId());
        add(where, params, "q.item_id=:item", "item", filter.itemId());
        add(
                where,
                params,
                "q.kind=:kind",
                "kind",
                filter.kind() == null ? null : filter.kind().name());
        add(where, params, "q.created_at>=:from", "from", filter.from());
        add(where, params, "q.created_at<:to", "to", filter.to());
        if (filter.status() != null && !filter.status().isEmpty()) {
            // A shown status comes from these stored ones, so the status index narrows the rows first.
            add(
                    where,
                    params,
                    "q.status IN (:stored)",
                    "stored",
                    filter.status().stream()
                            .flatMap(status -> status == ReviewStatus.QUEUED
                                    ? Stream.of(ReviewStatus.QUEUED, ReviewStatus.RUNNING)
                                    : Stream.of(status))
                            .map(Enum::name)
                            .distinct()
                            .toList());
            add(
                    where,
                    params,
                    ReviewShownStatus.SQL + " IN (:statuses)",
                    "statuses",
                    filter.status().stream().map(Enum::name).toList());
        }
        if (filter.queueState() != null)
            where.append(" AND EXISTS(SELECT 1 FROM review_job j WHERE j.workspace_id=q.workspace_id AND"
                    + " j.review_id=q.id AND " + queueStatePredicate(filter.queueState()) + ")");
        if (filter.developerId() != null || filter.practiceSlug() != null || filter.groupSlug() != null) {
            var basis = new StringBuilder("EXISTS(SELECT 1 FROM result r JOIN practice p ON p.id=r.practice_id AND"
                    + " p.workspace_id=r.workspace_id LEFT JOIN practice_group g ON g.id=p.group_id AND"
                    + " g.workspace_id=p.workspace_id WHERE r.workspace_id=q.workspace_id AND r.review_id=q.id AND"
                    + " NOT EXISTS(SELECT 1 FROM result_mark m WHERE m.workspace_id=r.workspace_id AND"
                    + " m.result_id=r.id AND m.kind='INCORRECT' AND m.removed_at IS NULL)");
            add(basis, params, "r.developer_id=:developer", "developer", filter.developerId());
            add(basis, params, "p.slug=:practice", "practice", filter.practiceSlug());
            add(basis, params, "g.slug=:group", "group", filter.groupSlug());
            where.append(" AND " + basis + ")");
        }
        var session = Objects.requireNonNull(entityManager).unwrap(Session.class);
        long total = session.createNativeQuery("SELECT count(*) FROM review q" + where, Long.class)
                .setProperties(params)
                .getSingleResult();
        var rows = session.createNativeQuery(
                        "SELECT q.* FROM review q" + where
                                + " ORDER BY "
                                + (filter.sort() == ReviewListSort.OLDEST
                                        ? "q.created_at ASC,q.id::text ASC"
                                        : "q.created_at DESC,q.id::text DESC")
                                + " LIMIT :limit OFFSET :offset",
                        Review.class)
                .setProperties(params)
                .setParameter("limit", page.getPageSize())
                .setParameter("offset", page.getOffset())
                .getResultList();
        return new PageImpl<>(rows, page, total);
    }

    /**
     * The queue's own split of a job, as {@code ReviewRuntimeRepository#queueCountsAt} counts it, so a
     * figure of the queue and the list it opens agree.
     */
    static String queueStatePredicate(ReviewQueueState state) {
        return switch (state) {
            case WAITING -> "j.status='QUEUED' AND (j.hold_reason IS NULL OR j.hold_reason<>'BUDGET')";
            case HELD -> "j.status='QUEUED' AND j.hold_reason='BUDGET'";
            case RUNNING -> "j.status='RUNNING'";
        };
    }

    private static void add(
            StringBuilder where, Map<String, Object> params, String sql, String key, @Nullable Object value) {
        if (value != null) {
            where.append(" AND " + sql);
            params.put(key, value);
        }
    }
}

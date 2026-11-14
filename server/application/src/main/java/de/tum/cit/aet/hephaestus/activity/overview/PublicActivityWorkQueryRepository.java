package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEvent;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Published work uses the same canonical counted events as the public person totals. */
@org.springframework.stereotype.Repository
interface PublicActivityWorkQueryRepository extends Repository<ActivityEvent, UUID> {
    String PAGE = ActivityPeopleQueryRepository.PUBLIC_EVENTS + """
            , public_work AS (
                SELECT concat('work:', work.id) AS id, n.event_type, n.occurred_at,
                    work.issue_type AS type, work.number, work.title, work.state,
                    coalesce(work.is_draft, false) AS draft, work.html_url,
                    r.name_with_owner AS repository_key, r.name AS repository_name
                FROM normalized n
                JOIN issue work ON work.id = CASE WHEN n.event_type IN ('PULL_REQUEST_OPENED', 'PULL_REQUEST_MERGED', 'ISSUE_CREATED')
                    THEN n.target_id ELSE n.reviewed_id END
                JOIN repository r ON r.id = n.repository_id
                WHERE work.repository_id = n.repository_id AND work.provider_id = r.provider_id
                  AND work.deleted_at IS NULL AND work.html_url IS NOT NULL AND btrim(work.html_url) <> ''
                  AND ((n.event_type = 'ISSUE_CREATED' AND work.issue_type = 'ISSUE')
                    OR (n.event_type <> 'ISSUE_CREATED' AND work.issue_type = 'PULL_REQUEST'))
                  AND n.event_type IN (:#{#selection.eventTypes()})
            ), page AS (
                SELECT id, max(occurred_at) AS occurred_at FROM public_work GROUP BY id
                HAVING max(occurred_at) < :#{#after.lastOccurredAt()}
                  OR (max(occurred_at) = :#{#after.lastOccurredAt()} AND id < :#{#after.id()})
                ORDER BY max(occurred_at) DESC, id DESC LIMIT :#{#selection.limit()}
            )
            SELECT w.id AS id, max(w.occurred_at) AS lastOccurredAt,
                w.type AS type, w.number AS number, w.title AS title, w.state AS state,
                w.draft AS draft, w.html_url AS htmlUrl, w.repository_key AS repositoryKey,
                w.repository_name AS repositoryName,
                count(*) FILTER (WHERE w.event_type = 'PULL_REQUEST_OPENED') AS pullRequestsOpened,
                count(*) FILTER (WHERE w.event_type = 'PULL_REQUEST_MERGED') AS pullRequestsMerged,
                count(*) FILTER (WHERE w.event_type = 'REVIEW_APPROVED') AS approvals,
                count(*) FILTER (WHERE w.event_type = 'REVIEW_CHANGES_REQUESTED') AS changeRequests,
                count(*) FILTER (WHERE w.event_type = 'REVIEW_COMMENTED') AS commentReviews,
                count(*) FILTER (WHERE w.event_type = 'ISSUE_CREATED') AS issuesOpened
            FROM public_work w JOIN page p ON p.id = w.id
            GROUP BY w.id, w.type, w.number, w.title, w.state, w.draft, w.html_url, w.repository_key, w.repository_name
            ORDER BY max(w.occurred_at) DESC, w.id DESC
            """;

    @Query(value = PAGE, nativeQuery = true)
    List<PublicWorkRow> findWork(
            @Param("scope") ActivityScope scope,
            @Param("range") TimeRange range,
            @Param("person") long person,
            @Param("own") boolean own,
            @Param("selection") Selection selection,
            @Param("after") ActivityWorkCursor after);

    record Selection(List<String> eventTypes, int limit) {}

    interface PublicWorkRow {
        String getId();

        Instant getLastOccurredAt();

        Long getPullRequestsOpened();

        Long getPullRequestsMerged();

        Long getApprovals();

        Long getChangeRequests();

        Long getCommentReviews();

        Long getIssuesOpened();

        String getType();

        Integer getNumber();

        String getTitle();

        String getState();

        Boolean getDraft();

        String getHtmlUrl();

        String getRepositoryKey();

        String getRepositoryName();

        default long count(ActivityKind kind) {
            return switch (kind) {
                case PULL_REQUEST_OPENED -> getPullRequestsOpened();
                case PULL_REQUEST_MERGED -> getPullRequestsMerged();
                case REVIEW_APPROVED -> getApprovals();
                case REVIEW_CHANGES_REQUESTED -> getChangeRequests();
                case REVIEW_COMMENTED -> getCommentReviews();
                case ISSUE_OPENED -> getIssuesOpened();
                default -> throw new InvalidInputException("This activity kind is not published.");
            };
        }
    }
}

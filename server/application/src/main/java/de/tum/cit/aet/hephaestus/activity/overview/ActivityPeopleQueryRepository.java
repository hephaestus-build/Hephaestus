package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEvent;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityBreakdownDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityCountsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityCoverageDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityRepositoryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityTeamDTO;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@org.springframework.stereotype.Repository
interface ActivityPeopleQueryRepository extends Repository<ActivityEvent, UUID> {
    String MONITORED = """
            WITH monitored AS (
                SELECT DISTINCT r.id, r.provider_id FROM repository_to_monitor m
                JOIN repository r ON r.name_with_owner = m.name_with_owner
                WHERE m.workspace_id = :#{#scope.workspaceId()}
            ), normalized AS (
                SELECT DISTINCT ON (e.actor_id, e.event_type, e.target_id)
                    e.actor_id, e.repository_id, e.event_type, e.target_id, e.occurred_at,
                    date_trunc('week', e.occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS week,
                    prr.pull_request_id AS reviewed_id,
                    CASE WHEN author.type = 'USER' AND author_machine.user_id IS NULL
                        THEN pr.author_id END AS helped_id
            """;
    String SOURCE = """
                FROM activity_event e
                JOIN "user" u ON u.id = e.actor_id
                JOIN monitored mr ON mr.id = e.repository_id AND mr.provider_id = u.provider_id
                LEFT JOIN workspace_membership wm ON wm.workspace_id = e.workspace_id AND wm.user_id = e.actor_id
                LEFT JOIN pull_request_review prr ON e.target_type = 'review' AND prr.id = e.target_id
                LEFT JOIN issue pr ON pr.id = prr.pull_request_id
                LEFT JOIN "user" author ON author.id = pr.author_id
                LEFT JOIN activity_automation author_machine ON author_machine.workspace_id = :#{#scope.workspaceId()} AND author_machine.user_id = pr.author_id
                WHERE e.workspace_id = :#{#scope.workspaceId()}
                  AND u.type IN ('USER', 'BOT')
                  AND (:own = true OR (NOT coalesce(wm.hidden, false)
                      AND NOT EXISTS (SELECT 1 FROM workspace_hidden_former_member h
                          WHERE h.workspace_id = :#{#scope.workspaceId()} AND h.user_id = e.actor_id)))
                  AND (:person = 0 OR e.actor_id = :person)
                  AND e.repository_id IN (:#{#scope.repositoryIds()})
                  AND (:#{#scope.teamIds().isEmpty()} = true OR (
                      EXISTS (SELECT 1 FROM team_membership tm WHERE tm.user_id = e.actor_id AND tm.team_id IN (:#{#scope.teamIds()}))
                      AND EXISTS (SELECT 1 FROM team_repository_permission p
                          WHERE p.repository_id = e.repository_id AND p.team_id IN (:#{#scope.teamIds()}))
                      AND NOT EXISTS (SELECT 1 FROM workspace_team_repository_settings s
                          WHERE s.workspace_id = :#{#scope.workspaceId()} AND s.repository_id = e.repository_id
                            AND s.team_id IN (:#{#scope.teamIds()}) AND s.hidden_from_contributions)
                      AND (e.target_type <> 'review' OR NOT EXISTS (
                          SELECT 1 FROM workspace_team_label_filter f WHERE f.workspace_id = :#{#scope.workspaceId()}
                          AND f.team_id IN (:#{#scope.teamIds()}) AND f.label_id IN (
                              SELECT l.id FROM label l WHERE l.repository_id = e.repository_id)) OR EXISTS (
                          SELECT 1 FROM workspace_team_label_filter f JOIN issue_label il ON il.label_id = f.label_id
                          WHERE f.workspace_id = :#{#scope.workspaceId()} AND f.team_id IN (:#{#scope.teamIds()}) AND il.issue_id = pr.id))
                  ))
                  AND (e.target_type <> 'review' OR (pr.author_id IS NULL OR pr.author_id <> e.actor_id))
                  AND e.event_type IN ('PULL_REQUEST_OPENED', 'PULL_REQUEST_MERGED', 'REVIEW_APPROVED',
                      'REVIEW_CHANGES_REQUESTED', 'REVIEW_COMMENTED', 'ISSUE_CREATED', 'COMMENT_CREATED', 'REVIEW_COMMENT_CREATED', 'PULL_REQUEST_CLOSED', 'ISSUE_CLOSED')
            """;
    String NORMALIZED = MONITORED + SOURCE + """
                  AND e.occurred_at >= :#{#range.from()} AND e.occurred_at < :#{#range.to()}
                ORDER BY e.actor_id, e.event_type, e.target_id, e.occurred_at
            ), marked AS (
                SELECT n.*,
                    row_number() OVER (PARTITION BY actor_id, reviewed_id) AS review_first,
                    row_number() OVER (PARTITION BY actor_id, week, reviewed_id) AS week_review_first,
                    row_number() OVER (PARTITION BY actor_id, helped_id) AS helped_first,
                    row_number() OVER (PARTITION BY actor_id, week) AS week_first
            """;
    String HEADLINE_COUNTS = """
                    count(*) FILTER (WHERE event_type = 'PULL_REQUEST_OPENED') AS opened,
                    count(*) FILTER (WHERE event_type = 'PULL_REQUEST_MERGED') AS merged,
                    count(*) FILTER (WHERE event_type = 'ISSUE_CREATED') AS issues,
                    count(*) FILTER (WHERE event_type IN ('COMMENT_CREATED', 'REVIEW_COMMENT_CREATED')) AS comments,
            """;
    String TYPE_COUNTS = """
                    count(*) FILTER (WHERE event_type = 'PULL_REQUEST_CLOSED') AS closed,
                    count(*) FILTER (WHERE event_type = 'REVIEW_APPROVED') AS approvals,
                    count(*) FILTER (WHERE event_type = 'REVIEW_CHANGES_REQUESTED') AS changes,
                    count(*) FILTER (WHERE event_type = 'REVIEW_COMMENTED') AS comment_reviews,
                    count(*) FILTER (WHERE event_type = 'COMMENT_CREATED') AS discussion_comments,
                    count(*) FILTER (WHERE event_type = 'REVIEW_COMMENT_CREATED') AS code_comments,
                    count(*) FILTER (WHERE event_type = 'ISSUE_CLOSED') AS issues_closed,
            """;
    String COUNTS = HEADLINE_COUNTS + """
                    CASE WHEN grouping(week) = 1
                        THEN count(*) FILTER (WHERE reviewed_id IS NOT NULL AND review_first = 1)
                        ELSE count(*) FILTER (WHERE reviewed_id IS NOT NULL AND week_review_first = 1) END AS reviewed,
                    CASE WHEN grouping(week) = 1
                        THEN count(*) FILTER (WHERE helped_id IS NOT NULL AND helped_first = 1)
                        ELSE 0 END AS helped,
                    count(*) FILTER (WHERE week_first = 1) AS active_weeks
            """;
    String PERSON_COUNTS = HEADLINE_COUNTS + TYPE_COUNTS + """
                    CASE WHEN grouping(repository_id) = 0 THEN count(*) FILTER (WHERE reviewed_id IS NOT NULL AND repo_review_first = 1)
                        WHEN grouping(week) = 1
                        THEN count(*) FILTER (WHERE reviewed_id IS NOT NULL AND review_first = 1)
                        ELSE count(*) FILTER (WHERE reviewed_id IS NOT NULL AND week_review_first = 1) END AS reviewed,
                    CASE WHEN grouping(repository_id) = 0 THEN count(*) FILTER (WHERE helped_id IS NOT NULL AND repo_helped_first = 1)
                        WHEN grouping(week) = 1
                        THEN count(*) FILTER (WHERE helped_id IS NOT NULL AND helped_first = 1)
                        ELSE count(*) FILTER (WHERE helped_id IS NOT NULL AND week_helped_first = 1) END AS helped,
                    CASE WHEN grouping(repository_id) = 0 THEN count(*) FILTER (WHERE repo_week_first = 1)
                        ELSE count(*) FILTER (WHERE week_first = 1) END AS active_weeks
            """;
    String RESULT = """
            )
            SELECT c.*, u.login, u.name, u.avatar_url, u.html_url,
                CASE WHEN c.total = 1 AND c.all_repositories = 1 THEN (SELECT e.occurred_at
            """ + SOURCE + """
                    AND e.actor_id = c.actor_id
                    AND (e.event_type IN ('PULL_REQUEST_OPENED', 'ISSUE_CREATED')
                        OR (prr.pull_request_id IS NOT NULL AND e.event_type IN ('REVIEW_APPROVED', 'REVIEW_CHANGES_REQUESTED', 'REVIEW_COMMENTED')))
                    ORDER BY e.occurred_at LIMIT 1) END AS first_contribution,
                (u.type = 'BOT' OR actor_machine.user_id IS NOT NULL) AS automation
            FROM counts c JOIN "user" u ON u.id = c.actor_id
            LEFT JOIN activity_automation actor_machine ON actor_machine.workspace_id = :#{#scope.workspaceId()} AND actor_machine.user_id = u.id
            ORDER BY c.actor_id, c.all_repositories DESC, c.total DESC, c.week, c.repository_id
            """;
    String PEOPLE = NORMALIZED + """
                FROM normalized n
            ), counts AS (
                SELECT actor_id, week, NULL::bigint AS repository_id, grouping(week) AS total, 1 AS all_repositories,
            """ + COUNTS + """
                FROM marked GROUP BY GROUPING SETS ((actor_id), (actor_id, week))
            """ + RESULT;
    String PERSON = NORMALIZED + """
                    , row_number() OVER (PARTITION BY actor_id, week, helped_id) AS week_helped_first,
                    row_number() OVER (PARTITION BY actor_id, repository_id, reviewed_id) AS repo_review_first,
                    row_number() OVER (PARTITION BY actor_id, repository_id, helped_id) AS repo_helped_first,
                    row_number() OVER (PARTITION BY actor_id, repository_id, week) AS repo_week_first
                FROM normalized n
            ), counts AS (
                SELECT actor_id, week, repository_id, grouping(week) AS total, grouping(repository_id) AS all_repositories,
            """ + PERSON_COUNTS + """
                FROM marked GROUP BY GROUPING SETS ((actor_id), (actor_id, week), (actor_id, repository_id))
            """ + RESULT;

    @Query(value = PEOPLE, nativeQuery = true)
    List<CountRow> findPeople(
            @Param("scope") ActivityScope scope,
            @Param("range") TimeRange range,
            @Param("person") long person,
            @Param("own") boolean own);

    @Query(value = PERSON, nativeQuery = true)
    List<CountRow> findPerson(
            @Param("scope") ActivityScope scope,
            @Param("range") TimeRange range,
            @Param("person") long person,
            @Param("own") boolean own);

    default List<PersonCount> people(ActivityScope scope, TimeRange range, long person, boolean own) {
        return findPerson(scope, range, person, own).stream()
                .map(row -> new PersonCount(
                        row.person(),
                        row.getAutomation(),
                        row.getTotal() == 1 && row.getAllRepositories() == 1,
                        row.getAllRepositories() == 0 ? row.getRepositoryId() : null,
                        row.getWeek(),
                        row.getFirstContribution(),
                        row.counts(),
                        row.breakdown()))
                .toList();
    }

    @Query(value = """
            SELECT u.id, u.login, u.name, u.avatar_url, u.html_url,
                (u.type = 'BOT' OR a.user_id IS NOT NULL) AS automation
            FROM "user" u
            LEFT JOIN workspace_membership wm ON wm.workspace_id = :workspace AND wm.user_id = u.id
            LEFT JOIN activity_automation a ON a.workspace_id = :workspace AND a.user_id = u.id
            WHERE u.id = :person AND u.type IN ('USER', 'BOT') AND (:own = true OR (NOT coalesce(wm.hidden, false)
              AND NOT EXISTS (SELECT 1 FROM workspace_hidden_former_member h
                  WHERE h.workspace_id = :workspace AND h.user_id = u.id)))
              AND ((:own = true AND wm.user_id IS NOT NULL) OR EXISTS (SELECT 1 FROM activity_event e JOIN repository r ON r.id = e.repository_id
                  WHERE e.workspace_id = :workspace AND e.actor_id = u.id AND r.provider_id = u.provider_id AND EXISTS (
                      SELECT 1 FROM repository_to_monitor m WHERE m.workspace_id = :workspace
                          AND m.name_with_owner = r.name_with_owner)))
            """, nativeQuery = true)
    Optional<ContributorRow> findContributor(
            @Param("workspace") long workspace, @Param("person") long userId, @Param("own") boolean own);

    default Contributor contributor(long workspace, long userId, boolean own) {
        var row = findContributor(workspace, userId, own)
                .orElseThrow(() -> new EntityNotFoundException("Contributor", userId));
        return new Contributor(row.person(), row.getAutomation());
    }

    @Query(value = """
                SELECT r.id, r.name_with_owner AS key, r.name FROM repository_to_monitor m
                JOIN repository r ON r.name_with_owner = m.name_with_owner
                WHERE m.workspace_id = :workspace AND (EXISTS (
                    SELECT 1 FROM workspace w JOIN organization o ON o.id = w.organization_id
                    WHERE w.id = :workspace AND o.provider_id = r.provider_id) OR EXISTS (
                    SELECT 1 FROM activity_event e WHERE e.workspace_id = :workspace AND e.repository_id = r.id))
                ORDER BY r.name_with_owner
                """, nativeQuery = true)
    List<RepositoryRow> findRepositories(@Param("workspace") long workspace);

    default List<ActivityRepositoryDTO> repositories(long workspace) {
        return findRepositories(workspace).stream()
                .map(row -> new ActivityRepositoryDTO(row.getId(), row.getKey(), row.getName()))
                .toList();
    }

    @Query(value = """
                WITH RECURSIVE hidden AS (
                    SELECT team_id FROM workspace_team_settings WHERE workspace_id = :workspace AND hidden
                    UNION
                    SELECT t.id FROM team t JOIN hidden h ON t.parent_id = h.team_id
                )
                SELECT t.id, t.slug AS key, t.name, t.parent_id FROM workspace w JOIN organization o ON o.id = w.organization_id
                JOIN team t ON t.provider_id = o.provider_id AND lower(t.organization) = lower(w.account_login)
                WHERE w.id = :workspace AND t.id NOT IN (SELECT team_id FROM hidden)
                ORDER BY t.name, t.slug
                """, nativeQuery = true)
    List<TeamRow> findTeams(@Param("workspace") long workspace);

    default List<ActivityTeamDTO> teams(long workspace) {
        return findTeams(workspace).stream()
                .map(row -> new ActivityTeamDTO(row.getId(), row.getKey(), row.getName(), row.getParentId()))
                .toList();
    }

    @Query(value = """
                SELECT min(e.occurred_at) AS start FROM activity_event e JOIN repository r ON r.id = e.repository_id
                WHERE e.workspace_id = :#{#scope.workspaceId()} AND e.repository_id IN (:#{#scope.repositoryIds()}) AND EXISTS (
                    SELECT 1 FROM repository_to_monitor m WHERE m.workspace_id = :#{#scope.workspaceId()} AND m.name_with_owner = r.name_with_owner)
                """, nativeQuery = true)
    @Nullable
    Instant findEarliest(@Param("scope") ActivityScope scope);

    default Instant earliest(ActivityScope scope, Instant fallback) {
        return Objects.requireNonNullElse(findEarliest(scope), fallback);
    }

    @Query(value = """
                WITH monitors AS (
                    SELECT m.*, (issue_backfill_high_water_mark IS NOT NULL
                        AND pull_request_backfill_high_water_mark IS NOT NULL
                        AND (issue_backfill_high_water_mark = 0 OR issue_backfill_checkpoint <= 0)
                        AND (pull_request_backfill_high_water_mark = 0 OR pull_request_backfill_checkpoint <= 0)) AS complete
                    FROM repository_to_monitor m WHERE workspace_id = :#{#scope.workspaceId()}
                        AND EXISTS (SELECT 1 FROM repository r WHERE r.name_with_owner = m.name_with_owner
                            AND r.id IN (:#{#scope.repositoryIds()}))
                )
                SELECT count(*) AS total, count(*) FILTER (WHERE complete) AS complete,
                    (SELECT min(e.occurred_at) FROM activity_event e JOIN repository r ON r.id = e.repository_id
                        WHERE e.workspace_id = :#{#scope.workspaceId()} AND e.repository_id IN (:#{#scope.repositoryIds()}) AND EXISTS (SELECT 1 FROM monitors m
                            WHERE m.complete AND m.name_with_owner = r.name_with_owner)) AS since
                FROM monitors
                """, nativeQuery = true)
    CoverageRow findCoverage(@Param("scope") ActivityScope scope);

    default ActivityCoverageDTO coverage(ActivityScope scope) {
        var row = findCoverage(scope);
        return new ActivityCoverageDTO(row.getSince(), row.getComplete(), row.getTotal());
    }

    @Query(value = """
            SELECT u.id FROM "user" u
            LEFT JOIN workspace_membership wm ON wm.workspace_id = :#{#scope.workspaceId()} AND wm.user_id = u.id
            WHERE u.type = 'USER' AND NOT coalesce(wm.hidden, false)
                AND NOT EXISTS (SELECT 1 FROM workspace_hidden_former_member h
                    WHERE h.workspace_id = :#{#scope.workspaceId()} AND h.user_id = u.id)
                AND NOT EXISTS (SELECT 1 FROM activity_automation a
                    WHERE a.workspace_id = :#{#scope.workspaceId()} AND a.user_id = u.id)
                AND (:#{#scope.teamIds().isEmpty()} = true OR EXISTS (SELECT 1 FROM team_membership tm
                    WHERE tm.user_id = u.id AND tm.team_id IN (:#{#scope.teamIds()})))
                AND EXISTS (SELECT 1 FROM activity_event e JOIN repository r ON r.id = e.repository_id
                    WHERE e.workspace_id = :#{#scope.workspaceId()} AND e.actor_id = u.id
                        AND e.repository_id IN (:#{#scope.repositoryIds()}) AND r.provider_id = u.provider_id)
            """, nativeQuery = true)
    List<Long> findActorIds(@Param("scope") ActivityScope scope);

    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM activity_event e WHERE e.workspace_id = :workspace AND e.actor_id = :person)
            OR EXISTS (SELECT 1 FROM workspace_membership m WHERE m.workspace_id = :workspace AND m.user_id = :person)
        """, nativeQuery = true)
    boolean canClassify(@Param("workspace") long workspace, @Param("person") long userId);

    interface RepositoryRow {
        long getId();

        String getKey();

        String getName();
    }

    interface TeamRow extends RepositoryRow {
        @Nullable
        Long getParentId();
    }

    interface CoverageRow {
        @Nullable
        Instant getSince();

        long getComplete();

        long getTotal();
    }

    interface ContributorRow {
        long getId();

        String getLogin();

        @Nullable
        String getName();

        String getAvatarUrl();

        String getHtmlUrl();

        boolean getAutomation();

        default UserInfoDTO person() {
            return new UserInfoDTO(
                    getId(),
                    getLogin(),
                    "",
                    getAvatarUrl(),
                    Objects.requireNonNullElse(getName(), getLogin()),
                    getHtmlUrl());
        }
    }

    interface CountRow {
        long getActorId();

        String getLogin();

        @Nullable
        String getName();

        String getAvatarUrl();

        String getHtmlUrl();

        boolean getAutomation();

        int getTotal();

        int getAllRepositories();

        @Nullable
        Long getRepositoryId();

        @Nullable
        Instant getWeek();

        @Nullable
        Instant getFirstContribution();

        long getOpened();

        long getMerged();

        long getReviewed();

        long getHelped();

        long getIssues();

        long getComments();

        long getActiveWeeks();

        int getClosed();

        int getApprovals();

        int getChanges();

        int getCommentReviews();

        int getDiscussionComments();

        int getCodeComments();

        int getIssuesClosed();

        default UserInfoDTO person() {
            return new UserInfoDTO(
                    getActorId(),
                    getLogin(),
                    "",
                    getAvatarUrl(),
                    Objects.requireNonNullElse(getName(), getLogin()),
                    getHtmlUrl());
        }

        default ActivityCountsDTO counts() {
            return new ActivityCountsDTO(
                    getOpened() + getReviewed() + getIssues(),
                    getOpened(),
                    getMerged(),
                    getReviewed(),
                    getHelped(),
                    getIssues(),
                    getComments(),
                    getActiveWeeks());
        }

        default ActivityBreakdownDTO breakdown() {
            return new ActivityBreakdownDTO(
                    getClosed(),
                    getApprovals(),
                    getChanges(),
                    getCommentReviews(),
                    getDiscussionComments(),
                    getCodeComments(),
                    getIssuesClosed());
        }
    }

    record Contributor(UserInfoDTO person, boolean automation) {}

    record PersonCount(
            UserInfoDTO person,
            boolean automation,
            boolean total,
            @Nullable Long repository,
            @Nullable Instant week,
            @Nullable Instant firstContribution,
            ActivityCountsDTO counts,
            ActivityBreakdownDTO breakdown) {}
}

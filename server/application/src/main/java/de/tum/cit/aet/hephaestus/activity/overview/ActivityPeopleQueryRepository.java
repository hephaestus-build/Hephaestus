package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityCountsDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityCoverageDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityRepositoryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityTeamDTO;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ActivityPeopleQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    // Normalize reviews to their pull request before DISTINCT, across states and repeated submissions.
    static final String PEOPLE = """
            WITH monitored AS (
                SELECT DISTINCT r.id, r.provider_id FROM repository_to_monitor m
                JOIN repository r ON r.name_with_owner = m.name_with_owner
                WHERE m.workspace_id = :workspace
            ), normalized AS (
                SELECT e.actor_id, e.repository_id, e.event_type, e.target_id, e.occurred_at,
                    date_trunc('week', e.occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS week,
                    prr.pull_request_id AS reviewed_id,
                    CASE WHEN author.type = 'USER' AND author_machine.user_id IS NULL
                        THEN pr.author_id END AS helped_id
                FROM activity_event e
                JOIN "user" u ON u.id = e.actor_id
                JOIN monitored mr ON mr.id = e.repository_id AND mr.provider_id = u.provider_id
                LEFT JOIN workspace_membership wm ON wm.workspace_id = e.workspace_id AND wm.user_id = e.actor_id
                LEFT JOIN pull_request_review prr ON e.target_type = 'review' AND prr.id = e.target_id
                LEFT JOIN issue pr ON pr.id = prr.pull_request_id
                LEFT JOIN "user" author ON author.id = pr.author_id
                LEFT JOIN activity_automation author_machine ON author_machine.workspace_id = :workspace AND author_machine.user_id = pr.author_id
                WHERE e.workspace_id = :workspace
                  AND e.occurred_at >= :from AND e.occurred_at < :to
                  AND u.type IN ('USER', 'BOT')
                  AND NOT coalesce(wm.hidden, false)
                  AND NOT EXISTS (SELECT 1 FROM workspace_hidden_former_member h
                      WHERE h.workspace_id = :workspace AND h.user_id = e.actor_id)
                  AND (:membersOnly = false OR wm.user_id IS NOT NULL)
                  AND (:person = 0 OR e.actor_id = :person)
                  AND (:filterRepos = false OR e.repository_id IN (:repositories))
                  AND (:filterTeams = false OR (
                      EXISTS (SELECT 1 FROM team_membership tm WHERE tm.user_id = e.actor_id AND tm.team_id IN (:teams))
                      AND EXISTS (SELECT 1 FROM team_repository_permission p
                          WHERE p.repository_id = e.repository_id AND p.team_id IN (:teams))
                      AND NOT EXISTS (SELECT 1 FROM workspace_team_repository_settings s
                          WHERE s.workspace_id = :workspace AND s.repository_id = e.repository_id
                            AND s.team_id IN (:teams) AND s.hidden_from_contributions)
                      AND (e.target_type <> 'review' OR NOT EXISTS (
                          SELECT 1 FROM workspace_team_label_filter f WHERE f.workspace_id = :workspace
                          AND f.team_id IN (:teams) AND f.label_id IN (
                              SELECT l.id FROM label l WHERE l.repository_id = e.repository_id)) OR EXISTS (
                          SELECT 1 FROM workspace_team_label_filter f JOIN issue_label il ON il.label_id = f.label_id
                          WHERE f.workspace_id = :workspace AND f.team_id IN (:teams) AND il.issue_id = pr.id))
                  ))
                  AND (e.target_type <> 'review' OR (pr.author_id <> e.actor_id))
                  AND e.event_type IN ('PULL_REQUEST_OPENED', 'PULL_REQUEST_MERGED', 'REVIEW_APPROVED',
                      'REVIEW_CHANGES_REQUESTED', 'REVIEW_COMMENTED', 'ISSUE_CREATED', 'COMMENT_CREATED', 'REVIEW_COMMENT_CREATED')
            ), counts AS (
                SELECT actor_id, week, grouping(week) AS total,
                    count(DISTINCT target_id) FILTER (WHERE event_type = 'PULL_REQUEST_OPENED') AS opened,
                    count(DISTINCT target_id) FILTER (WHERE event_type = 'PULL_REQUEST_MERGED') AS merged,
                    count(DISTINCT reviewed_id) AS reviewed,
                    count(DISTINCT helped_id) AS helped,
                    count(DISTINCT target_id) FILTER (WHERE event_type = 'ISSUE_CREATED') AS issues,
                    count(DISTINCT (event_type, target_id)) FILTER (WHERE event_type IN ('COMMENT_CREATED', 'REVIEW_COMMENT_CREATED')) AS comments,
                    count(DISTINCT week) AS active_weeks
                FROM normalized GROUP BY GROUPING SETS ((actor_id), (actor_id, week))
            )
            SELECT c.*, u.login, u.name, u.avatar_url, u.html_url,
                CASE WHEN c.total = 1 THEN (SELECT first.occurred_at FROM activity_event first
                    WHERE first.workspace_id = :workspace AND first.actor_id = c.actor_id
                    AND first.repository_id IN (SELECT id FROM monitored)
                    AND (first.event_type IN ('PULL_REQUEST_OPENED', 'ISSUE_CREATED')
                        OR (first.event_type IN ('REVIEW_APPROVED','REVIEW_CHANGES_REQUESTED','REVIEW_COMMENTED')
                            AND EXISTS (SELECT 1 FROM pull_request_review fr JOIN issue fp ON fp.id = fr.pull_request_id
                                WHERE fr.id = first.target_id AND fp.author_id <> first.actor_id)))
                    ORDER BY first.occurred_at LIMIT 1) END AS first_contribution,

                (u.type = 'BOT' OR actor_machine.user_id IS NOT NULL) AS automation
            FROM counts c JOIN "user" u ON u.id = c.actor_id
            LEFT JOIN activity_automation actor_machine ON actor_machine.workspace_id = :workspace AND actor_machine.user_id = u.id
            ORDER BY c.actor_id, c.total DESC, c.week
            """;

    List<PersonCount> people(
            long workspace,
            TimeRange range,
            Set<Long> teams,
            Set<Long> repositories,
            boolean membersOnly,
            long person) {
        return jdbc.query(
                PEOPLE,
                new MapSqlParameterSource()
                        .addValue("workspace", workspace)
                        .addValue("from", Timestamp.from(range.from()))
                        .addValue("to", Timestamp.from(range.to()))
                        .addValue("membersOnly", membersOnly)
                        .addValue("person", person)
                        .addValue("filterTeams", !teams.isEmpty())
                        .addValue("teams", teams.isEmpty() ? Set.of(0L) : teams)
                        .addValue("filterRepos", !repositories.isEmpty())
                        .addValue("repositories", repositories.isEmpty() ? Set.of(0L) : repositories),
                (rs, row) -> {
                    long opened = rs.getLong("opened");
                    long reviewed = rs.getLong("reviewed");
                    long issues = rs.getLong("issues");
                    return new PersonCount(
                            new UserInfoDTO(
                                    rs.getLong("actor_id"),
                                    rs.getString("login"),
                                    "",
                                    rs.getString("avatar_url"),
                                    Objects.requireNonNullElse(rs.getString("name"), rs.getString("login")),
                                    rs.getString("html_url")),
                            rs.getBoolean("automation"),
                            rs.getInt("total") == 1,
                            instant(rs, "week"),
                            instant(rs, "first_contribution"),
                            new ActivityCountsDTO(
                                    opened + reviewed + issues,
                                    opened,
                                    rs.getLong("merged"),
                                    reviewed,
                                    rs.getLong("helped"),
                                    issues,
                                    rs.getLong("comments"),
                                    rs.getLong("active_weeks")));
                });
    }

    List<ActivityRepositoryDTO> repositories(long workspace) {
        return jdbc.query(
                """
                SELECT r.id, r.name_with_owner, r.name FROM repository_to_monitor m
                JOIN repository r ON r.name_with_owner = m.name_with_owner
                WHERE m.workspace_id = :workspace AND (EXISTS (
                    SELECT 1 FROM workspace w JOIN organization o ON o.id = w.organization_id
                    WHERE w.id = :workspace AND o.provider_id = r.provider_id) OR EXISTS (
                    SELECT 1 FROM activity_event e WHERE e.workspace_id = :workspace AND e.repository_id = r.id))
                ORDER BY r.name_with_owner
                """,
                new MapSqlParameterSource("workspace", workspace),
                (rs, row) -> new ActivityRepositoryDTO(
                        rs.getLong("id"), rs.getString("name_with_owner"), rs.getString("name")));
    }

    List<ActivityTeamDTO> teams(long workspace) {
        return jdbc.query(
                """
                WITH RECURSIVE hidden AS (
                    SELECT team_id FROM workspace_team_settings WHERE workspace_id = :workspace AND hidden
                    UNION
                    SELECT t.id FROM team t JOIN hidden h ON t.parent_id = h.team_id
                )
                SELECT t.id, t.slug, t.name FROM workspace w JOIN organization o ON o.id = w.organization_id
                JOIN team t ON t.provider_id = o.provider_id AND lower(t.organization) = lower(w.account_login)
                WHERE w.id = :workspace AND t.id NOT IN (SELECT team_id FROM hidden)
                ORDER BY t.name, t.slug
                """,
                new MapSqlParameterSource("workspace", workspace),
                (rs, row) -> new ActivityTeamDTO(rs.getLong("id"), rs.getString("slug"), rs.getString("name")));
    }

    Instant earliest(long workspace, Instant fallback) {
        List<Instant> result = jdbc.query(
                """
                SELECT min(e.occurred_at) AS start FROM activity_event e JOIN repository r ON r.id = e.repository_id
                WHERE e.workspace_id = :workspace AND EXISTS (
                    SELECT 1 FROM repository_to_monitor m WHERE m.workspace_id = :workspace AND m.name_with_owner = r.name_with_owner)
                """,
                new MapSqlParameterSource("workspace", workspace),
                (rs, row) -> Objects.requireNonNullElse(instant(rs, "start"), fallback));
        return result.getFirst();
    }

    ActivityCoverageDTO coverage(long workspace) {
        return jdbc.query(
                        """
                WITH monitors AS (
                    SELECT m.*, (issue_backfill_high_water_mark IS NOT NULL
                        AND pull_request_backfill_high_water_mark IS NOT NULL
                        AND (issue_backfill_high_water_mark = 0 OR issue_backfill_checkpoint <= 0)
                        AND (pull_request_backfill_high_water_mark = 0 OR pull_request_backfill_checkpoint <= 0)) AS complete
                    FROM repository_to_monitor m WHERE workspace_id = :workspace
                )
                SELECT count(*) AS total, count(*) FILTER (WHERE complete) AS complete,
                    (SELECT min(e.occurred_at) FROM activity_event e JOIN repository r ON r.id = e.repository_id
                        WHERE e.workspace_id = :workspace AND EXISTS (SELECT 1 FROM monitors m
                            WHERE m.complete AND m.name_with_owner = r.name_with_owner)) AS since
                FROM monitors
                """,
                        new MapSqlParameterSource("workspace", workspace),
                        (rs, row) -> new ActivityCoverageDTO(
                                instant(rs, "since"), rs.getLong("complete"), rs.getLong("total")))
                .getFirst();
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record PersonCount(
            UserInfoDTO person,
            boolean automation,
            boolean total,
            @Nullable Instant week,
            @Nullable Instant firstContribution,
            ActivityCountsDTO counts) {}
}

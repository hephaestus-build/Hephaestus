package de.tum.cit.aet.hephaestus.integration.slack;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance person scope and explicitly workspace-bound Slack attribution")
public class SlackPersonSourceIdentityContributor implements PersonSourceIdentityContributor {
    private final JdbcTemplate jdbc;
    private static final String SOURCES = """
                FROM slack_message m JOIN slack_thread t ON t.workspace_id=m.workspace_id
                    AND t.slack_channel_id=m.slack_channel_id
                    AND t.slack_thread_ts=COALESCE(m.slack_thread_ts,m.slack_ts)
                JOIN identity_provider p ON p.type='SLACK' AND p.server_url='https://slack.com'
                """;

    @Override
    public Set<String> artifactKinds() {
        return Set.of("chat.conversation_thread");
    }

    @Override
    public List<Long> sourceIds(PersonScope person) {
        Set<Long> ids = new TreeSet<>();
        for (var identity : person.identities())
            ids.addAll(jdbc.query(
                    "SELECT DISTINCT t.id " + SOURCES
                            + " WHERE p.id=? AND m.author_slack_user_id=? AND m.slack_team_id=?",
                    (rs, row) -> rs.getLong(1),
                    identity.providerId(),
                    identity.subject(),
                    identity.teamId()));
        return List.copyOf(ids);
    }

    @Override
    public List<PersonIdentity> identitiesForSource(long workspaceId, String artifactKind, long artifactId) {
        return jdbc.query(
                "SELECT DISTINCT p.id,m.author_slack_user_id,m.slack_team_id " + SOURCES
                        + " WHERE t.workspace_id=? AND t.id=? AND m.author_slack_user_id IS NOT NULL AND m.author_slack_user_id<>''",
                (rs, row) -> new PersonIdentity(
                        rs.getLong(1),
                        Objects.requireNonNull(rs.getString(2)),
                        Objects.requireNonNull(rs.getString(3))),
                workspaceId,
                artifactId);
    }
}

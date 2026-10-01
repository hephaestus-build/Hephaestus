package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("An instance-wide exact provider identity processing fence")
public class PersonSuppressionService implements PersonProcessingSuppression {
    private final JdbcTemplate jdbc;

    public void suppress(PersonScope scope, UUID requestId) {
        for (PersonIdentity i : scope.identities()) {
            jdbc.update(
                    "INSERT INTO person_suppression(id, provider_id, subject, team_key) VALUES (?,?,?,?) ON CONFLICT(provider_id,subject,team_key) DO NOTHING",
                    UUID.randomUUID(),
                    i.providerId(),
                    i.subject(),
                    Objects.requireNonNullElse(i.teamId(), ""));
            UUID owner = jdbc.queryForObject(
                    "SELECT active_request_id FROM person_suppression WHERE provider_id=? AND subject=? AND team_key=? FOR UPDATE",
                    UUID.class,
                    i.providerId(),
                    i.subject(),
                    Objects.requireNonNullElse(i.teamId(), ""));
            if (owner != null && !owner.equals(requestId))
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "An erasure request already owns an identity; resume that request");
            jdbc.update(
                    "UPDATE person_suppression SET active_request_id=? WHERE provider_id=? AND subject=? AND team_key=?",
                    requestId,
                    i.providerId(),
                    i.subject(),
                    Objects.requireNonNullElse(i.teamId(), ""));
        }
    }

    public void release(UUID requestId) {
        jdbc.update("UPDATE person_suppression SET active_request_id=NULL WHERE active_request_id=?", requestId);
    }

    @Override
    public boolean isSuppressed(long providerId, String subject, @Nullable String teamId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM person_suppression s JOIN identity_provider p ON p.id=s.provider_id WHERE s.provider_id=? AND s.subject=? AND (s.team_key=? OR (p.type='OUTLINE' AND s.team_key='')))",
                Boolean.class,
                providerId,
                subject,
                Objects.requireNonNullElse(teamId, "")));
    }

    @Override
    public boolean isUserSuppressed(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM person_suppression s JOIN \"user\" u ON u.provider_id=s.provider_id AND u.native_id::text=s.subject WHERE u.id=? AND s.team_key='')",
                Boolean.class,
                userId));
    }

    @Override
    public boolean isArtifactSuppressed(long workspaceId, String artifactKind, long artifactId) {
        return switch (artifactKind) {
            case "scm.pull_request", "scm.issue" ->
                Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                      SELECT 1 FROM issue i
                      JOIN repository_to_monitor rm ON rm.repository_id=i.repository_id AND rm.workspace_id=?
                      JOIN "user" u ON u.id IN (
                        SELECT author_id FROM issue WHERE id=i.id
                        UNION SELECT merged_by_id FROM issue WHERE id=i.id
                        UNION SELECT author_id FROM issue_comment WHERE issue_id=i.id
                        UNION SELECT author_id FROM pull_request_review WHERE pull_request_id=i.id
                        UNION SELECT author_id FROM pull_request_review_comment WHERE pull_request_id=i.id
                      )
                      JOIN person_suppression s ON s.provider_id=u.provider_id AND s.subject=u.native_id::text
                          AND s.team_key=''
                      WHERE i.id=?
                    )
                    """, Boolean.class, workspaceId, artifactId));
            case "chat.conversation_thread" ->
                Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                      SELECT 1 FROM slack_thread t
                      JOIN slack_message m ON m.workspace_id=t.workspace_id
                        AND m.slack_channel_id=t.slack_channel_id
                        AND COALESCE(m.slack_thread_ts,m.slack_ts)=t.slack_thread_ts
                      JOIN person_suppression s ON s.subject=m.author_slack_user_id AND s.team_key=m.slack_team_id
                      JOIN identity_provider p ON p.id=s.provider_id AND p.type='SLACK'
                        AND p.server_url='https://slack.com'
                      WHERE t.workspace_id=? AND t.id=?
                    )
                    """, Boolean.class, workspaceId, artifactId));
            case "docs.document" ->
                Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                      SELECT 1 FROM outline_document d
                      JOIN connection c ON c.id=d.connection_id AND c.workspace_id=d.workspace_id
                      JOIN identity_provider p ON p.type='OUTLINE' AND p.server_url=c.config->>'serverUrl'
                      JOIN person_suppression s ON s.provider_id=p.id AND (
                        s.subject=d.created_by_subject OR s.subject=d.updated_by_subject
                        OR jsonb_exists(COALESCE(d.collaborator_subjects,'[]'::jsonb),s.subject)
                      )
                      WHERE d.workspace_id=? AND d.id=?
                    )
                    """, Boolean.class, workspaceId, artifactId));
            default -> false;
        };
    }
}

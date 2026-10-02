package de.tum.cit.aet.hephaestus.integration.scm;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** One source-role relation for exact scope selection and post-sync processing admission. */
@Component
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance person scope and explicitly workspace-bound source attribution")
public class ScmPersonSourceIdentityContributor implements PersonSourceIdentityContributor {
    private final JdbcTemplate jdbc;
    private static final String ROLES = """
                SELECT id AS artifact_id,author_id AS user_id FROM issue
                UNION ALL SELECT id,merged_by_id FROM issue
                UNION ALL SELECT issue_id,user_id FROM issue_assignee
                UNION ALL SELECT pull_request_id,user_id FROM pull_request_requested_reviewers
                UNION ALL SELECT issue_id,author_id FROM issue_comment
                UNION ALL SELECT pull_request_id,author_id FROM pull_request_review
                UNION ALL SELECT pull_request_id,author_id FROM pull_request_review_comment
                UNION ALL SELECT cp.pull_request_id,c.author_id FROM commit_pull_request cp JOIN git_commit c ON c.id=cp.commit_id
                UNION ALL SELECT cp.pull_request_id,c.committer_id FROM commit_pull_request cp JOIN git_commit c ON c.id=cp.commit_id
                UNION ALL SELECT cp.pull_request_id,c.user_id FROM commit_pull_request cp JOIN commit_contributor c ON c.commit_id=cp.commit_id
                """;

    @Override
    public Set<String> artifactKinds() {
        return Set.of("scm.issue", "scm.pull_request");
    }

    @Override
    public List<Long> sourceIds(PersonScope person) {
        return jdbc.query(
                "SELECT DISTINCT artifact_id FROM (" + ROLES + ") roles WHERE user_id=ANY(?) ORDER BY artifact_id",
                (rs, row) -> rs.getLong(1),
                new org.springframework.jdbc.support.SqlArrayValue(
                        "bigint", person.userIds().toArray()));
    }

    @Override
    public List<PersonIdentity> identitiesForSource(long workspaceId, String artifactKind, long artifactId) {
        boolean connected = connectedToWorkspace(workspaceId, artifactId);
        return jdbc.query(
                "SELECT DISTINCT u.provider_id,u.native_id::text FROM (" + ROLES + """
                ) roles JOIN issue i ON i.id=roles.artifact_id
                JOIN repository r ON r.id=i.repository_id
                JOIN identity_provider p ON p.id=r.provider_id
                JOIN "user" u ON u.id=roles.user_id
                WHERE i.id=? AND (
                    ?
                    OR EXISTS (SELECT 1 FROM artifact_signal a WHERE a.workspace_id=?
                        AND a.artifact_kind=? AND a.artifact_id=i.id)) AND ((?='scm.pull_request' AND i.issue_type='PULL_REQUEST')
                    OR (?='scm.issue' AND i.issue_type='ISSUE'))
                """,
                (rs, row) -> new PersonIdentity(rs.getLong(1), Objects.requireNonNull(rs.getString(2)), null),
                artifactId,
                connected,
                workspaceId,
                artifactKind,
                artifactKind,
                artifactKind);
    }

    private boolean connectedToWorkspace(long workspaceId, long artifactId) {
        var sources = jdbc.query(
                """
                SELECT r.native_id,p.type,p.server_url FROM issue i
                JOIN repository r ON r.id=i.repository_id JOIN identity_provider p ON p.id=r.provider_id
                WHERE i.id=?
                """,
                (rs, row) -> new RepositorySource(
                        rs.getLong(1), Objects.requireNonNull(rs.getString(2)), ScmOrigin.of(rs.getString(3))),
                artifactId);
        if (sources.isEmpty()) return false;
        var source = sources.getFirst();
        if (source.origin().isEmpty()) return false;
        return jdbc
                .query(
                        """
                SELECT c.kind,c.config->>'serverUrl' FROM repository_to_monitor rm
                JOIN connection c ON c.workspace_id=rm.workspace_id
                WHERE rm.workspace_id=? AND rm.native_id=? AND c.kind=?
                """,
                        (rs, row) -> PersonSourceNamespace.from(
                                        Objects.requireNonNull(rs.getString(1)), rs.getString(2))
                                .map(namespace ->
                                        ScmOrigin.of(namespace.serverUrl()).equals(source.origin()))
                                .orElse(false),
                        workspaceId,
                        source.nativeRepositoryId(),
                        source.type())
                .stream()
                .anyMatch(Boolean::booleanValue);
    }

    private record RepositorySource(long nativeRepositoryId, String type, Optional<String> origin) {}
}

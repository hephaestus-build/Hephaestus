package de.tum.cit.aet.hephaestus.integration.scm;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceScope;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;

/** One source-role relation for exact scope selection and post-sync processing admission. */
@Component
@RequiredArgsConstructor
public class ScmPersonSourceIdentityContributor implements PersonSourceIdentityContributor {
    private final JdbcTemplate jdbc;
    private static final String ROLES = """
        SELECT id AS source_id,author_id AS user_id FROM issue
        UNION ALL SELECT id,merged_by_id FROM issue
        UNION ALL SELECT issue_id,user_id FROM issue_assignee
        UNION ALL SELECT pull_request_id,user_id FROM pull_request_requested_reviewers
        UNION ALL SELECT issue_id,author_id FROM issue_comment
        UNION ALL SELECT pull_request_id,author_id FROM pull_request_review
        UNION ALL SELECT pull_request_id,resolved_by_id FROM pull_request_review_thread
        UNION ALL SELECT pull_request_id,author_id FROM pull_request_review_comment
        UNION ALL SELECT cp.pull_request_id,c.author_id FROM commit_pull_request cp JOIN git_commit c ON c.id=cp.commit_id
        UNION ALL SELECT cp.pull_request_id,c.committer_id FROM commit_pull_request cp JOIN git_commit c ON c.id=cp.commit_id
        UNION ALL SELECT cp.pull_request_id,c.user_id FROM commit_pull_request cp JOIN commit_contributor c ON c.commit_id=cp.commit_id
        """;

    @Override
    public PersonSourceScope scope() {
        return PersonSourceScope.SCM_SOURCES;
    }

    @Override
    public Set<String> itemKinds() {
        return Set.of(
                ScmItemEvents.ISSUE.value(),
                ScmItemEvents.PULL_REQUEST.value(),
                ScmItemEvents.PULL_REQUEST_REVIEW.value(),
                ScmItemEvents.REPOSITORY.value(),
                ScmItemEvents.FILE.value());
    }

    @Override
    public List<Long> sourceIds(PersonScope person) {
        return jdbc.query(
                "SELECT DISTINCT source_id FROM (" + ROLES + ") roles WHERE user_id=ANY(?) ORDER BY source_id",
                (rs, row) -> rs.getLong(1),
                new SqlArrayValue("bigint", person.userIds().toArray()));
    }

    @Override
    public List<PersonIdentity> identitiesForSource(long workspaceId, String itemKind, long sourceId) {
        // Tree captures omit Git authorship; person attribution stays with the source work.
        if (itemKind.equals(ScmItemEvents.REPOSITORY.value()) || itemKind.equals(ScmItemEvents.FILE.value()))
            return List.of();
        if (!itemKinds().contains(itemKind)) throw new IllegalArgumentException("Unsupported SCM item kind");
        if (itemKind.equals(ScmItemEvents.PULL_REQUEST_REVIEW.value())) {
            var parents = jdbc.query(
                    "SELECT parent.source_id FROM item child JOIN item parent ON parent.workspace_id=child.workspace_id AND parent.id=child.parent_id JOIN pull_request_review review ON review.id=child.source_id AND review.pull_request_id=parent.source_id WHERE child.workspace_id=? AND child.kind=? AND child.source_id=? AND parent.kind=?",
                    (rs, row) -> rs.getLong("source_id"),
                    workspaceId,
                    ScmItemEvents.PULL_REQUEST_REVIEW.value(),
                    sourceId,
                    ScmItemEvents.PULL_REQUEST.value());
            return parents.isEmpty()
                    ? List.of()
                    : identitiesForSource(workspaceId, ScmItemEvents.PULL_REQUEST.value(), parents.getFirst());
        }
        return Objects.requireNonNull(
                identitiesForSources(workspaceId, itemKind, List.of(sourceId)).get(sourceId));
    }

    /**
     * {@link #identitiesForSource} of many issues or pull requests: their repositories and each repository's workspace
     * connection are read once, and the people of every source in one query. A source's people count only while its
     * repository is connected to the workspace or the workspace holds an item of it. A review's attribution is its pull
     * request's, read one review at a time as above.
     */
    @Override
    public Map<Long, List<PersonIdentity>> identitiesForSources(
            long workspaceId, String itemKind, Collection<Long> sourceIds) {
        if (!itemKinds().contains(itemKind)) throw new IllegalArgumentException("Unsupported SCM item kind");
        if (!itemKind.equals(ScmItemEvents.ISSUE.value()) && !itemKind.equals(ScmItemEvents.PULL_REQUEST.value()))
            return PersonSourceIdentityContributor.super.identitiesForSources(workspaceId, itemKind, sourceIds);
        Map<Long, List<PersonIdentity>> identities = new LinkedHashMap<>();
        sourceIds.forEach(id -> identities.put(id, new ArrayList<>()));
        if (identities.isEmpty()) return identities;
        var ids = new SqlArrayValue("bigint", identities.keySet().toArray());
        Map<Long, RepositorySource> repositoryOf = new HashMap<>();
        jdbc.query(
                """
                SELECT i.id,r.native_id,p.type,p.server_url FROM issue i
                JOIN repository r ON r.id=i.repository_id JOIN identity_provider p ON p.id=r.provider_id
                WHERE i.id=ANY(?)
                """,
                rs -> {
                    repositoryOf.put(
                            rs.getLong(1),
                            new RepositorySource(
                                    rs.getLong(2),
                                    Objects.requireNonNull(rs.getString(3)),
                                    ScmOrigin.of(rs.getString(4))));
                },
                ids);
        Map<RepositorySource, Boolean> connectedRepository = new HashMap<>();
        var connected = repositoryOf.entrySet().stream()
                .filter(entry ->
                        connectedRepository.computeIfAbsent(entry.getValue(), source -> connected(workspaceId, source)))
                .map(Map.Entry::getKey)
                .toArray();
        jdbc.query(
                "SELECT DISTINCT i.id,u.provider_id,u.native_id::text FROM (" + ROLES + """
                    ) roles JOIN issue i ON i.id=roles.source_id
                    JOIN repository r ON r.id=i.repository_id
                    JOIN identity_provider p ON p.id=r.provider_id
                    JOIN "user" u ON u.id=roles.user_id
                    WHERE i.id=ANY(?) AND (
                        i.id=ANY(?)
                        OR EXISTS (SELECT 1 FROM item a WHERE a.workspace_id=?
                            AND a.kind=? AND a.source_id=i.id)) AND i.issue_type=?
                    """,
                rs -> {
                    Objects.requireNonNull(identities.get(rs.getLong(1)))
                            .add(new PersonIdentity(rs.getLong(2), Objects.requireNonNull(rs.getString(3)), null));
                },
                ids,
                new SqlArrayValue("bigint", connected),
                workspaceId,
                itemKind,
                itemKind.equals(ScmItemEvents.PULL_REQUEST.value()) ? "PULL_REQUEST" : "ISSUE");
        identities.replaceAll((id, people) -> List.copyOf(people));
        return identities;
    }

    private boolean connected(long workspaceId, RepositorySource source) {
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

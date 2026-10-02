package de.tum.cit.aet.hephaestus.integration.outline;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance person scope and explicitly workspace-bound Outline attribution")
public class OutlinePersonSourceIdentityContributor implements PersonSourceIdentityContributor {
    private final JdbcTemplate jdbc;
    private static final String SOURCES = """
                FROM outline_document d JOIN connection c ON c.id=d.connection_id AND c.workspace_id=d.workspace_id
                JOIN identity_provider p ON p.type='OUTLINE' AND p.server_url=c.config->>'serverUrl'
                CROSS JOIN LATERAL (
                    SELECT d.created_by_subject AS subject UNION SELECT d.updated_by_subject
                    UNION SELECT jsonb_array_elements_text(COALESCE(d.collaborator_subjects,'[]'::jsonb))
                ) authors
                """;

    @Override
    public Set<String> artifactKinds() {
        return Set.of("docs.document");
    }

    @Override
    public List<Long> sourceIds(PersonScope person) {
        Set<Long> ids = new TreeSet<>();
        for (var identity : person.identities())
            ids.addAll(jdbc.query(
                    "SELECT DISTINCT d.id " + SOURCES + " WHERE p.id=? AND authors.subject=?",
                    (rs, row) -> rs.getLong(1),
                    identity.providerId(),
                    identity.subject()));
        return List.copyOf(ids);
    }

    @Override
    public List<PersonIdentity> identitiesForSource(long workspaceId, String artifactKind, long artifactId) {
        return jdbc.query(
                "SELECT DISTINCT p.id,authors.subject " + SOURCES
                        + " WHERE d.workspace_id=? AND d.id=? AND authors.subject IS NOT NULL AND authors.subject<>''",
                (rs, row) -> new PersonIdentity(rs.getLong(1), Objects.requireNonNull(rs.getString(2)), null),
                workspaceId,
                artifactId);
    }
}

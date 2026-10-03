package de.tum.cit.aet.hephaestus.integration.outline.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A shared document is not owned by each of its collaborators. */
final class OutlineCollaboratorPersonDataContributor implements PersonDataContributor {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    OutlineCollaboratorPersonDataContributor(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String store() {
        return "outline_document_collaborator";
    }

    @Override
    public int getOrder() {
        return 280;
    }

    @Override
    public PersonDataSelection select(PersonScope person) {
        return new PersonDataSelection(jdbc.query(
                """
            SELECT t.id, jsonb_agg(DISTINCT i.subject ORDER BY i.subject)::text
            FROM outline_document t
            JOIN connection c ON c.id=t.connection_id
            JOIN identity_provider p ON p.type='OUTLINE' AND p.server_url=c.config->>'serverUrl'
            JOIN jsonb_to_recordset(CAST(:identities AS jsonb)) AS i("providerId" bigint,subject text,"teamId" text)
              ON i."providerId"=p.id AND jsonb_exists(COALESCE(t.collaborator_subjects,'[]'::jsonb),i.subject)
            GROUP BY t.id ORDER BY t.id
            """,
                JdbcPersonDataStore.parameters(person, mapper),
                (rs, row) -> new PersonDataSelection.RowKey(Map.of(
                        "id", Long.toString(rs.getLong(1)), "subjects", Objects.requireNonNull(rs.getString(2))))));
    }

    private Map<String, Object> parameters(PersonDataSelection.RowKey key) {
        return Map.of(
                "id",
                Long.valueOf(key.columns().get("id")),
                "subjects",
                key.columns().get("subjects"));
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection) {
        List<JsonNode> rows = new ArrayList<>();
        for (var key : selection.rows())
            rows.addAll(jdbc.query(
                    """
            SELECT jsonb_build_object('id',id,'workspaceId',workspace_id,'connectionId',connection_id,
              'documentId',document_id,'collaboratorSubjects',CAST(:subjects AS jsonb))::text
            FROM outline_document WHERE id=:id
            """, parameters(key), (rs, row) -> mapper.readTree(Objects.requireNonNull(rs.getString(1)))));
        return List.copyOf(rows);
    }

    @Override
    public long erase(PersonDataSelection selection) {
        long rows = 0;
        for (var key : selection.rows()) rows += jdbc.update("""
            UPDATE outline_document SET collaborator_subjects=(
              SELECT jsonb_agg(value) FROM jsonb_array_elements(collaborator_subjects)
              WHERE value NOT IN (SELECT value FROM jsonb_array_elements(CAST(:subjects AS jsonb))))
            WHERE id=:id
            """, parameters(key));
        return rows;
    }
}

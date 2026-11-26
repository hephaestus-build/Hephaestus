package de.tum.cit.aet.hephaestus.integration.outline.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlinePersonTeam;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({
    "outline_document",
    "outline_collection",
    "outline_document_editor",
    "outline_document_content",
    "outline_document_collaborator"
})
public class IntegrationOutlinePersonDataCatalog implements PersonDataCatalog {
    private static final String DOCUMENT_AUTHOR_SCOPE = """
        EXISTS (SELECT 1
          FROM jsonb_to_recordset(CAST(:identities AS jsonb)) AS i("providerId" bigint,subject text,"teamId" text)
          JOIN identity_provider p ON i."providerId"=p.id AND p.type='OUTLINE'
          JOIN connection c ON c.outline_origin=p.outline_origin AND c.kind='OUTLINE'
          WHERE c.id=t.connection_id AND c.workspace_id=t.workspace_id
            AND COALESCE(i."teamId",'')""" + OutlinePersonTeam.SQL_MATCH;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                documentStore(
                        "outline_document",
                        DOCUMENT_AUTHOR_SCOPE + " AND t.created_by_subject=i.subject)",
                        "id,workspace_id,connection_id,document_id,collection_id,collection_slug,parent_document_id,title,slug,body_markdown,content_hash,outline_updated_at,deleted_at,last_materialized_at,created_at,updated_at,created_by_subject,created_by_name,outline_created_at,body_evicted_at,archived_at,version",
                        "created_by_subject=NULL,created_by_name=NULL,title=NULL,slug=NULL,body_markdown=NULL,content_hash=NULL",
                        300),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "outline_collection",
                        "outline_collection",
                        "FALSE",
                        "id,workspace_id,connection_id,collection_id,name,url_id,color,icon,state,sync_status,documents_synced_at,last_sync_error,created_at,updated_at,documents_upstream,exports_skipped_for_budget,description,version",
                        "id",
                        "",
                        0),
                documentStore(
                        "outline_document_editor",
                        DOCUMENT_AUTHOR_SCOPE + " AND t.updated_by_subject=i.subject)",
                        "id,workspace_id,connection_id,document_id,updated_by_subject,updated_by_name,outline_updated_at",
                        "updated_by_subject=NULL,updated_by_name=NULL",
                        280),
                documentStore(
                        "outline_document_content",
                        "t.id = ANY(:documents)",
                        "id,workspace_id,connection_id,document_id,title,slug,body_markdown,content_hash,outline_updated_at,outline_created_at",
                        "title=NULL,slug=NULL,body_markdown=NULL,content_hash=NULL",
                        270),
                new OutlineCollaboratorPersonDataContributor(jdbc, mapper));
    }

    private JdbcPersonDataStore documentStore(String store, String predicate, String columns, String redact, int order) {
        return new JdbcPersonDataStore(jdbc, mapper, store, "outline_document", predicate, columns, "id", redact, order) {
            @Override
            public void prepareErasure(PersonDataSelection selection, PersonScope frozenScope) {
                requireRows(selection, select(frozenScope));
            }
        };
    }

    static void requireRows(PersonDataSelection frozen, PersonDataSelection current) {
        if (!Set.copyOf(frozen.rows()).containsAll(current.rows()))
            throw new IllegalStateException("The frozen erasure is missing Outline person data rows");
    }
}

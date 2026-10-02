package de.tum.cit.aet.hephaestus.integration.outline;

import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({
    "outline_document",
    "outline_document_event",
    "outline_collection",
    "outline_document_editor",
    "outline_document_content",
    "outline_document_collaborator"
})
public class IntegrationOutlinePersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "outline_document",
                        "outline_document",
                        "EXISTS (SELECT 1 FROM jsonb_to_recordset(CAST(:identities AS jsonb)) AS i(\"providerId\" bigint,subject text,\"teamId\" text) JOIN identity_provider p ON p.id=i.\"providerId\" JOIN connection c ON c.config->>'serverUrl'=p.server_url WHERE p.type='OUTLINE' AND c.id=t.connection_id AND i.subject=t.created_by_subject)",
                        "id,workspace_id,connection_id,document_id,collection_id,collection_slug,parent_document_id,title,slug,body_markdown,content_hash,outline_updated_at,deleted_at,last_materialized_at,created_at,updated_at,created_by_subject,created_by_name,outline_created_at,body_evicted_at,archived_at,version",
                        "id",
                        "created_by_subject=NULL,created_by_name=NULL,title=NULL,slug=NULL,body_markdown=NULL,content_hash=NULL",
                        300),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "outline_document_event",
                        "outline_document_event",
                        "EXISTS (SELECT 1 FROM jsonb_to_recordset(CAST(:identities AS jsonb)) AS i(\"providerId\" bigint,subject text,\"teamId\" text) JOIN identity_provider p ON p.id=i.\"providerId\" JOIN connection c ON c.config->>'serverUrl'=p.server_url WHERE p.type='OUTLINE' AND c.id=t.connection_id AND i.subject=t.actor_subject)",
                        "id,workspace_id,connection_id,document_id,event_name,actor_subject,occurred_at,created_at",
                        "id",
                        "",
                        290),
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
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "outline_document_editor",
                        "outline_document",
                        "EXISTS (SELECT 1 FROM jsonb_to_recordset(CAST(:identities AS jsonb)) AS i(\"providerId\" bigint,subject text,\"teamId\" text) JOIN identity_provider p ON p.id=i.\"providerId\" JOIN connection c ON c.config->>'serverUrl'=p.server_url WHERE p.type='OUTLINE' AND c.id=t.connection_id AND i.subject=t.updated_by_subject)",
                        "id,workspace_id,connection_id,document_id,updated_by_subject,updated_by_name,outline_updated_at",
                        "id",
                        "updated_by_subject=NULL,updated_by_name=NULL",
                        280),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "outline_document_content",
                        "outline_document",
                        "t.id = ANY(:documents)",
                        "id,workspace_id,connection_id,document_id,title,slug,body_markdown,content_hash,outline_updated_at,outline_created_at",
                        "id",
                        "title=NULL,slug=NULL,body_markdown=NULL,content_hash=NULL",
                        270),
                new OutlineCollaboratorPersonDataContributor(jdbc, mapper));
    }
}

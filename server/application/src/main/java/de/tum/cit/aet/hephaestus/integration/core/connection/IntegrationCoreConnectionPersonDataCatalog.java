package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
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
    "oauth_state_nonce",
    "connection_audit",
    "consumer_dead_letter",
    "sync_job",
    "item_event",
    "item_event_evidence",
    "item_event_requester"
})
public class IntegrationCoreConnectionPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "oauth_state_nonce",
                        "oauth_state_nonce",
                        "t.actor_account_id=:account",
                        "workspace_id,kind,issued_at,consumed_at,actor_account_id",
                        "nonce",
                        "",
                        100),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "connection_audit",
                        "connection_audit",
                        "t.actor_account_id = :account",
                        "id,connection_id,event_type,from_state,to_state,actor_kind,actor_ref,actor_account_id,correlation_id,detail,occurred_at",
                        "id",
                        "actor_account_id=NULL,actor_ref=NULL,detail=NULL",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "consumer_dead_letter",
                        "consumer_dead_letter",
                        "t.replayed_by = :account",
                        "id,workspace_id,stream_name,stream_sequence,error_class,received_at,failed_at,replayed_at,replayed_by",
                        "id,replayed_by",
                        "replayed_by=NULL",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "sync_job",
                        "sync_job",
                        "t.triggered_by_account_id = :account",
                        "id,workspace_id,connection_id,kind,type,trigger_source,status,cancel_requested,started_at,finished_at,created_at,heartbeat_at,items_processed,items_total,progress,error_summary,triggered_by_account_id",
                        "id",
                        "triggered_by_account_id=NULL,progress=NULL,error_summary=NULL",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "item_event",
                        "item_event",
                        "t.actor_user_id = ANY(:users)",
                        "id,workspace_id,item_id,name,item_version,occurred_at,found_by,state,reason,decided_at,last_attempted_at,retry_at,requested_by_id,actor_user_id",
                        "id",
                        "actor_user_id=NULL",
                        400),
                // Retained occasion evidence copies words and people beyond the actor. It is selected by every person
                // it copied, by mirror user or by the native identity it keeps, so a person whose mirror row is gone
                // is still found. The personal export lists the occasion, not the other people's words. Erasure
                // leaves only the statement that the record was erased and its fixed expiry, which names no person
                // and still bounds the copies made from it.
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "item_event_evidence",
                        "item_event",
                        "jsonb_typeof(t.evidence->'participants')='array' AND EXISTS (SELECT 1 FROM"
                                + " jsonb_array_elements(t.evidence->'participants') p"
                                + " WHERE CAST(p->>'userId' AS bigint) = ANY(:users)"
                                + " OR EXISTS (SELECT 1 FROM jsonb_to_recordset(CAST(:identities AS jsonb))"
                                + " AS i(\"providerId\" bigint,subject text,\"teamId\" text)"
                                + " WHERE CAST(p->'identity'->>'providerId' AS bigint)=i.\"providerId\""
                                + " AND p->'identity'->>'subject'=i.subject))",
                        "id,workspace_id,item_id,name,occurred_at,found_by,evidence_expires_at",
                        "id",
                        "evidence=jsonb_build_object('unretained','ERASED')",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "item_event_requester",
                        "item_event",
                        "t.requested_by_id = :account",
                        "id,workspace_id,item_id,name,state,occurred_at,requested_by_id",
                        "id",
                        "requested_by_id=NULL",
                        400));
    }
}

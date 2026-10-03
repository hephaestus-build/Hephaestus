package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobLifecycleService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonEvidenceErasure;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({
    "agent_job",
    "agent_job_evidence_copy",
    "llm_usage_event",
    "instance_llm_settings",
    "review_backfill_run",
    "review_sweep_schedule"
})
public class AgentPersonDataCatalog implements PersonDataCatalog {
    private static final String SUBJECT_JOBS = """
            SELECT j.id FROM agent_job j
            WHERE j.metadata->>'author_id' IN (SELECT unnest(CAST(:users AS bigint[]))::text)
            OR j.metadata->>'actor_user_id' IN (SELECT unnest(CAST(:users AS bigint[]))::text)
            OR j.metadata->>'about_user_id' IN (SELECT unnest(CAST(:users AS bigint[]))::text)
            OR j.id IN (SELECT agent_job_id FROM observation WHERE about_user_id = ANY(:users))
            OR j.id IN (SELECT agent_job_id FROM feedback
                WHERE about_user_id = ANY(:users) OR recipient_user_id = ANY(:users))
            """;
    private static final String REVIEWED_WORK = """
            (artifact_kind IN ('scm.issue','scm.pull_request') AND artifact_id = ANY(:artifacts))
            OR (artifact_kind='chat.conversation_thread' AND artifact_id = ANY(:conversations))
            OR (artifact_kind='docs.document' AND artifact_id = ANY(:documents))
            """;
    // Another developer's review whose inputs held the person's work or evidence.
    private static final String COPYING_JOBS =
            """
            SELECT j.id FROM agent_job j WHERE j.id = ANY(:derivedJobs)
            OR j.metadata->>'slack_thread_id' IN (SELECT unnest(CAST(:conversations AS bigint[]))::text)
            OR j.metadata->>'docs_document_id' IN (SELECT unnest(CAST(:documents AS bigint[]))::text)
            OR j.metadata->>'pull_request_id' IN (SELECT unnest(CAST(:artifacts AS bigint[]))::text)
            OR j.metadata->>'issue_id' IN (SELECT unnest(CAST(:artifacts AS bigint[]))::text)
            """ + " OR j.id IN (SELECT agent_job_id FROM observation WHERE " + REVIEWED_WORK + ")"
                    + " OR j.id IN (SELECT agent_job_id FROM feedback WHERE " + REVIEWED_WORK + ")";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final List<PersonEvidenceErasure> evidenceCopies;
    private final AgentJobLifecycleService lifecycle;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new AgentJobStore(
                        "agent_job",
                        "t.id IN (" + SUBJECT_JOBS + ")",
                        "metadata='{}'::jsonb,output=NULL,evidence_snapshot=NULL,container_logs=NULL,config_snapshot='{}'::jsonb,job_token=gen_random_uuid()::text,job_token_hash=NULL,error_message=NULL,delivery_comment_id=NULL,review_readiness=NULL",
                        -80),
                // The colleague keeps the delivery reference of their own review; nothing here is exported.
                new AgentJobStore(
                        "agent_job_evidence_copy",
                        "t.id IN (" + COPYING_JOBS + ") AND t.id NOT IN (" + SUBJECT_JOBS + ")",
                        "metadata='{}'::jsonb,output=NULL,evidence_snapshot=NULL,container_logs=NULL,config_snapshot='{}'::jsonb,job_token=gen_random_uuid()::text,job_token_hash=NULL,error_message=NULL,review_readiness=NULL",
                        -81),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "llm_usage_event",
                        "llm_usage_event",
                        "t.source_id IN (" + SUBJECT_JOBS
                                + ") OR t.source_id IN (SELECT id FROM chat_message WHERE thread_id IN (SELECT id FROM chat_thread WHERE user_id = ANY(:users)))",
                        "id,workspace_id,job_type,source_type,source_id,source_attempt,model,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,reasoning_tokens,total_calls,cost_usd,pricing_state,funding_source,applied_price_id,applied_workspace_model_id,applied_per_1m_input_usd,applied_per_1m_output_usd,applied_per_1m_cache_read_usd,applied_per_1m_cache_write_usd,occurred_at,usage_provenance",
                        "id",
                        "source_id=gen_random_uuid()",
                        -85),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "instance_llm_settings",
                        "instance_llm_settings",
                        "t.updated_by_account_id = :account",
                        "id,allowed_egress_hosts,allow_workspace_connections,updated_at,updated_by_account_id",
                        "id",
                        "updated_by_account_id=NULL",
                        400),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "review_backfill_run",
                        "review_backfill_run",
                        "t.requested_by_account_id = :account OR t.confirmed_by_account_id = :account",
                        "id,workspace_id,artifact_kind,from_at,to_at,status,pause_reason,estimated_artifacts,estimated_cost_usd,cursor_artifact_id,submitted_count,passed_count,requested_by_account_id,confirmed_by_account_id,created_at,started_at,finished_at,updated_at,version,failed_count,discovered_via,sweep_schedule_id",
                        "id",
                        "",
                        -75),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "review_sweep_schedule",
                        "review_sweep_schedule",
                        "t.created_by_account_id = :account",
                        "id,version,workspace_id,artifact_kind,cadence,lookback_days,enabled,next_run_at,last_run_at,created_by_account_id,created_at,updated_at",
                        "id",
                        "",
                        -70));
    }

    /** Exports and lists legacy provider writes only for the person's own reviews. */
    private final class AgentJobStore extends JdbcPersonDataStore {
        private final boolean subject;

        AgentJobStore(String store, String predicate, String redaction, int order) {
            super(
                    jdbc,
                    mapper,
                    store,
                    "agent_job",
                    predicate,
                    "id,workspace_id,purpose,job_type,status,evidence_snapshot,created_at,completed_at,delivery_status,delivery_comment_id",
                    "id",
                    redaction,
                    order);
            this.subject = store.equals("agent_job");
        }

        @Override
        public PersonDataSelection select(PersonScope scope) {
            var selected = super.select(scope);
            return subject ? selected.withExternalDeliveryFacts(legacyProviderWrites(selected)) : selected;
        }

        @Override
        public List<JsonNode> export(PersonDataSelection selection) {
            return subject ? super.export(selection) : List.of();
        }

        private List<JsonNode> legacyProviderWrites(PersonDataSelection selection) {
            return querySelected("""
                    jsonb_build_object('id',t.id,'workspaceId',t.workspace_id,
                      'commentRef',t.delivery_comment_id,'state',t.delivery_status,
                      'locator',COALESCE(NULLIF(t.metadata->>'pr_url',''),
                        NULLIF(t.metadata->>'issue_url','')))::text
                    """, "agent_job t", selection).stream()
                    .filter(row -> row.path("commentRef").isString()
                            && !row.path("commentRef").asString().isBlank())
                    .toList();
        }

        @Override
        public List<ExternalDelivery> externalDeliveries(PersonDataSelection selection) {
            if (!subject) return List.of();
            return legacyProviderWrites(selection).stream()
                    .map(row -> {
                        var locator = row.path("locator");
                        if (!locator.isString() || locator.asString().isBlank())
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Legacy provider feedback job "
                                            + row.path("id").asString()
                                            + " has no exact locator for the reviewed work");
                        return new ExternalDelivery(row.path("workspaceId").asLong(), locator.asString());
                    })
                    .distinct()
                    .toList();
        }

        private List<JsonNode> jobs(PersonDataSelection selection) {
            return querySelected(
                    "jsonb_build_object('id',t.id,'workspaceId',t.workspace_id,'status',t.status,"
                            + "'evidence',t.evidence_snapshot IS NOT NULL)::text",
                    "agent_job t",
                    selection);
        }

        @Override
        public void prepareErasure(PersonDataSelection selection) {
            for (var row : jobs(selection)) {
                if (Set.of("QUEUED", "RUNNING").contains(row.path("status").asString()))
                    lifecycle.cancel(
                            row.path("workspaceId").asLong(),
                            UUID.fromString(row.path("id").asString()));
            }
            eraseEvidence(selection);
        }

        private void eraseEvidence(PersonDataSelection selection) {
            if (selection.rows().isEmpty()) return;
            Set<UUID> ids = selection.rows().stream()
                    .map(row -> UUID.fromString(row.columns().get("id")))
                    .collect(Collectors.toUnmodifiableSet());
            if (evidenceCopies.isEmpty()
                    && jobs(selection).stream()
                            .anyMatch(row -> row.path("evidence").asBoolean()))
                throw new IllegalStateException("Evidence-copy erasure acknowledgement is unavailable");
            evidenceCopies.forEach(copy -> copy.eraseJobEvidence(ids));
        }

        @Override
        public long erase(PersonDataSelection selection) {
            if (selection.rows().isEmpty()) return 0;
            eraseEvidence(selection);
            return super.erase(selection);
        }
    }
}

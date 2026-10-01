package de.tum.cit.aet.hephaestus.agent;

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
    "agent_job",
    "llm_usage_event",
    "instance_llm_settings",
    "review_backfill_run",
    "review_sweep_schedule"
})
public class AgentPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final List<PersonEvidenceErasure> evidenceCopies;
    private final de.tum.cit.aet.hephaestus.agent.job.AgentJobLifecycleService lifecycle;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "agent_job",
                        "agent_job",
                        "t.id IN (SELECT j.id FROM agent_job j WHERE j.metadata->>'slack_thread_id' IN (SELECT id::text FROM slack_thread WHERE id = ANY(:conversations)) OR j.metadata->>'docs_document_id' IN (SELECT id::text FROM outline_document WHERE id = ANY(:documents)) OR j.metadata->>'author_id' IN (SELECT id::text FROM \"user\" WHERE id = ANY(:users)) OR j.metadata->>'actor_user_id' IN (SELECT id::text FROM \"user\" WHERE id = ANY(:users)) OR j.metadata->>'about_user_id' IN (SELECT id::text FROM \"user\" WHERE id = ANY(:users)) OR j.metadata->>'pull_request_id' IN (SELECT unnest(CAST(:artifacts AS bigint[]))::text) OR j.metadata->>'issue_id' IN (SELECT unnest(CAST(:artifacts AS bigint[]))::text) OR j.id IN (SELECT agent_job_id FROM observation WHERE (about_user_id = ANY(:users) OR (artifact_kind IN ('scm.issue','scm.pull_request') AND artifact_id = ANY(:artifacts)) OR (artifact_kind='chat.conversation_thread' AND artifact_id = ANY(:conversations)) OR (artifact_kind='docs.document' AND artifact_id = ANY(:documents)))) OR j.id IN (SELECT agent_job_id FROM feedback WHERE (about_user_id = ANY(:users) OR (artifact_kind IN ('scm.issue','scm.pull_request') AND artifact_id = ANY(:artifacts)) OR (artifact_kind='chat.conversation_thread' AND artifact_id = ANY(:conversations)) OR (artifact_kind='docs.document' AND artifact_id = ANY(:documents))) OR recipient_user_id = ANY(:users)))",
                        "id,workspace_id,purpose,job_type,status,evidence_snapshot,created_at,completed_at,delivery_status,delivery_comment_id",
                        "id",
                        "metadata='{}'::jsonb,output=NULL,evidence_snapshot=NULL,container_logs=NULL,config_snapshot='{}'::jsonb,job_token=gen_random_uuid()::text,job_token_hash=NULL,error_message=NULL,delivery_comment_id=NULL,review_readiness=NULL",
                        -80) {
                    @Override
                    public PersonDataSelection select(PersonScope person) {
                        java.util.Set<PersonDataSelection.RowKey> rows = new java.util.LinkedHashSet<>(
                                super.select(person).rows());
                        evidenceCopies.forEach(copy -> copy.jobsContaining(person)
                                .forEach(id -> rows.add(
                                        new PersonDataSelection.RowKey(java.util.Map.of("id", id.toString())))));
                        return new PersonDataSelection(rows.stream()
                                .sorted(java.util.Comparator.comparing(
                                        row -> row.columns().get("id")))
                                .toList());
                    }

                    @Override
                    public void prepareErasure(PersonDataSelection selection) {
                        for (var row : export(selection)) {
                            if (java.util.Set.of("QUEUED", "RUNNING")
                                    .contains(row.path("status").asString()))
                                lifecycle.cancel(
                                        row.path("workspace_id").asLong(),
                                        java.util.UUID.fromString(row.path("id").asString()));
                        }
                        eraseEvidence(selection);
                    }

                    private void eraseEvidence(PersonDataSelection selection) {
                        if (selection.rows().isEmpty()) return;
                        java.util.Set<java.util.UUID> ids = selection.rows().stream()
                                .map(row ->
                                        java.util.UUID.fromString(row.columns().get("id")))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
                        if (evidenceCopies.isEmpty()
                                && export(selection).stream()
                                        .anyMatch(row ->
                                                !row.path("evidence_snapshot").isNull()))
                            throw new IllegalStateException("Evidence-copy erasure acknowledgement is unavailable");
                        evidenceCopies.forEach(copy -> copy.eraseJobEvidence(ids));
                    }

                    @Override
                    public long erase(PersonDataSelection selection) {
                        if (selection.rows().isEmpty()) return 0;
                        eraseEvidence(selection);
                        return super.erase(selection);
                    }
                },
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "llm_usage_event",
                        "llm_usage_event",
                        "t.source_id IN (SELECT id FROM (SELECT j.id FROM agent_job j WHERE j.metadata->>'slack_thread_id' IN (SELECT id::text FROM slack_thread WHERE id = ANY(:conversations)) OR j.metadata->>'docs_document_id' IN (SELECT id::text FROM outline_document WHERE id = ANY(:documents)) OR j.metadata->>'author_id' IN (SELECT id::text FROM \"user\" WHERE id = ANY(:users)) OR j.metadata->>'actor_user_id' IN (SELECT id::text FROM \"user\" WHERE id = ANY(:users)) OR j.metadata->>'about_user_id' IN (SELECT id::text FROM \"user\" WHERE id = ANY(:users)) OR j.metadata->>'pull_request_id' IN (SELECT unnest(CAST(:artifacts AS bigint[]))::text) OR j.metadata->>'issue_id' IN (SELECT unnest(CAST(:artifacts AS bigint[]))::text) OR j.id IN (SELECT agent_job_id FROM observation WHERE (about_user_id = ANY(:users) OR (artifact_kind IN ('scm.issue','scm.pull_request') AND artifact_id = ANY(:artifacts)) OR (artifact_kind='chat.conversation_thread' AND artifact_id = ANY(:conversations)) OR (artifact_kind='docs.document' AND artifact_id = ANY(:documents)))) OR j.id IN (SELECT agent_job_id FROM feedback WHERE (about_user_id = ANY(:users) OR (artifact_kind IN ('scm.issue','scm.pull_request') AND artifact_id = ANY(:artifacts)) OR (artifact_kind='chat.conversation_thread' AND artifact_id = ANY(:conversations)) OR (artifact_kind='docs.document' AND artifact_id = ANY(:documents))) OR recipient_user_id = ANY(:users))) jobs) OR t.source_id IN (SELECT id FROM chat_message WHERE thread_id IN (SELECT id FROM chat_thread WHERE user_id = ANY(:users)))",
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
}

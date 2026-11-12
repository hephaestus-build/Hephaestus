package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonEvidenceErasure;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({"review_job", "review_composition_basis", "llm_usage_event", "instance_llm_settings"})
public class AgentPersonDataCatalog implements PersonDataCatalog {
    private static final String SUBJECT_JOBS = """
        SELECT j.id FROM review_job j JOIN review r ON (r.id,r.workspace_id)=(j.review_id,j.workspace_id)
        WHERE r.requested_by_id=:account
          OR EXISTS(SELECT 1 FROM result x WHERE (x.review_id,x.workspace_id)=(r.id,r.workspace_id)
            AND x.developer_id=:account)
          OR EXISTS(SELECT 1 FROM feedback f WHERE (f.review_id,f.workspace_id)=(r.id,r.workspace_id)
            AND f.developer_id=:account)
        """;
    private static final String COPYING_JOBS = """
        SELECT j.id FROM review_job j JOIN review r ON (r.id,r.workspace_id)=(j.review_id,j.workspace_id)
        WHERE j.id=ANY(:derivedJobs)
          OR EXISTS(SELECT 1 FROM item i WHERE i.workspace_id=j.workspace_id
            AND (i.id=r.item_id OR EXISTS(SELECT 1 FROM item reviewed
              WHERE reviewed.workspace_id=r.workspace_id AND reviewed.id=r.item_id AND reviewed.parent_id=i.id)
              OR EXISTS(SELECT 1 FROM review_item a
              WHERE (a.review_id,a.workspace_id)=(r.id,r.workspace_id) AND a.item_id=i.id))
            AND ((i.kind IN ('scm.issue','scm.pull_request') AND i.source_id=ANY(:items))
              OR (i.kind='chat.thread' AND i.source_id=ANY(:conversations))
              OR (i.kind='docs.document' AND i.source_id=ANY(:documents))))
        """;
    private static final String SELECTED_JOBS = SUBJECT_JOBS + " UNION " + COPYING_JOBS;

    /** The ledger source kinds of a review job's calls: its own model and each model kind of its practice scripts. */
    private static final Object[] REVIEW_SOURCE_TYPES = Arrays.stream(UsageSourceType.values())
            .filter(type -> type != UsageSourceType.MENTOR_TURN)
            .map(Enum::name)
            .distinct()
            .toArray();

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final List<PersonEvidenceErasure> evidenceCopies;
    private final ReviewJobLifecycleService lifecycle;
    private final AgentRuns agentRuns;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "review_composition_basis",
                        "review_composition_basis",
                        "t.job_id IN (" + SELECTED_JOBS + ")",
                        "job_id,workspace_id,admission_attempt,admission_digest,basis_digest,basis_version,copy_id,expires_at",
                        "job_id,workspace_id",
                        "",
                        -82),
                new AgentJobStore(),
                new LedgerStore(
                        jdbc,
                        mapper,
                        "llm_usage_event",
                        "llm_usage_event",
                        "t.source_id IN (" + SELECTED_JOBS
                                + ") OR t.review_id IN (SELECT review_id FROM review_job WHERE id IN ("
                                + SELECTED_JOBS
                                + ")) OR (t.source_type='MENTOR_TURN' AND t.source_id IN (SELECT mt.id FROM"
                                + " mentor_turn mt JOIN mentor_conversation mc ON mc.id=mt.conversation_id WHERE"
                                + " mc.account_id=:account)) OR (t.source_type='PRODUCT_FEEDBACK_DRAFT'"
                                + " AND t.workspace_id IS NULL AND t.source_id IN"
                                + " (SELECT p.id FROM product_feedback p WHERE p.account_id=:account))",
                        "id,workspace_id,job_type,source_type,source_id,source_attempt,review_id,run_id,lease_epoch,action_id,settle_after,call_state,model_kind,practice_id,model,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,reasoning_tokens,search_units,total_calls,unreported_calls,cost_usd,pricing_state,funding_source,applied_price_id,applied_workspace_model_id,applied_per_1m_input_usd,applied_per_1m_output_usd,applied_per_1m_cache_read_usd,applied_per_1m_cache_write_usd,started_at,occurred_at,usage_provenance",
                        "source_id,workspace_id",
                        "review_id=NULL",
                        -85),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "instance_llm_settings",
                        "instance_llm_settings",
                        "t.updated_by_account_id=:account",
                        "id,allowed_egress_hosts,allow_workspace_connections,updated_at,updated_by_account_id",
                        "id",
                        "updated_by_account_id=NULL",
                        400));
    }

    private record JobRow(UUID id, long workspaceId, ReviewJobStatus status, boolean evidence) {}

    /** One job exactly as the frozen selection names it: its id together with its own workspace. */
    private record JobKey(UUID id, long workspaceId) {}

    private final class AgentJobStore extends JdbcPersonDataStore {
        AgentJobStore() {
            super(
                    jdbc,
                    mapper,
                    "review_job",
                    "review_job",
                    "t.id IN (" + SELECTED_JOBS + ")",
                    "id,workspace_id,review_id,status,submitted_at,started_at,completed_at,processing_status",
                    "id,workspace_id",
                    "",
                    -80);
        }

        /** Deletes the selected jobs, then their runs: a run without its job has nothing left to drive. */
        @Override
        public long erase(PersonDataSelection selection, PersonScope frozenScope) {
            Map<Long, List<UUID>> runs = new HashMap<>();
            for (var key : keys(selection))
                jdbc.query(
                                "SELECT run_id FROM review_job WHERE id=:id AND workspace_id=:workspace",
                                Map.of("id", key.id(), "workspace", key.workspaceId()),
                                (row, number) -> Objects.requireNonNull(row.getObject("run_id", UUID.class)))
                        .forEach(run -> runs.computeIfAbsent(key.workspaceId(), workspace -> new ArrayList<>())
                                .add(run));
            long erased = super.erase(selection, frozenScope);
            runs.forEach(agentRuns::forget);
            return erased;
        }

        private List<JobRow> jobs(PersonDataSelection selection) {
            if (selection.rows().isEmpty()) return List.of();
            var workspaces = selection.rows().stream()
                    .map(row ->
                            Long.valueOf(Objects.requireNonNull(row.columns().get("workspace_id"))))
                    .distinct()
                    .toArray();
            var ids = selection.rows().stream()
                    .map(row -> UUID.fromString(row.columns().get("id")))
                    .toArray();
            return jdbc.query(
                    "SELECT id,workspace_id,status,capture IS NOT NULL AS evidence FROM review_job WHERE id=ANY(:ids)"
                            + " AND workspace_id=ANY(:workspaces)",
                    Map.of(
                            "ids",
                            new SqlArrayValue("uuid", ids),
                            "workspaces",
                            new SqlArrayValue("bigint", workspaces)),
                    (row, number) -> new JobRow(
                            Objects.requireNonNull(row.getObject("id", UUID.class)),
                            row.getLong("workspace_id"),
                            ReviewJobStatus.valueOf(Objects.requireNonNull(row.getString("status"))),
                            row.getBoolean("evidence")));
        }

        private Set<JobKey> keys(PersonDataSelection selection) {
            return selection.rows().stream()
                    .map(row -> new JobKey(
                            UUID.fromString(Objects.requireNonNull(row.columns().get("id"))),
                            Long.parseLong(Objects.requireNonNull(row.columns().get("workspace_id")))))
                    .collect(Collectors.toUnmodifiableSet());
        }

        /**
         * Cancels the selected jobs that still run, then detaches every ledger row of those jobs from them. Each step
         * uses the job id together with its own workspace, exactly as the frozen selection names it.
         *
         * <p>The cancellation ends the job's run, which settles its open calls, so no call of the job opens after it.
         * The detach gives the job's rows one new source id together and keeps every row with its tokens, cost and date.
         * A call that returns later measures its detached row and never writes the job back. Repeating the preparation is
         * safe: a cancelled job is not cancelled again, and a detached row no longer names the job.
         */
        @Override
        public void prepareErasure(PersonDataSelection selection) {
            Set<JobKey> frozen = keys(selection);
            for (var row : jobs(selection)) {
                if (!frozen.contains(new JobKey(row.id(), row.workspaceId()))) continue;
                if (row.status() == ReviewJobStatus.QUEUED || row.status() == ReviewJobStatus.RUNNING)
                    lifecycle.cancel(row.workspaceId(), row.id());
            }
            for (var key : frozen) detachLedger(key);
            eraseEvidence(selection);
        }

        private void detachLedger(JobKey key) {
            jdbc.update(
                    "UPDATE llm_usage_event SET source_id=:detached, review_id=NULL"
                            + " WHERE workspace_id=:workspace AND source_id=:job AND source_type=ANY(:types)",
                    Map.of(
                            "detached",
                            UUID.randomUUID(),
                            "workspace",
                            key.workspaceId(),
                            "job",
                            key.id(),
                            "types",
                            new SqlArrayValue("varchar", REVIEW_SOURCE_TYPES)));
        }

        private void eraseEvidence(PersonDataSelection selection) {
            if (selection.rows().isEmpty()) return;
            Set<UUID> ids = selection.rows().stream()
                    .map(row -> UUID.fromString(row.columns().get("id")))
                    .collect(Collectors.toUnmodifiableSet());
            if (evidenceCopies.isEmpty() && jobs(selection).stream().anyMatch(JobRow::evidence))
                throw new IllegalStateException("Evidence-copy erasure acknowledgement is unavailable");
            evidenceCopies.forEach(copy -> copy.eraseJobEvidence(ids));
        }
    }

    /**
     * The ledger rows of the person's jobs and turns, selected by their source: a job or a turn, each with its own
     * workspace. The person's turns are selected also before they have a row, because a call of a turn that runs may
     * open after the preview. The erasure detaches every row of each selected source from it: all of them take one new
     * source id together and lose their review, so the calls of one attempt still count as one run. Each row keeps its
     * tokens, cost and date.
     */
    private static final class LedgerStore extends JdbcPersonDataStore {
        private final NamedParameterJdbcTemplate jdbc;
        private final ObjectMapper mapper;
        private final String predicate;

        LedgerStore(
                NamedParameterJdbcTemplate jdbc,
                ObjectMapper mapper,
                String store,
                String table,
                String predicate,
                String columns,
                String keys,
                String redaction,
                int order) {
            super(jdbc, mapper, store, table, predicate, columns, keys, redaction, order);
            this.jdbc = jdbc;
            this.mapper = mapper;
            this.predicate = predicate;
        }

        @Override
        public PersonDataSelection select(PersonScope scope) {
            return new PersonDataSelection(jdbc.query(
                    "SELECT DISTINCT s.source_id::text AS source_id, s.workspace_id::text AS workspace_id FROM ("
                            + "SELECT t.source_id, t.workspace_id FROM llm_usage_event t WHERE " + predicate
                            + " UNION SELECT mt.id, mt.workspace_id FROM mentor_turn mt JOIN mentor_conversation mc"
                            + " ON mc.id = mt.conversation_id AND mc.workspace_id = mt.workspace_id"
                            + " WHERE mc.account_id = :account UNION SELECT p.id, NULL::bigint"
                            + " FROM product_feedback p WHERE p.account_id = :account) s ORDER BY 1, 2",
                    parameters(scope, mapper),
                    (rs, row) -> {
                        String source = Objects.requireNonNull(rs.getString("source_id"));
                        String workspace = rs.getString("workspace_id");
                        return new PersonDataSelection.RowKey(
                                workspace == null
                                        ? Map.of("source_id", source)
                                        : Map.of("source_id", source, "workspace_id", workspace));
                    }));
        }

        /** One entry for each selected source, with the rows of its calls, also when it has none yet. */
        @Override
        public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
            if (selection.rows().isEmpty()) return List.of();
            return jdbc.query(
                    """
                    SELECT jsonb_build_object('source_id', s.source_id, 'workspace_id', s.workspace_id, 'calls',
                        COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.started_at, t.id) FROM llm_usage_event t
                                  WHERE t.workspace_id IS NOT DISTINCT FROM s.workspace_id AND t.source_id = s.source_id), '[]'::jsonb))::text
                    FROM jsonb_to_recordset(CAST(:keys AS jsonb)) AS s(source_id uuid, workspace_id bigint)
                    ORDER BY s.source_id
                    """,
                    Map.of("keys", keys(selection)),
                    (rs, row) -> mapper.readTree(Objects.requireNonNull(rs.getString(1))));
        }

        private String keys(PersonDataSelection selection) {
            return mapper.writeValueAsString(selection.rows().stream()
                    .map(PersonDataSelection.RowKey::columns)
                    .toList());
        }

        /** The unit is the selected source, so the receipt counts what the preview counted. */
        @Override
        public long erase(PersonDataSelection selection, PersonScope frozenScope) {
            if (selection.rows().isEmpty()) return 0;
            jdbc.update("""
                    WITH detached AS (
                        SELECT s.workspace_id, s.source_id, gen_random_uuid() AS detached
                        FROM jsonb_to_recordset(CAST(:keys AS jsonb)) AS s(source_id uuid, workspace_id bigint))
                    UPDATE llm_usage_event e SET source_id = d.detached, review_id = NULL,
                        call_state = CASE WHEN e.source_type='PRODUCT_FEEDBACK_DRAFT' AND e.call_state='OPEN'
                                          THEN 'SETTLED' ELSE e.call_state END,
                        measurement_verification = CASE WHEN e.source_type='PRODUCT_FEEDBACK_DRAFT' AND e.call_state='OPEN'
                                                        THEN 'UNVERIFIABLE' ELSE e.measurement_verification END,
                        pricing_state = CASE WHEN e.source_type='PRODUCT_FEEDBACK_DRAFT' AND e.call_state='OPEN'
                                             THEN 'UNPRICED' ELSE e.pricing_state END,
                        cost_usd = CASE WHEN e.source_type='PRODUCT_FEEDBACK_DRAFT' AND e.call_state='OPEN'
                                        THEN NULL ELSE e.cost_usd END,
                        unreported_calls = CASE WHEN e.source_type='PRODUCT_FEEDBACK_DRAFT' AND e.call_state='OPEN'
                                                THEN 1 ELSE e.unreported_calls END
                    FROM detached d
                    WHERE e.workspace_id IS NOT DISTINCT FROM d.workspace_id AND e.source_id = d.source_id
                    """, Map.of("keys", keys(selection)));
            return selection.rows().size();
        }
    }
}

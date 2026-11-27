package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.LiveJdbcPersonDataStore;
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
@PersonDataStores({
    "review_job",
    "review_composition_basis",
    "work_place_handoff",
    "composer_transcript_part",
    "llm_usage_event",
    "instance_llm_settings"
})
public class AgentPersonDataCatalog implements PersonDataCatalog {
    private static final String SUBJECT_JOBS = """
        SELECT j.id FROM review_job j JOIN review r ON (r.id,r.workspace_id)=(j.review_id,j.workspace_id)
        WHERE r.requested_by_id=:account
          OR EXISTS(SELECT 1 FROM result x WHERE (x.review_id,x.workspace_id)=(r.id,r.workspace_id)
            AND x.person_account_id=:account)
          OR EXISTS(SELECT 1 FROM feedback f WHERE (f.review_id,f.workspace_id)=(r.id,r.workspace_id)
            AND f.person_account_id=:account)
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
            .filter(type -> type != UsageSourceType.CONVERSATION_TURN)
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
                // The work place's copy of a sealed review: the same jobs' public results and the work they cite.
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "work_place_handoff",
                        "work_place_handoff",
                        "t.job_id IN (" + SELECTED_JOBS + ")",
                        "id,workspace_id,review_id,job_id,item_id,producer,payload_digest,expires_at,created_at",
                        "id,workspace_id",
                        "",
                        -83),
                new ComposerTranscriptStore(),
                new AgentJobStore(),
                new LedgerStore(
                        jdbc,
                        mapper,
                        "llm_usage_event",
                        "llm_usage_event",
                        // A personal call never becomes a source key: only its payer's call-id path reaches it.
                        "t.source_type <> 'PERSONAL_CONVERSATION' AND (t.source_id IN (" + SELECTED_JOBS
                                + ") OR t.review_id IN (SELECT review_id FROM review_job WHERE id IN ("
                                + SELECTED_JOBS
                                + ")) OR (t.source_type='CONVERSATION_TURN' AND t.source_id IN (SELECT mt.id FROM"
                                + " conversation_turn mt JOIN conversation mc ON mc.id=mt.conversation_id WHERE"
                                + " mc.account_id=:account)) OR (t.source_type='PRODUCT_FEEDBACK_DRAFT'"
                                + " AND t.workspace_id IS NULL AND t.source_id IN"
                                + " (SELECT p.id FROM product_feedback p WHERE p.account_id=:account)))",
                        "id,workspace_id,job_type,source_type,source_id,source_attempt,review_id,run_id,lease_epoch,action_id,settle_after,call_state,model_kind,practice_id,payer_account_id,model,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,reasoning_tokens,search_units,total_calls,unreported_calls,cost_usd,pricing_state,funding_source,applied_price_id,applied_workspace_model_id,applied_per_1m_input_usd,applied_per_1m_output_usd,applied_per_1m_cache_read_usd,applied_per_1m_cache_write_usd,started_at,occurred_at,usage_provenance",
                        "source_id,workspace_id",
                        "review_id=NULL, payer_account_id=NULL",
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

    /**
     * The transcripts of the selected jobs' runs: the job's run, which after a handoff is the composition run that wrote
     * the answers, and the measuring run that handed it on. A served answer can name the developer and quote their
     * work, but only while its claim holds the run; the rest are digests. So export has no rows: what an answer drew on
     * is exported from its own store, and the answer is transient and derived. Erasure deletes the rows before the job
     * store forgets the runs, and also deletes the rows that a still-running claim adds after the preview.
     */
    private final class ComposerTranscriptStore extends LiveJdbcPersonDataStore {
        ComposerTranscriptStore() {
            super(
                    jdbc,
                    mapper,
                    "composer_transcript_part",
                    "composer_transcript_part",
                    "EXISTS(SELECT 1 FROM review_job j WHERE j.workspace_id=t.workspace_id"
                            + " AND t.run_id IN (j.run_id,j.handed_off_run_id) AND j.id IN (" + SELECTED_JOBS + "))",
                    "id",
                    "id,workspace_id",
                    "",
                    -81);
        }

        @Override
        public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
            return List.of();
        }
    }

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
        public void prepareErasure(PersonDataSelection selection, PersonScope frozenScope) {
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
     * The ledger rows of the person's jobs, turns, report drafts and personal conversations, selected by their source:
     * a job or a turn, each with its own workspace, or a draft or a personal attempt with none. The person's turns are
     * selected also before they have a row, because a call of a turn that runs may open after the preview. A personal
     * call opens only under the person-data write fence, so an erasure that holds it sees every one. The erasure
     * detaches every row of each selected source from it: all of them take one new source id together and lose their
     * review and payer, so the calls of one attempt still count as one run and the purse that paid keeps their cost.
     * Each row keeps its tokens, cost and date.
     */
    private static final class LedgerStore extends JdbcPersonDataStore {
        /** The key of a selected personal call: the row's id, because its source names the person's attempt. */
        private static final String PERSONAL_CALL = "personal_call_id";

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
            var sources = new PersonDataSelection(jdbc.query(
                    "SELECT DISTINCT s.source_id::text AS source_id, s.workspace_id::text AS workspace_id FROM ("
                            + "SELECT t.source_id, t.workspace_id FROM llm_usage_event t WHERE " + predicate
                            + " UNION SELECT mt.id, mt.workspace_id FROM conversation_turn mt JOIN conversation mc"
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
            Long account = scope.accountId();
            if (account == null) return sources;
            // A personal call is selected on its own, by its id: its source is the attempt, which only this row names.
            // The source-keyed export and erasure skip personal rows, so a report or turn that shares an attempt's
            // UUID never reaches another payer's call.
            List<PersonDataSelection.RowKey> rows = new ArrayList<>(sources.rows());
            rows.addAll(jdbc.query(
                    "SELECT t.id::text FROM llm_usage_event t WHERE t.source_type = 'PERSONAL_CONVERSATION'"
                            + " AND t.payer_account_id = :account ORDER BY t.id",
                    Map.of("account", account),
                    (rs, row) -> new PersonDataSelection.RowKey(
                            Map.of(PERSONAL_CALL, Objects.requireNonNull(rs.getString(1))))));
            return new PersonDataSelection(rows);
        }

        /**
         * One entry for each selected source, with the rows of its calls, also when it has none yet, and one entry for
         * each personal call with its month, model, cost and state only: no id links it to the attempt.
         */
        @Override
        public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
            List<JsonNode> exported = new ArrayList<>(exportSources(selection));
            List<UUID> calls = personalCalls(selection);
            if (!calls.isEmpty()) {
                exported.addAll(jdbc.query(
                        """
                        SELECT jsonb_build_object('month', to_char(t.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM'),
                            'model', t.model, 'cost_usd', t.cost_usd, 'cost_bound_usd', t.cost_bound_usd,
                            'call_state', t.call_state, 'pricing_state', t.pricing_state)::text
                        FROM llm_usage_event t
                        WHERE t.id = ANY(:calls) AND t.source_type = 'PERSONAL_CONVERSATION'
                        ORDER BY t.occurred_at, t.id
                        """,
                        Map.of("calls", new SqlArrayValue("uuid", calls.toArray())),
                        (rs, row) -> mapper.readTree(Objects.requireNonNull(rs.getString(1)))));
            }
            return exported;
        }

        private List<JsonNode> exportSources(PersonDataSelection selection) {
            if (sourceKeys(selection).isEmpty()) return List.of();
            return jdbc.query(
                    """
                    SELECT jsonb_build_object('source_id', s.source_id, 'workspace_id', s.workspace_id, 'calls',
                        COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.started_at, t.id) FROM llm_usage_event t
                                  WHERE t.workspace_id IS NOT DISTINCT FROM s.workspace_id AND t.source_id = s.source_id
                                    AND t.source_type <> 'PERSONAL_CONVERSATION'), '[]'::jsonb))::text
                    FROM jsonb_to_recordset(CAST(:keys AS jsonb)) AS s(source_id uuid, workspace_id bigint)
                    ORDER BY s.source_id
                    """,
                    Map.of("keys", keys(selection)),
                    (rs, row) -> mapper.readTree(Objects.requireNonNull(rs.getString(1))));
        }

        private List<PersonDataSelection.RowKey> sourceKeys(PersonDataSelection selection) {
            return selection.rows().stream()
                    .filter(row -> !row.columns().containsKey(PERSONAL_CALL))
                    .toList();
        }

        private static List<UUID> personalCalls(PersonDataSelection selection) {
            return selection.rows().stream()
                    .map(row -> row.columns().get(PERSONAL_CALL))
                    .filter(Objects::nonNull)
                    .map(UUID::fromString)
                    .toList();
        }

        private String keys(PersonDataSelection selection) {
            return mapper.writeValueAsString(sourceKeys(selection).stream()
                    .map(PersonDataSelection.RowKey::columns)
                    .toList());
        }

        /**
         * The unit is the selected source or personal call, so the receipt counts what the preview counted. A personal
         * call loses its payer and its source, and an open one settles at its bound, so the purse keeps counting it.
         */
        @Override
        public long erase(PersonDataSelection selection, PersonScope frozenScope) {
            if (selection.rows().isEmpty()) return 0;
            if (!sourceKeys(selection).isEmpty()) {
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
                            AND e.source_type <> 'PERSONAL_CONVERSATION'
                        """, Map.of("keys", keys(selection)));
            }
            List<UUID> calls = personalCalls(selection);
            if (!calls.isEmpty()) {
                jdbc.update("""
                        UPDATE llm_usage_event e SET source_id = NULL, payer_account_id = NULL,
                            call_state = CASE WHEN e.call_state='OPEN' THEN 'SETTLED' ELSE e.call_state END,
                            measurement_verification = CASE WHEN e.call_state='OPEN'
                                                            THEN 'UNVERIFIABLE' ELSE e.measurement_verification END,
                            pricing_state = CASE WHEN e.call_state='OPEN' THEN 'UNPRICED' ELSE e.pricing_state END,
                            cost_usd = CASE WHEN e.call_state='OPEN' THEN NULL ELSE e.cost_usd END,
                            unreported_calls = CASE WHEN e.call_state='OPEN' THEN 1 ELSE e.unreported_calls END
                        WHERE e.id = ANY(:calls) AND e.source_type = 'PERSONAL_CONVERSATION'
                        """, Map.of("calls", new SqlArrayValue("uuid", calls.toArray())));
            }
            return selection.rows().size();
        }
    }
}

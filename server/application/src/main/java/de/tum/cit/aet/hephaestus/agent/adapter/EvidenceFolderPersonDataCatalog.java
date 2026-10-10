package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobExecutor;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStateConflictException;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonEvidenceErasure;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Durable requests are acknowledged by the mounted store, never by the server's local filesystem. */
@Component
@PersonDataStores({"person_evidence_copy"})
@WorkspaceAgnostic("Exact-person receipts span workspaces; every job mutation also pins its workspace key")
public class EvidenceFolderPersonDataCatalog implements PersonEvidenceErasure, WorkspacePurgeContributor {
    private final FabricLayout layout;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper mapper;
    private final PersonDataCopyRecorder recorder;
    private final PersonDataCopyFence fence;
    private final ObjectProvider<AgentJobExecutor> executor;
    private final ObjectProvider<AgentJobLifecycleService> lifecycles;
    private static final ThreadLocal<ActiveCapture> ACTIVE = new ThreadLocal<>();
    /** Whether a receipt indexes the receipts of every job whose rows its capture copied. */
    private static final String DEPENDENCIES = "dependencies";

    private static final String COMPLETE = "COMPLETE";
    private static final String UNKNOWN = "UNKNOWN";
    private static final RowMapper<CopiedRow> COPIED_ROW_MAPPER = (rs, row) ->
            new CopiedRow(Objects.requireNonNull(rs.getObject(1, UUID.class)), rs.getLong(2), rs.getLong(3));
    private @Nullable UUID localStoreId;

    public EvidenceFolderPersonDataCatalog(
            FabricLayout layout,
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            ObjectMapper mapper,
            PersonDataCopyRecorder recorder,
            PersonDataCopyFence fence,
            ObjectProvider<AgentJobExecutor> executor,
            ObjectProvider<AgentJobLifecycleService> lifecycles) {
        this.layout = layout;
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.mapper = mapper;
        this.recorder = recorder;
        this.fence = fence;
        this.executor = executor;
        this.lifecycles = lifecycles;
    }

    public void beginCapture(AgentJob job) {
        if (ACTIVE.get() != null) throw new IllegalStateException("An evidence capture is already active");
        var admission = fence.capture();
        EvidenceFolderLease lease = null;
        PersonDataCopyRecorder.Capture provenance = null;
        try {
            UUID storeId = storeId();
            UUID copyId = UUID.randomUUID();
            long workspace = job.getWorkspace().getId();
            lease = EvidenceFolderLease.tryAcquire(layout.root(), workspace, job.getId())
                    .orElseThrow(() ->
                            new ReviewSourceNotReadyException("Another attempt still holds this evidence folder"));
            provenance = recorder.begin();
            ObjectNode receipt = mapper.createObjectNode();
            receipt.put("attempt", job.getRetryCount());
            receipt.putArray("identities");
            receipt.putArray("repositories");
            int changed = jdbc.update(
                    """
                INSERT INTO person_evidence_copy(id,workspace_id,job_id,store_id,state,payload)
                SELECT CAST(? AS uuid),workspace_id,id,CAST(? AS uuid),'CAPTURING',CAST(? AS jsonb) FROM agent_job
                WHERE id=? AND workspace_id=? AND status='RUNNING'
                """,
                    copyId.toString(),
                    storeId.toString(),
                    mapper.writeValueAsString(receipt),
                    job.getId(),
                    workspace);
            if (changed != 1) throw new IllegalStateException("This review attempt is no longer admitted");
            var capture = new ActiveCapture(job.getId(), workspace, copyId, receipt, provenance, lease, admission);
            ACTIVE.set(capture);
            provenance.onChange(() -> recordProgress(capture));
        } catch (RuntimeException | Error exception) {
            var failedLease = lease;
            var failedProvenance = provenance;
            try (admission;
                    failedLease;
                    failedProvenance) {
                throw exception;
            } finally {
                ACTIVE.remove();
            }
        }
    }

    /** Called only after the frozen folder exists; the returned lease belongs to PreparedJobInputs. */
    public AutoCloseable finishCapture(AgentJob job) {
        ActiveCapture capture = requireCapture(job);
        var admission = capture.admission();
        var provenance = capture.provenance();
        try {
            try (admission;
                    provenance) {
                capture.receipt()
                        .set(
                                "identities",
                                mapper.valueToTree(capture.provenance().identities()));
                capture.receipt()
                        .set(
                                "repositories",
                                mapper.valueToTree(capture.provenance().repositoryIds()));
                if (isSuppressed(
                        capture.provenance().identities(), capture.admission().jdbc())) {
                    // This capture still owns the folder lease; no runtime has received these inputs.
                    try {
                        deleteFolder(capture.workspaceId(), capture.jobId());
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                    int discarded = capture.admission()
                            .jdbc()
                            .update("""
                        UPDATE person_evidence_copy SET state='ERASED',payload='{}'::jsonb
                        WHERE id=? AND job_id=? AND workspace_id=? AND state='CAPTURING'
                        """, capture.copyId(), capture.jobId(), capture.workspaceId());
                    if (discarded != 1) throw new IllegalStateException("Evidence capture ownership changed");
                    throw new IllegalStateException("Copied evidence contains an erased native identity");
                }
                indexDependencies(capture);
                updateReceipt(capture);
                capture.lease().shareWithRemovals();
            }
            return capture.lease();
        } catch (RuntimeException | Error exception) {
            var failedLease = capture.lease();
            try (failedLease) {
                throw exception;
            }
        } finally {
            ACTIVE.remove();
        }
    }

    public void abortCapture(AgentJob job) {
        ActiveCapture capture = ACTIVE.get();
        if (capture == null) return;
        if (!capture.jobId().equals(job.getId())) throw new IllegalStateException("Wrong evidence capture owner");
        var admission = capture.admission();
        var lease = capture.lease();
        var provenance = capture.provenance();
        try (admission;
                lease;
                provenance) {
            capture.receipt()
                    .set("identities", mapper.valueToTree(capture.provenance().identities()));
            capture.receipt()
                    .set("repositories", mapper.valueToTree(capture.provenance().repositoryIds()));
            indexDependencies(capture);
            updateReceipt(capture);
        } finally {
            ACTIVE.remove();
        }
    }

    /**
     * Unions the receipts of the jobs whose rows this capture copied. Only a receipt that itself indexed every job it
     * copied stands for its dependencies; a legacy, missing, erased or unfinished one leaves this receipt UNKNOWN and
     * is never upgraded later. The identities found here index erasure only: capture refusal stays on direct copies.
     */
    private void indexDependencies(ActiveCapture capture) {
        var sources = capture.provenance().copiedJobIds().stream()
                .filter(job -> !job.equals(capture.jobId()))
                .toList();
        var dependencies = dependencies(capture.admission().jdbc(), capture.workspaceId(), sources);
        var identities = new LinkedHashSet<JsonNode>();
        capture.receipt().path("identities").forEach(identities::add);
        identities.addAll(dependencies.identities());
        var repositories = new LinkedHashSet<JsonNode>();
        capture.receipt().path("repositories").forEach(repositories::add);
        repositories.addAll(dependencies.repositories());
        capture.receipt().set("identities", mapper.valueToTree(identities));
        capture.receipt().set("repositories", mapper.valueToTree(repositories));
        capture.receipt().set("copiedJobs", mapper.valueToTree(sources));
        capture.receipt().put(DEPENDENCIES, dependencies.complete() ? COMPLETE : UNKNOWN);
    }

    private record Dependencies(boolean complete, List<JsonNode> identities, List<JsonNode> repositories) {}

    private Dependencies dependencies(JdbcOperations controls, long workspaceId, Collection<UUID> sources) {
        if (sources.isEmpty()) return new Dependencies(true, List.of(), List.of());
        var receipts = controls.query(
                """
            SELECT job_id,state,payload::text FROM person_evidence_copy
            WHERE workspace_id=? AND job_id=ANY(?)
            """,
                (rs, row) -> new SourceReceipt(
                        Objects.requireNonNull(rs.getObject(1, UUID.class)),
                        Objects.requireNonNull(rs.getString(2)),
                        mapper.readTree(Objects.requireNonNull(rs.getString(3)))),
                workspaceId,
                new SqlArrayValue("uuid", sources.toArray()));
        boolean complete = receipts.stream()
                        .map(SourceReceipt::jobId)
                        .collect(Collectors.toSet())
                        .containsAll(sources)
                && receipts.stream()
                        .allMatch(receipt -> receipt.state().equals("READY")
                                && COMPLETE.equals(
                                        receipt.payload().path(DEPENDENCIES).asString()));
        var identities = new ArrayList<JsonNode>();
        var repositories = new ArrayList<JsonNode>();
        for (var receipt : receipts) {
            receipt.payload().path("identities").forEach(identities::add);
            receipt.payload().path("repositories").forEach(repositories::add);
        }
        return new Dependencies(complete, identities, repositories);
    }

    private record SourceReceipt(UUID jobId, String state, JsonNode payload) {}

    private record CopiedRow(UUID sourceJob, long recipient, long about) {}

    /**
     * A read that the runtime receives after its capture was frozen. Copy admission is held from before the read
     * selects its rows until their people and source receipts are unioned into the attempt's READY receipt, so an
     * erasure either precedes the read or finds this job. A receipt that is no longer READY is never revived; the read
     * is answered as before and the attempt keeps no transcript.
     */
    public <T> T indexLateRead(
            UUID jobId,
            long workspaceId,
            int attempt,
            Supplier<T> read,
            Function<T, Set<UUID>> observationIds,
            Function<T, Set<UUID>> feedbackIds) {
        try (var admission = fence.capture()) {
            T result = read.get();
            var observations = observationIds.apply(result);
            var feedback = feedbackIds.apply(result);
            var controls = admission.jdbc();
            var rows = new ArrayList<CopiedRow>();
            if (!observations.isEmpty())
                rows.addAll(controls.query(
                        "SELECT agent_job_id,about_user_id,about_user_id FROM observation WHERE workspace_id=? AND id=ANY(?)",
                        COPIED_ROW_MAPPER,
                        workspaceId,
                        new SqlArrayValue("uuid", observations.toArray())));
            if (!feedback.isEmpty())
                rows.addAll(controls.query(
                        "SELECT agent_job_id,recipient_user_id,about_user_id FROM feedback WHERE workspace_id=? AND id=ANY(?)",
                        COPIED_ROW_MAPPER,
                        workspaceId,
                        new SqlArrayValue("uuid", feedback.toArray())));
            int found = rows.size();
            var sources = new HashSet<UUID>();
            var users = new HashSet<Long>();
            for (var row : rows) {
                sources.add(row.sourceJob());
                users.add(row.recipient());
                users.add(row.about());
            }
            sources.remove(jobId);
            var dependencies = dependencies(controls, workspaceId, sources);
            List<PersonCopyIdentity> direct;
            try (var frame = recorder.begin()) {
                users.forEach(recorder::recordUser);
                direct = frame.identities();
            }
            var identities = new ArrayList<JsonNode>(dependencies.identities());
            direct.forEach(identity -> identities.add(mapper.valueToTree(identity)));
            // A row the read returned that this lookup no longer finds has no provenance to vouch for it.
            boolean complete = dependencies.complete() && found == observations.size() + feedback.size();
            // One statement: concurrent unions serialize on the row, and a receipt only ever gains people.
            controls.update(
                    """
                UPDATE person_evidence_copy SET payload=jsonb_set(jsonb_set(jsonb_set(jsonb_set(payload,
                    '{identities}',(SELECT COALESCE(jsonb_agg(DISTINCT e),'[]'::jsonb) FROM jsonb_array_elements(
                        COALESCE(payload->'identities','[]'::jsonb) || CAST(? AS jsonb)) e)),
                    '{repositories}',(SELECT COALESCE(jsonb_agg(DISTINCT e),'[]'::jsonb) FROM jsonb_array_elements(
                        COALESCE(payload->'repositories','[]'::jsonb) || CAST(? AS jsonb)) e)),
                    '{copiedJobs}',(SELECT COALESCE(jsonb_agg(DISTINCT e),'[]'::jsonb) FROM jsonb_array_elements(
                        COALESCE(payload->'copiedJobs','[]'::jsonb) || CAST(? AS jsonb)) e)),
                    '{dependencies}',CASE WHEN ? AND payload->>'dependencies'='COMPLETE'
                        THEN '"COMPLETE"'::jsonb ELSE '"UNKNOWN"'::jsonb END)
                WHERE job_id=? AND workspace_id=? AND store_id=CAST(? AS uuid) AND state='READY'
                  AND payload->>'attempt'=?
                """,
                    mapper.writeValueAsString(identities),
                    mapper.writeValueAsString(dependencies.repositories()),
                    mapper.writeValueAsString(sources.stream().sorted().toList()),
                    complete,
                    jobId,
                    workspaceId,
                    storeId().toString(),
                    Integer.toString(attempt));
            return result;
        }
    }

    /** Whether this store may keep the attempt's debug transcript. */
    public enum TraceCustody {
        /** The attempt's READY receipt indexes every person its copied rows may name. */
        INDEXED,
        /** The receipt is READY, but a copied row depends on a receipt that cannot vouch for its own copies. */
        UNINDEXED_DEPENDENCY,
        /** This store holds no READY receipt for the attempt: it was erased, purged, or belongs elsewhere. */
        NOT_HELD
    }

    public TraceCustody traceCustody(UUID jobId, long workspaceId, int attempt) {
        if (!Files.exists(layout.root().resolve(".person-evidence-store-id"))) return TraceCustody.NOT_HELD;
        var dependencies = jdbc.queryForList(
                """
            SELECT COALESCE(payload->>'dependencies','') FROM person_evidence_copy
            WHERE job_id=? AND workspace_id=? AND store_id=CAST(? AS uuid) AND state='READY' AND payload->>'attempt'=?
            """, String.class, jobId, workspaceId, storeId().toString(), Integer.toString(attempt));
        if (dependencies.size() != 1) return TraceCustody.NOT_HELD;
        return COMPLETE.equals(dependencies.getFirst()) ? TraceCustody.INDEXED : TraceCustody.UNINDEXED_DEPENDENCY;
    }

    private ActiveCapture requireCapture(AgentJob job) {
        ActiveCapture capture = ACTIVE.get();
        if (capture == null || !capture.jobId().equals(job.getId()))
            throw new IllegalStateException("Evidence capture has no exact provenance owner");
        return capture;
    }

    private void recordProgress(ActiveCapture capture) {
        capture.receipt()
                .set("identities", mapper.valueToTree(capture.provenance().identities()));
        capture.receipt()
                .set("repositories", mapper.valueToTree(capture.provenance().repositoryIds()));
        int changed = capture.admission()
                .jdbc()
                .update(
                        "UPDATE person_evidence_copy SET payload=CAST(? AS jsonb) WHERE id=? AND job_id=? AND workspace_id=? AND state='CAPTURING'",
                        mapper.writeValueAsString(capture.receipt()),
                        capture.copyId(),
                        capture.jobId(),
                        capture.workspaceId());
        if (changed != 1) throw new IllegalStateException("Evidence capture ownership changed");
    }

    private void updateReceipt(ActiveCapture capture) {
        int changed = capture.admission()
                .jdbc()
                .update(
                        """
            UPDATE person_evidence_copy SET payload=CAST(? AS jsonb),state='READY'
            WHERE job_id=? AND workspace_id=? AND id=CAST(? AS uuid) AND state='CAPTURING'
            """,
                        mapper.writeValueAsString(capture.receipt()),
                        capture.jobId(),
                        capture.workspaceId(),
                        capture.copyId().toString());
        if (changed != 1) throw new IllegalStateException("Evidence capture ownership changed");
    }

    private boolean isSuppressed(List<PersonCopyIdentity> identities, JdbcOperations controls) {
        if (identities.isEmpty()) return false;
        var blocked = controls.query(
                """
            SELECT DISTINCT p.type,p.server_url,s.subject,NULLIF(s.team_key,'')
            FROM person_suppression s JOIN identity_provider p ON p.id=s.provider_id
            WHERE s.subject=ANY(?)
            """,
                (rs, row) -> new PersonCopyIdentity(
                        Objects.requireNonNull(rs.getString(1)), Objects.requireNonNull(rs.getString(2)),
                        Objects.requireNonNull(rs.getString(3)), rs.getString(4)),
                new SqlArrayValue(
                        "text",
                        identities.stream()
                                .map(PersonCopyIdentity::subject)
                                .distinct()
                                .toArray()));
        return blocked.stream()
                .anyMatch(control -> identities.stream()
                        .anyMatch(identity -> control.providerType().equals(identity.providerType())
                                && control.providerOrigin().equals(identity.providerOrigin())
                                && control.subject().equals(identity.subject())
                                && (Objects.equals(control.teamId(), identity.teamId())
                                        || (control.providerType().equals("OUTLINE") && control.teamId() == null))));
    }

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(new PersonDataContributor() {
            @Override
            public String store() {
                return "person_evidence_copy";
            }

            @Override
            public int getOrder() {
                return -300;
            }

            @Override
            public PersonDataSelection select(PersonScope person) {
                return selectCopies(person);
            }

            @Override
            public List<JsonNode> export(PersonDataSelection selection) {
                return selection.rows().stream()
                        .map(key -> {
                            ObjectNode row = mapper.createObjectNode();
                            row.put("jobId", key.columns().get("job_id"));
                            row.put("copyId", key.columns().get("copy_id"));
                            row.put("workspaceId", key.columns().get("workspace_id"));
                            row.put("kind", "short-lived-review-evidence");
                            return (JsonNode) row;
                        })
                        .toList();
            }

            @Override
            public void prepareErasure(PersonDataSelection selection) {
                requestRemoval(selection);
                awaitRemoval(selection);
            }

            @Override
            public long erase(PersonDataSelection selection) {
                return eraseAcknowledgedCopies(selection);
            }
        });
    }

    private long eraseAcknowledgedCopies(PersonDataSelection selection) {
        if (!allRemoved(selection)) throw new IllegalStateException("Evidence removal is not acknowledged");
        long count = 0;
        for (var key : selection.rows())
            count += jdbc.update(
                    """
                    UPDATE person_evidence_copy SET payload='{}'::jsonb
                    WHERE id=CAST(? AS uuid) AND job_id=? AND workspace_id=? AND state='ERASED'
                      AND payload<>'{}'::jsonb
                    """,
                    key.columns().get("copy_id"),
                    UUID.fromString(key.columns().get("job_id")),
                    Long.parseLong(key.columns().get("workspace_id")));
        return count;
    }

    private PersonDataSelection selectCopies(PersonScope person) {
        return selectCopies(person, true);
    }

    private PersonDataSelection selectCopies(PersonScope person, boolean includeUnknown) {
        var parameters = new HashMap<>(JdbcPersonDataStore.parameters(person, mapper));
        parameters.put("includeUnknown", includeUnknown);
        var identities = jdbc.query(
                """
            SELECT p.type,p.server_url,i.subject,i."teamId"
            FROM jsonb_to_recordset(CAST(? AS jsonb)) AS i("providerId" bigint,subject text,"teamId" text)
            JOIN identity_provider p ON p.id=i."providerId"
            """,
                (rs, row) -> new PersonCopyIdentity(
                        Objects.requireNonNull(rs.getString(1)), Objects.requireNonNull(rs.getString(2)),
                        Objects.requireNonNull(rs.getString(3)), rs.getString(4)),
                mapper.writeValueAsString(person.identities()));
        parameters.put("copyIdentities", mapper.writeValueAsString(identities));
        return new PersonDataSelection(namedJdbc.query(
                """
            SELECT c.job_id,c.id,c.workspace_id FROM person_evidence_copy c
            WHERE c.payload<>'{}'::jsonb AND
              (EXISTS(
                SELECT 1 FROM jsonb_to_recordset(COALESCE(c.payload->'identities','[]'::jsonb))
                  AS k("providerType" text,"providerOrigin" text,subject text,"teamId" text)
                JOIN jsonb_to_recordset(CAST(:copyIdentities AS jsonb))
                  AS i("providerType" text,"providerOrigin" text,subject text,"teamId" text)
                  ON i.subject=k.subject AND i."providerType"=k."providerType" AND i."providerOrigin"=k."providerOrigin"
                WHERE COALESCE(k."teamId",'')=COALESCE(i."teamId",'') OR (i."providerType"='OUTLINE' AND i."teamId" IS NULL))
                OR (:includeUnknown AND EXISTS(SELECT 1 FROM jsonb_array_elements_text(COALESCE(c.payload->'repositories','[]'::jsonb)) repository_id
                  JOIN git_commit gc ON gc.repository_id=repository_id.value::bigint
                  WHERE gc.author_id=ANY(:users) OR gc.committer_id=ANY(:users)
                    OR EXISTS(SELECT 1 FROM commit_contributor contributor WHERE contributor.commit_id=gc.id AND contributor.user_id=ANY(:users)))))
            ORDER BY c.job_id,c.id
            """,
                parameters,
                (rs, row) -> new PersonDataSelection.RowKey(Map.of(
                        "job_id", rs.getString(1), "copy_id", rs.getString(2), "workspace_id", rs.getString(3)))));
    }

    /** Queue removal in the workspace transaction; workers can acknowledge only after it commits. */
    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        jdbc.update("""
            UPDATE person_evidence_copy
            SET state=CASE WHEN state='ERASED' THEN state ELSE 'PURGE_REQUESTED' END,
                payload=CASE WHEN state='ERASED' THEN '{}'::jsonb ELSE payload END
            WHERE workspace_id=?
            """, workspaceId);
    }

    @Override
    public int getOrder() {
        return -60;
    }

    @Override
    public Set<UUID> jobsContaining(PersonScope person) {
        return selectCopies(person, false).rows().stream()
                .map(key -> UUID.fromString(key.columns().get("job_id")))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void eraseJobEvidence(Set<UUID> jobIds) {
        var selection = new PersonDataSelection(jdbc.query(
                """
            SELECT c.job_id,c.id,c.workspace_id FROM person_evidence_copy c
            WHERE c.job_id=ANY(?) ORDER BY c.job_id,c.id
            """,
                (rs, row) -> new PersonDataSelection.RowKey(
                        Map.of("job_id", rs.getString(1), "copy_id", rs.getString(2), "workspace_id", rs.getString(3))),
                new SqlArrayValue("uuid", jobIds.toArray())));
        requestRemoval(selection);
        awaitRemoval(selection);
    }

    private void requestRemoval(PersonDataSelection selection) {
        var cancelled = new HashSet<UUID>();
        for (var key : selection.rows()) {
            UUID jobId = UUID.fromString(key.columns().get("job_id"));
            long workspaceId = Long.parseLong(key.columns().get("workspace_id"));
            if (!cancelled.add(jobId)) continue;
            Boolean running = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM agent_job WHERE id=? AND workspace_id=? AND status IN ('QUEUED','RUNNING'))",
                    Boolean.class,
                    jobId,
                    workspaceId);
            if (Boolean.TRUE.equals(running)) {
                try {
                    lifecycles.getObject().cancel(workspaceId, jobId);
                } catch (AgentJobStateConflictException raced) {
                    if (Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM agent_job WHERE id=? AND workspace_id=? AND status IN ('QUEUED','RUNNING'))",
                            Boolean.class,
                            jobId,
                            workspaceId))) throw raced;
                }
            }
        }
        for (var key : selection.rows())
            jdbc.update(
                    """
            UPDATE person_evidence_copy SET state=CASE WHEN state='PURGE_REQUESTED' THEN state ELSE 'ERASE_REQUESTED' END
            WHERE id=CAST(? AS uuid) AND job_id=? AND workspace_id=? AND state<>'ERASED'
            """,
                    key.columns().get("copy_id"),
                    UUID.fromString(key.columns().get("job_id")),
                    Long.parseLong(key.columns().get("workspace_id")));
    }

    private boolean allRemoved(PersonDataSelection selection) {
        for (var key : selection.rows()) {
            Boolean remains = jdbc.queryForObject(
                    """
                SELECT NOT EXISTS(SELECT 1 FROM person_evidence_copy WHERE job_id=? AND workspace_id=?
                  AND id=CAST(? AS uuid) AND state='ERASED')
                """,
                    Boolean.class,
                    UUID.fromString(key.columns().get("job_id")),
                    Long.parseLong(key.columns().get("workspace_id")),
                    key.columns().get("copy_id"));
            if (Boolean.TRUE.equals(remains)) return false;
        }
        return true;
    }

    private void awaitRemoval(PersonDataSelection selection) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            removeLocalRequests();
            if (allRemoved(selection)) return;
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Evidence removal was interrupted", exception);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("A mounted evidence store has not acknowledged removal");
    }

    /** Worker polling also serves a resumed request after the owning volume returns. */
    public void removeLocalRequests() {
        if (!Files.exists(layout.root().resolve(".person-evidence-store-id"))) return;
        String owner = storeId().toString();
        jdbc.query(
                """
            SELECT c.job_id,c.workspace_id,c.id FROM person_evidence_copy c
            WHERE c.store_id=CAST(? AS uuid) AND c.state IN ('ERASE_REQUESTED','PURGE_REQUESTED') ORDER BY c.job_id,c.id
            """,
                (RowCallbackHandler) rs -> {
                    UUID jobId = Objects.requireNonNull(rs.getObject(1, UUID.class));
                    long workspaceId = rs.getLong(2);
                    String copyId = Objects.requireNonNull(rs.getString(3));
                    AgentJobExecutor localExecutor = executor.getIfAvailable();
                    if (localExecutor != null) localExecutor.cancelLocalJob(jobId, "Person data erasure");
                    var acquired = EvidenceFolderLease.tryAcquire(layout.root(), workspaceId, jobId);
                    if (acquired.isEmpty()) return;
                    var lease = acquired.get();
                    try (lease) {
                        deleteFolder(workspaceId, jobId);
                        jdbc.update("""
                    UPDATE person_evidence_copy SET state='ERASED',
                        payload=CASE WHEN state='PURGE_REQUESTED'
                            THEN '{}'::jsonb ELSE payload END
                    WHERE id=CAST(? AS uuid) AND job_id=? AND workspace_id=? AND store_id=CAST(? AS uuid) AND state IN ('ERASE_REQUESTED','PURGE_REQUESTED')
                    """, copyId, jobId, workspaceId, owner);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                },
                owner);
    }

    private void deleteFolder(long workspaceId, UUID jobId) throws IOException {
        var folder = layout.jobsRoot().resolve(Long.toString(workspaceId)).resolve(jobId.toString());
        FileUtils.deleteDirectory(folder.toFile());
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Evidence folder remains");
    }

    private synchronized UUID storeId() {
        if (localStoreId != null) return localStoreId;
        var identity = layout.root().resolve(".person-evidence-store-id");
        try {
            Files.createDirectories(layout.root());
            try {
                Files.writeString(
                        identity,
                        UUID.randomUUID().toString(),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException existing) {
                // The mounted store, not a transient connection, owns this receipt.
            }
            UUID result = UUID.fromString(Files.readString(identity).strip());
            localStoreId = result;
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record ActiveCapture(
            UUID jobId,
            long workspaceId,
            UUID copyId,
            ObjectNode receipt,
            PersonDataCopyRecorder.Capture provenance,
            EvidenceFolderLease lease,
            PersonDataCopyFence.Lease admission) {}
}

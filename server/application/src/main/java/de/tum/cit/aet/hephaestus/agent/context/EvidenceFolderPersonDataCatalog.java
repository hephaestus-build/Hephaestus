package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobExecutor;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Durable requests are acknowledged by the mounted store, never by the server's local filesystem. */
@Component
@PersonDataStores({"person_evidence_copy"})
@WorkspaceAgnostic("Exact-person receipts span workspaces; every job mutation also pins its workspace key")
public class EvidenceFolderPersonDataCatalog implements PersonEvidenceErasure {
    private final FabricLayout layout;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper mapper;
    private final PersonDataCopyRecorder recorder;
    private final PersonDataCopyFence fence;
    private final ObjectProvider<AgentJobExecutor> executor;
    private final ObjectProvider<de.tum.cit.aet.hephaestus.agent.job.AgentJobLifecycleService> lifecycles;
    private final ThreadLocal<ActiveCapture> active = new ThreadLocal<>();
    private @Nullable UUID localStoreId;

    public EvidenceFolderPersonDataCatalog(
            FabricLayout layout,
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            ObjectMapper mapper,
            PersonDataCopyRecorder recorder,
            PersonDataCopyFence fence,
            ObjectProvider<AgentJobExecutor> executor,
            ObjectProvider<de.tum.cit.aet.hephaestus.agent.job.AgentJobLifecycleService> lifecycles) {
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
        if (active.get() != null) throw new IllegalStateException("An evidence capture is already active");
        var admission = fence.capture();
        EvidenceFolderLease lease = null;
        PersonDataCopyRecorder.Capture provenance = null;
        try {
            UUID storeId = storeId();
            UUID copyId = UUID.randomUUID();
            long workspace = job.getWorkspace().getId();
            lease = EvidenceFolderLease.tryAcquire(layout.root(), workspace, job.getId())
                    .orElseThrow(() -> new IllegalStateException("Another attempt still holds this evidence folder"));
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
            active.set(capture);
            provenance.onChange(() -> recordProgress(capture));
        } catch (RuntimeException exception) {
            if (provenance != null) provenance.close();
            if (lease != null) lease.close();
            admission.close();
            throw exception;
        }
    }

    /** Called only after the frozen folder exists; the returned lease belongs to PreparedJobInputs. */
    public AutoCloseable finishCapture(AgentJob job) {
        ActiveCapture capture = requireCapture(job);
        try {
            capture.receipt()
                    .set("identities", mapper.valueToTree(capture.provenance().identities()));
            capture.receipt()
                    .set("repositories", mapper.valueToTree(capture.provenance().repositoryIds()));
            if (isSuppressed(
                    capture.provenance().identities(), capture.admission().jdbc()))
                throw new IllegalStateException("Copied evidence contains an erased native identity");
            updateReceipt(capture);
            return capture.lease();
        } catch (RuntimeException exception) {
            capture.lease().close();
            throw exception;
        } finally {
            capture.provenance().close();
            capture.admission().close();
            active.remove();
        }
    }

    public void abortCapture(AgentJob job) {
        ActiveCapture capture = active.get();
        if (capture == null) return;
        if (!capture.jobId().equals(job.getId())) throw new IllegalStateException("Wrong evidence capture owner");
        try {
            capture.receipt()
                    .set("identities", mapper.valueToTree(capture.provenance().identities()));
            capture.receipt()
                    .set("repositories", mapper.valueToTree(capture.provenance().repositoryIds()));
            updateReceipt(capture);
        } finally {
            capture.provenance().close();
            capture.lease().close();
            capture.admission().close();
            active.remove();
        }
    }

    private ActiveCapture requireCapture(AgentJob job) {
        ActiveCapture capture = active.get();
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

    private boolean isSuppressed(
            List<PersonCopyIdentity> identities, org.springframework.jdbc.core.JdbcOperations controls) {
        return Boolean.TRUE.equals(controls.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM jsonb_to_recordset(CAST(? AS jsonb))
              AS i("providerType" text,"providerOrigin" text,subject text,"teamId" text)
              JOIN identity_provider p ON p.type=i."providerType" AND p.server_url=i."providerOrigin"
              JOIN person_suppression s ON s.provider_id=p.id AND s.subject=i.subject
              WHERE s.team_key=COALESCE(i."teamId",'') OR (p.type='OUTLINE' AND s.team_key=''))
            """, Boolean.class, mapper.writeValueAsString(identities)));
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
        });
    }

    private PersonDataSelection selectCopies(PersonScope person) {
        return selectCopies(person, true);
    }

    private PersonDataSelection selectCopies(PersonScope person, boolean includeUnknown) {
        var parameters = new HashMap<>(JdbcPersonDataStore.parameters(person, mapper));
        parameters.put("includeUnknown", includeUnknown);
        return new PersonDataSelection(namedJdbc.query(
                """
            SELECT c.job_id,c.id,c.workspace_id FROM person_evidence_copy c
            WHERE c.state<>'ERASED' AND
              (EXISTS(
                SELECT 1 FROM jsonb_to_recordset(COALESCE(c.payload->'identities','[]'::jsonb))
                  AS k("providerType" text,"providerOrigin" text,subject text,"teamId" text)
                JOIN jsonb_to_recordset(CAST(:identities AS jsonb)) AS i("providerId" bigint,subject text,"teamId" text)
                  ON i.subject=k.subject
                JOIN identity_provider p ON p.id=i."providerId" AND p.type=k."providerType" AND p.server_url=k."providerOrigin"
                WHERE COALESCE(k."teamId",'')=COALESCE(i."teamId",'') OR (p.type='OUTLINE' AND i."teamId" IS NULL))
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

    @Override
    public Set<UUID> jobsContaining(PersonScope person) {
        return selectCopies(person, false).rows().stream()
                .map(key -> UUID.fromString(key.columns().get("job_id")))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
                new org.springframework.jdbc.support.SqlArrayValue("uuid", jobIds.toArray())));
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
                } catch (de.tum.cit.aet.hephaestus.agent.job.AgentJobStateConflictException raced) {
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
            UPDATE person_evidence_copy SET state='ERASE_REQUESTED'
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
            WHERE c.store_id=CAST(? AS uuid) AND c.state='ERASE_REQUESTED' ORDER BY c.job_id,c.id
            """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    UUID jobId = Objects.requireNonNull(rs.getObject(1, UUID.class));
                    long workspaceId = rs.getLong(2);
                    String copyId = Objects.requireNonNull(rs.getString(3));
                    AgentJobExecutor localExecutor = executor.getIfAvailable();
                    if (localExecutor != null) localExecutor.cancelLocalJob(jobId, "Person data erasure");
                    var acquired = EvidenceFolderLease.tryAcquire(layout.root(), workspaceId, jobId);
                    if (acquired.isEmpty()) return;
                    var lease = acquired.get();
                    try (lease) {
                        var folder = layout.jobsRoot()
                                .resolve(Long.toString(workspaceId))
                                .resolve(jobId.toString());
                        FileUtils.deleteDirectory(folder.toFile());
                        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS))
                            throw new IllegalStateException("Evidence folder remains");
                        jdbc.update("""
                    UPDATE person_evidence_copy SET state='ERASED'
                    WHERE id=CAST(? AS uuid) AND job_id=? AND workspace_id=? AND store_id=CAST(? AS uuid) AND state='ERASE_REQUESTED'
                    """, copyId, jobId, workspaceId, owner);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                },
                owner);
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
            } catch (java.nio.file.FileAlreadyExistsException existing) {
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

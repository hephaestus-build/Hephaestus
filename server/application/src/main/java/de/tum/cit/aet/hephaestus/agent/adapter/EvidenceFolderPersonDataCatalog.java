package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobStateConflictException;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonEvidenceErasure;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The receipts of people's data that review attempts copy into their folders. Erasure and purge mark a receipt
 * {@code ERASED} in PostgreSQL and notify {@link EvidenceErasureNotifications#CHANNEL}; every server then removes its own
 * folders of the receipt's job once its leases are released and removal succeeds ({@link #removeErasedFolders}).
 * No erasure waits for a server. {@link EvidenceCaptures} writes a receipt while an attempt captures, and
 * {@link EvidenceReceipts} reads them.
 *
 * <p>Exact-person receipts span workspaces. Every job mutation also pins its workspace key.
 */
@Component
@PersonDataStores({"person_evidence_copy", "review_attempt_input"})
public class EvidenceFolderPersonDataCatalog implements PersonEvidenceErasure, WorkspacePurgeContributor {
    public static final String COPY_AUTHORITY_UNAVAILABLE = "COPY_AUTHORITY_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(EvidenceFolderPersonDataCatalog.class);

    private static final Pattern WORKSPACE_FOLDER = Pattern.compile("[1-9][0-9]{0,18}");

    /** The attempt that a job folder's entry belongs to: {@code <attempt>-…}, or {@code .<attempt>-…} while staged. */
    private static final Pattern ENTRY_ATTEMPT = Pattern.compile("\\.?([0-9]+)-");

    private final FabricLayout layout;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper mapper;
    private final ObjectProvider<ReviewAttemptCustody> custody;
    private final ObjectProvider<AgentSandboxes> sandboxes;
    private final ObjectProvider<SandboxGatewaySessions> sessions;
    private final ObjectProvider<ReviewJobLifecycleService> lifecycles;

    public EvidenceFolderPersonDataCatalog(
            FabricLayout layout,
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            ObjectMapper mapper,
            ObjectProvider<ReviewAttemptCustody> custody,
            ObjectProvider<AgentSandboxes> sandboxes,
            ObjectProvider<SandboxGatewaySessions> sessions,
            ObjectProvider<ReviewJobLifecycleService> lifecycles) {
        this.layout = layout;
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.mapper = mapper;
        this.custody = custody;
        this.sandboxes = sandboxes;
        this.sessions = sessions;
        this.lifecycles = lifecycles;
    }

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new PersonDataContributor() {
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
                    public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
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
                    public void prepareErasure(PersonDataSelection selection, PersonScope frozenScope) {
                        requestRemoval(selection);
                    }

                    @Override
                    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
                        return clearErasedCopies(selection);
                    }
                },
                new PersonDataContributor() {
                    @Override
                    public String store() {
                        return "review_attempt_input";
                    }

                    @Override
                    public int getOrder() {
                        return -299;
                    }

                    // The recorded inputs of the jobs whose copies name the person: the same copies, as rows of a job.
                    @Override
                    public PersonDataSelection select(PersonScope person) {
                        var copies = selectCopies(person).rows().stream()
                                .map(key -> key.columns().get("copy_id"))
                                .toArray();
                        return new PersonDataSelection(jdbc.query(
                                """
                        SELECT DISTINCT i.job_id,i.workspace_id FROM review_attempt_input i
                        JOIN person_evidence_copy c ON c.job_id=i.job_id AND c.workspace_id=i.workspace_id
                        WHERE c.id=ANY(CAST(? AS uuid[])) ORDER BY i.job_id
                        """,
                                (rs, row) -> new PersonDataSelection.RowKey(
                                        Map.of("job_id", rs.getString(1), "workspace_id", rs.getString(2))),
                                new SqlArrayValue("text", copies)));
                    }

                    @Override
                    public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
                        return selection.rows().stream()
                                .map(key -> {
                                    ObjectNode row = mapper.createObjectNode();
                                    row.put("jobId", key.columns().get("job_id"));
                                    row.put("workspaceId", key.columns().get("workspace_id"));
                                    row.put("kind", "running-review-attempt-input");
                                    return (JsonNode) row;
                                })
                                .toList();
                    }

                    // The copies' erasure cancels the jobs and deletes these rows first; this deletes what remains.
                    @Override
                    public void prepareErasure(PersonDataSelection selection, PersonScope frozenScope) {}

                    @Override
                    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
                        long count = 0;
                        for (var key : selection.rows())
                            count += jdbc.update(
                                    "DELETE FROM review_attempt_input WHERE job_id=? AND workspace_id=?",
                                    UUID.fromString(key.columns().get("job_id")),
                                    Long.parseLong(key.columns().get("workspace_id")));
                        return count;
                    }
                });
    }

    // The sandbox ID contains no copied personal data and lets every reconcile retry physical removal.
    private long clearErasedCopies(PersonDataSelection selection) {
        if (!allRemoved(selection)) throw new IllegalStateException("A selected evidence copy is not erased");
        long count = 0;
        for (var key : selection.rows())
            count += jdbc.update(
                    """
                    UPDATE person_evidence_copy SET payload=jsonb_strip_nulls(jsonb_build_object('sandboxId',payload->'sandboxId'))
                    WHERE id=CAST(? AS uuid) AND job_id=? AND workspace_id=? AND state='ERASED'
                      AND payload-'sandboxId'<>'{}'::jsonb
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

    /**
     * Removes the receipts of review jobs that retention deleted. Once the job row is gone, no result reaches its
     * receipt (the review item's job reference is cleared), so the receipt vouches for nothing and keeps identities
     * without a purpose. A receipt that still names a sandbox stays until physical deletion succeeds.
     * Each server removes the folders of a job without its row in its next reconcile pass.
     *
     * @return the larger number of receipts retired or deleted, so a full batch asks for another
     */
    public int retireReceiptsOfDeletedJobs(int batchSize) {
        int retired = jdbc.update("""
            UPDATE person_evidence_copy SET state='ERASED',
              payload=jsonb_strip_nulls(jsonb_build_object('sandboxId',payload->'sandboxId'))
            WHERE id IN (SELECT c.id FROM person_evidence_copy c
              WHERE NOT EXISTS(SELECT 1 FROM review_job j WHERE j.id=c.job_id AND j.workspace_id=c.workspace_id)
                AND c.payload->>'sandboxId' IS NOT NULL AND c.state<>'ERASED' LIMIT ?)
            """, batchSize);
        int deleted = jdbc.update("""
            DELETE FROM person_evidence_copy WHERE id IN (
              SELECT c.id FROM person_evidence_copy c
              WHERE NOT EXISTS(SELECT 1 FROM review_job j WHERE j.id=c.job_id AND j.workspace_id=c.workspace_id)
                AND c.payload->>'sandboxId' IS NULL
              LIMIT ?)
            """, batchSize);
        return Math.max(retired, deleted);
    }

    /** Erases the workspace's copies in its purge transaction; each server removes its folders after the commit. */
    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        jdbc.update(
                "UPDATE person_evidence_copy SET state='ERASED', payload=jsonb_strip_nulls(jsonb_build_object('sandboxId',payload->'sandboxId')) WHERE workspace_id=?",
                workspaceId);
        jdbc.update("DELETE FROM review_attempt_input WHERE workspace_id=?", workspaceId);
        notifyErased();
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
    }

    /**
     * Marks the selected copies erased, then cancels their running jobs and deletes their jobs' attempt inputs. From
     * then on, no attempt can admit or retain the copies, and only {@link #removeErasedFolders} removes the folders
     * of their jobs, in a later reconcile pass once leases are released. The copies are erased before the jobs stop,
     * so an attempt that a cancellation ends never finds its folder unclaimed: its release and the sweep leave the
     * folder to erasure, which removes and counts it.
     */
    private void requestRemoval(PersonDataSelection selection) {
        var jobs = new LinkedHashMap<UUID, Long>();
        for (var key : selection.rows())
            jobs.putIfAbsent(
                    UUID.fromString(key.columns().get("job_id")),
                    Long.parseLong(key.columns().get("workspace_id")));
        for (var key : selection.rows())
            jdbc.update(
                    """
                    UPDATE person_evidence_copy SET state='ERASED'
                    WHERE id=CAST(? AS uuid) AND job_id=? AND workspace_id=? AND state<>'ERASED'
                    """,
                    key.columns().get("copy_id"),
                    UUID.fromString(key.columns().get("job_id")),
                    Long.parseLong(key.columns().get("workspace_id")));
        jobs.forEach((jobId, workspaceId) -> {
            if (running(jobId, workspaceId)) {
                try {
                    lifecycles.getObject().cancel(workspaceId, jobId);
                } catch (ReviewJobStateConflictException raced) {
                    if (running(jobId, workspaceId)) throw raced;
                }
            }
            jdbc.update("DELETE FROM review_attempt_input WHERE job_id=? AND workspace_id=?", jobId, workspaceId);
        });
        if (!jobs.isEmpty()) notifyErased();
    }

    /** Sends the wake-up in the caller's transaction, so PostgreSQL delivers it only if the erasure commits. */
    private void notifyErased() {
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_notify(?, '')) sent", Integer.class, EvidenceErasureNotifications.CHANNEL);
    }

    /**
     * The given jobs that have an erased copy, keyed by job id with their workspace. Their folders are erasure's to
     * remove ({@link #removeErasedFolders}); no other cleanup touches them.
     */
    public Set<UUID> erasedJobs(Map<UUID, Long> jobs) {
        if (jobs.isEmpty()) return Set.of();
        var ids = new ArrayList<UUID>(jobs.keySet());
        return Set.copyOf(jdbc.queryForList(
                """
                SELECT DISTINCT c.job_id FROM person_evidence_copy c
                JOIN unnest(CAST(? AS uuid[]), CAST(? AS bigint[])) AS f(job_id, workspace_id)
                  ON f.job_id=c.job_id AND f.workspace_id=c.workspace_id
                WHERE c.state='ERASED'
                """,
                UUID.class,
                new SqlArrayValue("uuid", ids.toArray()),
                new SqlArrayValue("bigint", ids.stream().map(jobs::get).toArray())));
    }

    private boolean running(UUID jobId, long workspaceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM review_job WHERE id=? AND workspace_id=? AND status IN ('QUEUED','RUNNING'))",
                Boolean.class,
                jobId,
                workspaceId));
    }

    /** A receipt that retention deleted is removed too. */
    private boolean allRemoved(PersonDataSelection selection) {
        for (var key : selection.rows()) {
            Boolean remains = jdbc.queryForObject(
                    """
                    SELECT EXISTS(SELECT 1 FROM person_evidence_copy WHERE job_id=? AND workspace_id=?
                      AND id=CAST(? AS uuid) AND state<>'ERASED')
                    """,
                    Boolean.class,
                    UUID.fromString(key.columns().get("job_id")),
                    Long.parseLong(key.columns().get("workspace_id")),
                    key.columns().get("copy_id"));
            if (Boolean.TRUE.equals(remains)) return false;
        }
        return true;
    }

    /**
     * Removes this server's folders of every job with an erased copy, except the folders of its attempts whose copy is
     * still live, and stops a sandbox that this server runs for such a job. The reconcile pass runs it every 30 seconds
     * and on each {@link EvidenceErasureNotifications} wake-up. Held leases and failed removal defer cleanup to a later
     * pass; an erasure commit has no physical-deletion deadline.
     *
     * @return the job folders it removed erased attempts from, and those it could not remove them from now
     */
    public ErasedFolders removeErasedFolders() {
        var local = new HashMap<>(localJobFolders());
        // A startup sweep can remove the attempt folder before its sandbox and gateway archive are removed.
        AgentSandboxes sandboxOwner = sandboxes.getIfAvailable();
        SandboxGatewaySessions gateway = sessions.getIfAvailable();
        var archives = gateway == null ? Set.<UUID>of() : gateway.jobIds();
        jdbc.query(
                "SELECT job_id,workspace_id FROM person_evidence_copy WHERE state='ERASED'"
                        + " AND ((? AND payload->>'sandboxId' IS NOT NULL) OR job_id=ANY(?))",
                (RowCallbackHandler)
                        rs -> local.put(Objects.requireNonNull(rs.getObject(1, UUID.class)), rs.getLong(2)),
                sandboxOwner != null,
                new SqlArrayValue("uuid", archives.toArray()));
        if (local.isEmpty()) return new ErasedFolders(0, 0);
        var erased = new HashMap<UUID, Set<String>>();
        var erasedSandboxes = new HashMap<UUID, List<UUID>>();
        var jobIds = new ArrayList<UUID>(local.keySet());
        jdbc.query(
                """
                SELECT c.job_id,
                  ARRAY(SELECT DISTINCT e.payload->>'sandboxId' FROM person_evidence_copy e WHERE e.job_id=c.job_id
                    AND e.workspace_id=c.workspace_id AND e.state='ERASED'
                    AND e.payload->>'sandboxId' IS NOT NULL) AS sandboxes,
                  ARRAY(SELECT l.payload->>'attempt' FROM person_evidence_copy l WHERE l.job_id=c.job_id
                    AND l.workspace_id=c.workspace_id AND l.state IN ('CAPTURING','READY')) AS live
                FROM person_evidence_copy c
                JOIN unnest(CAST(? AS uuid[]), CAST(? AS bigint[])) AS f(job_id, workspace_id)
                  ON f.job_id=c.job_id AND f.workspace_id=c.workspace_id
                WHERE c.state='ERASED'
                GROUP BY c.job_id, c.workspace_id
                """,
                (RowCallbackHandler) rs -> {
                    var live = new HashSet<String>();
                    var sandboxIds = new ArrayList<UUID>();
                    for (Object sandbox : (Object[]) rs.getArray(2).getArray())
                        sandboxIds.add(UUID.fromString(sandbox.toString()));
                    erasedSandboxes.put(Objects.requireNonNull(rs.getObject(1, UUID.class)), sandboxIds);
                    for (Object attempt : (Object[]) rs.getArray(3).getArray())
                        if (attempt != null) live.add(attempt.toString());
                    erased.put(Objects.requireNonNull(rs.getObject(1, UUID.class)), live);
                },
                new SqlArrayValue("uuid", jobIds.toArray()),
                new SqlArrayValue("bigint", jobIds.stream().map(local::get).toArray()));
        int removed = 0;
        int remaining = 0;
        for (var entry : erased.entrySet()) {
            UUID jobId = entry.getKey();
            long workspaceId = Objects.requireNonNull(local.get(jobId));
            try {
                var sandboxIds = Objects.requireNonNull(erasedSandboxes.get(jobId));
                if (sandboxOwner != null) {
                    for (UUID sandboxId : sandboxIds) {
                        try {
                            sandboxOwner.release(workspaceId, sandboxId);
                            jdbc.update(
                                    "UPDATE person_evidence_copy SET payload=payload-'sandboxId'"
                                            + " WHERE job_id=? AND workspace_id=? AND state='ERASED' AND payload->>'sandboxId'=?",
                                    jobId,
                                    workspaceId,
                                    sandboxId.toString());
                        } catch (RuntimeException failure) {
                            log.warn("Could not remove an erased evidence sandbox: sandboxId={}", sandboxId, failure);
                            remaining++;
                        }
                    }
                }
                // An attempt that this server holds keeps the folder's lease until its inputs are released.
                ReviewAttemptCustody held = custody.getIfAvailable();
                if (held != null && entry.getValue().isEmpty()) held.releaseJob(jobId);
                var acquired = EvidenceFolderLease.tryAcquire(layout.root(), workspaceId, jobId);
                if (acquired.isEmpty()) {
                    remaining++;
                    continue;
                }
                var lease = acquired.get();
                try (lease) {
                    if (gateway != null && entry.getValue().isEmpty()) {
                        try {
                            gateway.closeJob(jobId);
                        } catch (RuntimeException failure) {
                            log.warn("Could not remove an erased evidence archive: jobId={}", jobId, failure);
                            remaining++;
                        }
                    }
                    if (removeErasedAttempts(workspaceId, jobId, entry.getValue())) removed++;
                }
            } catch (IOException | RuntimeException failure) {
                log.warn("Could not remove an erased evidence folder: jobId={}", jobId, failure);
                remaining++;
            }
        }
        return new ErasedFolders(removed, remaining);
    }

    /**
     * One pass of {@link #removeErasedFolders}.
     *
     * @param remaining erased copies that this server could not remove: cleanup succeeds only once this is 0
     */
    public record ErasedFolders(int removed, int remaining) {}

    /** The job folders under this server's jobs root, by job id, with their workspace. */
    private Map<UUID, Long> localJobFolders() {
        Path jobs = layout.jobsRoot();
        if (!Files.isDirectory(jobs, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        var folders = new HashMap<UUID, Long>();
        try (var workspaces = Files.list(jobs)) {
            for (Path workspace : workspaces.toList()) {
                String workspaceName = String.valueOf(workspace.getFileName());
                if (!WORKSPACE_FOLDER.matcher(workspaceName).matches()) continue;
                try (var entries = Files.list(workspace)) {
                    for (Path job : entries.toList()) {
                        try {
                            folders.put(
                                    UUID.fromString(String.valueOf(job.getFileName())), Long.parseLong(workspaceName));
                        } catch (IllegalArgumentException notAJob) {
                            log.debug("Skipping an entry outside the job folder format: path={}", job, notAJob);
                        }
                    }
                } catch (NoSuchFileException removedUnderTheWalk) {
                    log.debug(
                            "An evidence workspace folder was removed during the sweep: path={}",
                            workspace,
                            removedUnderTheWalk);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return folders;
    }

    /**
     * Deletes each entry of the job's folder whose attempt has no live copy: attempt folders, their staging folders
     * and transcripts. An entry that names no attempt goes too. The emptied job folder goes last.
     *
     * @return whether anything was deleted
     */
    private boolean removeErasedAttempts(long workspaceId, UUID jobId, Set<String> liveAttempts) throws IOException {
        Path folder = layout.jobsRoot().resolve(Long.toString(workspaceId)).resolve(jobId.toString());
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return false;
        boolean removed = false;
        try (var entries = Files.list(folder)) {
            for (Path entry : entries.toList()) {
                var attempt = ENTRY_ATTEMPT.matcher(String.valueOf(entry.getFileName()));
                if (attempt.lookingAt() && liveAttempts.contains(attempt.group(1))) continue;
                FileUtils.forceDelete(entry.toFile());
                removed = true;
            }
        }
        try (var remaining = Files.list(folder)) {
            if (remaining.findAny().isEmpty()) Files.deleteIfExists(folder);
        }
        return removed;
    }
}

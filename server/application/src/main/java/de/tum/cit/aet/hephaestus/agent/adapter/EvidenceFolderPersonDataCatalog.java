package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopy;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLifecycleService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobStateConflictException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
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
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
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
 * No erasure waits for a server.
 */
@Component
@PersonDataStores({"person_evidence_copy", "review_attempt_input"})
@WorkspaceAgnostic("Exact-person receipts span workspaces; every job mutation also pins its workspace key")
public class EvidenceFolderPersonDataCatalog implements PersonEvidenceErasure, WorkspacePurgeContributor {
    private static final ThreadLocal<JdbcOperations> LATE_READ_CONTROLS = new ThreadLocal<>();

    public static final String COPY_AUTHORITY_UNAVAILABLE = "COPY_AUTHORITY_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(EvidenceFolderPersonDataCatalog.class);

    private static final Pattern WORKSPACE_FOLDER = Pattern.compile("[1-9][0-9]{0,18}");

    /** The attempt that a job folder's entry belongs to: {@code <attempt>-…}, or {@code .<attempt>-…} while staged. */
    private static final Pattern ENTRY_ATTEMPT = Pattern.compile("\\.?([0-9]+)-");

    private final FabricLayout layout;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper mapper;
    private final PersonDataCopyRecorder recorder;
    private final PersonDataCopyFence fence;
    private final ObjectProvider<ReviewAttemptCustody> custody;
    private final ObjectProvider<AgentSandboxes> sandboxes;
    private final ObjectProvider<SandboxGatewaySessions> sessions;
    private final ObjectProvider<ReviewJobLifecycleService> lifecycles;
    private static final ThreadLocal<ActiveCapture> ACTIVE = new ThreadLocal<>();
    /** Whether a receipt indexes the receipts of every job whose rows its capture copied. */
    private static final String DEPENDENCIES = "dependencies";

    private static final String COMPLETE = "COMPLETE";
    /** Who an attempt staged from an earlier attempt's retained inputs copies: that receipt's people, carried over. */
    private static final String INHERITED_IDENTITIES = "inheritedIdentities";

    private static final String INHERITED_REPOSITORIES = "inheritedRepositories";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String INCOMPLETE_ASSESSMENT = "Copied evidence dependency assessment is incomplete";

    public EvidenceFolderPersonDataCatalog(
            FabricLayout layout,
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            ObjectMapper mapper,
            PersonDataCopyRecorder recorder,
            PersonDataCopyFence fence,
            ObjectProvider<ReviewAttemptCustody> custody,
            ObjectProvider<AgentSandboxes> sandboxes,
            ObjectProvider<SandboxGatewaySessions> sessions,
            ObjectProvider<ReviewJobLifecycleService> lifecycles) {
        this.layout = layout;
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.mapper = mapper;
        this.recorder = recorder;
        this.fence = fence;
        this.custody = custody;
        this.sandboxes = sandboxes;
        this.sessions = sessions;
        this.lifecycles = lifecycles;
    }

    public void beginCapture(ReviewJob job) {
        if (ACTIVE.get() != null) throw new IllegalStateException("An evidence capture is already active");
        var admission = fence.capture();
        EvidenceFolderLease lease = null;
        PersonDataCopyRecorder.Capture provenance = null;
        try {
            UUID copyId = UUID.randomUUID();
            long workspace = job.getWorkspaceId();
            lease = EvidenceFolderLease.tryAcquire(layout.root(), workspace, job.getId())
                    .orElseThrow(() ->
                            new ReviewSourceNotReadyException("Another attempt still holds this evidence folder"));
            provenance = recorder.begin();
            ObjectNode receipt = mapper.createObjectNode();
            receipt.put("attempt", job.getAttempt());
            jdbc
                    .queryForList(
                            "SELECT id FROM agent_sandbox WHERE workspace_id=? AND subject_key=? AND state='READY'",
                            UUID.class,
                            workspace,
                            ReviewRunKind.subject(job.getReviewId()))
                    .stream()
                    .findFirst()
                    .ifPresent(id -> receipt.put("sandboxId", id.toString()));
            receipt.putArray("identities");
            receipt.putArray("repositories");
            int changed =
                    jdbc.update("""
                    INSERT INTO person_evidence_copy(id,workspace_id,job_id,state,payload)
                    SELECT CAST(? AS uuid),workspace_id,id,'CAPTURING',CAST(? AS jsonb) FROM review_job
                    WHERE id=? AND workspace_id=? AND status='RUNNING'
                    """, copyId.toString(), mapper.writeValueAsString(receipt), job.getId(), workspace);
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
    public AutoCloseable finishCapture(ReviewJob job) {
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
                    capture.admission()
                            .jdbc()
                            .update(
                                    "DELETE FROM review_attempt_input WHERE job_id=? AND workspace_id=?",
                                    capture.jobId(),
                                    capture.workspaceId());
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

    public void abortCapture(ReviewJob job) {
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
        capture.receipt().path(INHERITED_IDENTITIES).forEach(identities::add);
        identities.addAll(dependencies.identities());
        var repositories = new LinkedHashSet<JsonNode>();
        capture.receipt().path("repositories").forEach(repositories::add);
        capture.receipt().path(INHERITED_REPOSITORIES).forEach(repositories::add);
        repositories.addAll(dependencies.repositories());
        capture.receipt().set("identities", mapper.valueToTree(identities));
        capture.receipt().set("repositories", mapper.valueToTree(repositories));
        capture.receipt().set("copiedJobs", mapper.valueToTree(sources));
        capture.receipt().put(DEPENDENCIES, dependencies.complete() ? COMPLETE : UNKNOWN);
        if (!dependencies.complete())
            incomplete(Assessment.CAPTURE, dependencies.counts()).log(INCOMPLETE_ASSESSMENT);
    }

    private record Dependencies(
            boolean complete,
            List<JsonNode> identities,
            List<JsonNode> repositories,
            List<JsonNode> sourceJobIds,
            DependencyCounts counts) {}

    private enum Assessment {
        CAPTURE,
        LATE_READ
    }

    /**
     * What one dependency assessment read. Jobs are the distinct direct source jobs whose rows were copied; receipts
     * are their rows, and one job can own several. A receipt that is not READY and a READY receipt whose dependencies
     * are not COMPLETE are counted apart, never both.
     */
    private record DependencyCounts(
            int sourceJobs,
            int sourceJobsWithoutReceipts,
            int receipts,
            int notReadyReceipts,
            int unvouchedReadyReceipts) {}

    /**
     * The added fields are counts only; the existing logging context remains. This reports what the assessment
     * read, not whether the receipt update that follows still found the attempt's receipt.
     */
    private static LoggingEventBuilder incomplete(Assessment assessment, DependencyCounts counts) {
        return log.atInfo()
                .addKeyValue("evidence.assessment", assessment.name())
                .addKeyValue("evidence.sourceJobs", counts.sourceJobs())
                .addKeyValue("evidence.sourceJobsWithoutReceipts", counts.sourceJobsWithoutReceipts())
                .addKeyValue("evidence.receipts", counts.receipts())
                .addKeyValue("evidence.notReadyReceipts", counts.notReadyReceipts())
                .addKeyValue("evidence.unvouchedReadyReceipts", counts.unvouchedReadyReceipts());
    }

    private Dependencies dependencies(JdbcOperations controls, long workspaceId, Collection<UUID> sources) {
        if (sources.isEmpty())
            return new Dependencies(true, List.of(), List.of(), List.of(), new DependencyCounts(0, 0, 0, 0, 0));
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
        return dependencies(sources, receipts);
    }

    private Dependencies dependencies(Collection<UUID> sources, List<SourceReceipt> receipts) {
        var covered = receipts.stream().map(SourceReceipt::jobId).collect(Collectors.toSet());
        boolean complete = covered.containsAll(sources)
                && receipts.stream()
                        .allMatch(receipt -> receipt.state().equals("READY")
                                && COMPLETE.equals(
                                        receipt.payload().path(DEPENDENCIES).asString()));
        var identities = new ArrayList<JsonNode>();
        var repositories = new ArrayList<JsonNode>();
        var sourceJobIds = new ArrayList<JsonNode>();
        int notReady = 0;
        int unvouched = 0;
        for (var receipt : receipts) {
            if (!receipt.state().equals("READY")) notReady++;
            else if (!COMPLETE.equals(receipt.payload().path(DEPENDENCIES).asString())) unvouched++;
            receipt.payload().path("identities").forEach(identities::add);
            receipt.payload().path("repositories").forEach(repositories::add);
            receipt.payload().path("copiedJobs").forEach(sourceJobIds::add);
        }
        var requested = Set.copyOf(sources);
        var counts = new DependencyCounts(
                requested.size(),
                (int) requested.stream().filter(job -> !covered.contains(job)).count(),
                receipts.size(),
                notReady,
                unvouched);
        return new Dependencies(complete, identities, repositories, sourceJobIds, counts);
    }

    /** Keeps known privacy controls even when retained copy authority is incomplete. */
    public HistoryCustody custody(long workspaceId, List<UUID> producingJobs) {
        if (producingJobs.isEmpty()) return new HistoryCustody(false, false, List.of(), List.of(), List.of());
        try (var admission = fence.capture()) {
            var retained = dependencies(admission.jdbc(), workspaceId, producingJobs);
            var identities = retained.identities().stream()
                    .map(identity -> mapper.convertValue(identity, PersonCopyIdentity.class))
                    .toList();
            boolean refused = retained.counts().notReadyReceipts() > 0 || isSuppressed(identities, admission.jdbc());
            var sourceJobIds = new LinkedHashSet<>(producingJobs);
            retained.sourceJobIds().stream()
                    .map(node -> UUID.fromString(node.asString()))
                    .forEach(sourceJobIds::add);
            return new HistoryCustody(
                    retained.complete() && !refused,
                    refused,
                    identities,
                    retained.repositories().stream()
                            .map(JsonNode::longValue)
                            .distinct()
                            .toList(),
                    List.copyOf(sourceJobIds));
        }
    }

    /**
     * The producing jobs whose historical words may be copied now. Missing, incomplete, revoked or suppressed
     * receipts leave the record's facts usable but cannot vouch for its prose. Accepted people are recorded before
     * formatting, while copy admission prevents an erasure from passing between authorization and indexing.
     */
    public Set<UUID> recordVouchedHistoricalJobs(long workspaceId, Collection<UUID> sourceJobs) {
        Set<UUID> selectedJobs = Set.copyOf(sourceJobs);
        if (selectedJobs.isEmpty()) return Set.of();
        ActiveCapture capture = ACTIVE.get();
        if (capture != null)
            return recordVouchedHistoricalJobs(
                    workspaceId, selectedJobs, capture.admission().jdbc());
        JdbcOperations lateRead = LATE_READ_CONTROLS.get();
        if (lateRead != null) return recordVouchedHistoricalJobs(workspaceId, selectedJobs, lateRead);
        try (var admission = fence.capture()) {
            return recordVouchedHistoricalJobs(workspaceId, selectedJobs, admission.jdbc());
        }
    }

    private Set<UUID> recordVouchedHistoricalJobs(long workspaceId, Set<UUID> sourceJobs, JdbcOperations controls) {
        var receipts = controls.query(
                "SELECT job_id,state,payload::text FROM person_evidence_copy WHERE workspace_id=? AND job_id=ANY(?)",
                (rs, row) -> new SourceReceipt(
                        Objects.requireNonNull(rs.getObject(1, UUID.class)),
                        Objects.requireNonNull(rs.getString(2)),
                        mapper.readTree(Objects.requireNonNull(rs.getString(3)))),
                workspaceId,
                new SqlArrayValue("uuid", sourceJobs.toArray()));
        var byJob = receipts.stream().collect(Collectors.groupingBy(SourceReceipt::jobId));
        var complete = new HashMap<UUID, Dependencies>();
        var people = new HashMap<UUID, List<PersonCopyIdentity>>();
        for (UUID sourceJob : sourceJobs) {
            var rows = byJob.getOrDefault(sourceJob, List.of());
            var dependency = dependencies(Set.of(sourceJob), rows);
            if (!dependency.complete()) continue;
            try {
                if (rows.stream()
                        .anyMatch(row -> !row.payload().path("identities").isArray())) continue;
                var identities = dependency.identities().stream()
                        .map(identity -> {
                            if (!identity.isObject()) throw new IllegalArgumentException("Invalid copy identity");
                            return mapper.convertValue(identity, PersonCopyIdentity.class);
                        })
                        .toList();
                complete.put(sourceJob, dependency);
                people.put(sourceJob, identities);
            } catch (RuntimeException malformed) {
                // A malformed producing receipt cannot authorize copied words.
            }
        }
        var blocked = suppressionControls(
                people.values().stream().flatMap(List::stream).toList(), controls);
        var vouched = new HashSet<UUID>();
        people.forEach((sourceJob, identities) -> {
            if (isSuppressed(identities, blocked)) return;
            identities.forEach(recorder::recordIdentity);
            Objects.requireNonNull(complete.get(sourceJob))
                    .repositories()
                    .forEach(repository -> recorder.recordRepository(repository.asLong()));
            recorder.recordCopiedJob(sourceJob);
            vouched.add(sourceJob);
        });
        return Set.copyOf(vouched);
    }

    private record SourceReceipt(UUID jobId, String state, JsonNode payload) {}

    /**
     * A read that the runtime receives after its capture was frozen. Copy admission is held from before the read
     * selects its rows until their people and source receipts are unioned into the attempt's READY receipt, so an
     * erasure either precedes the read or finds this job. The current receipt must still be READY. Historical words
     * without complete, unsuppressed provenance are refused before the result returns; no receipt is revived.
     */
    public <T> T indexLateRead(
            UUID jobId, long workspaceId, int attempt, Supplier<T> read, Function<T, List<HistoryCopy>> copiedRecords) {
        try (var admission = fence.capture()) {
            JdbcOperations previous = LATE_READ_CONTROLS.get();
            LATE_READ_CONTROLS.set(admission.jdbc());
            try {
                T result = read.get();
                if (!vouchesForLiveAttempt(jobId, workspaceId, attempt))
                    throw new CopyUnavailableException("This review's copy authority is unavailable");
                var records = copiedRecords.apply(result);
                if (records.stream().anyMatch(copy -> !copy.complete()))
                    throw new CopyUnavailableException("Historical words have no complete copy authority");
                var controls = admission.jdbc();
                var sources = records.stream()
                        .flatMap(copy -> copy.sourceJobs().stream())
                        .collect(Collectors.toSet());
                sources.remove(jobId);
                var copiedIdentities = records.stream()
                        .flatMap(copy -> copy.identities().stream())
                        .distinct()
                        .toList();
                var identities =
                        copiedIdentities.stream().map(mapper::valueToTree).toList();
                var copiedRepositories = records.stream()
                        .flatMap(copy -> copy.repositories().stream())
                        .distinct()
                        .toList();
                if (isSuppressed(copiedIdentities, controls))
                    throw new CopyUnavailableException("Historical review copy authority is unavailable");
                // This read is complete; the update still preserves an earlier UNKNOWN capture.
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
                        WHERE job_id=? AND workspace_id=? AND state='READY'
                          AND payload->>'attempt'=?
                        """,
                        mapper.writeValueAsString(identities),
                        mapper.writeValueAsString(copiedRepositories),
                        mapper.writeValueAsString(sources.stream().sorted().toList()),
                        true,
                        jobId,
                        workspaceId,
                        Integer.toString(attempt));
                return result;
            } finally {
                if (previous == null) LATE_READ_CONTROLS.remove();
                else LATE_READ_CONTROLS.set(previous);
            }
        }
    }

    /** Closed custody refusal, distinct from a temporarily unavailable source or database transport failure. */
    public static class CopyUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CopyUnavailableException(String reason) {
            super(reason);
        }

        public CopyUnavailableException(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    /** Whether a server may keep the attempt's debug transcript. */
    public enum TraceCustody {
        /** The attempt's READY receipt indexes every person its copied rows may name. */
        INDEXED,
        /** The receipt is READY, but a copied row depends on a receipt that cannot vouch for its own copies. */
        UNINDEXED_DEPENDENCY,
        /** No READY receipt exists for the attempt: it was erased or purged. */
        NOT_HELD
    }

    public TraceCustody traceCustody(UUID jobId, long workspaceId, int attempt) {
        var dependencies = jdbc.queryForList("""
                SELECT COALESCE(payload->>'dependencies','') FROM person_evidence_copy
                WHERE job_id=? AND workspace_id=? AND state='READY' AND payload->>'attempt'=?
                """, String.class, jobId, workspaceId, Integer.toString(attempt));
        if (dependencies.size() != 1) return TraceCustody.NOT_HELD;
        return COMPLETE.equals(dependencies.getFirst()) ? TraceCustody.INDEXED : TraceCustody.UNINDEXED_DEPENDENCY;
    }

    /**
     * The receipt of an attempt's capture, only if it is ready and indexes every person its copied rows may name: the
     * one receipt that can vouch for inputs retained from that attempt.
     */
    public Optional<UUID> completeReceipt(UUID jobId, long workspaceId, int attempt) {
        return jdbc
                .query(
                        """
                        SELECT id FROM person_evidence_copy
                        WHERE job_id=? AND workspace_id=? AND state='READY'
                          AND payload->>'attempt'=? AND payload->>'dependencies'=?
                        """,
                        (rs, row) -> Objects.requireNonNull(rs.getObject(1, UUID.class)),
                        jobId,
                        workspaceId,
                        Integer.toString(attempt),
                        COMPLETE)
                .stream()
                .findFirst();
    }

    /**
     * Holds copy admission until the caller's READ_COMMITTED transaction commits, then answers whether the original
     * receipt still vouches for inputs read from its attempt's folder: READY, COMPLETE, that attempt's, and naming no
     * person erased since. Called before the caller takes any work or job lock, as erasure orders them. Bytes read
     * before an erasure began are kept only if this answers true, and an erasure that begins after waits for the
     * commit and then finds the job through this receipt. The fence's own hold requires that transaction.
     */
    public boolean holdVouchingReceipt(UUID copyId, UUID jobId, long workspaceId, int attempt) {
        holdCopyAdmission();
        return vouchingReceipt(jdbc, copyId, jobId, workspaceId, attempt) != null;
    }

    /** Holds the existing copy/erasure fence before a recovery transaction takes any job lock. */
    public void holdCopyAdmission() {
        fence.holdForCapture();
    }

    /**
     * The still-owning admitting attempt may finish from the capture it received. Incomplete copied-job lineage
     * prevents retaining or recovering its inputs, not this live writer. Its READY receipt must still be unique
     * and name no suppressed identity. The caller holds copy admission before its job lock.
     */
    public boolean vouchesForLiveAttempt(UUID jobId, long workspaceId, int attempt) {
        var payloads = jdbc.query(
                """
                SELECT payload::text FROM person_evidence_copy
                WHERE job_id=? AND workspace_id=? AND state='READY' AND payload->>'attempt'=?
                """,
                (rs, row) -> mapper.readTree(Objects.requireNonNull(rs.getString(1))),
                jobId,
                workspaceId,
                Integer.toString(attempt));
        if (payloads.size() != 1 || !payloads.getFirst().path("identities").isArray()) return false;
        List<PersonCopyIdentity> identities;
        try {
            identities =
                    List.of(mapper.convertValue(payloads.getFirst().path("identities"), PersonCopyIdentity[].class));
        } catch (RuntimeException malformedIdentities) {
            return false;
        }
        return !isSuppressed(identities, jdbc);
    }

    /** A durable original receipt can vouch for retained inputs, whichever server wrote them. */
    public boolean vouchesForOriginalCopy(UUID copyId, UUID jobId, long workspaceId, int attempt) {
        return vouchingReceipt(jdbc, copyId, jobId, workspaceId, attempt) != null;
    }

    /** The original receipt's payload, only while it still vouches for its attempt's retained inputs. */
    private @Nullable JsonNode vouchingReceipt(
            JdbcOperations controls, UUID copyId, UUID jobId, long workspaceId, int attempt) {
        var payloads = controls.query(
                """
                SELECT payload::text FROM person_evidence_copy
                WHERE id=CAST(? AS uuid) AND job_id=? AND workspace_id=? AND state='READY'
                  AND payload->>'attempt'=? AND payload->>'dependencies'=?
                """,
                (rs, row) -> mapper.readTree(Objects.requireNonNull(rs.getString(1))),
                copyId.toString(),
                jobId,
                workspaceId,
                Integer.toString(attempt),
                COMPLETE);
        if (payloads.size() != 1) return null;
        JsonNode original = payloads.getFirst();
        var identities = List.of(mapper.convertValue(original.path("identities"), PersonCopyIdentity[].class));
        return isSuppressed(identities, controls) ? null : original;
    }

    /**
     * Makes the active capture copy the people of an earlier attempt's receipt, whose retained inputs it stages. Under
     * the capture's erasure fence, so a person erased meanwhile refuses the attempt before any runtime receives them;
     * a receipt erased, purged, no longer complete or not the admitting attempt's refuses it too.
     */
    public void inheritReceipt(ReviewJob job, UUID originalCopyId, int originalAttempt) {
        ActiveCapture capture = requireCapture(job);
        JsonNode original = vouchingReceipt(
                capture.admission().jdbc(), originalCopyId, capture.jobId(), capture.workspaceId(), originalAttempt);
        if (original == null) throw new IllegalStateException("The retained inputs' receipt no longer vouches");
        var identities = new LinkedHashSet<JsonNode>();
        capture.receipt().path(INHERITED_IDENTITIES).forEach(identities::add);
        original.path("identities").forEach(identities::add);
        capture.receipt().set(INHERITED_IDENTITIES, mapper.valueToTree(identities));
        var repositories = new LinkedHashSet<JsonNode>();
        capture.receipt().path(INHERITED_REPOSITORIES).forEach(repositories::add);
        original.path("repositories").forEach(repositories::add);
        capture.receipt().set(INHERITED_REPOSITORIES, mapper.valueToTree(repositories));
        capture.receipt().put("inheritedCopy", originalCopyId.toString());
    }

    private ActiveCapture requireCapture(ReviewJob job) {
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
        String payload = mapper.writeValueAsString(capture.receipt());
        var controls = capture.admission().jdbc();
        int changed = controls.update("""
            UPDATE person_evidence_copy SET payload=CAST(? AS jsonb)
            WHERE id=? AND job_id=? AND workspace_id=? AND state='CAPTURING'
              AND payload IS DISTINCT FROM CAST(? AS jsonb)
            """, payload, capture.copyId(), capture.jobId(), capture.workspaceId(), payload);
        // Copied-job callbacks may leave this progress receipt unchanged; they still require capture ownership.
        if (changed == 0
                && Boolean.TRUE.equals(controls.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM person_evidence_copy
                            WHERE id=? AND job_id=? AND workspace_id=? AND state='CAPTURING')
                        """, Boolean.class, capture.copyId(), capture.jobId(), capture.workspaceId()))) return;
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
        return isSuppressed(identities, suppressionControls(identities, controls));
    }

    private List<PersonCopyIdentity> suppressionControls(List<PersonCopyIdentity> identities, JdbcOperations controls) {
        if (identities.isEmpty()) return List.of();
        return controls.query(
                """
                SELECT DISTINCT p.type,p.server_url,s.provider_user_key,NULLIF(s.team_key,'')
                FROM identity_restriction s JOIN identity_provider p ON p.id=s.provider_id
                WHERE s.kind='ERASED' AND s.provider_user_key=ANY(?)
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
    }

    private static boolean isSuppressed(List<PersonCopyIdentity> identities, List<PersonCopyIdentity> blocked) {
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
                    public void prepareErasure(PersonDataSelection selection) {
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
                    public void prepareErasure(PersonDataSelection selection) {}

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
     * Cancels the selected copies' running jobs, marks the copies erased and deletes their jobs' attempt inputs. From
     * the commit on, no attempt can admit or retain the copies, and each server removes its folders of their jobs
     * in a later reconcile pass once leases are released and cleanup succeeds.
     */
    private void requestRemoval(PersonDataSelection selection) {
        var jobs = new LinkedHashMap<UUID, Long>();
        for (var key : selection.rows())
            jobs.putIfAbsent(
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
        for (var key : selection.rows())
            jdbc.update(
                    """
                    UPDATE person_evidence_copy SET state='ERASED'
                    WHERE id=CAST(? AS uuid) AND job_id=? AND workspace_id=? AND state<>'ERASED'
                    """,
                    key.columns().get("copy_id"),
                    UUID.fromString(key.columns().get("job_id")),
                    Long.parseLong(key.columns().get("workspace_id")));
        if (!jobs.isEmpty()) notifyErased();
    }

    /** Sends the wake-up in the caller's transaction, so PostgreSQL delivers it only if the erasure commits. */
    private void notifyErased() {
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_notify(?, '')) sent", Integer.class, EvidenceErasureNotifications.CHANNEL);
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
     * Deletes each entry of the job's folder whose attempt has no live copy: attempt folders, their staging folders,
     * end markers and transcripts. An entry that names no attempt goes too. The emptied job folder goes last.
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

    private void deleteFolder(long workspaceId, UUID jobId) throws IOException {
        var folder = layout.jobsRoot().resolve(Long.toString(workspaceId)).resolve(jobId.toString());
        FileUtils.deleteDirectory(folder.toFile());
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Evidence folder remains");
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

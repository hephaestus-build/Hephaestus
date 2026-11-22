package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopy;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The write path of review attempts' receipts. A capture opens its attempt's receipt under copy admission and the
 * attempt's evidence folder lease, records the people and repositories that the attempt copies, and then publishes
 * the receipt or discards the copy. Inherited receipts and late reads add their people to the attempt's receipt. Only
 * this class holds the thread's active capture and its late-read controls.
 */
@Component
public class EvidenceCaptures {
    private static final ThreadLocal<JdbcOperations> LATE_READ_CONTROLS = new ThreadLocal<>();

    private static final Logger log = LoggerFactory.getLogger(EvidenceCaptures.class);

    private final FabricLayout layout;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PersonDataCopyRecorder recorder;
    private final PersonDataCopyFence fence;
    private final EvidenceReceipts receipts;
    private static final ThreadLocal<ActiveCapture> ACTIVE = new ThreadLocal<>();
    /** Who an attempt staged from an earlier attempt's retained inputs copies: that receipt's people, carried over. */
    private static final String INHERITED_IDENTITIES = "inheritedIdentities";

    private static final String INHERITED_REPOSITORIES = "inheritedRepositories";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String INCOMPLETE_ASSESSMENT = "Copied evidence dependency assessment is incomplete";

    public EvidenceCaptures(
            FabricLayout layout,
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PersonDataCopyRecorder recorder,
            PersonDataCopyFence fence,
            EvidenceReceipts receipts) {
        this.layout = layout;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.recorder = recorder;
        this.fence = fence;
        this.receipts = receipts;
    }

    public void beginCapture(ReviewJob job) {
        if (ACTIVE.get() != null) throw new IllegalStateException("An evidence capture is already active");
        var admission = layout.spool().capture(job.getWorkspaceId(), fence::capture);
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
                if (receipts.isSuppressed(
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
        var dependencies = receipts.dependencies(capture.admission().jdbc(), capture.workspaceId(), sources);
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
        capture.receipt()
                .put(EvidenceReceipts.DEPENDENCIES, dependencies.complete() ? EvidenceReceipts.COMPLETE : UNKNOWN);
        if (!dependencies.complete())
            incomplete(Assessment.CAPTURE, dependencies.counts()).log(INCOMPLETE_ASSESSMENT);
    }

    private enum Assessment {
        CAPTURE,
        LATE_READ
    }

    /**
     * The added fields are counts only; the existing logging context remains. This reports what the assessment
     * read, not whether the receipt update that follows still found the attempt's receipt.
     */
    private static LoggingEventBuilder incomplete(Assessment assessment, EvidenceReceipts.DependencyCounts counts) {
        return log.atInfo()
                .addKeyValue("evidence.assessment", assessment.name())
                .addKeyValue("evidence.sourceJobs", counts.sourceJobs())
                .addKeyValue("evidence.sourceJobsWithoutReceipts", counts.sourceJobsWithoutReceipts())
                .addKeyValue("evidence.receipts", counts.receipts())
                .addKeyValue("evidence.notReadyReceipts", counts.notReadyReceipts())
                .addKeyValue("evidence.unvouchedReadyReceipts", counts.unvouchedReadyReceipts());
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
                (rs, row) -> new EvidenceReceipts.SourceReceipt(
                        Objects.requireNonNull(rs.getObject(1, UUID.class)),
                        Objects.requireNonNull(rs.getString(2)),
                        mapper.readTree(Objects.requireNonNull(rs.getString(3)))),
                workspaceId,
                new SqlArrayValue("uuid", sourceJobs.toArray()));
        var byJob = receipts.stream().collect(Collectors.groupingBy(EvidenceReceipts.SourceReceipt::jobId));
        var complete = new HashMap<UUID, EvidenceReceipts.Dependencies>();
        var people = new HashMap<UUID, List<PersonCopyIdentity>>();
        for (UUID sourceJob : sourceJobs) {
            var rows = byJob.getOrDefault(sourceJob, List.of());
            var dependency = this.receipts.dependencies(Set.of(sourceJob), rows);
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
        var blocked = this.receipts.suppressionControls(
                people.values().stream().flatMap(List::stream).toList(), controls);
        var vouched = new HashSet<UUID>();
        people.forEach((sourceJob, identities) -> {
            if (EvidenceReceipts.isSuppressed(identities, blocked)) return;
            identities.forEach(recorder::recordIdentity);
            Objects.requireNonNull(complete.get(sourceJob))
                    .repositories()
                    .forEach(repository -> recorder.recordRepository(repository.asLong()));
            recorder.recordCopiedJob(sourceJob);
            vouched.add(sourceJob);
        });
        return Set.copyOf(vouched);
    }

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
                if (!receipts.vouchesForLiveAttempt(jobId, workspaceId, attempt))
                    throw new EvidenceReceipts.CopyUnavailableException("This review's copy authority is unavailable");
                var records = copiedRecords.apply(result);
                if (records.stream().anyMatch(copy -> !copy.complete()))
                    throw new EvidenceReceipts.CopyUnavailableException(
                            "Historical words have no complete copy authority");
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
                if (receipts.isSuppressed(copiedIdentities, controls))
                    throw new EvidenceReceipts.CopyUnavailableException(
                            "Historical review copy authority is unavailable");
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

    /**
     * Makes the active capture copy the people of an earlier attempt's receipt, whose retained inputs it stages. Under
     * the capture's erasure fence, so a person erased meanwhile refuses the attempt before any runtime receives them;
     * a receipt erased, purged, no longer complete or not the admitting attempt's refuses it too.
     */
    public void inheritReceipt(ReviewJob job, UUID originalCopyId, int originalAttempt) {
        ActiveCapture capture = requireCapture(job);
        JsonNode original = receipts.vouchingReceipt(
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

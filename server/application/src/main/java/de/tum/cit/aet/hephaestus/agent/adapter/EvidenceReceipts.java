package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads of the receipts that {@link EvidenceCaptures} writes: whether a receipt still vouches for an attempt's
 * inputs, the custody of copied history, and the copy admission that a reader holds against erasure. It also holds
 * the assessment of copied source jobs' receipts and the check of identities against erasure controls, which
 * captures use too. {@link EvidenceFolderPersonDataCatalog} erases the receipts.
 */
@Component
public class EvidenceReceipts {
    /** Whether a receipt indexes the receipts of every job whose rows its capture copied. */
    static final String DEPENDENCIES = "dependencies";

    static final String COMPLETE = "COMPLETE";

    private final FabricLayout layout;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PersonDataCopyFence fence;

    public EvidenceReceipts(FabricLayout layout, JdbcTemplate jdbc, ObjectMapper mapper, PersonDataCopyFence fence) {
        this.layout = layout;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.fence = fence;
    }

    record Dependencies(
            boolean complete,
            List<JsonNode> identities,
            List<JsonNode> repositories,
            List<JsonNode> sourceJobIds,
            DependencyCounts counts) {}

    /**
     * What one dependency assessment read. Jobs are the distinct direct source jobs whose rows were copied; receipts
     * are their rows, and one job can own several. A receipt that is not READY and a READY receipt whose dependencies
     * are not COMPLETE are counted apart, never both.
     */
    record DependencyCounts(
            int sourceJobs,
            int sourceJobsWithoutReceipts,
            int receipts,
            int notReadyReceipts,
            int unvouchedReadyReceipts) {}

    Dependencies dependencies(JdbcOperations controls, long workspaceId, Collection<UUID> sources) {
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

    Dependencies dependencies(Collection<UUID> sources, List<SourceReceipt> receipts) {
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

    record SourceReceipt(UUID jobId, String state, JsonNode payload) {}

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

    /** Whether this attempt began copying, even if its server died before publishing the receipt. */
    public boolean hasAttemptCapture(ReviewJob job) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (SELECT 1 FROM person_evidence_copy
                WHERE job_id=? AND workspace_id=? AND payload->>'attempt'=?)
                """, Boolean.class, job.getId(), job.getWorkspaceId(), Integer.toString(job.getAttempt())));
    }

    /** Restores only a live capture, while erasure cannot revoke its receipt or remove its recorded bytes. */
    public <T> T withLiveAttempt(ReviewJob job, Supplier<T> restore) {
        try (var admission = layout.spool().capture(job.getWorkspaceId(), fence::capture)) {
            if (!vouchesForLiveAttempt(admission.jdbc(), job.getId(), job.getWorkspaceId(), job.getAttempt()))
                throw new CopyUnavailableException("The attempt's captured inputs are no longer held");
            return restore.get();
        }
    }

    /** Holds the existing copy/erasure fence before a recovery transaction takes any job lock. */
    public void holdCopyAdmission() {
        fence.holdForCapture();
    }

    /**
     * The still-owning admitting attempt may finish from the capture it received. Incomplete copied-job lineage
     * prevents retaining or recovering its inputs, not this live writer. Its READY receipt must still be unique
     * and name no suppressed identity. Admission asks under copy admission, before its job lock. A folder rebuild asks
     * with no lock, and asks again when the rebuild fails.
     */
    public boolean vouchesForLiveAttempt(UUID jobId, long workspaceId, int attempt) {
        return vouchesForLiveAttempt(jdbc, jobId, workspaceId, attempt);
    }

    private boolean vouchesForLiveAttempt(JdbcOperations controls, UUID jobId, long workspaceId, int attempt) {
        var payloads = controls.query(
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
        return !isSuppressed(identities, controls);
    }

    /** A durable original receipt can vouch for retained inputs, whichever server wrote them. */
    public boolean vouchesForOriginalCopy(UUID copyId, UUID jobId, long workspaceId, int attempt) {
        return vouchingReceipt(jdbc, copyId, jobId, workspaceId, attempt) != null;
    }

    /** The original receipt's payload, only while it still vouches for its attempt's retained inputs. */
    @Nullable
    JsonNode vouchingReceipt(JdbcOperations controls, UUID copyId, UUID jobId, long workspaceId, int attempt) {
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

    boolean isSuppressed(List<PersonCopyIdentity> identities, JdbcOperations controls) {
        if (identities.isEmpty()) return false;
        return isSuppressed(identities, suppressionControls(identities, controls));
    }

    List<PersonCopyIdentity> suppressionControls(List<PersonCopyIdentity> identities, JdbcOperations controls) {
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

    static boolean isSuppressed(List<PersonCopyIdentity> identities, List<PersonCopyIdentity> blocked) {
        return blocked.stream()
                .anyMatch(control -> identities.stream()
                        .anyMatch(identity -> control.providerType().equals(identity.providerType())
                                && control.providerOrigin().equals(identity.providerOrigin())
                                && control.subject().equals(identity.subject())
                                && (Objects.equals(control.teamId(), identity.teamId())
                                        || (control.providerType().equals("OUTLINE") && control.teamId() == null))));
    }
}

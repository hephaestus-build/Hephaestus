package de.tum.cit.aet.hephaestus.agent.context;

import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.directory;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceCaptures;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceReceipts;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunAuthority;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Result admission's view of a review attempt's evidence, addressed by the attempt's admission identity or by its job:
 * the copy receipts that it completes, holds, inherits and vouches for, its copy admission and late reads, and the
 * attempt folder that it makes sure exists. The runs that continue an attempt use the same receipts.
 */
@Component
@ConditionalOnServerRole
public class AttemptCopyReceipts {
    private final EvidenceReceipts receipts;
    private final EvidenceCaptures captures;
    private final JobEvidenceFiles folders;
    private final FabricLayout layout;
    private final ReviewJobRepository jobs;
    private final AgentRunAuthority authority;

    public AttemptCopyReceipts(
            EvidenceReceipts receipts,
            EvidenceCaptures captures,
            JobEvidenceFiles folders,
            FabricLayout layout,
            ReviewJobRepository jobs,
            AgentRunAuthority authority) {
        this.receipts = receipts;
        this.captures = captures;
        this.folders = folders;
        this.layout = layout;
        this.jobs = jobs;
        this.authority = authority;
    }

    /**
     * {@link JobEvidenceFiles#ensureAttemptFolder(ReviewJob, Runnable)} for the attempt that {@code identity} names, when it is
     * still its job's attempt and not yet admitted. Admission calls it with no transaction or lock held. An admitted
     * attempt reads no folder there: admission removes it once it commits.
     */
    public void ensureAttemptFolder(ResultAdmissionService.AdmissionIdentity identity) {
        JobEvidenceFiles.requireNoTransaction();
        Path root = directory(
                layout.jobsRoot(), identity.workspaceId(), identity.jobId(), identity.attempt(), identity.runId());
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        jobs.findByIdAndWorkspaceId(identity.jobId(), identity.workspaceId())
                .filter(job ->
                        directory(layout.jobsRoot(), job).equals(root) && !ResultAdmissionService.isAdmitted(job))
                // A rebuild can wait for the folder and then fetch from the provider: it rechecks the run's authority
                // once it holds the folder, before it reads (ADR 0134).
                .ifPresent(job -> folders.ensureAttemptFolder(
                        job,
                        () -> authority.require(
                                new AgentRunFence(
                                        identity.runId(),
                                        identity.workspaceId(),
                                        ReviewRunKind.epoch(identity.attempt())),
                                AuthorityBoundary.CONTEXT_READ)));
    }

    /** See {@link EvidenceReceipts#completeReceipt}. */
    public Optional<UUID> completeReceipt(ResultAdmissionService.AdmissionIdentity identity) {
        return receipts.completeReceipt(identity.jobId(), identity.workspaceId(), identity.attempt());
    }

    /** See {@link EvidenceReceipts#holdCopyAdmission}. */
    public void holdCopyAdmission() {
        receipts.holdCopyAdmission();
    }

    /** See {@link EvidenceReceipts#vouchesForLiveAttempt}. */
    public boolean vouchesForLiveAttempt(ResultAdmissionService.AdmissionIdentity identity) {
        return receipts.vouchesForLiveAttempt(identity.jobId(), identity.workspaceId(), identity.attempt());
    }

    /** See {@link EvidenceReceipts#vouchesForOriginalCopy}. */
    public boolean vouchesForOriginalCopy(UUID copyId, UUID jobId, long workspaceId, int attempt) {
        return receipts.vouchesForOriginalCopy(copyId, jobId, workspaceId, attempt);
    }

    /** See {@link EvidenceReceipts#holdVouchingReceipt}. */
    public boolean holdVouchingReceipt(ResultAdmissionService.AdmissionIdentity identity, UUID copyId) {
        return receipts.holdVouchingReceipt(copyId, identity.jobId(), identity.workspaceId(), identity.attempt());
    }

    /** See {@link EvidenceCaptures#inheritReceipt}. */
    public void inheritPersonCapture(ReviewJob job, UUID originalCopyId, int originalAttempt) {
        captures.inheritReceipt(job, originalCopyId, originalAttempt);
    }

    /** The exact rows a read during runtime returned, by the tables that own them. */
    public record LateCopies(List<HistoryCopy> records) {
        public LateCopies {
            records = List.copyOf(records);
        }
    }

    /** See {@link EvidenceCaptures#indexLateRead}. */
    public <T> T indexLateRead(
            ResultAdmissionService.AdmissionIdentity identity, Supplier<T> read, Function<T, LateCopies> copies) {
        return captures.indexLateRead(
                identity.jobId(),
                identity.workspaceId(),
                identity.attempt(),
                read,
                result -> copies.apply(result).records());
    }
}

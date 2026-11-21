package de.tum.cit.aet.hephaestus.agent.context;

import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.directory;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
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
public class AttemptCopyReceipts {
    private final EvidenceFolderPersonDataCatalog personCopies;
    private final JobEvidenceFiles folders;
    private final FabricLayout layout;
    private final ReviewJobRepository jobs;

    public AttemptCopyReceipts(
            EvidenceFolderPersonDataCatalog personCopies,
            JobEvidenceFiles folders,
            FabricLayout layout,
            ReviewJobRepository jobs) {
        this.personCopies = personCopies;
        this.folders = folders;
        this.layout = layout;
        this.jobs = jobs;
    }

    /**
     * {@link JobEvidenceFiles#ensureAttemptFolder(ReviewJob)} for the attempt that {@code identity} names, when it is
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
                .ifPresent(folders::ensureAttemptFolder);
    }

    /** See {@link EvidenceFolderPersonDataCatalog#completeReceipt}. */
    public Optional<UUID> completeReceipt(ResultAdmissionService.AdmissionIdentity identity) {
        return personCopies.completeReceipt(identity.jobId(), identity.workspaceId(), identity.attempt());
    }

    /** See {@link EvidenceFolderPersonDataCatalog#holdCopyAdmission}. */
    public void holdCopyAdmission() {
        personCopies.holdCopyAdmission();
    }

    /** See {@link EvidenceFolderPersonDataCatalog#vouchesForLiveAttempt}. */
    public boolean vouchesForLiveAttempt(ResultAdmissionService.AdmissionIdentity identity) {
        return personCopies.vouchesForLiveAttempt(identity.jobId(), identity.workspaceId(), identity.attempt());
    }

    /** See {@link EvidenceFolderPersonDataCatalog#vouchesForOriginalCopy}. */
    public boolean vouchesForOriginalCopy(UUID copyId, UUID jobId, long workspaceId, int attempt) {
        return personCopies.vouchesForOriginalCopy(copyId, jobId, workspaceId, attempt);
    }

    /** See {@link EvidenceFolderPersonDataCatalog#holdVouchingReceipt}. */
    public boolean holdVouchingReceipt(ResultAdmissionService.AdmissionIdentity identity, UUID copyId) {
        return personCopies.holdVouchingReceipt(copyId, identity.jobId(), identity.workspaceId(), identity.attempt());
    }

    /** See {@link EvidenceFolderPersonDataCatalog#inheritReceipt}. */
    public void inheritPersonCapture(ReviewJob job, UUID originalCopyId, int originalAttempt) {
        personCopies.inheritReceipt(job, originalCopyId, originalAttempt);
    }

    /** The exact rows a read during runtime returned, by the tables that own them. */
    public record LateCopies(List<HistoryCopy> records) {
        public LateCopies {
            records = List.copyOf(records);
        }
    }

    /** See {@link EvidenceFolderPersonDataCatalog#indexLateRead}. */
    public <T> T indexLateRead(
            ResultAdmissionService.AdmissionIdentity identity, Supplier<T> read, Function<T, LateCopies> copies) {
        return personCopies.indexLateRead(
                identity.jobId(),
                identity.workspaceId(),
                identity.attempt(),
                read,
                result -> copies.apply(result).records());
    }
}

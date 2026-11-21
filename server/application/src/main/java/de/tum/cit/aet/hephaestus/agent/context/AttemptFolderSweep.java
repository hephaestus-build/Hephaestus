package de.tum.cit.aet.hephaestus.agent.context;

import static de.tum.cit.aet.hephaestus.agent.context.AttemptTranscripts.TRACE_RECORD;
import static de.tum.cit.aet.hephaestus.agent.context.AttemptTranscripts.TRACE_SUFFIX;
import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.delete;
import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.directory;
import static de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles.TRACE_RETENTION;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderLease;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The removal of the attempt folders and transcripts that no attempt reads, for {@link JobEvidenceFiles}: the sweep of
 * the jobs root, the removal of an attempt's folder when its inputs are released, and the removal of empty job
 * folders. The folders of a job with an erased copy are erasure's.
 */
final class AttemptFolderSweep {
    private static final Logger log = LoggerFactory.getLogger(AttemptFolderSweep.class);

    private static final JsonMapper TRACE_RECORDS = new JsonMapper();

    private final FabricLayout layout;
    private final ReviewJobRepository jobs;
    private final Clock clock;
    private final EvidenceFolderPersonDataCatalog personCopies;

    AttemptFolderSweep(
            FabricLayout layout, ReviewJobRepository jobs, Clock clock, EvidenceFolderPersonDataCatalog personCopies) {
        this.layout = layout;
        this.jobs = jobs;
        this.clock = clock;
        this.personCopies = personCopies;
    }

    /**
     * Removes the folder, with its staging remainder, if no attempt reads it: under the runtime's shared
     * lease, one removal at a time with admission, or under an exclusive lease of its own. Each claim names a new folder
     * (its attempt counts the run's leases), so a folder that no attempt reads never becomes read; the decision is
     * still made from the job as it is recorded under the lease. The folder of a job with an erased copy is erasure's
     * to remove and count.
     *
     * @return false when another holder keeps the lease to itself, and the folder stays for a later pass
     */
    boolean retire(Path root) throws IOException {
        Path jobDirectory = Objects.requireNonNull(root.getParent());
        Path workspaceDirectory = Objects.requireNonNull(jobDirectory.getParent());
        long workspaceId = Long.parseLong(
                Objects.requireNonNull(workspaceDirectory.getFileName()).toString());
        UUID jobId = UUID.fromString(
                Objects.requireNonNull(jobDirectory.getFileName()).toString());
        return EvidenceFolderLease.removeAsOwner(layout.root(), workspaceId, jobId, () -> {
            if (!present(root)) return;
            if (!personCopies.erasedJobs(Map.of(jobId, workspaceId)).isEmpty()) return;
            var job = recordedJob(root);
            if (job.isEmpty() || !readsFolder(root, job.get())) deleteAttempt(root);
        });
    }

    /**
     * Removes every attempt folder that no attempt reads: the folders of ended, admitted and superseded attempts, and
     * of deleted jobs. Only the running attempt of its job reads its folder; a retry or a composition takes a new one.
     * The folders of a job with an erased copy are erasure's: the same reconcile pass removes and counts them.
     *
     * @return the folders that remain although no attempt reads them, because their lease is held or their removal
     *     failed, and the expired transcripts that could not be removed: the reconcile counts them, and a later pass
     *     retries them
     */
    int cleanEndedAttempts() {
        int remaining = layout.spool().reconcile();
        if (!Files.isDirectory(layout.jobsRoot())) return remaining;
        try (var paths = Files.walk(layout.jobsRoot(), 3)) {
            var roots = new HashSet<Path>();
            var traces = new HashSet<Path>();
            paths.filter(path -> layout.jobsRoot().relativize(path).getNameCount() == 3)
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        if (name.endsWith(TRACE_SUFFIX) || name.contains(TRACE_SUFFIX + ".preparing-")) {
                            traces.add(path);
                        } else if (name.startsWith(".") && name.contains(".preparing-")) {
                            roots.add(path.resolveSibling(name.substring(1, name.indexOf(".preparing-"))));
                        } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            roots.add(path);
                        }
                    });
            // Erasure removes and counts the folders of a job with an erased copy; this sweep leaves them to it.
            var erased = erasedJobs(roots, traces);
            for (Path root : roots) {
                if (!root.getFileName().toString().matches("[0-9]+-[0-9a-f]{64}")) continue;
                if (ofErasedJob(erased, root)) continue;
                // A capture holds this lease until its inputs are released, after retiring its own folder; erasure
                // and the other sweep take it too. Whoever holds it owns the job's folders, and a later pass retries.
                Optional<EvidenceFolderLease> acquired;
                try {
                    acquired = leaseJob(root);
                    // The lease is the job's, not the folder's: a later run of the job can hold it while a folder that
                    // no attempt reads waits. This server removes such a folder under its runtime's shared lease; a
                    // folder that it cannot remove stays and counts.
                    if (acquired.isEmpty()) {
                        var holder = recordedJob(root);
                        if ((holder.isEmpty() || !readsFolder(root, holder.get())) && !retire(root)) remaining++;
                        continue;
                    }
                } catch (RuntimeException | IOException exception) {
                    log.warn("Could not clean attempt folder {}", root, exception);
                    remaining++;
                    continue;
                }
                var lease = acquired.get();
                try (lease) {
                    // The holder before this one may have removed it; only a removal of our own is reported.
                    if (!present(root)) continue;
                    var job = recordedJob(root);
                    if (job.isPresent() && readsFolder(root, job.get())) continue;
                    deleteAttempt(root);
                    // An ended job's file and text inventory goes with its folder; a running one still reads it.
                    if (job.isPresent() && job.get().getStatus() != ReviewJobStatus.RUNNING) {
                        ReviewJob ended = job.get();
                        jobs.expireCaptureInventory(
                                ended.getId(), ended.getWorkspaceId(), ended.getAttempt(), ended.getRunId());
                    }
                } catch (RuntimeException | IOException exception) {
                    log.warn("Could not clean attempt folder {}", root, exception);
                    remaining++;
                }
            }
            for (Path trace : traces) if (!ofErasedJob(erased, trace) && !cleanTrace(trace)) remaining++;
            removeEmptyJobFolders(erased);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return remaining;
    }

    /** The jobs of these attempt folders and transcripts that have an erased copy. */
    private Set<UUID> erasedJobs(Set<Path> roots, Set<Path> traces) {
        var jobs = new HashMap<UUID, Long>();
        for (Path entry : roots) addJob(jobs, entry);
        for (Path entry : traces) addJob(jobs, entry);
        return personCopies.erasedJobs(jobs);
    }

    private static void addJob(Map<UUID, Long> jobs, Path entry) {
        Path jobDirectory = Objects.requireNonNull(entry.getParent());
        Path workspaceDirectory = Objects.requireNonNull(jobDirectory.getParent());
        try {
            jobs.put(
                    UUID.fromString(
                            Objects.requireNonNull(jobDirectory.getFileName()).toString()),
                    Long.parseLong(Objects.requireNonNull(workspaceDirectory.getFileName())
                            .toString()));
        } catch (IllegalArgumentException notAJobFolder) {
            // Not a job's folder: the sweep skips its entries by their names.
        }
    }

    private static boolean ofErasedJob(Set<UUID> erased, Path entry) {
        try {
            return erased.contains(UUID.fromString(Objects.requireNonNull(
                            Objects.requireNonNull(entry.getParent()).getFileName())
                    .toString()));
        } catch (IllegalArgumentException notAJobFolder) {
            return false;
        }
    }

    /**
     * Removes each job folder that holds nothing more: no attempt, staging copy or transcript. A capture holds the job's
     * lease while it creates the folder, so the folder is removed only under that lease.
     */
    private void removeEmptyJobFolders(Set<UUID> erased) throws IOException {
        try (var workspaces = Files.list(layout.jobsRoot())) {
            for (Path workspace : workspaces.toList()) {
                if (!workspace.getFileName().toString().matches("[0-9]+")
                        || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var jobFolders = Files.list(workspace)) {
                    for (Path jobFolder : jobFolders.toList()) {
                        if (!isEmptyDirectory(jobFolder)) continue;
                        UUID jobId;
                        try {
                            jobId = UUID.fromString(jobFolder.getFileName().toString());
                        } catch (IllegalArgumentException notAJobFolder) {
                            continue;
                        }
                        if (erased.contains(jobId)) continue;
                        var acquired = EvidenceFolderLease.tryAcquire(
                                layout.root(),
                                Long.parseLong(workspace.getFileName().toString()),
                                jobId);
                        if (acquired.isEmpty()) continue;
                        var lease = acquired.get();
                        try (lease) {
                            if (isEmptyDirectory(jobFolder)) Files.deleteIfExists(jobFolder);
                        } catch (DirectoryNotEmptyException written) {
                            // A capture wrote into it after the check; the next pass sees what it holds.
                        }
                    }
                }
            }
        }
    }

    private static boolean isEmptyDirectory(Path folder) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var entries = Files.list(folder)) {
            return entries.findAny().isEmpty();
        }
    }

    /**
     * Whether the folder is the one that the job's running attempt reads. A composition reads the folder of its own
     * run; the folder of the run that measured the review is then another run's, and the next sweep removes it.
     */
    private boolean readsFolder(Path root, ReviewJob job) {
        return matches(root, job) && job.getStatus() == ReviewJobStatus.RUNNING;
    }

    /**
     * A transcript outlives its attempt, its admitted inputs and a restart, whatever the job's status, until its day
     * is over, or until the earlier expiry its record states. A staging copy left by a stopped writer goes at once: the
     * runtime that wrote it no longer holds the job. The first pass after the expiry that gets the job's lease removes
     * it, so the removal lags the expiry by up to the sweep interval or a lease holder's work.
     */
    private boolean cleanTrace(Path trace) {
        try {
            var acquired = leaseJob(trace);
            if (acquired.isEmpty()) return true;
            var lease = acquired.get();
            try (lease) {
                if (!Files.exists(trace, LinkOption.NOFOLLOW_LINKS)) return true;
                Path record = trace.resolve(TRACE_RECORD);
                if (trace.getFileName().toString().startsWith(".")
                        || !Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)
                        || !keptUntil(record).isAfter(clock.instant())) {
                    delete(trace);
                }
            }
            return true;
        } catch (RuntimeException | IOException exception) {
            log.warn("Could not clean review transcript {}", trace, exception);
            return false;
        }
    }

    /** The earlier of the record's day and the expiry it states; a record that states none readably keeps its day. */
    private static Instant keptUntil(Path record) throws IOException {
        Instant day = Files.getLastModifiedTime(record, LinkOption.NOFOLLOW_LINKS)
                .toInstant()
                .plus(TRACE_RETENTION);
        Instant stated;
        try {
            stated = Instant.parse(TRACE_RECORDS
                    .readTree(Files.readAllBytes(record))
                    .path("expiresAt")
                    .asString(""));
        } catch (RuntimeException unreadable) {
            return day;
        }
        return stated.isBefore(day) ? stated : day;
    }

    private Optional<EvidenceFolderLease> leaseJob(Path root) {
        Path jobDirectory = Objects.requireNonNull(root.getParent());
        Path workspaceDirectory = Objects.requireNonNull(jobDirectory.getParent());
        return EvidenceFolderLease.tryAcquire(
                layout.root(),
                Long.parseLong(
                        Objects.requireNonNull(workspaceDirectory.getFileName()).toString()),
                UUID.fromString(
                        Objects.requireNonNull(jobDirectory.getFileName()).toString()));
    }

    /** Whether anything of the attempt remains: its folder or a staging folder. */
    private static boolean present(Path root) throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true;
        try (var siblings = Files.list(root.getParent())) {
            return siblings.anyMatch(
                    path -> path.getFileName().toString().startsWith("." + root.getFileName() + ".preparing-"));
        } catch (NoSuchFileException jobRemoved) {
            // Erasure removes the whole job folder; nothing of this attempt is left.
            return false;
        }
    }

    private Optional<ReviewJob> recordedJob(Path root) {
        Path jobDirectory = Objects.requireNonNull(root.getParent());
        Path workspaceDirectory = Objects.requireNonNull(jobDirectory.getParent());
        return jobs.findByIdAndWorkspaceId(
                UUID.fromString(
                        Objects.requireNonNull(jobDirectory.getFileName()).toString()),
                Long.parseLong(
                        Objects.requireNonNull(workspaceDirectory.getFileName()).toString()));
    }

    private boolean matches(Path root, ReviewJob job) {
        return directory(layout.jobsRoot(), job).equals(root);
    }

    /**
     * Removes an admitted attempt's folder, with its staging remainder, under the runtime's shared lease or a new
     * exclusive one. A sweep or an erasure that holds the job exclusively removes admitted bytes itself.
     */
    void discardAttempt(long workspaceId, UUID jobId, Path root) throws IOException {
        EvidenceFolderLease.removeAsOwner(layout.root(), workspaceId, jobId, () -> deleteAttempt(root));
    }

    private static void deleteAttempt(Path root) throws IOException {
        delete(root);
        try (var siblings = Files.list(root.getParent())) {
            for (Path staging : siblings.filter(
                            path -> path.getFileName().toString().startsWith("." + root.getFileName() + ".preparing-"))
                    .toList()) delete(staging);
        }
    }
}

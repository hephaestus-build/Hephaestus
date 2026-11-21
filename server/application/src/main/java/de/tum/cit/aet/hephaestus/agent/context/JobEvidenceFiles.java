package de.tum.cit.aet.hephaestus.agent.context;

import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.delete;
import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.directory;
import static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolders.safePath;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderLease;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxResultListener;
import de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.evidence.capture.CheckoutKey;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JobEvidenceFiles implements SandboxResultListener {
    private static final Logger log = LoggerFactory.getLogger(JobEvidenceFiles.class);

    /** The most bytes of a checkout that a search proof reads: {@code PROOF_BYTES} in {@code pi-verified-search.ts}. */
    static final long SEARCH_PROOF_BYTES = 512L * 1024 * 1024;

    /** The capture's checkout digest of each staged directory, by its target ({@link #checkoutDigests}). */
    public static final String CHECKOUTS = "checkouts";

    /** The capture's {@link CheckoutKey} of each staged checkout, by its target: how another server builds it again. */
    public static final String CHECKOUT_KEYS = "checkoutKeys";

    private static final JsonMapper CHECKOUT_KEY_READER = new JsonMapper();

    /** How long a rebuild waits for a folder lease that a sweep or a runtime holds. */
    private static final Duration REBUILD_LEASE_WAIT = Duration.ofSeconds(10);

    private static final Duration REBUILD_LEASE_POLL = Duration.ofMillis(50);

    /** One rebuild for each folder in this JVM: a second caller waits for the first and then finds the folder. */
    private final ConcurrentMap<Path, ReentrantLock> rebuilds = new ConcurrentHashMap<>();

    /** How long an attempt's debug transcript stays for next-day investigation. */
    public static final Duration TRACE_RETENTION = Duration.ofHours(24);

    /**
     * The field of a job's capture record that states when the occasion record its inputs copy expires. A transcript
     * that may repeat those words is kept no longer; the record's {@code expiresAt} states that earlier deadline.
     */
    public static final String COPIES_EXPIRE_AT = "copiesExpireAt";

    public static final long TRACE_BYTE_BUDGET = 8L * 1024 * 1024;

    private final FabricLayout layout;
    private final ReviewJobRepository jobs;
    private final Clock clock;
    private final EvidenceFolderPersonDataCatalog personCopies;
    private final AttemptInputs attemptInputs;
    private final AttemptTranscripts transcripts;
    private final AttemptFolderSweep sweep;

    public JobEvidenceFiles(
            FabricLayout layout,
            ReviewJobRepository jobs,
            Clock clock,
            EvidenceFolderPersonDataCatalog personCopies,
            AttemptInputs attemptInputs) {
        this.layout = layout;
        this.jobs = jobs;
        this.clock = clock;
        this.personCopies = personCopies;
        this.attemptInputs = attemptInputs;
        this.transcripts = new AttemptTranscripts(layout, jobs, clock, personCopies);
        this.sweep = new AttemptFolderSweep(layout, jobs, clock, personCopies);
    }

    /** The durable preparation checkpoint; the owning protocol is in the agent-runs guide. */
    public enum Preparation {
        NEW,
        INTERRUPTED,
        PUBLISHED,
        UNAVAILABLE
    }

    /** Called under the preparation cluster lock, before any provider read or new capture. */
    public Preparation preparation(ReviewJob job) {
        JsonNode capture = job.getCapture();
        boolean published = capture != null
                && capture.path("attempt").asInt(-1) == job.getAttempt()
                && job.getRunId().toString().equals(capture.path("runId").asString(null));
        boolean recorded = !attemptInputs.list(job).isEmpty();
        if (published && capture != null)
            return recorded
                            && !expired(capture)
                            && personCopies.vouchesForLiveAttempt(job.getId(), job.getWorkspaceId(), job.getAttempt())
                    ? Preparation.PUBLISHED
                    : Preparation.UNAVAILABLE;
        return recorded || personCopies.hasAttemptCapture(job) ? Preparation.INTERRUPTED : Preparation.NEW;
    }

    private boolean expired(JsonNode capture) {
        return capture.path(COPIES_EXPIRE_AT).isString()
                && !Instant.parse(capture.path(COPIES_EXPIRE_AT).asString()).isAfter(clock.instant());
    }

    /** Reuses exactly the frozen files; a resumed epoch never captures the providers again. */
    public PreparedJobInputs resume(ReviewJob job) {
        return personCopies.withLiveAttempt(job, () -> {
            JsonNode capture = Objects.requireNonNull(job.getCapture());
            var lease = acquireForRebuild(job);
            Path root = directory(layout.jobsRoot(), job);
            try {
                var recorded = attemptInputs.list(job);
                if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) rebuild(job, root, capture, recorded);
                var paths = new LinkedHashMap<String, Path>();
                for (var file : recorded) {
                    Path path = safePath(root, file.path());
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || !path.toRealPath().startsWith(root.toRealPath()))
                        throw new CheckoutUnverifiedException(
                                CheckoutUnverifiedException.Reason.UNAVAILABLE, "A frozen input is unavailable");
                    try (var content = Files.newInputStream(path)) {
                        if (!file.sha256().equals(ProvenanceDigest.sha256Hex(content)))
                            throw new CheckoutUnverifiedException(
                                    CheckoutUnverifiedException.Reason.MISMATCH,
                                    "A frozen input differs from its recorded digest");
                    }
                    if (!Files.getPosixFilePermissions(path)
                            .equals(PosixFilePermissions.fromString(file.permissions())))
                        throw new CheckoutUnverifiedException(
                                CheckoutUnverifiedException.Reason.MISMATCH,
                                "A frozen input differs from its recorded permissions");
                    paths.put(file.path(), path);
                }
                var directories = new ArrayList<EvidenceDirectory>();
                for (var target : capture.path("directories")) {
                    String name = target.asString();
                    Path checkout = safePath(root, name.substring(0, name.length() - 1));
                    String expected = capture.path(CHECKOUTS).path(name).asString(null);
                    if (expected == null
                            || !checkoutDigest(checkout, SEARCH_PROOF_BYTES).equals(Optional.of(expected)))
                        throw new CheckoutUnverifiedException(
                                CheckoutUnverifiedException.Reason.MISMATCH,
                                "A frozen checkout differs from its recorded content digest");
                    directories.add(new EvidenceDirectory(
                            name,
                            checkout,
                            CHECKOUT_KEY_READER.treeToValue(
                                    capture.path(CHECKOUT_KEYS).path(name), CheckoutKey.class)));
                }
                var manifest = CHECKOUT_KEY_READER.treeToValue(capture.path("manifest"), JobFolderIndex.class);
                var readiness = capture.path("readiness").isObject()
                        ? CHECKOUT_KEY_READER.treeToValue(
                                capture.path("readiness"), AutomatedReviewReadinessReport.class)
                        : null;
                Instant expiry = capture.path(COPIES_EXPIRE_AT).isString()
                        ? Instant.parse(capture.path(COPIES_EXPIRE_AT).asString())
                        : null;
                if (expired(capture))
                    throw new CheckoutUnverifiedException(
                            CheckoutUnverifiedException.Reason.UNAVAILABLE, "The frozen inputs expired");
                lease.shareWithRemovals();
                return new PreparedJobInputs(
                        paths, directories, List.of(() -> sweep.retire(root), lease), manifest, readiness, expiry);
            } catch (IOException failure) {
                lease.close();
                throw new UncheckedIOException(failure);
            } catch (RuntimeException | Error failure) {
                lease.close();
                throw failure;
            }
        });
    }

    public void beginPersonCapture(ReviewJob job) {
        personCopies.beginCapture(job);
    }

    public void abortPersonCapture(ReviewJob job) {
        personCopies.abortCapture(job);
    }

    /** Rendering scratch belongs to the fenced attempt and has the same cleanup boundary as its frozen folder. */
    public Path renderingDirectory(ReviewJob job) {
        Path root = directory(layout.jobsRoot(), job);
        try {
            Files.createDirectories(root.getParent());
            return Files.createTempDirectory(root.getParent(), "." + root.getFileName() + ".preparing-");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public PreparedJobInputs prepare(
            ReviewJob job, PreparedEvidence inputs, @Nullable AutomatedReviewReadinessReport report) {
        try {
            requireBudget(inputs);
        } catch (RuntimeException exception) {
            inputs.close();
            throw exception;
        }
        Path root = directory(layout.jobsRoot(), job);
        Path staging = null;
        try {
            Files.createDirectories(root.getParent());
            if (Files.exists(root)) throw new IllegalStateException("Attempt evidence already exists");
            staging = Files.createTempDirectory(root.getParent(), "." + root.getFileName() + ".preparing-");
            var staged = new LinkedHashMap<String, Path>();
            var directories = new ArrayList<EvidenceDirectory>();
            for (EvidenceDirectory directory : inputs.directories()) {
                Path destination = safePath(
                        staging,
                        directory.target().substring(0, directory.target().length() - 1));
                copyDirectory(directory.source(), destination);
                directories.add(directory.at(safePath(
                        root, directory.target().substring(0, directory.target().length() - 1))));
            }
            for (var entry : inputs.files().entrySet()) {
                Path target = safePath(staging, entry.getKey());
                Files.createDirectories(target.getParent());
                byte[] bytes = entry.getValue().clone();
                Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                Files.setPosixFilePermissions(target, Set.of(PosixFilePermission.OWNER_READ));
                staged.put(entry.getKey(), safePath(root, entry.getKey()));
            }
            for (var entry : inputs.filesOnDisk().entrySet()) {
                Path target = safePath(staging, entry.getKey());
                Files.createDirectories(target.getParent());
                if (!Files.isRegularFile(entry.getValue(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("Prepared input is not a regular file");
                }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    boolean witness = inputs.directories().stream()
                            .anyMatch(directory -> entry.getKey().startsWith(directory.target())
                                    && directory
                                            .source()
                                            .resolve(entry.getKey()
                                                    .substring(
                                                            directory.target().length()))
                                            .equals(entry.getValue()
                                                    .toAbsolutePath()
                                                    .normalize()));
                    if (!witness || Files.mismatch(entry.getValue(), target) != -1) {
                        throw new IllegalArgumentException("Conflicting prepared directory witness");
                    }
                } else {
                    Files.copy(entry.getValue(), target);
                    makeReadOnly(target, entry.getValue());
                }
                staged.put(entry.getKey(), safePath(root, entry.getKey()));
            }
            // rename(2) refuses a populated target, so two preparations of one attempt cannot both publish.
            Files.move(staging, root, StandardCopyOption.ATOMIC_MOVE);
            staging = null;
            // Any server rebuilds this folder from these rows and the checkouts' recorded keys.
            attemptInputs.record(job.getId(), job.getWorkspaceId(), job.getAttempt(), staged);
            inputs.close();
            var cleanups = new ArrayList<AutoCloseable>();
            var closed = new AtomicBoolean();
            cleanups.add(() -> {
                if (closed.compareAndSet(false, true)) {
                    sweep.retire(root);
                }
            });
            cleanups.add(personCopies.finishCapture(job));
            return new PreparedJobInputs(staged, directories, cleanups, inputs.manifest(), report);
        } catch (DataAccessException | TransactionException exception) {
            if (staging != null) delete(staging);
            inputs.close();
            throw exception;
        } catch (IOException | RuntimeException exception) {
            if (staging != null) delete(staging);
            inputs.close();
            throw new IllegalStateException("Could not prepare attempt evidence: " + job.getId(), exception);
        }
    }

    private static void requireBudget(PreparedEvidence inputs) {
        long bytes = 0;
        try {
            for (byte[] value : inputs.files().values()) bytes = Math.addExact(bytes, value.length);
            for (var directory : inputs.directories()) {
                try (var entries = Files.walk(directory.source())) {
                    var files = entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .iterator();
                    while (files.hasNext()) {
                        bytes = Math.addExact(bytes, Files.size(files.next()));
                        checkBudget(bytes);
                    }
                }
            }
            for (var entry : inputs.filesOnDisk().entrySet()) {
                if (inputs.directories().stream()
                        .noneMatch(directory -> entry.getKey().startsWith(directory.target()))) {
                    bytes = Math.addExact(bytes, Files.size(entry.getValue()));
                    checkBudget(bytes);
                }
            }
            checkBudget(bytes);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void checkBudget(long bytes) {
        long budget = SandboxGatewaySessions.WORKSPACE_BYTE_BUDGET;
        if (bytes > budget) {
            throw new WorkspaceBudgetExceededException(bytes, budget);
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Invalid or overlapping prepared directory");
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile())
                    throw new IllegalArgumentException("Prepared directory contains a non-regular file");
                Path destination = target.resolve(source.relativize(file));
                Files.copy(file, destination);
                makeReadOnly(destination, file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void makeReadOnly(Path target, Path source) throws IOException {
        var permissions = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
        if (Files.isExecutable(source)) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        Files.setPosixFilePermissions(target, permissions);
    }

    /**
     * Rebuilds the folder of the job's running attempt on this server when another server captured it: from the
     * attempt's recorded files ({@link AttemptInputs}) and its checkouts, which this server builds from the recorded
     * commit and tree and checks against the recorded checkout digest. A folder that exists stays as it is. One rebuild
     * runs for each folder in this JVM; the others wait for it. An attempt whose copy is erased, or that ended, gets
     * no folder, also when an erasure commits while the folder is rebuilt. The readers of the folder never rebuild it:
     * they run in admission's transaction.
     *
     * @throws IllegalStateException when a transaction is active: a checkout build can fetch from the provider for as
     *     long as the provider takes
     * @throws CheckoutUnverifiedException when the folder cannot be rebuilt with the recorded identity, or the running
     *     attempt's recorded files are missing: a capture failure, never missing evidence
     * @throws AttemptFolderBusyException when another process holds the folder longer than a rebuild waits: retryable
     */
    public void ensureAttemptFolder(ReviewJob job) {
        requireNoTransaction();
        Path root = directory(layout.jobsRoot(), job);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        JsonNode capture = job.getCapture();
        if (capture == null) return;
        var rebuild = rebuilds.computeIfAbsent(root, folder -> new ReentrantLock());
        rebuild.lock();
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) || !live(job)) return;
            var files = attemptInputs.list(job);
            if (files.isEmpty()) {
                throw new CheckoutUnverifiedException(
                        CheckoutUnverifiedException.Reason.UNAVAILABLE, "The running attempt has no recorded files");
            }
            var lease = acquireForRebuild(job);
            try (lease) {
                rebuild(job, root, capture, files);
            }
        } catch (CheckoutUnverifiedException failure) {
            // An erasure that committed during the rebuild keeps its own reason. The reconcile removes a folder that
            // was published after it.
            if (!live(job)) return;
            log.warn(
                    "Captured checkout cannot be built here: jobId={}, attempt={}, reason={}",
                    job.getId(),
                    job.getAttempt(),
                    failure.reason().tag());
            throw failure;
        } finally {
            rebuild.unlock();
            if (!rebuild.hasQueuedThreads()) rebuilds.remove(root, rebuild);
        }
    }

    /**
     * Requires the folder of the job's running attempt, which {@link #ensureAttemptFolder(ReviewJob)} builds with no
     * transaction held.
     *
     * @throws AttemptFolderBusyException when the folder is not on this server: retryable, since the caller builds it
     *     after its transaction ends
     */
    void requireAttemptFolder(ReviewJob job) {
        if (Files.exists(directory(layout.jobsRoot(), job), LinkOption.NOFOLLOW_LINKS)
                || job.getCapture() == null
                || !live(job)) return;
        throw new AttemptFolderBusyException("The evidence folder is not on this server");
    }

    static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("An evidence folder is rebuilt before any transaction starts");
    }

    private boolean live(ReviewJob job) {
        return job.getStatus() == ReviewJobStatus.RUNNING
                && personCopies.vouchesForLiveAttempt(job.getId(), job.getWorkspaceId(), job.getAttempt());
    }

    /** The job's folder lease, waited for while a sweep or a runtime of this or another process holds it briefly. */
    private EvidenceFolderLease acquireForRebuild(ReviewJob job) {
        long deadline = System.nanoTime() + REBUILD_LEASE_WAIT.toNanos();
        while (true) {
            var acquired = EvidenceFolderLease.tryAcquire(layout.root(), job.getWorkspaceId(), job.getId());
            if (acquired.isPresent()) return acquired.get();
            if (System.nanoTime() > deadline)
                throw new AttemptFolderBusyException("Another process holds this evidence folder");
            try {
                Thread.sleep(REBUILD_LEASE_POLL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AttemptFolderBusyException("Interrupted while waiting for the evidence folder", interrupted);
            }
        }
    }

    private void rebuild(ReviewJob job, Path root, JsonNode capture, List<AttemptInputs.RecordedFile> files) {
        Path staging = null;
        try {
            Files.createDirectories(root.getParent());
            staging = Files.createTempDirectory(root.getParent(), "." + root.getFileName() + ".preparing-");
            var directories = new ArrayList<String>();
            for (JsonNode directory : capture.path("directories")) {
                String target = directory.asString();
                JsonNode key = capture.path(CHECKOUT_KEYS).path(target);
                if (!key.isObject())
                    throw new CheckoutUnverifiedException(
                            CheckoutUnverifiedException.Reason.UNAVAILABLE, "The capture recorded no checkout key");
                String digest = capture.path(CHECKOUTS).path(target).asString(null);
                Path copy = safePath(staging, target.substring(0, target.length() - 1));
                attemptInputs.copyCheckout(
                        job.getWorkspaceId(),
                        CHECKOUT_KEY_READER.treeToValue(key, CheckoutKey.class),
                        copy,
                        built -> digest == null
                                || checkoutDigest(built, SEARCH_PROOF_BYTES).equals(Optional.of(digest)));
                try (var built = Files.walk(copy)) {
                    for (Path file : built.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .toList()) makeReadOnly(file, file);
                }
                directories.add(target);
            }
            for (var file : files) {
                // One file in memory at a time: a folder can hold up to the workspace byte budget.
                byte[] content = attemptInputs.content(job, file.path());
                if (!file.sha256().equals(ProvenanceDigest.sha256Hex(content)))
                    throw new CheckoutUnverifiedException(
                            CheckoutUnverifiedException.Reason.MISMATCH, "A recorded file differs from its digest");
                Path target = safePath(staging, file.path());
                Files.createDirectories(target.getParent());
                // A file inside a checkout, such as its recorded refs, is restored as the capture staged it.
                boolean inCheckout = directories.stream().anyMatch(file.path()::startsWith);
                if (inCheckout) Files.deleteIfExists(target);
                Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(file.permissions()));
            }
            try {
                Files.move(staging, root, StandardCopyOption.ATOMIC_MOVE);
                staging = null;
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException concurrent) {
                // Another process published the same folder first.
            }
        } catch (IllegalArgumentException | IllegalStateException malformed) {
            // A recorded path or key that does not rebuild: a capture failure, never a coverage refusal.
            throw new CheckoutUnverifiedException(
                    CheckoutUnverifiedException.Reason.MISMATCH,
                    "The recorded capture does not rebuild the folder",
                    malformed);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            if (staging != null) delete(staging);
        }
    }

    /**
     * The checkout digest of each staged directory, by its target, for the capture to record before the sandbox
     * starts. It is the digest that the agent's search server computes over the bytes it searched ({@code
     * checkoutDigest} in {@code pi-search-worker.ts}): the SHA-256 of one line {@code <path>\0<SHA-256 of the
     * file>\n} for each regular file outside {@code .git}, in the byte order of the paths. A command in the sandbox can
     * change the sandbox's copy, so admission counts a search only where the two agree. A directory with more bytes
     * than a search proof reads gets none.
     */
    public static Map<String, String> checkoutDigests(List<EvidenceDirectory> directories) {
        var digests = new TreeMap<String, String>();
        for (EvidenceDirectory directory : directories)
            checkoutDigest(directory.source(), SEARCH_PROOF_BYTES)
                    .ifPresent(digest -> digests.put(directory.target(), digest));
        return digests;
    }

    static Optional<String> checkoutDigest(Path checkout, long maxBytes) {
        var digests = new TreeMap<byte[], String>(Arrays::compareUnsigned);
        var walk = new SimpleFileVisitor<Path>() {
            long bytes;

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return !directory.equals(checkout) && ".git".equals(String.valueOf(directory.getFileName()))
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                bytes += attributes.size();
                if (bytes > maxBytes) return FileVisitResult.TERMINATE;
                String path = checkout.relativize(file).toString();
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    digests.put(path.getBytes(StandardCharsets.UTF_8), ProvenanceDigest.sha256Hex(input));
                }
                return FileVisitResult.CONTINUE;
            }
        };
        try {
            if (!Files.isDirectory(checkout, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            Files.walkFileTree(checkout, walk);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        if (walk.bytes > maxBytes) return Optional.empty();
        MessageDigest digest = ProvenanceDigest.sha256();
        digests.forEach((path, sha) -> {
            digest.update(path);
            digest.update((byte) 0);
            digest.update(sha.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        });
        return Optional.of(ProvenanceDigest.hex(digest));
    }

    /** Admission has committed; recheck ownership before removing the verified bytes. */
    public void discardAdmittedAttempt(ResultAdmissionService.AdmissionIdentity identity) {
        try {
            var recorded = jobs.findByIdAndWorkspaceId(identity.jobId(), identity.workspaceId());
            if (recorded.isEmpty()) return;
            ReviewJob job = recorded.get();
            if (!ResultAdmissionService.isAdmitted(job)
                    || job.getAttempt() != identity.attempt()
                    || !identity.runId().equals(job.getRunId())) return;
            Path root = directory(layout.jobsRoot(), job);
            sweep.discardAttempt(identity.workspaceId(), identity.jobId(), root);
        } catch (IOException | RuntimeException exception) {
            // Admission is durable. The scheduled cleaner retries a failed local removal.
            log.warn("Could not remove admitted attempt {}", identity.jobId(), exception);
        }
    }

    /** See {@link AttemptTranscripts#bind}. */
    @Override
    public Consumer<Map<String, byte[]>> bind(UUID jobId, int attempt, String image) {
        return transcripts.bind(jobId, attempt, image);
    }

    public void cleanAfterRestart() {
        cleanEndedAttempts();
    }

    /** See {@link AttemptFolderSweep#cleanEndedAttempts}. */
    public int cleanEndedAttempts() {
        return sweep.cleanEndedAttempts();
    }

    /** Gateway archives belong to the same durable receipt and folder lease as the frozen attempt. */
    public Path archiveDirectory(ReviewJob job) {
        Path root = directory(layout.jobsRoot(), job);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new UncheckedIOException(new NoSuchFileException(root.toString()));
        return root.resolve(".archives");
    }
}

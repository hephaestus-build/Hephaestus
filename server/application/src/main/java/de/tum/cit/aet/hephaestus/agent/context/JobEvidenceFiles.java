package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderLease;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxResultListener;
import de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException;
import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
public class JobEvidenceFiles implements SandboxResultListener {
    private static final Logger log = LoggerFactory.getLogger(JobEvidenceFiles.class);

    /** Invalid UTF-8 cannot match a quote: this lone surrogate is rejected by quoteDigest. */
    private static final String UNDECODABLE = "\uDC00";

    static final Duration RETENTION_GRACE = Duration.ofHours(1);

    /** How long an attempt's debug transcript stays for next-day investigation. */
    static final Duration TRACE_RETENTION = Duration.ofHours(24);

    /** Where the gateway runner puts the native session transcripts in a result. */
    private static final String TRACES = "traces/";

    private static final String TRACE_SUFFIX = ".trace";
    private static final String TRACE_RECORD = "record.json";

    /**
     * The collector's manifest (gateway-run.ts). It is sandbox output: a METADATA_ONLY record keeps only what
     * {@link TraceSummary} rebuilds from it.
     */
    private static final String TRACE_MANIFEST = "manifest.json";

    private static final long TRACE_BYTE_BUDGET = 8L * 1024 * 1024;
    private static final int TRACE_FILE_BUDGET = 2 * 1024 * 1024;

    private final FabricLayout layout;
    private final AgentJobRepository jobs;
    private final Clock clock;
    private final EvidenceFolderPersonDataCatalog personCopies;

    public JobEvidenceFiles(
            FabricLayout layout, AgentJobRepository jobs, Clock clock, EvidenceFolderPersonDataCatalog personCopies) {
        this.layout = layout;
        this.jobs = jobs;
        this.clock = clock;
        this.personCopies = personCopies;
    }

    public void beginPersonCapture(AgentJob job) {
        personCopies.beginCapture(job);
    }

    public void abortPersonCapture(AgentJob job) {
        personCopies.abortCapture(job);
    }

    /** Rendering scratch belongs to the fenced attempt and has the same cleanup boundary as its frozen folder. */
    public Path renderingDirectory(AgentJob job) {
        Path root = directory(job);
        try {
            Files.createDirectories(root.getParent());
            return Files.createTempDirectory(root.getParent(), "." + root.getFileName() + ".preparing-");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public PreparedJobInputs prepare(
            AgentJob job, PreparedEvidence inputs, @Nullable AutomatedReviewReadinessReport report) {
        try {
            requireBudget(inputs);
        } catch (RuntimeException exception) {
            inputs.close();
            throw exception;
        }
        Path root = directory(job);
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
                directories.add(new EvidenceDirectory(
                        directory.target(),
                        safePath(
                                root,
                                directory
                                        .target()
                                        .substring(0, directory.target().length() - 1))));
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
            inputs.close();
            var cleanups = new ArrayList<AutoCloseable>();
            var closed = new AtomicBoolean();
            cleanups.add(() -> {
                if (closed.compareAndSet(false, true)) {
                    retire(job, root);
                }
            });
            cleanups.add(personCopies.finishCapture(job));
            return new PreparedJobInputs(staged, directories, cleanups, inputs.manifest(), report);
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

    @FunctionalInterface
    public interface TextInspection<T> {
        T inspect(Reader reader) throws IOException;
    }

    public <T> Optional<T> inspect(AgentJob job, String artifactPath, String sha, TextInspection<T> inspection) {
        try {
            MessageDigest digest = ProvenanceDigest.sha256();
            T result;
            try (var reader = new InputStreamReader(
                    new DigestInputStream(Files.newInputStream(artifact(job, artifactPath)), digest), decoder())) {
                result = inspection.inspect(reader);
                reader.transferTo(Writer.nullWriter());
            }
            requireDigest(sha, ProvenanceDigest.hex(digest));
            return Optional.of(result);
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public Optional<Boolean> containsUtf8AtLines(
            AgentJob job, String artifactPath, String sha, String text, int startLine, int endLine) {
        try {
            QuoteMatch match = verifyUtf8AtLines(artifact(job, artifactPath), text, startLine, endLine);
            requireDigest(sha, Objects.requireNonNull(match.artifactSha256()));
            return Optional.of(match.matches());
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public Path repositoryForVerification(AgentJob job, String headSha256, String refsSha256) {
        return repositoryForVerification(job, SandboxLayout.REPO_MOUNT_RELATIVE, headSha256, refsSha256);
    }

    public Path repositoryForVerification(AgentJob job, String root, String headSha256, String refsSha256) {
        if (!root.matches("repos/[a-zA-Z0-9_-]+/")) throw new IllegalArgumentException("Invalid repository root");
        String headPath = root + ".git/HEAD";
        if (!existsWithDigest(job, headPath, headSha256)) {
            throw new IllegalStateException("Captured repository is unavailable");
        }
        if (!existsWithDigest(job, root + ".git/hephaestus-captured-refs", refsSha256)) {
            throw new IllegalStateException("Captured repository refs are unavailable");
        }
        return directory(job).resolve(root);
    }

    /** False when the artifact is absent; a present artifact whose bytes differ from {@code sha} throws. */
    private boolean existsWithDigest(AgentJob job, String artifactPath, String sha) {
        return inspect(job, artifactPath, sha, reader -> Boolean.TRUE).isPresent();
    }

    /** @param artifactSha256 digest of the raw bytes read, or null when the cited artifact does not exist */
    public record QuoteMatch(boolean matches, @Nullable String artifactSha256) {
        public static QuoteMatch absent() {
            return new QuoteMatch(false, null);
        }
    }

    public static QuoteMatch verifyUtf8AtLines(Path file, String text, int startLine, int endLine) throws IOException {
        if (startLine < 1 || endLine < startLine || text.isEmpty())
            throw new IllegalArgumentException("Invalid citation quote or line range");
        MessageDigest digest = ProvenanceDigest.sha256();
        boolean found = false;
        try (var reader = new InputStreamReader(new DigestInputStream(Files.newInputStream(file), digest), decoder())) {
            char[] buffer = new char[8192];
            StringBuilder window = new StringBuilder();
            long line = 1;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (found) continue;
                for (int i = 0; i < read; i++) {
                    char character = buffer[i];
                    if (line >= startLine && line <= endLine) window.append(character);
                    if (character == '\n') line++;
                }
                found = window.indexOf(text) >= 0;
                window.delete(0, Math.max(0, window.length() - text.length() + 1));
            }
        }
        return new QuoteMatch(found, ProvenanceDigest.hex(digest));
    }

    private static CharsetDecoder decoder() {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(UNDECODABLE);
    }

    /** Under the runtime's own lease, one removal at a time with admission; an exclusive owner otherwise retires it. */
    private void retire(AgentJob owner, Path root) throws IOException {
        EvidenceFolderLease.removeAsOwner(layout.root(), owner.getWorkspace().getId(), owner.getId(), () -> {
            if (!Files.exists(root)) return;
            var job = recordedJob(root);
            if (job.isPresent() && admitted(job.get()) && matches(root, job.get())) {
                deleteAttempt(root);
            } else {
                markEnded(root);
            }
        });
    }

    /** Admission has committed; recheck ownership before removing the verified bytes. */
    public void discardAdmittedAttempt(ObservationAdmissionService.AdmissionIdentity identity) {
        try {
            var recorded = jobs.findByIdAndWorkspaceId(identity.jobId(), identity.workspaceId());
            if (recorded.isEmpty()) return;
            AgentJob job = recorded.get();
            if (!ObservationAdmissionService.isAdmitted(job)
                    || job.getRetryCount() != identity.attempt()
                    || !identity.workerId().equals(job.getWorkerId())) return;
            // Removed now under the runtime's shared lease, or a new exclusive one. A sweep or an erasure that
            // holds the job exclusively removes admitted bytes itself.
            Path root = directory(job);
            EvidenceFolderLease.removeAsOwner(
                    layout.root(), identity.workspaceId(), identity.jobId(), () -> deleteAttempt(root));
        } catch (IOException | RuntimeException exception) {
            // Admission is durable. The scheduled cleaner retries a failed local removal.
            log.warn("Could not remove admitted attempt {}", identity.jobId(), exception);
        }
    }

    /** The exact rows a read during runtime returned, by the tables that own them. */
    public record LateCopies(Set<UUID> observationIds, Set<UUID> feedbackIds) {}

    /** See {@link EvidenceFolderPersonDataCatalog#indexLateRead}. */
    public <T> T indexLateRead(
            ObservationAdmissionService.AdmissionIdentity identity, Supplier<T> read, Function<T, LateCopies> copies) {
        return personCopies.indexLateRead(
                identity.jobId(),
                identity.workspaceId(),
                identity.attempt(),
                read,
                result -> copies.apply(result).observationIds(),
                result -> copies.apply(result).feedbackIds());
    }

    /** What a review attempt was launched as, captured before its sandbox existed. */
    private record LaunchedAttempt(
            long workspaceId,
            UUID jobId,
            int attempt,
            String workerId,
            String image,
            @Nullable String model,
            @Nullable String promptDigest,
            @Nullable String inputsDigest) {}

    /**
     * Binds the attempt the executor is launching: the job must be RUNNING as exactly this attempt. Anything else, and
     * a mentor session, which is never bound, keeps no transcript.
     */
    @Override
    public Consumer<Map<String, byte[]>> bind(UUID jobId, int attempt, String image) {
        var launched = jobs.findByIdWithWorkspace(jobId)
                .filter(job -> job.getStatus() == AgentJobStatus.RUNNING
                        && job.getRetryCount() == attempt
                        && job.getWorkerId() != null)
                .map(job -> new LaunchedAttempt(
                        job.getWorkspace().getId(),
                        jobId,
                        attempt,
                        Objects.requireNonNull(job.getWorkerId()),
                        image,
                        job.getConfigSnapshot() == null
                                ? null
                                : job.getConfigSnapshot()
                                        .path("upstreamModelId")
                                        .asString(null),
                        job.getPromptDigest(),
                        job.getInputsDigest()));
        if (launched.isEmpty()) return files -> {};
        var bound = launched.get();
        return files -> keepTranscript(bound, files);
    }

    /**
     * Keeps the native session transcripts of an admitted upload beside the attempt folder for a day. Only while the
     * job is still owned as the attempt that was launched, only by the runtime that holds that attempt's lease, and only
     * for its READY receipt in this store. A receipt that cannot index every copied dependency keeps a summary the
     * worker rebuilds itself, marked METADATA_ONLY. Never throws into the upload, and never changes the job's status.
     */
    private void keepTranscript(LaunchedAttempt launched, Map<String, byte[]> files) {
        var traces = new TreeMap<String, byte[]>();
        files.forEach((name, bytes) -> {
            if (name.startsWith(TRACES) && name.length() > TRACES.length())
                traces.put(name.substring(TRACES.length()), bytes);
        });
        if (traces.isEmpty()) return;
        try {
            // A delayed upload of an earlier attempt finds the job requeued under another attempt or owner.
            boolean owned = jobs.findByIdAndWorkspaceId(launched.jobId(), launched.workspaceId())
                    .filter(job -> job.getRetryCount() == launched.attempt()
                            && launched.workerId().equals(job.getWorkerId()))
                    .isPresent();
            if (!owned) return;
            Path root = directory(launched.workspaceId(), launched.jobId(), launched.attempt(), launched.workerId());
            Path trace = root.resolveSibling(root.getFileName() + TRACE_SUFFIX);
            EvidenceFolderLease.writeAsRuntime(layout.root(), launched.workspaceId(), launched.jobId(), () -> {
                var custody = personCopies.traceCustody(launched.jobId(), launched.workspaceId(), launched.attempt());
                if (custody == EvidenceFolderPersonDataCatalog.TraceCustody.NOT_HELD
                        || Files.exists(trace, LinkOption.NOFOLLOW_LINKS)) return;
                Files.createDirectories(root.getParent());
                Path staging = Files.createTempDirectory(root.getParent(), "." + trace.getFileName() + ".preparing-");
                try {
                    boolean withinBudget = boundedTraces(traces);
                    boolean raw = custody == EvidenceFolderPersonDataCatalog.TraceCustody.INDEXED && withinBudget;
                    ObjectNode record = JsonNodeFactory.instance.objectNode();
                    record.put("jobId", launched.jobId().toString());
                    record.put("workspaceId", launched.workspaceId());
                    record.put("attempt", launched.attempt());
                    record.put("image", launched.image());
                    record.put("model", launched.model());
                    record.put("promptDigest", launched.promptDigest());
                    record.put("inputsDigest", launched.inputsDigest());
                    record.put("retention", raw ? "NATIVE_TRANSCRIPT" : "METADATA_ONLY");
                    // A copied row whose source cannot vouch for the people it names keeps every session's words out.
                    if (!raw)
                        record.put(
                                "contentUnavailable", withinBudget ? "OWNERSHIP_INCOMPLETE" : "TRACE_OUTPUT_INVALID");
                    record.put(
                            "expiresAt", clock.instant().plus(TRACE_RETENTION).toString());
                    if (raw) {
                        for (var entry : traces.entrySet()) {
                            Path target = safePath(staging, entry.getKey());
                            Files.createDirectories(target.getParent());
                            Files.write(target, entry.getValue(), StandardOpenOption.CREATE_NEW);
                        }
                    } else {
                        var sessions = TraceSummary.of(traces.get(TRACE_MANIFEST));
                        if (sessions == null) record.putNull("sessions");
                        else record.set("sessions", sessions);
                        record.put("sessionScanTruncated", TraceSummary.scanTruncated(traces.get(TRACE_MANIFEST)));
                    }
                    Path recordFile = staging.resolve(TRACE_RECORD);
                    Files.writeString(recordFile, record.toString(), StandardOpenOption.CREATE_NEW);
                    // The sweep counts the retention from this timestamp, as it counts the grace from an ended marker.
                    Files.setLastModifiedTime(recordFile, FileTime.from(clock.instant()));
                    Files.move(staging, trace, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) delete(staging);
                }
            });
        } catch (IOException | RuntimeException exception) {
            log.warn(
                    "Could not keep the review transcript of job {}: {}",
                    launched.jobId(),
                    exception.getClass().getSimpleName());
        }
    }

    /** The sandbox cannot enlarge debug retention beyond the collector's declared limits. */
    private static boolean boundedTraces(Map<String, byte[]> traces) {
        long bytes = 0;
        if (traces.size() > 1001) return false;
        for (var entry : traces.entrySet()) {
            boolean manifest = TRACE_MANIFEST.equals(entry.getKey());
            if (!manifest && !entry.getKey().matches("[0-9]{4}\\.jsonl")) return false;
            if (entry.getValue().length > (manifest ? 1024 * 1024 : TRACE_FILE_BUDGET)) return false;
            bytes += entry.getValue().length;
            if (bytes > TRACE_BYTE_BUDGET) return false;
        }
        return true;
    }

    public void cleanAfterRestart() {
        cleanAttempts(true);
    }

    public void cleanEndedAttempts() {
        cleanAttempts(false);
    }

    private void cleanAttempts(boolean restarting) {
        cleanStaleGitSpool();
        if (!Files.isDirectory(layout.jobsRoot())) return;
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
                        } else if (name.endsWith(".ended")) {
                            roots.add(path.resolveSibling(name.substring(0, name.lastIndexOf('.'))));
                        } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            roots.add(path);
                        }
                    });
            for (Path root : roots) {
                if (!root.getFileName().toString().matches("[0-9]+-[0-9a-f]{64}")) continue;
                // A capture holds this lease until its inputs are released, after retiring its own folder; erasure
                // and the other sweep take it too. Whoever holds it owns the job's folders, and a later pass retries.
                Optional<EvidenceFolderLease> acquired;
                try {
                    acquired = leaseJob(root);
                } catch (RuntimeException exception) {
                    log.warn("Could not clean attempt folder {}", root, exception);
                    continue;
                }
                if (acquired.isEmpty()) continue;
                var lease = acquired.get();
                try (lease) {
                    // The holder before this one may have removed it; only a removal of our own is reported.
                    if (!present(root)) continue;
                    var job = recordedJob(root);
                    if (job.isPresent() && matches(root, job.get())) {
                        if (admitted(job.get())) {
                            deleteAttempt(root);
                            continue;
                        }
                        if (job.get().getStatus() == AgentJobStatus.RUNNING) continue;
                    }
                    if (restarting) {
                        deleteAttempt(root);
                        discardRetiredInventory(root, job);
                        continue;
                    }
                    Path ended = markEnded(root);
                    if (!Files.getLastModifiedTime(ended)
                            .toInstant()
                            .plus(RETENTION_GRACE)
                            .isAfter(clock.instant())) {
                        deleteAttempt(root);
                        discardRetiredInventory(root, job);
                    }
                } catch (RuntimeException | IOException exception) {
                    log.warn("Could not clean attempt folder {}", root, exception);
                }
            }
            traces.forEach(this::cleanTrace);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * A transcript outlives its attempt, its admitted inputs and a restart, whatever the job's status, until its day
     * is over. A staging copy left by a stopped writer goes at once: the runtime that wrote it no longer holds the job.
     */
    private void cleanTrace(Path trace) {
        try {
            var acquired = leaseJob(trace);
            if (acquired.isEmpty()) return;
            var lease = acquired.get();
            try (lease) {
                if (!Files.exists(trace, LinkOption.NOFOLLOW_LINKS)) return;
                Path record = trace.resolve(TRACE_RECORD);
                if (trace.getFileName().toString().startsWith(".")
                        || !Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)
                        || !Files.getLastModifiedTime(record, LinkOption.NOFOLLOW_LINKS)
                                .toInstant()
                                .plus(TRACE_RETENTION)
                                .isAfter(clock.instant())) {
                    delete(trace);
                }
            }
        } catch (RuntimeException | IOException exception) {
            log.warn("Could not clean review transcript {}", trace, exception);
        }
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

    /** Whether anything of the attempt remains: its folder, its ended marker or a staging folder. */
    private static boolean present(Path root) throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(root.resolveSibling(root.getFileName() + ".ended"), LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try (var siblings = Files.list(root.getParent())) {
            return siblings.anyMatch(
                    path -> path.getFileName().toString().startsWith("." + root.getFileName() + ".preparing-"));
        } catch (NoSuchFileException jobRemoved) {
            // Erasure removes the whole job folder; nothing of this attempt is left.
            return false;
        }
    }

    private void discardRetiredInventory(Path root, Optional<AgentJob> recorded) {
        if (recorded.isEmpty() || !matches(root, recorded.get())) return;
        AgentJob job = recorded.get();
        jobs.discardRetiredArtifactInventory(
                job.getId(),
                job.getWorkspace().getId(),
                job.getRetryCount(),
                Objects.requireNonNull(job.getWorkerId()));
    }

    private void cleanStaleGitSpool() {
        if (!Files.isDirectory(layout.root())) return;
        try (var entries = Files.list(layout.root())) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!name.startsWith(GitRepositoryManager.GIT_SNAPSHOT_PREFIX)) continue;
                if (GitRepositoryManager.isCurrentProcessSpool(entry)) continue;
                try {
                    if (Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)
                            .toInstant()
                            .plus(RETENTION_GRACE)
                            .isAfter(clock.instant())) {
                        continue;
                    }
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        delete(entry);
                    } else {
                        Files.deleteIfExists(entry);
                    }
                } catch (RuntimeException | IOException exception) {
                    log.warn("Could not clean stale Git spool entry {}", entry, exception);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Optional<AgentJob> recordedJob(Path root) {
        Path jobDirectory = Objects.requireNonNull(root.getParent());
        Path workspaceDirectory = Objects.requireNonNull(jobDirectory.getParent());
        return jobs.findByIdAndWorkspaceId(
                UUID.fromString(
                        Objects.requireNonNull(jobDirectory.getFileName()).toString()),
                Long.parseLong(
                        Objects.requireNonNull(workspaceDirectory.getFileName()).toString()));
    }

    private boolean matches(Path root, AgentJob job) {
        return job.getWorkerId() != null && directory(job).equals(root);
    }

    private static boolean admitted(AgentJob job) {
        return ObservationAdmissionService.isAdmitted(job);
    }

    private Path markEnded(Path root) throws IOException {
        Path ended = root.resolveSibling(root.getFileName() + ".ended");
        try {
            Files.createFile(ended);
            Files.setLastModifiedTime(ended, FileTime.from(clock.instant()));
        } catch (FileAlreadyExistsException alreadyEnded) {
            // The marker's timestamp is when the attempt first ended; the retention grace counts from it.
        }
        return ended;
    }

    private static void deleteAttempt(Path root) throws IOException {
        delete(root);
        try (var siblings = Files.list(root.getParent())) {
            for (Path staging : siblings.filter(
                            path -> path.getFileName().toString().startsWith("." + root.getFileName() + ".preparing-"))
                    .toList()) delete(staging);
        }
        Files.deleteIfExists(root.resolveSibling(root.getFileName() + ".ended"));
    }

    private Path artifact(AgentJob job, String artifactPath) throws IOException {
        Path root = directory(job);
        Path artifact = safePath(root, artifactPath);
        if (!artifact.toRealPath().startsWith(root.toRealPath())
                || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Evidence path is not a regular captured file");
        }
        return artifact;
    }

    private Path directory(AgentJob job) {
        String worker = Objects.requireNonNull(job.getWorkerId(), "Attempt has no owning worker");
        return directory(job.getWorkspace().getId(), job.getId(), job.getRetryCount(), worker);
    }

    private Path directory(long workspaceId, UUID jobId, int attempt, String worker) {
        return layout.jobsRoot()
                .resolve(Long.toString(workspaceId))
                .resolve(jobId.toString())
                .resolve(attempt + "-" + ProvenanceDigest.sha256Hex(worker.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The collector's manifest is sandbox output: what a METADATA_ONLY record keeps is rebuilt here from it, field by
     * field, as owned enums, booleans and bounded counts. Nothing the manifest carries is copied as written.
     */
    private static final class TraceSummary {
        private static final JsonMapper MANIFESTS = new JsonMapper();
        /** The collector's own reserve for its manifest (gateway-run.ts); a larger one was not written by it. */
        private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

        private static final int MAX_SESSIONS = 1000;
        private static final long MAX_COUNT = 1_000_000;
        private static final long MAX_TOKENS = 1_000_000_000_000L;
        private static final long MAX_BYTES = TRACE_BYTE_BUDGET;
        private static final long MAX_REVISION = (1L << 53) - 1;
        private static final Set<String> PHASES = Set.of("practice", "public-review", "private-feedback");
        private static final Set<String> OMISSIONS = Set.of("oversize", "budget", "unreadable");
        private static final Set<String> STOP_REASONS =
                Set.of("stop", "length", "toolUse", "error", "aborted", "other");
        /** The tools a review session is given (pi-runner.ts); the collector counts any other name as "other". */
        private static final Set<String> TOOL_NAMES = Set.of(
                "read",
                "grep",
                "find",
                "ls",
                "bash",
                "codemode",
                "read_practice",
                "report_observation",
                "report_feedback",
                "report_review",
                "other");
        /** The provider adapter's closed failure diagnostic (docker/agents/pi/patches), as the collector keeps it. */
        private static final Set<String> MODEL_FAILURE_KINDS = Set.of(
                "HTTP_ERROR",
                "CONNECTION_TIMEOUT",
                "CONNECTION_ERROR",
                "STREAM_INCOMPLETE",
                "FINISH_REASON_ERROR",
                "STREAM_ERROR_EVENT",
                "RESPONSE_FAILED",
                "RESPONSE_STATUS_ERROR",
                "TOOL_CALL_INCOMPLETE",
                "ABORTED",
                "UNKNOWN");

        private static final Set<String> MODEL_FAILURE_PHASES = Set.of("request", "response_body");
        private static final Set<String> MODEL_FAILURE_SOURCES = Set.of("ADAPTER", "MISSING", "INVALID");
        /** The adapter's call span totals in milliseconds (gateway-run.ts), each a bounded integer. */
        private static final List<String> MODEL_CALL_TIMING =
                List.of("elapsedMs", "failedElapsedMs", "maxElapsedMs", "responseMs");

        private static final long MAX_DURATION_MS = (1L << 53) - 1;
        /** The largest time a JavaScript Date can hold, in epoch milliseconds. */
        private static final long MAX_EPOCH_MS = 8_640_000_000_000_000L;

        private TraceSummary() {}

        /** The sessions as the worker states them, or null when the manifest is absent, oversize or unreadable. */
        static @Nullable ArrayNode of(byte @Nullable [] manifest) {
            if (manifest == null || manifest.length > MAX_MANIFEST_BYTES) return null;
            JsonNode root;
            try {
                root = MANIFESTS.readTree(manifest);
            } catch (RuntimeException unreadable) {
                log.warn(
                        "Unreadable transcript manifest: {}",
                        unreadable.getClass().getSimpleName());
                return null;
            }
            if (!root.path("sessions").isArray()) return null;
            ArrayNode sessions = JsonNodeFactory.instance.arrayNode();
            for (JsonNode session : root.path("sessions")) {
                if (sessions.size() == MAX_SESSIONS) break;
                ObjectNode kept = sessions.addObject();
                kept.put("copied", session.path("file").isString());
                putBounded(kept, "bytes", session.path("bytes"), MAX_BYTES);
                putFlag(kept, "redacted", session.path("redacted"));
                putFlag(kept, "partialLineDropped", session.path("partialLineDropped"));
                putOwned(kept, "omitted", session.path("omitted"), OMISSIONS);
                JsonNode summary = session.path("summary");
                if (!summary.isObject()) continue;
                ObjectNode facts = kept.putObject("summary");
                putOwned(facts, "phase", summary.path("phase"), PHASES);
                putBounded(facts, "practiceRevisionId", summary.path("practiceRevisionId"), MAX_REVISION);
                for (String count : List.of(
                        "entries", "assistantCalls", "toolErrors", "compactions", "unattributedModelFailures")) {
                    putBounded(facts, count, summary.path(count), MAX_COUNT);
                }
                ObjectNode usage = facts.putObject("usage");
                for (String bucket : List.of("input", "output", "cacheRead", "cacheWrite")) {
                    putBounded(usage, bucket, summary.path("usage").path(bucket), MAX_TOKENS);
                }
                putCounts(facts.putObject("stopReasons"), summary.path("stopReasons"), STOP_REASONS);
                putCounts(facts.putObject("toolCalls"), summary.path("toolCalls"), TOOL_NAMES);
                putCounts(facts.putObject("modelFailures"), summary.path("modelFailures"), MODEL_FAILURE_KINDS);
                putBounded(facts, "lastModelFailureAt", summary.path("lastModelFailureAt"), MAX_EPOCH_MS);
                putModelFailure(facts, summary.path("finalModelFailure"));
                JsonNode callTiming = summary.path("modelCallTiming");
                if (callTiming.isObject()) {
                    ObjectNode timing = facts.putObject("modelCallTiming");
                    for (String field : List.of("timedCalls", "respondedCalls")) {
                        putBounded(timing, field, callTiming.path(field), MAX_COUNT);
                    }
                    for (String field : MODEL_CALL_TIMING) {
                        putBounded(timing, field, callTiming.path(field), MAX_DURATION_MS);
                    }
                }
            }
            return sessions;
        }

        /** Only an owned kind keeps a final failure; its phase, status (HTTP_ERROR only) and time each on their own. */
        private static void putModelFailure(ObjectNode facts, JsonNode failure) {
            JsonNode kind = failure.path("kind");
            if (!kind.isString() || !MODEL_FAILURE_KINDS.contains(kind.asString())) return;
            ObjectNode kept = facts.putObject("finalModelFailure");
            kept.put("kind", kind.asString());
            putOwned(kept, "source", failure.path("source"), MODEL_FAILURE_SOURCES);
            putOwned(kept, "phase", failure.path("phase"), MODEL_FAILURE_PHASES);
            if ("HTTP_ERROR".equals(kind.asString())) putBetween(kept, "status", failure.path("status"), 400, 599);
            putBounded(kept, "at", failure.path("at"), MAX_EPOCH_MS);
        }

        static boolean scanTruncated(byte @Nullable [] manifest) {
            if (manifest == null || manifest.length > MAX_MANIFEST_BYTES) return true;
            try {
                var root = MANIFESTS.readTree(manifest);
                var value = root.path("sessionScanTruncated");
                return !root.path("sessions").isArray()
                        || root.path("sessions").size() > MAX_SESSIONS
                        || !value.isBoolean()
                        || value.asBoolean();
            } catch (RuntimeException unreadable) {
                return true;
            }
        }

        private static void putBounded(ObjectNode target, String field, JsonNode value, long max) {
            putBetween(target, field, value, 0, max);
        }

        private static void putBetween(ObjectNode target, String field, JsonNode value, long min, long max) {
            if (value.isIntegralNumber()
                    && value.canConvertToLong()
                    && value.asLong() >= min
                    && value.asLong() <= max) {
                target.put(field, value.asLong());
            }
        }

        private static void putFlag(ObjectNode target, String field, JsonNode value) {
            if (value.isBoolean()) target.put(field, value.asBoolean());
        }

        private static void putOwned(ObjectNode target, String field, JsonNode value, Set<String> owned) {
            if (value.isString() && owned.contains(value.asString())) target.put(field, value.asString());
        }

        /** Only owned keys, each a bounded count; another key is dropped, not renamed. */
        private static void putCounts(ObjectNode target, JsonNode counts, Set<String> owned) {
            for (String key : owned) putBounded(target, key, counts.path(key), MAX_COUNT);
        }
    }

    private static Path safePath(Path root, String path) {
        Path relative = Path.of(path);
        if (relative.isAbsolute()
                || !relative.normalize().equals(relative)
                || path.isBlank()
                || path.contains("\\")
                || relative.startsWith("..")) {
            throw new IllegalArgumentException("Unsafe evidence path: " + path);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root))
            throw new IllegalArgumentException("Unsafe evidence path");
        return resolved;
    }

    private static void requireDigest(String expected, String actual) {
        if (!expected.equals(actual)) throw new IllegalStateException("Captured evidence digest mismatch");
    }

    private static void delete(Path root) {
        try {
            FileUtils.deleteDirectory(root.toFile());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

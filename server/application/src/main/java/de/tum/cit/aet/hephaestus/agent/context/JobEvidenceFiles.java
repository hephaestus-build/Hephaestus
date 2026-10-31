package de.tum.cit.aet.hephaestus.agent.context;

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
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.CitationRequirement;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.fabric.EvidenceSpool;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.ByteBuffer;
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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
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
    public static final Duration TRACE_RETENTION = Duration.ofHours(24);

    /**
     * The field of a job's capture record that states when the occasion record its inputs copy expires. A transcript
     * that may repeat those words is kept no longer; the record's {@code expiresAt} states that earlier deadline.
     */
    public static final String COPIES_EXPIRE_AT = "copiesExpireAt";

    private static final JsonMapper TRACE_RECORDS = new JsonMapper();

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
    private final ReviewJobRepository jobs;
    private final Clock clock;
    private final EvidenceFolderPersonDataCatalog personCopies;

    public JobEvidenceFiles(
            FabricLayout layout, ReviewJobRepository jobs, Clock clock, EvidenceFolderPersonDataCatalog personCopies) {
        this.layout = layout;
        this.jobs = jobs;
        this.clock = clock;
        this.personCopies = personCopies;
    }

    public void beginPersonCapture(ReviewJob job) {
        personCopies.beginCapture(job);
    }

    public void abortPersonCapture(ReviewJob job) {
        personCopies.abortCapture(job);
    }

    /** Rendering scratch belongs to the fenced attempt and has the same cleanup boundary as its frozen folder. */
    public Path renderingDirectory(ReviewJob job) {
        Path root = directory(job);
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

    public EvidenceDirectory verificationDirectory(ReviewJob job) {
        return new EvidenceDirectory("inputs/", directory(job));
    }

    /** Adds host reads only when both their source witness and staged content digest still match. */
    public JobFolderIndex registerReadReceipts(
            ReviewJob job,
            JobFolderIndex manifest,
            List<ReadReceipt> receipts,
            ClasspathSourceCatalogRegistry catalogs) {
        var texts = new ArrayList<>(manifest.citableTexts());
        for (var receipt : receipts) {
            String digest = receipt.contentDigest();
            if (digest == null || !digest.matches("[0-9a-f]{64}")) continue;
            var witness = manifest.files().stream()
                    .filter(file -> file.kind().equals(receipt.sourceKind())
                            && file.file().path().equals(receipt.sourcePath()))
                    .toList();
            if (witness.size() != 1
                    || !existsWithDigest(
                            job, receipt.sourcePath(), witness.getFirst().file().sha256())) {
                throw new IllegalArgumentException("The host read has no authenticated source witness");
            }
            var contract = catalogs.requireSource(receipt.sourceKind());
            String commit = contract.citation().commit() == CitationRequirement.FORBIDDEN ? null : receipt.version();
            var existing = texts.stream()
                    .filter(text -> text.source().equals(receipt.sourceKind().value())
                            && text.path().equals(receipt.path())
                            && text.side() == CitationSide.NEW
                            && Objects.equals(text.commit(), commit))
                    .toList();
            String stagedPath;
            if (existing.size() == 1) stagedPath = existing.getFirst().stagedPath();
            else if (existing.size() > 1) throw new IllegalArgumentException("Ambiguous host read text");
            else if (receipt.path().equals(receipt.sourcePath())) stagedPath = receipt.path();
            else {
                JsonNode capture = job.getCapture();
                var roots = new ArrayList<String>();
                if (capture != null)
                    for (JsonNode directory : capture.path("directories")) {
                        String root = directory.asString();
                        if (root.endsWith("/") && receipt.sourcePath().startsWith(root)) roots.add(root);
                    }
                if (roots.size() != 1)
                    throw new IllegalArgumentException("The host read has no unique captured directory");
                stagedPath = roots.getFirst() + receipt.path();
            }
            if (!existsWithDigest(job, stagedPath, digest))
                throw new IllegalArgumentException("The host read content is unavailable");
            if (existing.isEmpty())
                texts.add(new CitableText(
                        receipt.sourceKind().value(), receipt.path(), CitationSide.NEW, commit, stagedPath, digest));
            else if (!existing.getFirst().sha256().equals(digest))
                throw new IllegalArgumentException("The host read digest differs from its captured text");
        }
        return new JobFolderIndex(
                manifest.contractVersion(),
                manifest.catalogDigest(),
                manifest.itemKind(),
                manifest.capturedAt(),
                manifest.sources(),
                manifest.refusals(),
                manifest.files(),
                texts);
    }

    public Optional<String> readText(ReviewJob job, String path, String sha) {
        try {
            Path file = sourceFile(job, path);
            if (Files.size(file) > 32 * 1024 * 1024L)
                throw new IllegalArgumentException("Captured text exceeds its read bound");
            byte[] bytes = Files.readAllBytes(file);
            requireDigest(sha, ProvenanceDigest.sha256Hex(bytes));
            return Optional.of(decoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public <T> Optional<T> inspect(ReviewJob job, String filePath, String sha, TextInspection<T> inspection) {
        try {
            MessageDigest digest = ProvenanceDigest.sha256();
            T result;
            try (var reader = new InputStreamReader(
                    new DigestInputStream(Files.newInputStream(sourceFile(job, filePath)), digest), decoder())) {
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
            ReviewJob job, String filePath, String sha, String text, int startLine, int endLine) {
        try {
            QuoteMatch match = verifyUtf8AtLines(sourceFile(job, filePath), text, startLine, endLine);
            requireDigest(sha, Objects.requireNonNull(match.fileSha256()));
            return Optional.of(match.matches());
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private boolean existsWithDigest(ReviewJob job, String filePath, String sha) {
        return inspect(job, filePath, sha, reader -> Boolean.TRUE).isPresent();
    }

    /** @param fileSha256 digest of the raw bytes read, or null when the cited file does not exist */
    public record QuoteMatch(boolean matches, @Nullable String fileSha256) {
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
    private void retire(ReviewJob owner, Path root) throws IOException {
        EvidenceFolderLease.removeAsOwner(layout.root(), owner.getWorkspaceId(), owner.getId(), () -> {
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
    public void discardAdmittedAttempt(ResultAdmissionService.AdmissionIdentity identity) {
        try {
            var recorded = jobs.findByIdAndWorkspaceId(identity.jobId(), identity.workspaceId());
            if (recorded.isEmpty()) return;
            ReviewJob job = recorded.get();
            if (!ResultAdmissionService.isAdmitted(job)
                    || job.getAttempt() != identity.attempt()
                    || !identity.leaseId().equals(job.getLeaseId())) return;
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

    /** The manifest's rendered companion, which {@link JobFolderIndexBuilder} writes beside it. */
    private static final String INDEX_MARKDOWN_PATH = "INDEX.md";

    /**
     * The finite inputs the feedback of this attempt is composed from, exactly as its folder holds them: the manifest
     * and its rendered index (with every source's recorded bytes, digests, absences and omissions), every captured
     * context record, the practice index and whole standards, the staged history of earlier results, and the
     * checkout's recorded HEAD and refs. Sandbox work and the checkout's objects are not among them. Read while the
     * attempt still owns its folder; a context record the manifest does not list with these exact bytes, or any
     * unreadable file, leaves nothing to retain rather than a part.
     */
    public Optional<Map<String, byte[]>> originalCompositionInputs(ResultAdmissionService.AdmissionIdentity identity) {
        Path root = directory(identity.workspaceId(), identity.jobId(), identity.attempt(), identity.leaseId());
        try {
            var files = new TreeMap<String, byte[]>();
            byte[] index = Files.readAllBytes(regularFile(root, SandboxLayout.MANIFEST_PATH));
            files.put(SandboxLayout.MANIFEST_PATH, index);
            var recordedJob = jobs.findByIdAndWorkspaceId(identity.jobId(), identity.workspaceId())
                    .orElseThrow();
            var capture = Objects.requireNonNull(recordedJob.getCapture());
            var manifest = new JsonMapper().treeToValue(capture.path("manifest"), JobFolderIndex.class);
            files.put(SandboxLayout.MANIFEST_PATH, new JsonMapper().writeValueAsBytes(manifest));
            // The rendered index beside the manifest, only as this attempt was staged with it.
            Path rendered = root.resolve(INDEX_MARKDOWN_PATH);
            if (Files.isRegularFile(rendered, LinkOption.NOFOLLOW_LINKS))
                files.put(INDEX_MARKDOWN_PATH, Files.readAllBytes(rendered));
            for (String prefix : List.of(
                    SandboxLayout.CONTEXT_PREFIX, SandboxLayout.PRACTICES_PREFIX, SandboxLayout.HISTORY_PREFIX)) {
                Path directory = root.resolve(prefix);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var entries = Files.walk(directory)) {
                    for (Path path : entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .toList()) {
                        files.put(root.relativize(path).toString(), Files.readAllBytes(path));
                    }
                }
            }
            var listed = new HashMap<String, CapturedSource>();
            manifest.files().forEach(sourceFile -> listed.put(sourceFile.file().path(), sourceFile.file()));
            for (var text : manifest.citableTexts()) {
                byte[] bytes = Files.readAllBytes(regularFile(root, text.stagedPath()));
                requireDigest(text.sha256(), ProvenanceDigest.sha256Hex(bytes));
                files.put(text.stagedPath(), bytes);
            }
            for (var file : files.entrySet()) {
                boolean recorded = file.getKey().startsWith(SandboxLayout.CONTEXT_PREFIX)
                        || file.getKey().startsWith(SandboxLayout.REPO_MOUNT_RELATIVE);
                if (!recorded) continue;
                CapturedSource sourceFile = listed.get(file.getKey());
                if (sourceFile == null
                        && manifest.citableTexts().stream()
                                .anyMatch(text -> text.stagedPath().equals(file.getKey())
                                        && text.sha256().equals(ProvenanceDigest.sha256Hex(file.getValue())))) continue;
                if (sourceFile == null
                        || sourceFile.bytes() != file.getValue().length
                        || !sourceFile.sha256().equals(ProvenanceDigest.sha256Hex(file.getValue()))) {
                    return Optional.empty();
                }
            }
            for (String path : listed.keySet()) {
                if (path.startsWith(SandboxLayout.CONTEXT_PREFIX) && !files.containsKey(path)) return Optional.empty();
            }
            return Optional.of(Collections.unmodifiableMap(files));
        } catch (IOException | RuntimeException exception) {
            log.warn(
                    "Original writer inputs unreadable: jobId={}, type={}",
                    identity.jobId(),
                    exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Path regularFile(Path root, String relative) throws IOException {
        Path file = safePath(root, relative);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a captured file");
        return file;
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

    /**
     * What a review attempt was launched as, captured before its sandbox existed.
     *
     * @param copiesExpireAt when the occasion record the attempt's inputs copy expires; null when they copy none
     */
    private record LaunchedAttempt(
            long workspaceId,
            UUID jobId,
            int attempt,
            UUID leaseId,
            String image,
            @Nullable String model,
            @Nullable String promptDigest,
            @Nullable String inputsDigest,
            @Nullable Instant copiesExpireAt) {}

    /**
     * Binds the attempt the executor is launching: the job must be RUNNING as exactly this attempt. Anything else, and
     * a mentor session, which is never bound, keeps no transcript.
     */
    @Override
    public Consumer<Map<String, byte[]>> bind(UUID jobId, int attempt, String image) {
        var launched = jobs.findById(jobId)
                .filter(job -> job.getStatus() == ReviewJobStatus.RUNNING
                        && job.getAttempt() == attempt
                        && job.getLeaseId() != null)
                .map(job -> new LaunchedAttempt(
                        job.getWorkspaceId(),
                        jobId,
                        attempt,
                        Objects.requireNonNull(job.getLeaseId()),
                        image,
                        job.getConfigSnapshot() == null
                                ? null
                                : job.getConfigSnapshot()
                                        .path("model")
                                        .path("upstreamModelId")
                                        .asString(null),
                        job.getCapture() == null
                                ? null
                                : job.getCapture().path("promptDigest").asString(null),
                        job.getCapture() == null
                                ? null
                                : job.getCapture().path("inputsDigest").asString(null),
                        copiesExpireAt(job.getCapture())));
        if (launched.isEmpty()) return files -> {};
        var bound = launched.get();
        return files -> keepTranscript(bound, files);
    }

    /** A stated expiry that cannot be read keeps no copy, as if it had passed. */
    private static @Nullable Instant copiesExpireAt(@Nullable JsonNode capture) {
        if (capture == null || !capture.has(COPIES_EXPIRE_AT)) return null;
        try {
            return Instant.parse(capture.path(COPIES_EXPIRE_AT).asString(""));
        } catch (DateTimeParseException unreadable) {
            return Instant.EPOCH;
        }
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
                    .filter(job -> job.getAttempt() == launched.attempt()
                            && launched.leaseId().equals(job.getLeaseId()))
                    .isPresent();
            if (!owned) return;
            Path root = directory(launched.workspaceId(), launched.jobId(), launched.attempt(), launched.leaseId());
            Path trace = root.resolveSibling(root.getFileName() + TRACE_SUFFIX);
            EvidenceFolderLease.writeAsRuntime(layout.root(), launched.workspaceId(), launched.jobId(), () -> {
                var custody = personCopies.traceCustody(launched.jobId(), launched.workspaceId(), launched.attempt());
                if (custody == EvidenceFolderPersonDataCatalog.TraceCustody.NOT_HELD
                        || Files.exists(trace, LinkOption.NOFOLLOW_LINKS)) return;
                Files.createDirectories(root.getParent());
                Path staging = Files.createTempDirectory(root.getParent(), "." + trace.getFileName() + ".preparing-");
                try {
                    Instant now = clock.instant();
                    Instant expiresAt = now.plus(TRACE_RETENTION);
                    var occasionExpiry = launched.copiesExpireAt();
                    boolean occasionKept = occasionExpiry == null || occasionExpiry.isAfter(now);
                    boolean withinBudget = boundedTraces(traces);
                    boolean raw = custody == EvidenceFolderPersonDataCatalog.TraceCustody.INDEXED
                            && withinBudget
                            && occasionKept;
                    // The sessions' words may repeat the occasion's record, so they go when it expires. The summary
                    // that replaces them names no person and copies none of those words.
                    if (raw && occasionExpiry != null && occasionExpiry.isBefore(expiresAt)) expiresAt = occasionExpiry;
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
                                "contentUnavailable",
                                !occasionKept
                                        ? "OCCASION_EVIDENCE_UNAVAILABLE"
                                        : withinBudget ? "OWNERSHIP_INCOMPLETE" : "TRACE_OUTPUT_INVALID");
                    record.put("expiresAt", expiresAt.toString());
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
                    // The sweep counts the retention from this timestamp, as it counts the grace from an ended marker,
                    // and ends it sooner when the record states an earlier expiry.
                    Files.setLastModifiedTime(recordFile, FileTime.from(now));
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
                        if (job.get().getStatus() == ReviewJobStatus.RUNNING) continue;
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
     * is over, or until the earlier expiry its record states. A staging copy left by a stopped writer goes at once: the
     * runtime that wrote it no longer holds the job. The first pass after the expiry that gets the job's lease removes
     * it, so the removal lags the expiry by up to the sweep interval or a lease holder's work.
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
                        || !keptUntil(record).isAfter(clock.instant())) {
                    delete(trace);
                }
            }
        } catch (RuntimeException | IOException exception) {
            log.warn("Could not clean review transcript {}", trace, exception);
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

    private void discardRetiredInventory(Path root, Optional<ReviewJob> recorded) {
        if (recorded.isEmpty() || !matches(root, recorded.get())) return;
        ReviewJob job = recorded.get();
        jobs.expireCaptureInventory(
                job.getId(), job.getWorkspaceId(), job.getAttempt(), Objects.requireNonNull(job.getLeaseId()));
    }

    private void cleanStaleGitSpool() {
        if (!Files.isDirectory(layout.root())) return;
        try (var entries = Files.list(layout.root())) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!name.startsWith(EvidenceSpool.PREFIX)) continue;
                if (EvidenceSpool.isCurrentProcess(entry)) continue;
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
        return job.getLeaseId() != null && directory(job).equals(root);
    }

    private static boolean admitted(ReviewJob job) {
        return ResultAdmissionService.isAdmitted(job);
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

    private Path sourceFile(ReviewJob job, String filePath) throws IOException {
        Path root = directory(job);
        Path sourceFile = safePath(root, filePath);
        if (!sourceFile.toRealPath().startsWith(root.toRealPath())
                || !Files.isRegularFile(sourceFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Evidence path is not a regular captured file");
        }
        return sourceFile;
    }

    private Path directory(ReviewJob job) {
        UUID lease = Objects.requireNonNull(job.getLeaseId(), "Attempt has no lease");
        return directory(job.getWorkspaceId(), job.getId(), job.getAttempt(), lease);
    }

    private Path directory(long workspaceId, UUID jobId, int attempt, UUID lease) {
        return layout.jobsRoot()
                .resolve(Long.toString(workspaceId))
                .resolve(jobId.toString())
                .resolve(attempt + "-"
                        + ProvenanceDigest.sha256Hex(lease.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The collector's manifest is sandbox output: what a METADATA_ONLY record keeps is rebuilt here from it, field by
     * field, as owned enums, booleans, bounded counts and numeric identifiers. Nothing the manifest carries is copied
     * as written.
     */
    private static final class TraceSummary {
        private static final JsonMapper MANIFESTS = new JsonMapper();
        /** The collector's own reserve for its manifest (gateway-run.ts); a larger one was not written by it. */
        private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

        private static final int MAX_SESSIONS = 1000;
        private static final long MAX_COUNT = 1_000_000;
        private static final long MAX_TOKENS = 1_000_000_000_000L;
        private static final long MAX_BYTES = TRACE_BYTE_BUDGET;
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
                "upsert_problem",
                "remove_problem",
                "read_problem",
                "submit_result",
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
                putPracticeVersionId(facts, summary.path("practiceVersionId"));
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

        private static void putPracticeVersionId(ObjectNode facts, JsonNode value) {
            if (!value.isString()) return;
            String version = value.asString();
            if (version.matches("[1-9][0-9]{0,18}")
                    && (version.length() < 19 || version.compareTo(Long.toString(Long.MAX_VALUE)) <= 0)) {
                facts.put("practiceVersionId", version);
            }
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

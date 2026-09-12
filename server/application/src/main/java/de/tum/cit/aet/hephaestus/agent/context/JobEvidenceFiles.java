package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Worker-local evidence retained through admission or the failed-attempt cleanup grace. */
@Component
public class JobEvidenceFiles {
    private static final Logger log = LoggerFactory.getLogger(JobEvidenceFiles.class);

    /**
     * Malformed input decodes to a lone surrogate, which no valid UTF-8 sequence produces and no quote
     * survives {@code CitationVerification.quoteDigest} carrying, so a quote can only verify against
     * bytes that decode and an undecodable byte elsewhere in the artifact costs nothing.
     */
    private static final String UNDECODABLE = "\uDC00";

    private final FabricLayout layout;
    private final AgentJobRepository jobs;
    private final Clock clock;

    public JobEvidenceFiles(FabricLayout layout, AgentJobRepository jobs, Clock clock) {
        this.layout = layout;
        this.jobs = jobs;
        this.clock = clock;
    }

    public PreparedJobInputs prepare(AgentJob job, PreparedJobInputs inputs) {
        Path root = directory(job);
        Path staging = null;
        try {
            Files.createDirectories(root.getParent());
            if (Files.exists(root)) throw new IllegalStateException("Attempt evidence already exists");
            staging = Files.createTempDirectory(root.getParent(), "." + root.getFileName() + ".preparing-");
            var staged = new LinkedHashMap<String, Path>();
            var frozen = new LinkedHashMap<String, byte[]>();
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
                frozen.put(entry.getKey(), bytes);
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
            var cleanups = new ArrayList<>(inputs.cleanups());
            var closed = new AtomicBoolean();
            cleanups.add(() -> {
                if (closed.compareAndSet(false, true)) {
                    retire(root);
                }
            });
            return new PreparedJobInputs(
                    frozen,
                    staged,
                    directories,
                    cleanups,
                    inputs.artifactSourceManifest(),
                    inputs.automatedReviewReadinessReport());
        } catch (IOException | RuntimeException exception) {
            if (staging != null) delete(staging);
            inputs.close();
            throw new IllegalStateException("Could not prepare attempt evidence: " + job.getId(), exception);
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
        String headPath = SandboxLayout.REPO_MOUNT_RELATIVE + ".git/HEAD";
        inspect(job, headPath, headSha256, reader -> Boolean.TRUE)
                .orElseThrow(() -> new IllegalStateException("Captured repository is unavailable"));
        inspect(
                        job,
                        SandboxLayout.REPO_MOUNT_RELATIVE + ".git/hephaestus-captured-refs",
                        refsSha256,
                        reader -> Boolean.TRUE)
                .orElseThrow(() -> new IllegalStateException("Captured repository refs are unavailable"));
        return directory(job).resolve(SandboxLayout.REPO_MOUNT_RELATIVE);
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

    private void retire(Path root) throws IOException {
        if (!Files.exists(root)) return;
        var job = recordedJob(root);
        if (job.isPresent() && admitted(job.get()) && matches(root, job.get())) {
            deleteAttempt(root);
        } else {
            markEnded(root);
        }
    }

    public void cleanEndedAttempts() {
        if (!Files.isDirectory(layout.jobsRoot())) return;
        try (var paths = Files.walk(layout.jobsRoot(), 3)) {
            var roots = new HashSet<Path>();
            paths.filter(path -> layout.jobsRoot().relativize(path).getNameCount() == 3)
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        if (name.startsWith(".") && name.contains(".preparing-")) {
                            roots.add(path.resolveSibling(name.substring(1, name.indexOf(".preparing-"))));
                        } else if (name.endsWith(".ended")) {
                            roots.add(path.resolveSibling(name.substring(0, name.lastIndexOf('.'))));
                        } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            roots.add(path);
                        }
                    });
            for (Path root : roots) {
                if (!root.getFileName().toString().matches("[0-9]+-[0-9a-f]{64}")) continue;
                try {
                    var job = recordedJob(root);
                    if (job.isPresent() && matches(root, job.get())) {
                        if (job.get().getStatus() == AgentJobStatus.RUNNING) continue;
                        if (admitted(job.get())) {
                            deleteAttempt(root);
                            continue;
                        }
                    }
                    Path ended = markEnded(root);
                    if (!Files.getLastModifiedTime(ended)
                            .toInstant()
                            .plus(Duration.ofHours(1))
                            .isAfter(clock.instant())) {
                        deleteAttempt(root);
                    }
                } catch (RuntimeException | IOException exception) {
                    log.warn("Could not clean attempt folder {}", root, exception);
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

    /** Evidence outlives admission only while the attempt that admitted it can still fail. */
    private static boolean admitted(AgentJob job) {
        return job.getStatus() == AgentJobStatus.COMPLETED && ObservationAdmissionService.isAdmitted(job);
    }

    private Path markEnded(Path root) throws IOException {
        Path ended = root.resolveSibling(root.getFileName() + ".ended");
        try {
            Files.createFile(ended);
            Files.setLastModifiedTime(ended, FileTime.from(clock.instant()));
        } catch (FileAlreadyExistsException ignored) {
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
        return layout.jobsRoot()
                .resolve(job.getWorkspace().getId().toString())
                .resolve(job.getId().toString())
                .resolve(job.getRetryCount() + "-"
                        + ProvenanceDigest.sha256Hex(worker.getBytes(StandardCharsets.UTF_8)));
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

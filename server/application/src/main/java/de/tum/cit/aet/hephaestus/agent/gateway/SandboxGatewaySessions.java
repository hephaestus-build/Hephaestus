package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxOutputArchive;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnWorkerRole
public class SandboxGatewaySessions {
    private static final Logger log = LoggerFactory.getLogger(SandboxGatewaySessions.class);

    public static final long WORKSPACE_BYTE_BUDGET = 512L * 1024 * 1024;
    private final long workspaceByteBudget;
    private final List<SandboxResultListener> listeners;

    public SandboxGatewaySessions() {
        this(WORKSPACE_BYTE_BUDGET, List.of());
    }

    @Autowired
    public SandboxGatewaySessions(ObjectProvider<SandboxResultListener> listeners) {
        this(WORKSPACE_BYTE_BUDGET, listeners.orderedStream().toList());
    }

    SandboxGatewaySessions(long workspaceByteBudget) {
        this(workspaceByteBudget, List.of());
    }

    SandboxGatewaySessions(long workspaceByteBudget, List<SandboxResultListener> listeners) {
        if (workspaceByteBudget <= 0) throw new IllegalArgumentException("Workspace budget must be positive");
        this.workspaceByteBudget = workspaceByteBudget;
        this.listeners = List.copyOf(listeners);
    }

    public long workspaceByteBudget() {
        return workspaceByteBudget;
    }

    private static final String BEARER_PREFIX = "Bearer ";
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public Session register(String token, Path inputTar, String outputRoot) throws IOException {
        return register(UUID.randomUUID(), token, inputTar, outputRoot);
    }

    public Session register(UUID id, String token, Path inputTar, String outputRoot) throws IOException {
        return register(id, token, inputTar, outputRoot, List.of());
    }

    /**
     * A review attempt's session. Each listener binds the attempt now, before its sandbox exists, so an upload is only
     * ever handled as the attempt it was launched for. A listener that cannot bind does not stop the launch.
     */
    public Session registerAttempt(
            UUID jobId, int attempt, String image, String token, Path inputTar, String outputRoot) throws IOException {
        var bound = new ArrayList<Consumer<Map<String, byte[]>>>();
        for (SandboxResultListener listener : listeners) {
            try {
                bound.add(listener.bind(jobId, attempt, image));
            } catch (RuntimeException exception) {
                log.warn(
                        "A result listener could not bind job {}: {}",
                        jobId,
                        exception.getClass().getSimpleName());
            }
        }
        return register(jobId, token, inputTar, outputRoot, bound);
    }

    private Session register(
            UUID id, String token, Path inputTar, String outputRoot, List<Consumer<Map<String, byte[]>>> admitted)
            throws IOException {
        var session = new Session(id, token, inputTar, outputRoot, admitted);
        if (sessions.putIfAbsent(id, session) != null) {
            throw new IllegalStateException("Gateway session already exists for this job");
        }
        return session;
    }

    /** A wrong credential and an unknown session look the same to the caller. */
    public Session require(UUID id, String authorization) {
        var session = sessions.get(id);
        String token = bearerToken(authorization);
        if (session == null || token == null || !MessageDigest.isEqual(session.tokenHash, tokenHash(token))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return session;
    }

    private static @Nullable String bearerToken(String authorization) {
        if (!authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) return null;
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static byte[] tokenHash(String token) {
        return AgentJob.computeTokenHash(token).getBytes(StandardCharsets.US_ASCII);
    }

    private void requireBudget(long bytes) {
        if (bytes < 0 || bytes > workspaceByteBudget)
            throw new WorkspaceBudgetExceededException(bytes, workspaceByteBudget);
    }

    public final class Session implements AutoCloseable {
        private final UUID id;
        private final byte[] tokenHash;
        private final Path inputTar;
        private final String outputRoot;
        private final long inputBytes;
        private final Map<String, Path> selections = new HashMap<>();
        private long downloadedBytes;
        private boolean closed;
        private boolean uploading;
        private @Nullable GatewayInteractiveChannel interactive;
        private @Nullable Map<String, byte[]> result;
        private byte @Nullable [] resultDigest;
        private final List<Consumer<Map<String, byte[]>>> admitted;

        private Session(
                UUID id, String token, Path inputTar, String outputRoot, List<Consumer<Map<String, byte[]>>> admitted)
                throws IOException {
            this.id = id;
            this.admitted = List.copyOf(admitted);
            this.tokenHash = tokenHash(token);
            this.inputTar = inputTar;
            this.outputRoot = outputRoot;
            this.inputBytes = Files.size(inputTar);
            requireBudget(inputBytes);
            this.downloadedBytes = inputBytes;
        }

        public UUID id() {
            return id;
        }

        public long inputBytes() {
            return inputBytes;
        }

        public synchronized GatewayInteractiveChannel enableInteractive(int maxFrameBytes) throws IOException {
            if (interactive != null || closed) {
                throw new IllegalStateException("Interactive session already initialized or closed");
            }
            interactive = new GatewayInteractiveChannel(maxFrameBytes);
            return interactive;
        }

        public synchronized GatewayInteractiveChannel interactive() {
            if (interactive == null || closed) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return interactive;
        }

        public synchronized @Nullable Integer frameByteBudget() {
            return interactive == null ? null : interactive.maxFrameBytes();
        }

        public synchronized InputStream download() throws IOException {
            if (closed) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            // A dropped transfer can restart from byte zero from the same staged archive.
            return Files.newInputStream(inputTar);
        }

        /** Selections can only contain bytes from the permission-checked, frozen initial archive. */
        public synchronized Download download(String parameter, String value) throws IOException {
            if (closed) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            String prefix = selectionPrefix(parameter, value);
            Path selected = selections.get(prefix);
            if (selected == null) {
                selected = Files.createTempFile(inputTar.getParent(), "workspace-selection-", ".tar");
                boolean retained = false;
                try {
                    boolean found = false;
                    try (var input = new TarArchiveInputStream(Files.newInputStream(inputTar));
                            var output = new TarArchiveOutputStream(Files.newOutputStream(selected))) {
                        output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                        output.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                        TarArchiveEntry entry;
                        while ((entry = input.getNextEntry()) != null) {
                            if (!entry.getName().startsWith(prefix)) continue;
                            Path path = Path.of(entry.getName());
                            if (path.isAbsolute()
                                    || !path.normalize().equals(path)
                                    || entry.getName().contains("\\")
                                    || (!entry.isFile() && !entry.isDirectory())) {
                                throw new IOException("Unsafe workspace archive entry");
                            }
                            requireBudget(downloadedBytes + output.getBytesWritten() + entry.getSize());
                            output.putArchiveEntry(entry);
                            input.transferTo(output);
                            output.closeArchiveEntry();
                            found |= entry.isFile();
                        }
                    }
                    if (!found) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    long bytes = Files.size(selected);
                    requireBudget(downloadedBytes + bytes);
                    selections.put(prefix, selected);
                    downloadedBytes += bytes;
                    retained = true;
                } finally {
                    if (!retained) Files.deleteIfExists(selected);
                }
            }
            return new Download(Files.newInputStream(selected), Files.size(selected));
        }

        private static String selectionPrefix(String parameter, String value) {
            if ("area".equals(parameter)
                    && Set.of("scm", "chat", "docs", "people", "practices").contains(value)) {
                return "context/" + value + "/";
            }
            if ("repo".equals(parameter) && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                return "repos/" + value + "/";
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        public record Download(InputStream input, long bytes) implements AutoCloseable {
            @Override
            public void close() throws IOException {
                input.close();
            }
        }

        public record UploadResult(boolean admitted, String etag) {}

        public UploadResult upload(InputStream input, String contentDigest) throws IOException {
            byte[] expectedDigest = parseDigest(contentDigest);
            synchronized (this) {
                if (closed) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                }
                if (uploading) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Result upload in progress");
                }
                uploading = true;
            }
            try {
                var digest = sha256();
                var files =
                        Map.copyOf(new SandboxOutputArchive().read(new DigestInputStream(input, digest), outputRoot));
                byte[] actualDigest = digest.digest();
                if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Result digest does not match its body");
                }
                synchronized (this) {
                    if (closed) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    }
                    if (resultDigest != null) {
                        return new UploadResult(false, etag(resultDigest));
                    }
                    result = files;
                    resultDigest = actualDigest;
                }
                // Before the sandbox is answered, so whatever a listener keeps exists before the attempt can end.
                for (var listener : admitted) {
                    try {
                        listener.accept(files);
                    } catch (RuntimeException exception) {
                        log.warn(
                                "A result listener failed for session {}: {}",
                                id,
                                exception.getClass().getSimpleName());
                    }
                }
                return new UploadResult(true, etag(actualDigest));
            } finally {
                synchronized (this) {
                    uploading = false;
                }
            }
        }

        private static String etag(byte[] digest) {
            return '"' + HexFormat.of().formatHex(digest) + '"';
        }

        private static byte[] parseDigest(String contentDigest) {
            if (!contentDigest.startsWith("sha-256=:") || !contentDigest.endsWith(":")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A SHA-256 Content-Digest is required");
            }
            try {
                byte[] digest = Base64.getDecoder().decode(contentDigest.substring(9, contentDigest.length() - 1));
                if (digest.length == 32
                        && contentDigest.equals(
                                "sha-256=:" + Base64.getEncoder().encodeToString(digest) + ":")) {
                    return digest;
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid base64 is the same invalid request as an unsupported digest.
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid SHA-256 Content-Digest");
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("JVM does not provide SHA-256", exception);
            }
        }

        public synchronized Map<String, byte[]> result() {
            if (result == null) {
                throw new IllegalStateException("Sandbox exited without uploading its result");
            }
            return result;
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
            sessions.remove(id, this);
            try {
                if (interactive != null) {
                    interactive.close();
                }
            } finally {
                try {
                    for (Path selection : selections.values()) Files.deleteIfExists(selection);
                } finally {
                    Files.deleteIfExists(inputTar);
                }
            }
        }
    }
}

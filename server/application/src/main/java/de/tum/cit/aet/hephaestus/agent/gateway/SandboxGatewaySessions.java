package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxOutputArchive;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnAgentRuntime
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

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** Keeps this replica's frozen input archive until its custody is released. */
    public Session register(UUID id, Path inputTar) throws IOException {
        var session = new Session(id, inputTar);
        if (sessions.putIfAbsent(id, session) != null)
            throw new IllegalStateException("Gateway session already exists for this job");
        return session;
    }

    /** A result is admitted by the domain fence, without recreating its input archive on this replica. */
    public ReceivedResult receiveAttempt(ReviewJob job, String image, InputStream input, String contentDigest)
            throws IOException {
        var uploaded = readUpload(input, contentDigest, "out");
        notifyUploaded(job.getId(), bind(job.getId(), job.getAttempt(), image), uploaded.files());
        return uploaded;
    }

    public record ReceivedResult(Map<String, byte[]> files, String etag) {}

    private List<Consumer<Map<String, byte[]>>> bind(UUID jobId, int attempt, String image) {
        var bound = new ArrayList<Consumer<Map<String, byte[]>>>();
        for (var listener : listeners) {
            try {
                bound.add(listener.bind(jobId, attempt, image));
            } catch (RuntimeException exception) {
                log.warn(
                        "A result listener could not bind job {}: {}",
                        jobId,
                        exception.getClass().getSimpleName());
            }
        }
        return bound;
    }

    private static void notifyUploaded(
            UUID id, List<Consumer<Map<String, byte[]>>> listeners, Map<String, byte[]> files) {
        for (var listener : listeners) {
            try {
                listener.accept(files);
            } catch (RuntimeException exception) {
                log.warn(
                        "A result listener failed for session {}: {}",
                        id,
                        exception.getClass().getSimpleName());
            }
        }
    }

    private static ReceivedResult readUpload(InputStream input, String contentDigest, String outputRoot)
            throws IOException {
        byte[] expected = parseDigest(contentDigest);
        var digest = sha256();
        var files = Map.copyOf(new SandboxOutputArchive().read(new DigestInputStream(input, digest), outputRoot));
        byte[] actual = digest.digest();
        if (!MessageDigest.isEqual(expected, actual))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Result digest does not match its body");
        return new ReceivedResult(files, etag(actual));
    }

    /** Jobs whose archives this server still holds, also when their attempt folders have already gone. */
    public Set<UUID> jobIds() {
        return Set.copyOf(sessions.keySet());
    }

    /** Removes the local archive before a job's evidence removal is acknowledged. */
    public void closeJob(UUID jobId) {
        Session session = sessions.get(jobId);
        if (session == null) return;
        try {
            session.close();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void requireBudget(long bytes) {
        if (bytes < 0 || bytes > workspaceByteBudget)
            throw new WorkspaceBudgetExceededException(bytes, workspaceByteBudget);
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
                    && contentDigest.equals("sha-256=:" + Base64.getEncoder().encodeToString(digest) + ":")) {
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

    /** A local input archive only; results are admitted without a replica session. */
    public final class Session implements AutoCloseable {
        private final UUID id;
        private final Path inputTar;
        private final long inputBytes;
        private boolean closed;

        private Session(UUID id, Path inputTar) throws IOException {
            this.id = id;
            this.inputTar = inputTar;
            this.inputBytes = Files.size(inputTar);
            requireBudget(inputBytes);
        }

        public long inputBytes() {
            return inputBytes;
        }

        public synchronized InputStream download() throws IOException {
            if (closed) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            return Files.newInputStream(inputTar);
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
            Files.deleteIfExists(inputTar);
            sessions.remove(id, this);
        }
    }
}

package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxOutputArchive;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
public class SandboxGatewaySessions {
    private static final String BEARER_PREFIX = "Bearer ";
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public Session register(String token, Path inputTar, String outputRoot) throws IOException {
        return register(UUID.randomUUID(), token, inputTar, outputRoot);
    }

    public Session register(UUID id, String token, Path inputTar, String outputRoot) throws IOException {
        var session = new Session(id, token, inputTar, outputRoot);
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

    public final class Session implements AutoCloseable {
        private final UUID id;
        private final byte[] tokenHash;
        private final Path inputTar;
        private final String outputRoot;
        private final long inputBytes;
        private boolean closed;
        private boolean uploading;
        private @Nullable GatewayInteractiveChannel interactive;
        private @Nullable Map<String, byte[]> result;
        private byte @Nullable [] resultDigest;

        private Session(UUID id, String token, Path inputTar, String outputRoot) throws IOException {
            this.id = id;
            this.tokenHash = tokenHash(token);
            this.inputTar = inputTar;
            this.outputRoot = outputRoot;
            this.inputBytes = Files.size(inputTar);
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
                    return new UploadResult(true, etag(actualDigest));
                }
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
                Files.deleteIfExists(inputTar);
            }
        }
    }
}

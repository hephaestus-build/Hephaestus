package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxOutputArchive;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public Session register(String token, Path inputTar, String outputRoot) throws IOException {
        var session = new Session(UUID.randomUUID(), token, inputTar, outputRoot);
        sessions.put(session.id(), session);
        return session;
    }

    public Session require(UUID id, String authorization) {
        var session = sessions.get(id);
        if (session == null
                || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(session.tokenHash, tokenHash(authorization.substring(7)))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return session;
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
        private boolean downloaded;
        private boolean closed;
        private boolean uploading;
        private @Nullable GatewayInteractiveChannel interactive;
        private @Nullable Map<String, byte[]> result;

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

        public synchronized GatewayInteractiveChannel enableInteractive(int maxFrameBytes, int writeTimeoutMs)
                throws IOException {
            if (interactive != null || closed) {
                throw new IllegalStateException("Interactive session already initialized or closed");
            }
            interactive = new GatewayInteractiveChannel(maxFrameBytes, writeTimeoutMs);
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
            if (downloaded) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Workspace budget already consumed");
            }
            var stream = Files.newInputStream(inputTar);
            downloaded = true;
            return stream;
        }

        public void upload(InputStream input) throws IOException {
            synchronized (this) {
                if (closed) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                }
                if (result != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Result already uploaded");
                }
                if (uploading) {
                    throw new ResponseStatusException(HttpStatus.TOO_EARLY, "Result upload in progress");
                }
                uploading = true;
            }
            try {
                var files = Map.copyOf(new SandboxOutputArchive().read(input, outputRoot));
                synchronized (this) {
                    if (closed) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    }
                    result = files;
                }
            } finally {
                synchronized (this) {
                    uploading = false;
                }
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

package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * A backend that runs nothing: each stage reports what the test scripted, and the runtime uploads its result through
 * the attempt's gateway session as a sandbox does. It records what the runner asked of it.
 */
final class FakeSandboxBackend implements SandboxBackend {

    static final String GATEWAY = "http://10.0.0.2:8081";

    /** A step that the test runs in place of a stage; it may block, cancel, or throw. */
    @FunctionalInterface
    interface Step {
        void run() throws Exception;
    }

    private final SandboxGatewaySessions sessions;

    final List<Launch> launches = new CopyOnWriteArrayList<>();
    final List<String> calls = new CopyOnWriteArrayList<>();
    volatile Map<String, String> environment = Map.of();

    volatile Step onLaunch = () -> {};
    volatile Step onEnded = () -> {};
    volatile @Nullable RuntimeException killFailure;
    volatile @Nullable RuntimeException removeFailure;

    volatile Observation initialized = new Observation(false, 0);
    volatile Observation ended = new Observation(false, 0);

    /** What the runtime uploads before it ends; null uploads nothing. */
    volatile @Nullable Map<String, byte[]> result = Map.of("result.json", "{}".getBytes(StandardCharsets.UTF_8));

    volatile String initializerTranscript = "";
    volatile String runtimeTranscript = "";

    FakeSandboxBackend(SandboxGatewaySessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public Handle launch(Launch launch) {
        launches.add(launch);
        environment = launch.environment().at(GATEWAY);
        calls.add("launch");
        run(onLaunch);
        return new Handle("fake-" + launch.id());
    }

    @Override
    public Observation observe(Handle handle, Stage stage, Instant deadline) {
        calls.add("observe " + stage);
        if (stage == Stage.ENDED) {
            run(onEnded);
            Map<String, byte[]> files = result;
            if (files != null) {
                upload(files);
            }
            return ended;
        }
        return stage == Stage.INITIALIZED ? initialized : new Observation(false, null);
    }

    @Override
    public boolean running(Handle handle) {
        return false;
    }

    @Override
    public String transcript(Handle handle, Container container) {
        calls.add("transcript " + container);
        return container == Container.INITIALIZER ? initializerTranscript : runtimeTranscript;
    }

    @Override
    public void kill(Handle handle) {
        calls.add("kill");
        RuntimeException failure = killFailure;
        if (failure != null) {
            killFailure = null;
            throw failure;
        }
    }

    @Override
    public void remove(Handle handle) {
        calls.add("remove");
        RuntimeException failure = removeFailure;
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public List<Listed> list() {
        return List.of();
    }

    Launch launch() {
        return launches.getFirst();
    }

    /** Uploads the result archive through the session that the sandbox's environment names. */
    private void upload(Map<String, byte[]> files) {
        String runtimeUrl = Objects.requireNonNull(environment.get("SANDBOX_RUNTIME_URL"));
        UUID sessionId = UUID.fromString(runtimeUrl.substring(runtimeUrl.lastIndexOf('/') + 1));
        try {
            byte[] archive = resultTar(files);
            sessions.require(sessionId, "Bearer " + environment.get("LLM_PROXY_TOKEN"))
                    .upload(
                            new ByteArrayInputStream(archive),
                            "sha-256=:"
                                    + Base64.getEncoder()
                                            .encodeToString(MessageDigest.getInstance("SHA-256")
                                                    .digest(archive))
                                    + ":");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] resultTar(Map<String, byte[]> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                var entry = new TarArchiveEntry("out/" + file.getKey());
                entry.setSize(file.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void run(Step step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

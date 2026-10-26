package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link HostSandbox} hosts on the sandbox a practice review uses: the workspace filled from the host's files
 * through a host gateway session, then the host's process with {@code /workspace} read-only.
 */
public class SandboxHosts implements HostSandbox {

    private static final Logger log = LoggerFactory.getLogger(SandboxHosts.class);

    private final SandboxBackend backend;
    private final SandboxWorkspaceManager workspaces;
    private final SandboxGatewaySessions gatewaySessions;
    private final String image;
    private final Map<UUID, Resources> running = new ConcurrentHashMap<>();

    public SandboxHosts(
            SandboxBackend backend,
            SandboxWorkspaceManager workspaces,
            SandboxGatewaySessions gatewaySessions,
            String image) {
        this.backend = backend;
        this.workspaces = workspaces;
        this.gatewaySessions = gatewaySessions;
        this.image = image;
    }

    /** What a host runs on, for its removal. */
    private record Resources(
            SandboxBackend.@Nullable Handle handle, SandboxGatewaySessions.@Nullable Session session) {}

    @Override
    public boolean ready() {
        return backend.ready();
    }

    @Override
    public Host launch(HostSpec spec) throws IOException {
        UUID id = UUID.randomUUID();
        String token = ReviewJob.generateJobToken();
        SandboxGatewaySessions.Session session = null;
        SandboxBackend.Handle handle = null;
        try {
            session = openSession(id, token, spec);
            UUID sessionId = session.id();
            handle = backend.launch(new SandboxBackend.Launch(
                    SandboxBackend.Kind.HOST, id, null, image, spec.command(), spec.limits(), null, gateway -> {
                        Map<String, String> environment = new LinkedHashMap<>(spec.environment());
                        environment.put("LLM_PROXY_URL", gateway + "/internal/llm");
                        environment.put("SANDBOX_RUNTIME_URL", gateway + "/internal/llm/runtime/" + sessionId);
                        environment.put("LLM_PROXY_TOKEN", token);
                        return environment;
                    }));
            // The limits' maxRuntime bounds only the workspace's filling and the start of the host's process.
            Instant deadline = Instant.now().plus(spec.limits().maxRuntime());
            SandboxBackend.Observation initialized =
                    backend.observe(handle, SandboxBackend.Stage.INITIALIZED, deadline);
            Integer exit = initialized.exitCode();
            if (initialized.timedOut() || exit == null || exit != 0) {
                log.warn(
                        "The workspace of host {} could not be initialized: exitCode={}, timedOut={}",
                        id,
                        exit,
                        initialized.timedOut());
                throw new IOException("The workspace of host " + id + " could not be initialized (exit " + exit
                        + (initialized.timedOut() ? ", timed out" : "") + ")");
            }
            SandboxBackend.Observation started = backend.observe(handle, SandboxBackend.Stage.STARTED, deadline);
            if (started.timedOut() || started.exitCode() != null) {
                throw new IOException("The process of host " + id + " did not start");
            }
            running.put(id, new Resources(handle, session));
            log.info("Started host {}", id);
            return new Host(id);
        } catch (SandboxException e) {
            remove(id, new Resources(handle, session));
            // A host that could not start is the caller's IOException; the backend logged a cause it knows.
            throw new IOException("Host " + id + " could not start", e);
        } catch (IOException | RuntimeException e) {
            remove(id, new Resources(handle, session));
            throw e;
        }
    }

    @Override
    public boolean isRunning(Host host) {
        Resources resources = running.get(host.id());
        SandboxBackend.Handle handle = resources == null ? null : resources.handle();
        return handle != null && backend.running(handle);
    }

    @Override
    public void remove(Host host) {
        Resources resources = running.remove(host.id());
        if (resources != null) {
            remove(host.id(), resources);
        }
    }

    private SandboxGatewaySessions.Session openSession(UUID id, String token, HostSpec spec) throws IOException {
        Path inputTar = workspaces.createInputTar(spec.files(), Map.of());
        try {
            return gatewaySessions.registerHost(id, token, inputTar, "out");
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(inputTar);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    /**
     * Removes what the host runs on. The host's output is not read: anything in the host can write to it, Pi and Node
     * included, so it can hold the conversation, a provider's answer or a credential. What the worker logs of a host
     * is its id and the class of a failed removal step.
     */
    private void remove(UUID id, Resources resources) {
        log.info("Removing host {}", id);
        SandboxBackend.Handle handle = resources.handle();
        if (handle != null) {
            try {
                backend.remove(handle);
            } catch (RuntimeException e) {
                // The class only, here and below: a runtime client exception can quote the response body.
                log.warn(
                        "Could not remove the sandbox of host {}: {}",
                        id,
                        e.getClass().getSimpleName());
            }
        }
        SandboxGatewaySessions.Session session = resources.session();
        if (session != null) {
            try {
                session.close();
            } catch (IOException | RuntimeException e) {
                log.warn(
                        "Could not close the gateway session of host {}: {}",
                        id,
                        e.getClass().getSimpleName());
            }
        }
    }
}

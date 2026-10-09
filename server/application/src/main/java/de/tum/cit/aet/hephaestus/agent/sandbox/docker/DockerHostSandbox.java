package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs a {@link HostSandbox} host on the sandbox primitives a practice review uses. */
public class DockerHostSandbox implements HostSandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerHostSandbox.class);

    private static final String CONTAINER_USER = "1000:1000";
    private static final String CONTAINER_HOSTNAME = "host";

    private final SandboxNetworkManager networks;
    private final SandboxWorkspaceManager workspaces;
    private final SandboxContainerManager containers;
    private final ContainerSecurityPolicy securityPolicy;
    private final SandboxCreator creator;
    private final SandboxAttemptLauncher launcher;
    private final String image;
    private final String owner;
    private final int gatewayPort;
    private final Map<UUID, Resources> running = new ConcurrentHashMap<>();

    public DockerHostSandbox(
            SandboxNetworkManager networks,
            SandboxWorkspaceManager workspaces,
            SandboxContainerManager containers,
            ContainerSecurityPolicy securityPolicy,
            SandboxCreator creator,
            SandboxGatewaySessions gatewaySessions,
            DockerVolumeOperations volumes,
            String image,
            String owner,
            int gatewayPort) {
        this.networks = networks;
        this.workspaces = workspaces;
        this.containers = containers;
        this.securityPolicy = securityPolicy;
        this.creator = creator;
        this.launcher = new SandboxAttemptLauncher(containers, gatewaySessions, volumes, gatewayPort);
        this.image = image;
        this.owner = owner;
        this.gatewayPort = gatewayPort;
    }

    /** What a host runs on, for its removal. */
    private record Resources(
            @Nullable String containerId,
            @Nullable String networkId,
            SandboxAttemptLauncher.@Nullable Attempt attempt) {}

    @Override
    public Host launch(HostSpec spec) throws IOException {
        UUID id = UUID.randomUUID();
        String token = AgentJob.generateJobToken();
        Map<String, String> labels = new HashMap<>(creator.labels());
        // A worker on the machine itself joins no network, so its host reaches it through the host gateway, which
        // only a network with a route out reaches. A worker in a container that it cannot identify cannot join the
        // host's internal network either; it starts no host rather than give the host that route.
        boolean outsideDocker = labels.isEmpty();
        if (outsideDocker && !creator.outsideContainer()) {
            throw new IOException("This worker runs in a container that it cannot identify, so it cannot join the "
                    + "internal network of its Heph host. Keep the worker container's default hostname.");
        }
        labels.put(SandboxLabels.OWNER, owner);
        labels.put(SandboxLabels.KIND, SandboxLabels.KIND_HEPH_HOST);
        labels.put(SandboxLabels.SESSION_ID, id.toString());
        NetworkPolicy policy = new NetworkPolicy(outsideDocker, null, token);

        String networkId = null;
        SandboxAttemptLauncher.Attempt attempt = null;
        String containerId = null;
        try {
            networkId = networks.createJobNetwork(id, outsideDocker, labels);
            String workerAddress = networks.connectAppServer(networkId);
            List<String> extraHosts = List.of();
            if (workerAddress == null) {
                log.warn(
                        "This worker runs outside a container, so its host {} is on a network with internet access",
                        id);
                workerAddress = "host.docker.internal";
                extraHosts = List.of("host.docker.internal:host-gateway");
            }
            attempt = launcher.open(id, null, null, policy, workspaces.createInputTar(spec.files(), Map.of()), labels);
            Map<String, String> environment = new LinkedHashMap<>(spec.environment());
            environment.put("LLM_PROXY_URL", "http://" + workerAddress + ":" + gatewayPort + "/internal/llm");
            environment.put("SANDBOX_RUNTIME_URL", attempt.runtimeUrl(workerAddress));
            environment.put("LLM_PROXY_TOKEN", token);
            DockerOperations.ContainerSpec template = new DockerOperations.ContainerSpec(
                    image,
                    List.of(),
                    environment,
                    networkId,
                    CONTAINER_HOSTNAME,
                    CONTAINER_USER,
                    labels,
                    securityPolicy.buildHostConfig(SecurityProfile.DEFAULT, spec.limits(), policy),
                    extraHosts);
            var initialized = attempt.initialize(template, spec.limits().maxRuntime(), ignored -> {});
            if (!initialized.succeeded()) {
                throw new IOException("The workspace of host " + id + " could not be initialized (exit "
                        + initialized.exitCode() + (initialized.timedOut() ? ", timed out" : "") + ")");
            }
            containerId = containers.createContainer(attempt.runtime(template, spec.command()));
            containers.startContainer(containerId);
            running.put(id, new Resources(containerId, networkId, attempt));
            log.info("Started host {}", id);
            return new Host(id);
        } catch (IOException | RuntimeException e) {
            remove(id, new Resources(containerId, networkId, attempt));
            throw e;
        }
    }

    @Override
    public boolean isRunning(Host host) {
        Resources resources = running.get(host.id());
        String containerId = resources == null ? null : resources.containerId();
        return containerId != null
                && containers.listManagedContainers().stream()
                        .anyMatch(
                                container -> container.id().equals(containerId) && "running".equals(container.state()));
    }

    @Override
    public void remove(Host host) {
        Resources resources = running.remove(host.id());
        if (resources != null) {
            remove(host.id(), resources);
        }
    }

    /**
     * Removes what the host runs on. The host writes its diagnostics to its output, so the end of that output goes to
     * this worker's log first: why a host could not start, or what it reported before it was lost.
     */
    private void remove(UUID id, Resources resources) {
        String containerId = resources.containerId();
        if (containerId != null) {
            String output = containers.getLogs(containerId, DockerSandboxAdapter.ERROR_ECHO_TAIL_LINES);
            if (!output.isBlank()) {
                log.warn("Output of host {} before its removal:\n{}", id, DockerSandboxAdapter.eventTail(output));
            }
            try {
                containers.forceRemove(containerId);
            } catch (RuntimeException e) {
                log.warn("Could not remove the container of host {}: {}", id, e.getMessage());
            }
        }
        SandboxAttemptLauncher.Attempt attempt = resources.attempt();
        if (attempt != null) {
            try {
                attempt.close();
            } catch (IOException | RuntimeException e) {
                log.warn("Could not remove the workspace of host {}: {}", id, e.getMessage());
            }
        }
        String networkId = resources.networkId();
        if (networkId != null) {
            try {
                networks.disconnectAppServer(networkId);
            } catch (RuntimeException e) {
                // Not worth stopping for: the removal below reports what the daemon actually refuses.
                log.debug("Could not disconnect from the network of host {}: {}", id, e.getMessage());
            }
            try {
                networks.removeNetwork(networkId);
            } catch (RuntimeException e) {
                log.warn("Could not remove the network of host {}: {}", id, e.getMessage());
            }
        }
    }
}

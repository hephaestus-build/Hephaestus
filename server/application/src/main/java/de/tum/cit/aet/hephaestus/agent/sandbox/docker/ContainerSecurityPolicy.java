package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Applies the worker's security minimums when translating sandbox specifications to Docker. */
public class ContainerSecurityPolicy {

    private static final int NOFILE_LIMIT = 1024;
    private static final long NANO_CPUS_PER_CPU = 1_000_000_000L;

    private static final Set<String> ALLOWED_IPC_MODES = Set.of("none", "private");

    private static final Map<String, String> MANDATORY_TMPFS = Map.of(
            "/tmp",
            "rw,noexec,nosuid,nodev,size=1073741824",
            "/run",
            "rw,noexec,nosuid,nodev,size=67108864",
            // Pi extracts native addons here; loading them requires executable mappings.
            "/home/agent/.local",
            "rw,exec,nosuid,nodev,size=1073741824");

    private final DockerSandboxProperties properties;
    private final @Nullable String seccompProfileJson;

    public ContainerSecurityPolicy(DockerSandboxProperties properties, @Nullable String seccompProfileJson) {
        this.properties = properties;
        this.seccompProfileJson = seccompProfileJson;
    }

    public DockerOperations.HostConfigSpec buildHostConfig(
            SecurityProfile security, ResourceLimits resources, @Nullable NetworkPolicy networkPolicy) {
        List<String> securityOpts = new ArrayList<>();
        securityOpts.add("no-new-privileges");

        if (seccompProfileJson != null) {
            securityOpts.add("seccomp=" + seccompProfileJson);
        }

        // Disable Docker's upstream DNS forwarding on isolated networks.
        List<String> dns = new ArrayList<>();
        if (networkPolicy == null || !networkPolicy.internetAccess()) {
            dns.add("0.0.0.0");
        }

        // RLIMIT_NPROC is host-UID scoped and can prevent a non-root container from starting on a
        // shared host. The cgroup pids limit already constrains all tasks inside the container.
        Map<String, DockerOperations.UlimitSpec> ulimits = Map.of(
                "nofile",
                new DockerOperations.UlimitSpec(NOFILE_LIMIT, NOFILE_LIMIT),
                "core",
                new DockerOperations.UlimitSpec(0, 0));

        // A caller cannot bypass the worker's configured isolation runtime.
        String globalRuntime = properties.containerRuntime();
        String runtime;
        if (globalRuntime != null && !globalRuntime.isBlank()) {
            runtime = globalRuntime;
        } else {
            runtime = security.runtime();
        }

        List<String> dropCaps = security.dropCapabilities();
        if (dropCaps == null || !dropCaps.contains("ALL")) {
            dropCaps = List.of("ALL");
        }

        String ipcMode = security.ipcMode();
        if (ipcMode == null || !ALLOWED_IPC_MODES.contains(ipcMode)) {
            ipcMode = "none";
        }

        Map<String, String> tmpfs = new HashMap<>(MANDATORY_TMPFS);
        if (security.tmpfsMounts() != null) {
            for (var entry : security.tmpfsMounts().entrySet()) {
                if (!MANDATORY_TMPFS.containsKey(entry.getKey())) {
                    tmpfs.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return new DockerOperations.HostConfigSpec(
                resources.memoryBytes(),
                resources.memoryBytes(), // memory-swap = memory (no swap)
                (long) (resources.cpus() * NANO_CPUS_PER_CPU),
                resources.pidsLimit(),
                false, // /workspace receives docker cp input before start and runner output after start
                false, // never privileged
                dropCaps,
                securityOpts,
                tmpfs,
                dns,
                "private", // cgroup namespace always private
                ipcMode,
                runtime,
                ulimits);
    }

    public Map<String, String> buildLabels(UUID jobId) {
        return Map.of(SandboxLabels.MANAGED, "true", SandboxLabels.JOB_ID, jobId.toString());
    }
}

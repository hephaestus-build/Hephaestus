package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Shared value types for Docker sandbox operations.
 *
 * <p>These records are used by the focused operation interfaces ({@link DockerContainerOperations},
 * {@link DockerNetworkOperations}, {@link DockerVolumeOperations}) and their implementations.
 */
public final class DockerOperations {

    private DockerOperations() {}

    /** Specification for creating a container. */
    public record ContainerSpec(
            String image,
            List<String> command,
            Map<String, String> environment,
            @Nullable String networkId,
            @Nullable String hostname,
            @Nullable String user,
            Map<String, String> labels,
            HostConfigSpec hostConfig,
            List<String> extraHosts) {}

    /** Host configuration for container resource limits and security. */
    public record HostConfigSpec(
            long memoryBytes,
            long memorySwapBytes,
            long nanoCpus,
            int pidsLimit,
            boolean readonlyRootfs,
            boolean privileged,
            List<String> capDrop,
            List<String> securityOpts,
            Map<String, String> tmpfsMounts,
            List<String> dns,
            @Nullable String cgroupnsMode,
            @Nullable String ipcMode,
            @Nullable String runtime,
            Map<String, UlimitSpec> ulimits,
            List<VolumeMount> volumeMounts) {}

    public record VolumeMount(String name, String target, boolean readOnly) {}

    public record VolumeInfo(String name, Map<String, String> labels) {}

    public record UlimitSpec(long soft, long hard) {}

    public record WaitResult(int exitCode) {}

    /** {@code createdAt} is null when the daemon reported none — an unknown age counts as young. */
    public record ContainerInfo(
            String id,
            String name,
            Map<String, String> labels,
            String state,
            @Nullable Instant createdAt) {}

    public record NetworkInfo(String id, String name) {}
}

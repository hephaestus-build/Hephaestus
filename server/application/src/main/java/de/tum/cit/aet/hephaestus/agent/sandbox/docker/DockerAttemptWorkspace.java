package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Attempt-local storage; the worker's evidence directory is never mounted into a sandbox. */
public final class DockerAttemptWorkspace implements AutoCloseable {
    private static final List<String> TARGETS =
            List.of("/workspace", "/workspace/work", "/workspace/out", "/workspace/.pi", "/workspace/.sessions");
    private final DockerVolumeOperations volumes;
    private final List<DockerOperations.VolumeMount> mounts = new ArrayList<>();

    public DockerAttemptWorkspace(DockerVolumeOperations volumes, UUID id, Map<String, String> labels) {
        this.volumes = volumes;
        var volumeLabels = new java.util.HashMap<>(labels);
        volumeLabels.put(SandboxLabels.KIND, SandboxLabels.KIND_ATTEMPT_WORKSPACE);
        volumeLabels.putIfAbsent(SandboxLabels.JOB_ID, labels.getOrDefault(SandboxLabels.SESSION_ID, id.toString()));
        volumeLabels.put(SandboxLabels.CREATED_AT, java.time.Instant.now().toString());
        try {
            for (int index = 0; index < TARGETS.size(); index++) {
                var mount =
                        new DockerOperations.VolumeMount("hephaestus-" + id + "-" + index, TARGETS.get(index), false);
                volumes.createVolume(mount.name(), volumeLabels);
                mounts.add(mount);
            }
        } catch (RuntimeException exception) {
            try {
                close();
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    public DockerOperations.HostConfigSpec configure(DockerOperations.HostConfigSpec base, boolean initialization) {
        var configured = mounts.stream()
                .map(mount -> new DockerOperations.VolumeMount(
                        mount.name(),
                        mount.target(),
                        !initialization && mount.target().equals("/workspace")))
                .toList();
        return new DockerOperations.HostConfigSpec(
                base.memoryBytes(),
                base.memorySwapBytes(),
                base.nanoCpus(),
                base.pidsLimit(),
                true,
                false,
                base.capDrop(),
                base.securityOpts(),
                base.tmpfsMounts(),
                base.dns(),
                base.cgroupnsMode(),
                base.ipcMode(),
                base.runtime(),
                base.ulimits(),
                configured);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (var mount : mounts.reversed()) {
            try {
                volumes.removeVolume(mount.name());
            } catch (RuntimeException exception) {
                failure = exception;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}

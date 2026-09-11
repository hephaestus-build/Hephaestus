package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.util.List;
import java.util.Map;

/** The attempt volumes this sandbox owner created; other owners' volumes on the same daemon are invisible here. */
public class SandboxVolumeManager {
    private final DockerVolumeOperations volumes;
    private final DockerSandboxProperties properties;

    public SandboxVolumeManager(DockerVolumeOperations volumes, DockerSandboxProperties properties) {
        this.volumes = volumes;
        this.properties = properties;
    }

    public List<DockerOperations.VolumeInfo> listAttemptVolumes() {
        return volumes.listVolumes(Map.of(
                SandboxLabels.OWNER, properties.owner(), SandboxLabels.KIND, SandboxLabels.KIND_ATTEMPT_WORKSPACE));
    }

    public void removeVolume(String name) {
        volumes.removeVolume(name);
    }
}

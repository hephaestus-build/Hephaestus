package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.util.List;
import java.util.Map;

public interface DockerVolumeOperations {
    void createVolume(String name, Map<String, String> labels);

    void removeVolume(String name);

    List<DockerOperations.VolumeInfo> listVolumes(Map<String, String> labels);
}

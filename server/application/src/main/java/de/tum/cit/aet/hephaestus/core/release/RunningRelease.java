package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Consumes the lock renderer's projection; signature verification belongs to deployment tooling. */
@Component
public class RunningRelease implements InfoContributor {
    private final ReleaseStatusDTO.RunningReleaseDTO identity;

    public RunningRelease(ObjectProvider<BuildProperties> builds, Environment environment, ObjectMapper mapper) {
        BuildProperties build = builds.getIfAvailable();
        String version = build == null ? "unknown" : build.getVersion();
        String commit = build == null ? "unknown" : build.get("commit");
        if (version == null) version = "unknown";
        if (commit == null || !commit.matches("[a-f0-9]{40}")) commit = "unknown";
        List<String> roles = List.of("server", "worker", "webhook").stream()
                .filter(role -> environment.getProperty(
                        RuntimeRole.PROPERTY_PREFIX + "." + role + ".enabled", Boolean.class, true))
                .toList();
        String status = "UNKNOWN";
        Map<String, String> images = new TreeMap<>();
        String projection = environment.getProperty("HEPHAESTUS_DEPLOYMENT_IDENTITY", "");
        if (!projection.isBlank()) {
            try {
                var node = mapper.readTree(projection);
                String release = node.path("release").asText("");
                String source = node.path("commit").asText("");
                var imageNode = node.path("images");
                if (!release.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
                        || !source.matches("[a-f0-9]{40}")
                        || !imageNode.isObject()
                        || imageNode.isEmpty()) {
                    throw new IllegalArgumentException("Invalid deployment projection");
                }
                imageNode.properties().forEach(entry -> {
                    String reference = entry.getValue().asText("");
                    if (!entry.getKey().matches("[a-z0-9-]+")
                            || !reference.matches("(?:ghcr\\.io|docker\\.io)/[a-z0-9._/-]+@sha256:[a-f0-9]{64}")) {
                        throw new IllegalArgumentException("Invalid image projection");
                    }
                    images.put(entry.getKey(), reference);
                });
                status = source.equals(commit) ? "DEPLOYMENT_REPORTED" : "MISMATCH";
                // Release promotion assigns the tag without rebuilding the JAR.
                if (source.equals(commit)) version = release.substring(1);
            } catch (RuntimeException exception) {
                status = "INVALID";
                images.clear();
            }
        }
        String channel = !status.equals("DEPLOYMENT_REPORTED")
                ? "unknown"
                : version.matches("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})")
                        ? "stable"
                        : version.matches("[0-9]+\\.[0-9]+\\.[0-9]+-(?!development)[0-9A-Za-z.-]+")
                                ? "prerelease"
                                : "unknown";
        identity = new ReleaseStatusDTO.RunningReleaseDTO(version, commit, channel, status, roles, Map.copyOf(images));
    }

    public ReleaseStatusDTO.RunningReleaseDTO get() {
        return identity;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("release", identity);
    }
}

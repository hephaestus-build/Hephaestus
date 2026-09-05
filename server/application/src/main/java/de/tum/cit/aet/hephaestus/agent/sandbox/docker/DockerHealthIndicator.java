package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Health indicator for Docker daemon connectivity.
 *
 * <p>Pings the Docker daemon and reports UP/DOWN status with operational details (active container
 * count, capacity). Intended for readiness probes, not liveness — a Docker outage should prevent
 * new job acceptance but should not restart the app-server.
 */
public class DockerHealthIndicator implements HealthIndicator {

    private final SandboxContainerManager containerManager;
    private final SandboxProperties properties;
    private final DockerSandboxProperties dockerProperties;

    public DockerHealthIndicator(
            SandboxContainerManager containerManager,
            SandboxProperties properties,
            DockerSandboxProperties dockerProperties) {
        this.containerManager = containerManager;
        this.properties = properties;
        this.dockerProperties = dockerProperties;
    }

    @Override
    public Health health() {
        try {
            boolean reachable = containerManager.ping();
            if (reachable) {
                int activeContainers = containerManager.listManagedContainers().size();
                return Health.up()
                        .withDetail("dockerHost", dockerProperties.host())
                        .withDetail("activeContainers", activeContainers)
                        .withDetail("maxConcurrentContainers", properties.maxConcurrentContainers())
                        .build();
            } else {
                return Health.down()
                        .withDetail("dockerHost", dockerProperties.host())
                        .withDetail("error", "Docker daemon not reachable")
                        .build();
            }
        } catch (Exception e) {
            return Health.down()
                    .withDetail("dockerHost", dockerProperties.host())
                    .withDetail("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .build();
        }
    }
}

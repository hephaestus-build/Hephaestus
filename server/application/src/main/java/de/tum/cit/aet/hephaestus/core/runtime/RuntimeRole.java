package de.tum.cit.aet.hephaestus.core.runtime;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.core.env.Environment;

/**
 * Runtime role names — string constants used in {@code @ConditionalOnProperty} keys to gate
 * subsystems by deployment role. Single source of truth so role gating cannot drift between
 * the property file, the ArchUnit boundary test, and the smoke test.
 *
 * <p>See ADR 0005 (two-role baseline) and ADR 0008 (third role: webhook). Defaults: every
 * role enabled (single-JVM monolith); production deploys flip the appropriate flag to
 * {@code false} per pod via the corresponding profile YAML.
 */
public final class RuntimeRole {

    private RuntimeRole() {}

    /**
     * Property prefix for every runtime role flag. Concrete keys are
     * {@code hephaestus.runtime.<role>.enabled}.
     */
    public static final String PROPERTY_PREFIX = "hephaestus.runtime";

    /**
     * Wired property key for the server-role gate. Gates {@code ServerSchedulingConfig},
     * {@code IntegrationNatsConsumer}, {@code WorkspaceStartupListener}, the user-facing
     * {@code core.auth} web/auth surface and {@code WorkspaceContextFilter} (both via
     * {@link ConditionalOnServerRole}). Authoritative list lives in {@code RuntimeRoleBoundaryTest}.
     */
    public static final String SERVER_PROPERTY = PROPERTY_PREFIX + ".server.enabled";

    /**
     * Wired property key for the worker-role gate. Gates {@code DockerSandboxConfiguration}
     * and the poll-based {@code AgentJobExecutor}.
     */
    public static final String WORKER_PROPERTY = PROPERTY_PREFIX + ".worker.enabled";

    /**
     * Wired property key for the webhook-role gate. Gates webhook HTTP ingress,
     * inbound signature verification, JetStream publishing/bootstrap, health
     * indicators, and graceful-shutdown lifecycle.
     *
     * <p>The {@code webhook-server} production container deploys with this flag {@code true}
     * and {@link #SERVER_PROPERTY} / {@link #WORKER_PROPERTY} {@code false} so it runs
     * webhook ingestion in isolation from the {@code application-server} container —
     * giving restart independence (push events from GitHub/GitLab are not manually
     * redeliverable). See ADR 0008.
     */
    public static final String WEBHOOK_PROPERTY = PROPERTY_PREFIX + ".webhook.enabled";

    /**
     * Property key enabling the agent job poller, which additionally requires
     * {@link #WORKER_PROPERTY}. The LLM proxy gates on {@link #WORKER_PROPERTY} alone, so it is always
     * up wherever jobs can run — it is the only LLM credential path a job has.
     */
    public static final String AGENT_ENABLED_PROPERTY = "hephaestus.agent.enabled";

    /**
     * The roles this process booted with, in declaration order. Reads the flags directly rather than
     * probing for role-conditional beans, so a role-agnostic caller can answer without depending on
     * any of them.
     */
    public static List<String> enabled(Environment environment) {
        return Stream.of("server", "worker", "webhook")
                .filter(role -> environment.getProperty(PROPERTY_PREFIX + "." + role + ".enabled", Boolean.class, true))
                .toList();
    }
}

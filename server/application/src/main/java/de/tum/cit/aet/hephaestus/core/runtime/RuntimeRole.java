package de.tum.cit.aet.hephaestus.core.runtime;

import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;

/**
 * The slices of one JAR a container can boot, and the flag that switches each off. Every role defaults
 * to on, so a JAR started with no flags is the single-JVM monolith; a production container turns off
 * the roles it does not run (ADR 0005, ADR 0008). Beans gate on {@link ConditionalOnServerRole},
 * {@link ConditionalOnWorkerRole} or {@link ConditionalOnWebhookRole}.
 */
public enum RuntimeRole {
    SERVER(RuntimeRole.SERVER_PROPERTY),
    WORKER(RuntimeRole.WORKER_PROPERTY),
    WEBHOOK(RuntimeRole.WEBHOOK_PROPERTY);

    public static final String PROPERTY_PREFIX = "hephaestus.runtime";

    public static final String SERVER_PROPERTY = PROPERTY_PREFIX + ".server.enabled";

    public static final String WORKER_PROPERTY = PROPERTY_PREFIX + ".worker.enabled";

    public static final String WEBHOOK_PROPERTY = PROPERTY_PREFIX + ".webhook.enabled";

    /**
     * Enables agent jobs, off when unset: submitting them on any role, and polling them where
     * {@link #WORKER_PROPERTY} is also on. The LLM proxy gates on {@link #WORKER_PROPERTY} alone, so it
     * is up wherever jobs can run — it is the only LLM credential path a job has.
     */
    public static final String AGENT_ENABLED_PROPERTY = "hephaestus.agent.enabled";

    private final String property;

    RuntimeRole(String property) {
        this.property = property;
    }

    /** The flag that switches this role off. */
    public String property() {
        return property;
    }

    /** The roles this process booted with, read from the flags so no role-conditional bean is needed. */
    public static List<RuntimeRole> enabled(Environment environment) {
        return Arrays.stream(values())
                .filter(role -> environment.getProperty(role.property, Boolean.class, true))
                .toList();
    }
}

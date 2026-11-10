package de.tum.cit.aet.hephaestus.core.runtime;

import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;

/**
 * The role a container boots, and the flag that switches it off. The server is the only role: it receives provider
 * deliveries, serves the API, and runs the agent sandboxes and their gateway (ADR 0005). Beans gate on
 * {@link ConditionalOnServerRole}.
 */
public enum RuntimeRole {
    SERVER(RuntimeRole.SERVER_PROPERTY);

    public static final String PROPERTY_PREFIX = "hephaestus.runtime";

    public static final String SERVER_PROPERTY = PROPERTY_PREFIX + ".server.enabled";

    /** Enables agent jobs and the LLM proxy on the server, off when unset. */
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

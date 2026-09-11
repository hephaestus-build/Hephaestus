package de.tum.cit.aet.hephaestus.core.runtime;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs which runtime roles wired at boot. Surfaces the most common ops mistake — every role
 * flag set to {@code false} (a do-nothing JVM) — as a WARN instead of letting the pod silently
 * idle. Reads property values directly to avoid coupling to any role-conditional bean.
 */
@Component
public class RuntimeRoleStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRoleStartupLogger.class);

    private final Environment environment;

    public RuntimeRoleStartupLogger(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logRoles() {
        List<RuntimeRole> enabled = RuntimeRole.enabled(environment);

        var event = enabled.isEmpty() ? log.atWarn() : log.atInfo();
        event = event.addKeyValue("event.name", "runtime.roles.configured")
                .addKeyValue("runtime.server.enabled", enabled.contains(RuntimeRole.SERVER))
                .addKeyValue("runtime.worker.enabled", enabled.contains(RuntimeRole.WORKER))
                .addKeyValue("runtime.webhook.enabled", enabled.contains(RuntimeRole.WEBHOOK));
        if (enabled.isEmpty()) {
            event.log(
                    "All runtime roles disabled — this JVM will accept no work. Set at least one of "
                            + "{}=true, {}=true, or {}=true.",
                    RuntimeRole.SERVER_PROPERTY,
                    RuntimeRole.WORKER_PROPERTY,
                    RuntimeRole.WEBHOOK_PROPERTY);
            return;
        }
        event.log("Runtime roles enabled: {}", enabled);
    }
}

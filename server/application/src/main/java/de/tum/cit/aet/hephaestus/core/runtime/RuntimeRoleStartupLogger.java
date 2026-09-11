package de.tum.cit.aet.hephaestus.core.runtime;

import java.util.ArrayList;
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
        boolean server = environment.getProperty(RuntimeRole.SERVER_PROPERTY, Boolean.class, true);
        boolean worker = environment.getProperty(RuntimeRole.WORKER_PROPERTY, Boolean.class, true);
        boolean webhook = environment.getProperty(RuntimeRole.WEBHOOK_PROPERTY, Boolean.class, true);

        List<String> enabled = new ArrayList<>(3);
        if (server) enabled.add("server");
        if (worker) enabled.add("worker");
        if (webhook) enabled.add("webhook");

        var event = enabled.isEmpty() ? log.atWarn() : log.atInfo();
        event = event.addKeyValue("event.name", "runtime.roles.configured")
                .addKeyValue("runtime.server.enabled", server)
                .addKeyValue("runtime.worker.enabled", worker)
                .addKeyValue("runtime.webhook.enabled", webhook);
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

package de.tum.cit.aet.hephaestus.core.runtime;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import java.lang.management.ManagementFactory;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs whether the JVM uses its Base64 intrinsics. The image turns them off while its JRE is below the fix of
 * CVE-2026-114938 (server/application/project.toml), and an operator reads this event to verify that a running server
 * does.
 */
@Component
public class Base64IntrinsicsStartupLogger {

    static final String OPTION = "UseBASE64Intrinsics";

    private static final Logger log = LoggerFactory.getLogger(Base64IntrinsicsStartupLogger.class);

    private final Supplier<Optional<VMOption>> option;

    public Base64IntrinsicsStartupLogger() {
        this(Base64IntrinsicsStartupLogger::effectiveOption);
    }

    Base64IntrinsicsStartupLogger(Supplier<Optional<VMOption>> option) {
        this.option = option;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logBase64Intrinsics() {
        Optional<VMOption> effective = option.get();
        if (effective.isEmpty()) {
            log.atWarn()
                    .addKeyValue("event.name", "jvm.base64_intrinsics.configured")
                    .log(
                            "The JVM does not report {}; this server cannot show whether its Base64 intrinsics are off.",
                            OPTION);
            return;
        }
        boolean enabled = Boolean.parseBoolean(effective.get().getValue());
        log.atInfo()
                .addKeyValue("event.name", "jvm.base64_intrinsics.configured")
                .addKeyValue("jvm.base64_intrinsics.enabled", enabled)
                .addKeyValue("jvm.option.origin", effective.get().getOrigin().name())
                .log("JVM Base64 intrinsics enabled: {}", enabled);
    }

    private static Optional<VMOption> effectiveOption() {
        try {
            return Optional.of(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .getVMOption(OPTION));
        } catch (IllegalArgumentException absent) {
            return Optional.empty();
        }
    }
}

package de.tum.cit.aet.hephaestus.core.runtime;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * The components a process that runs only the webhook role scans: the receiver, the adapters' {@code webhook}
 * packages, and the few process beans every listener needs. Everything else in the application stays out of the
 * context, so the receiver holds no {@code DataSource}, no JPA and no repositories, and a PostgreSQL outage cannot
 * stop it from accepting deliveries (ADR 0066).
 *
 * <p>Spring Boot consults every {@link TypeExcludeFilter} bean during component and configuration-properties
 * scanning, as its test slices do. Names are strings because {@code core} must not depend on the modules it lists.
 * {@code WebhookReceiverScopeArchitectureTest} fails when a receiver component falls outside this scope or a listed name
 * no longer names a class; {@code WebhookRoleContextIntegrationTest} boots the role and posts deliveries.
 */
public final class WebhookReceiverScope extends TypeExcludeFilter {

    private static final String ROOT = "de.tum.cit.aet.hephaestus.";

    /** The receiver substrate and every adapter's {@code webhook} package. */
    private static final Pattern RECEIVER_PACKAGE =
            Pattern.compile(Pattern.quote(ROOT) + "(core\\.webhook|integration(\\.[a-z]+)*\\.webhook)");

    /**
     * Process beans outside those packages: HTTP security, the NATS connection, logging, release identity, and the
     * agent image guards, which stop every role on a reference the workers cannot run (ADR 0031).
     */
    public static final Set<String> PROCESS_COMPONENTS = Set.of(
            ROOT + "SecurityConfig",
            ROOT + "agent.runtime.AgentImageProperties",
            ROOT + "agent.sandbox.AgentImagePinGuard",
            ROOT + "agent.sandbox.AgentImageReferenceGuard",
            ROOT + "config.CorsProperties",
            ROOT + "core.TimeConfig",
            ROOT + "core.WorkspaceSubdomainProperties",
            ROOT + "core.release.ReleaseProperties",
            ROOT + "core.release.RunningRelease",
            ROOT + "core.runtime.BuildProfileGuard",
            ROOT + "core.runtime.RuntimeRoleStartupLogger",
            ROOT + "core.security.ProxyTrustGuard",
            ROOT + "integration.core.consumer.NatsConfig",
            ROOT + "integration.core.consumer.NatsConnectionProperties",
            ROOT + "integration.slack.events.SlackSignatureVerifier",
            ROOT + "observability.MdcContextConfiguration",
            ROOT + "observability.RequestCorrelationFilter");

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
        String className = metadataReader.getClassMetadata().getClassName();
        if (!className.startsWith(ROOT)) {
            return false;
        }
        String topLevel = className.split("\\$", 2)[0];
        String packageName = topLevel.substring(0, topLevel.lastIndexOf('.'));
        return !RECEIVER_PACKAGE.matcher(packageName).matches() && !PROCESS_COMPONENTS.contains(topLevel);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other != null && getClass() == other.getClass();
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    /** Registered in {@code META-INF/spring.factories}: installs the scope before the scan when only the webhook role runs. */
    public static final class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            if (RuntimeRole.enabled(context.getEnvironment()).equals(List.of(RuntimeRole.WEBHOOK))) {
                context.getBeanFactory().registerSingleton("webhookReceiverScope", new WebhookReceiverScope());
            }
        }
    }
}

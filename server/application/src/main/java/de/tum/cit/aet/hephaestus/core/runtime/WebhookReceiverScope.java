package de.tum.cit.aet.hephaestus.core.runtime;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * The components a process that runs only the webhook role scans: the receiver, the adapters' {@code webhook}
 * packages, and the few process beans every listener needs, which carry {@link ReceiverComponent}. Everything else in the application stays out of the
 * context, so the receiver holds no {@code DataSource}, no JPA and no repositories, and a PostgreSQL outage cannot
 * stop it from accepting deliveries (ADR 0066).
 *
 * <p>Spring Boot consults every {@link TypeExcludeFilter} bean during component and configuration-properties
 * scanning, as its test slices do. {@code WebhookReceiverScopeArchitectureTest} holds every class in the scope to the
 * receiver's rule (no persistence type), and {@code WebhookRoleContextIntegrationTest} boots the role and fails on any
 * persistence bean.
 */
public final class WebhookReceiverScope extends TypeExcludeFilter {

    private static final String ROOT = "de.tum.cit.aet.hephaestus.";

    /** The receiver substrate and every adapter's {@code webhook} package. */
    private static final Pattern RECEIVER_PACKAGE =
            Pattern.compile(Pattern.quote(ROOT) + "(core\\.webhook|integration(\\.[a-z]+)*\\.webhook)");

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
        String className = metadataReader.getClassMetadata().getClassName();
        if (!className.startsWith(ROOT)) {
            return false;
        }
        String topLevel = className.split("\\$", 2)[0];
        String packageName = topLevel.substring(0, topLevel.lastIndexOf('.'));
        return !RECEIVER_PACKAGE.matcher(packageName).matches()
                && !isReceiverComponent(metadataReader, metadataReaderFactory);
    }

    /** A nested class belongs to its top-level class: the marker sits there. */
    private static boolean isReceiverComponent(MetadataReader metadataReader, MetadataReaderFactory readers) {
        if (metadataReader.getAnnotationMetadata().hasAnnotation(ReceiverComponent.class.getName())) {
            return true;
        }
        String enclosing = metadataReader.getClassMetadata().getEnclosingClassName();
        if (enclosing == null) {
            return false;
        }
        try {
            return isReceiverComponent(readers.getMetadataReader(enclosing), readers);
        } catch (IOException e) {
            return false;
        }
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

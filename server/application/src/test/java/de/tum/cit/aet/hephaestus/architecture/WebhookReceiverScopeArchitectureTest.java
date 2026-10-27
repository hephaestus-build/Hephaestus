package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.WebhookReceiverScope;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookPublishGate;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.data.repository.Repository;

/**
 * The webhook role scans only {@link WebhookReceiverScope}, and has no database (ADR 0066). Two rules keep both true:
 * every receiver component is in the scope, and nothing the scope admits for the webhook role depends on a
 * persistence type. A server- or worker-gated class in a {@code webhook} package is scanned but never created there.
 */
class WebhookReceiverScopeArchitectureTest extends HephaestusArchitectureTest {

    private static final List<Class<?>> RECEIVER_SPIS = List.of(
            WebhookSignatureVerifier.class,
            SubjectKeyDeriver.class,
            WebhookPublishGate.class,
            WebhookSecretSource.class);

    private static final List<String> PERSISTENCE_PACKAGES = List.of(
            "jakarta.persistence",
            "org.springframework.data",
            "org.springframework.jdbc",
            "org.springframework.orm",
            "org.springframework.transaction",
            "javax.sql",
            "liquibase",
            "org.hibernate");

    private final WebhookReceiverScope scope = new WebhookReceiverScope();
    private final MetadataReaderFactory readers = new SimpleMetadataReaderFactory();

    @Test
    void shouldScanEveryReceiverComponentWhenOnlyTheWebhookRoleRuns() throws IOException {
        Set<String> excluded = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            boolean receiver = javaClass.isAnnotatedWith(ConditionalOnWebhookRole.class)
                    || (!javaClass.isInterface() && RECEIVER_SPIS.stream().anyMatch(javaClass::isAssignableTo));
            if (receiver && !inScope(javaClass)) {
                excluded.add(javaClass.getName());
            }
        }

        assertThat(excluded)
                .as("receiver components the webhook role's scan leaves out; move them or mark them @ReceiverComponent")
                .isEmpty();
    }

    @Test
    void shouldDependOnNoPersistenceTypeWhenTheWebhookRoleCanCreateIt() throws IOException {
        Set<String> violations = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            if (!inScope(javaClass) || gatedOffTheWebhookRole(javaClass)) {
                continue;
            }
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                if (isPersistence(dependency.getTargetClass())) {
                    violations.add(javaClass.getName() + " -> "
                            + dependency.getTargetClass().getName());
                }
            }
        }

        assertThat(violations)
                .as("the webhook role has no database (ADR 0066); read what it needs from NATS or the request")
                .isEmpty();
    }

    private boolean inScope(JavaClass javaClass) throws IOException {
        return javaClass.getName().startsWith("de.tum.cit.aet.hephaestus.")
                && !scope.match(readers.getMetadataReader(javaClass.getName()), readers);
    }

    private static boolean gatedOffTheWebhookRole(JavaClass javaClass) {
        JavaClass topLevel = javaClass;
        while (topLevel.getEnclosingClass().isPresent()) {
            topLevel = topLevel.getEnclosingClass().orElseThrow();
        }
        return topLevel.isAnnotatedWith(ConditionalOnServerRole.class)
                || topLevel.isAnnotatedWith(ConditionalOnWorkerRole.class);
    }

    private static boolean isPersistence(JavaClass target) {
        String name = target.getName();
        return PERSISTENCE_PACKAGES.stream().anyMatch(prefix -> name.startsWith(prefix + "."))
                || target.isAnnotatedWith(Entity.class)
                || (!target.getName().startsWith("org.springframework.data")
                        && target.isAssignableTo(Repository.class));
    }
}

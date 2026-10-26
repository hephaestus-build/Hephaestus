package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.core.runtime.WebhookReceiverScope;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookPublishGate;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSecretSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

/**
 * The webhook role scans only {@link WebhookReceiverScope}. A receiver component outside it would be missing from the
 * receiver: a verifier or deriver as a 501 for every delivery of its kind, a guard as a check that silently stops.
 */
class WebhookReceiverScopeArchitectureTest extends HephaestusArchitectureTest {

    private static final List<Class<?>> RECEIVER_SPIS = List.of(
            WebhookSignatureVerifier.class,
            SubjectKeyDeriver.class,
            WebhookPublishGate.class,
            WebhookSecretSource.class);

    private final WebhookReceiverScope scope = new WebhookReceiverScope();
    private final MetadataReaderFactory readers = new SimpleMetadataReaderFactory();

    @Test
    void shouldScanEveryReceiverComponentWhenOnlyTheWebhookRoleRuns() throws IOException {
        Set<String> excluded = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            boolean receiver = javaClass.isAnnotatedWith(ConditionalOnWebhookRole.class)
                    || (!javaClass.isInterface() && RECEIVER_SPIS.stream().anyMatch(javaClass::isAssignableTo));
            if (receiver && scope.match(readers.getMetadataReader(javaClass.getName()), readers)) {
                excluded.add(javaClass.getName());
            }
        }

        assertThat(excluded)
                .as(
                        "receiver components the webhook role's scan leaves out; move them or list them in WebhookReceiverScope")
                .isEmpty();
    }

    @Test
    void shouldNameExistingClassesWhenItListsProcessComponents() {
        Set<String> classNames = new TreeSet<>();
        classes.forEach(javaClass -> classNames.add(javaClass.getName()));

        assertThat(WebhookReceiverScope.PROCESS_COMPONENTS)
                .as("a renamed class drops out of the receiver without a compile error")
                .allSatisfy(name -> assertThat(classNames).contains(name));
    }
}

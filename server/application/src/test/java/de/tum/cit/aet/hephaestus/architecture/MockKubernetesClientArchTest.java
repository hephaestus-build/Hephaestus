package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.NoRequestRetries;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

/**
 * Every mock Kubernetes API client in the tests makes no request retries ({@link NoRequestRetries}). A test that
 * answers 5xx would otherwise wait through the client's retries, and retries pending when it closes its client fail
 * into later tests.
 */
class MockKubernetesClientArchTest extends HephaestusArchitectureTest {

    @Test
    void shouldBuildEveryAnnotatedMockClientWithoutRequestRetries() {
        classes()
                .that()
                .areAnnotatedWith(EnableKubernetesMockClient.class)
                .should(new ArchCondition<JavaClass>("build its mock client with NoRequestRetries") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        Object customizer = type.getAnnotationOfType(EnableKubernetesMockClient.class.getName())
                                .get("kubernetesClientBuilderCustomizer")
                                .orElse(null);
                        boolean noRetries = customizer instanceof JavaClass declared
                                && declared.isEquivalentTo(NoRequestRetries.class);
                        events.add(new SimpleConditionEvent(
                                type,
                                noRetries,
                                type.getName()
                                        + " builds a mock client with request retries; set kubernetesClientBuilderCustomizer"
                                        + " = NoRequestRetries.class"));
                    }
                })
                .check(classesWithTests);
    }

    @Test
    void shouldBuildNoMockClientWithTheDefaultRetries() {
        noClasses()
                .should()
                .callMethod(KubernetesMockServer.class, "createClient")
                .because("a mock client is built with createClient(new NoRequestRetries())")
                .check(classesWithTests);
    }
}

package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.KubernetesSandboxConfiguration;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.mock.env.MockEnvironment;

/** The proxy and the sandboxes exist exactly where the agent runtime runs: on the server, with agent jobs on. */
class MentorRoleGatingTest extends BaseUnitTest {

    private static final String AGENT_ON = RuntimeRole.AGENT_ENABLED_PROPERTY + "=true";

    @ParameterizedTest
    @ValueSource(
            classes = {
                LlmProxyController.class,
                LlmProxyService.class,
                LlmProxySecurityConfig.class,
                KubernetesSandboxConfiguration.class
            })
    void shouldRegisterOnlyWithTheAgentRuntime(Class<?> type) {
        assertThat(registers(type)).isFalse();
        assertThat(registers(type, AGENT_ON)).isTrue();
        assertThat(registers(type, AGENT_ON, RuntimeRole.SERVER_PROPERTY + "=false"))
                .isFalse();
    }

    /** Evaluates the type's conditions as component scanning does, without instantiating it. */
    private static boolean registers(Class<?> type, String... properties) {
        MockEnvironment environment = new MockEnvironment();
        TestPropertyValues.of(properties).applyTo(environment);
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        new AnnotatedBeanDefinitionReader(registry, environment).registerBean(type);
        return registry.getBeanNamesForType(type).length > 0;
    }
}

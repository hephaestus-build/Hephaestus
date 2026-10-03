package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.lang.reflect.ParameterizedType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.mock.env.MockEnvironment;

class AgentRoleGatingTest extends BaseUnitTest {

    private static final String AGENT_ON = RuntimeRole.AGENT_ENABLED_PROPERTY + "=true";

    @ParameterizedTest
    @ValueSource(classes = {AgentJobExecutor.class, WorkerLivenessReporter.class})
    void shouldPollJobsOnlyWhenAgentJobsAndTheWorkerRoleAreOn(Class<?> poller) {
        assertThat(registers(poller)).isFalse();
        assertThat(registers(poller, AGENT_ON)).isTrue();
        assertThat(registers(poller, AGENT_ON, RuntimeRole.WORKER_PROPERTY + "=false"))
                .isFalse();
        assertThat(registers(poller, AGENT_ON, RuntimeRole.SERVER_PROPERTY + "=false"))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(classes = {AgentJobEventListener.class, IssueAgentJobEventListener.class, BotCommandProcessor.class})
    void shouldSubmitJobsOnlyWhenAgentJobsAreOn(Class<?> submitter) {
        assertThat(registers(submitter)).isFalse();
        assertThat(registers(submitter, AGENT_ON)).isTrue();
    }

    @Test
    void shouldRecoverJobsOnlyWhenAgentJobsAndTheServerRoleAreOn() {
        assertThat(registers(AgentJobZombieSweeper.class)).isFalse();
        assertThat(registers(AgentJobZombieSweeper.class, AGENT_ON)).isTrue();
        assertThat(registers(AgentJobZombieSweeper.class, AGENT_ON, RuntimeRole.SERVER_PROPERTY + "=false"))
                .isFalse();
    }

    @Test
    void shouldAllowSettingsServiceWithoutServerOnlyAuditLogger() {
        assertThat(InstanceSettingsService.class.isAnnotationPresent(ConditionalOnServerRole.class))
                .isFalse();
        assertThat(InstanceSettingsService.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getGenericParameterTypes())
                        .anySatisfy(type -> {
                            assertThat(type).isInstanceOf(ParameterizedType.class);
                            ParameterizedType parameterized = (ParameterizedType) type;
                            assertThat(parameterized.getRawType()).isEqualTo(Optional.class);
                            assertThat(parameterized.getActualTypeArguments()).containsExactly(AuthEventLogger.class);
                        }));
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

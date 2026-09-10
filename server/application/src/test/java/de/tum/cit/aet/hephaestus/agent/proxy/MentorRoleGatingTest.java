package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerSandboxConfiguration;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class MentorRoleGatingTest extends BaseUnitTest {

    @Test
    void shouldKeepProxyAvailableWheneverWorkerRoleIsEnabled() {
        assertWorkerRoleCondition(LlmProxyController.class);
        assertWorkerRoleCondition(LlmProxyService.class);
        assertWorkerRoleCondition(LlmProxySecurityConfig.class);
    }

    @Test
    void shouldKeepInteractiveSandboxAvailableWheneverWorkerRoleIsEnabled() {
        assertWorkerRoleCondition(DockerSandboxConfiguration.class);
    }

    private void assertWorkerRoleCondition(Class<?> type) {
        ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.name()).containsExactly(RuntimeRole.WORKER_PROPERTY);
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}

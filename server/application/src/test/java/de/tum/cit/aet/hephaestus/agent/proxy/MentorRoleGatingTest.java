package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.KubernetesSandboxConfiguration;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;

class MentorRoleGatingTest extends BaseUnitTest {

    @Test
    void shouldKeepProxyAvailableWheneverWorkerRoleIsEnabled() {
        assertWorkerRoleCondition(LlmProxyController.class);
        assertWorkerRoleCondition(LlmProxyService.class);
        assertWorkerRoleCondition(LlmProxySecurityConfig.class);
    }

    @Test
    void shouldKeepTheSandboxAvailableWheneverWorkerRoleIsEnabled() {
        assertWorkerRoleCondition(KubernetesSandboxConfiguration.class);
    }

    private void assertWorkerRoleCondition(Class<?> type) {
        ConditionalOnWorkerRole workerRole =
                Objects.requireNonNull(AnnotatedElementUtils.findMergedAnnotation(type, ConditionalOnWorkerRole.class));
        ConditionalOnProperty condition = Objects.requireNonNull(
                AnnotatedElementUtils.findMergedAnnotation(workerRole.annotationType(), ConditionalOnProperty.class));
        assertThat(condition.name()).containsExactly(RuntimeRole.WORKER_PROPERTY);
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}

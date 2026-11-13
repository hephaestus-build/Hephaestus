package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceErasureNotifications;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class JobEvidenceMaintenanceConfigurationTest extends BaseUnitTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=default")
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(JobEvidenceMaintenanceConfiguration.class)
            .withBean(JobEvidenceFiles.class, () -> mock(JobEvidenceFiles.class))
            .withBean(EvidenceReconciler.class, () -> mock(EvidenceReconciler.class))
            .withBean(EvidenceErasureNotifications.class, () -> mock(EvidenceErasureNotifications.class));

    @Test
    void shouldScheduleEvidenceReconciliationOnTheServerWithoutAgentJobs() {
        context.withPropertyValues("hephaestus.agent.enabled=false").run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                            .flatMap(holder -> holder.getScheduledTasks().stream()))
                    .hasSize(1);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "specs", "cds-training"})
    void shouldLeaveEvidenceMaintenanceToTheTestInTestAndBuildProfiles(String profile) {
        context.withPropertyValues("spring.profiles.active=" + profile).run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app).hasSingleBean(JobEvidenceMaintenanceConfiguration.class);
            assertThat(app.getBeansOfType(ScheduledTaskHolder.class)).isEmpty();
        });
    }

    @Test
    void shouldNotScheduleEvidenceMaintenanceWithoutTheServerRole() {
        context.withPropertyValues("hephaestus.runtime.server.enabled=false").run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app.getBeansOfType(ScheduledTaskHolder.class)).isEmpty();
        });
    }
}

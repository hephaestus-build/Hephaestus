package de.tum.cit.aet.hephaestus.core.database.backup.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.database.backup.BackupScheduleMonitor;
import de.tum.cit.aet.hephaestus.core.database.backup.BackupScheduleProperties;
import de.tum.cit.aet.hephaestus.core.database.backup.BackupSchedules;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** The properties bind on every start, as the application's properties scan binds them; the reader starts only with both. */
class KubernetesBackupScheduleConfigurationTest extends BaseUnitTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BackupScheduleProperties.class)
    static class Scanned {}

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(
                    Scanned.class, KubernetesBackupScheduleConfiguration.class, BackupScheduleMonitor.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void shouldStartWithNoReaderWhenBackupsAreOff() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(BackupSchedules.class)
                .doesNotHaveBean(BackupScheduleMonitor.class));
    }

    @Test
    void shouldReadTheSchedulesWhenBothValuesAreSet() {
        contextRunner
                .withPropertyValues(
                        "hephaestus.database.backup.namespace=hephaestus",
                        "hephaestus.database.backup.cron-jobs=heph-postgresql-backup-full,heph-postgresql-backup-diff")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(BackupSchedules.class)
                        .hasSingleBean(BackupScheduleMonitor.class));
    }

    @Test
    void shouldRefuseToStartWithAnEmptyListOrABlankNamespace() {
        contextRunner
                .withPropertyValues(
                        "hephaestus.database.backup.namespace=hephaestus", "hephaestus.database.backup.cron-jobs=")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("hephaestus.database.backup.cron-jobs=heph-postgresql-backup-full")
                .run(context -> assertThat(context).hasFailed());
    }
}

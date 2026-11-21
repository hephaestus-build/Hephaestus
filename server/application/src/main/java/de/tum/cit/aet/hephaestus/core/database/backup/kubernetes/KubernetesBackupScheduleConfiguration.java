package de.tum.cit.aet.hephaestus.core.database.backup.kubernetes;

import de.tum.cit.aet.hephaestus.core.database.backup.BackupScheduleProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The backup schedules through the Kubernetes API, with the server's own service account token. */
@Configuration(proxyBeanMethods = false)
@Profile("!specs & !cds-training")
@ConditionalOnServerRole
@ConditionalOnProperty(prefix = "hephaestus.database.backup", name = "cron-jobs")
public class KubernetesBackupScheduleConfiguration {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    @Bean(destroyMethod = "close")
    public KubernetesBackupSchedules backupSchedules(BackupScheduleProperties properties) {
        // Set but empty is not unset: a monitor that reads no schedule would report a missing backup for ever.
        if (properties.cronJobs().isEmpty() || properties.namespace().isBlank()) {
            throw new IllegalStateException("hephaestus.database.backup.cron-jobs and hephaestus.database.backup"
                    + ".namespace must both be set to read the backup schedules");
        }
        Config config = new ConfigBuilder(Config.autoConfigure(null))
                .withRequestTimeout((int) REQUEST_TIMEOUT.toMillis())
                .withConnectionTimeout((int) CONNECTION_TIMEOUT.toMillis())
                .build();
        return new KubernetesBackupSchedules(
                new KubernetesClientBuilder().withConfig(config).build(), properties.namespace());
    }
}

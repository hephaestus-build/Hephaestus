package de.tum.cit.aet.hephaestus.core.database.backup.kubernetes;

import de.tum.cit.aet.hephaestus.core.database.backup.BackupSchedules;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The backup CronJobs of the release namespace, each read by name. The chart's Role grants {@code get} on exactly
 * these CronJobs and nothing else, so the client neither lists nor watches.
 */
public class KubernetesBackupSchedules implements BackupSchedules, AutoCloseable {

    private final KubernetesClient client;
    private final String namespace;

    public KubernetesBackupSchedules(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    @Override
    public Optional<Schedule> read(String name) {
        CronJob cronJob = client.batch()
                .v1()
                .cronjobs()
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (cronJob == null) {
            return Optional.empty();
        }
        Instant created = Instant.parse(cronJob.getMetadata().getCreationTimestamp());
        String lastSuccess =
                cronJob.getStatus() == null ? null : cronJob.getStatus().getLastSuccessfulTime();
        return Optional.of(new Schedule(created, instant(lastSuccess)));
    }

    private static @Nullable Instant instant(@Nullable String time) {
        return time == null || time.isBlank() ? null : Instant.parse(time);
    }

    @Override
    public void close() {
        client.close();
    }
}

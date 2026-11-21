package de.tum.cit.aet.hephaestus.core.database.backup.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes.NoRequestRetries;
import de.tum.cit.aet.hephaestus.core.database.backup.BackupSchedules;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@EnableKubernetesMockClient(kubernetesClientBuilderCustomizer = NoRequestRetries.class)
class KubernetesBackupSchedulesTest {

    private static final String NAMESPACE = "hephaestus";
    private static final String NAME = "hephaestus-postgresql-backup-diff";
    private static final String PATH = "/apis/batch/v1/namespaces/" + NAMESPACE + "/cronjobs/" + NAME;

    private @Nullable KubernetesClient client;
    private @Nullable KubernetesMockServer server;

    private Optional<BackupSchedules.Schedule> read() {
        return new KubernetesBackupSchedules(Objects.requireNonNull(client), NAMESPACE).read(NAME);
    }

    private void answers(CronJob cronJob) {
        Objects.requireNonNull(server)
                .expect()
                .get()
                .withPath(PATH)
                .andReturn(200, cronJob)
                .once();
    }

    private static CronJobBuilder cronJob() {
        return new CronJobBuilder()
                .withNewMetadata()
                .withName(NAME)
                .withNamespace(NAMESPACE)
                .withCreationTimestamp("2026-11-01T02:00:00Z")
                .endMetadata();
    }

    @Test
    void shouldReadTheCreationAndTheLastSuccessByName() throws InterruptedException {
        answers(cronJob()
                .withNewStatus()
                .withLastSuccessfulTime("2026-11-20T02:15:42Z")
                .endStatus()
                .build());

        assertThat(read())
                .contains(new BackupSchedules.Schedule(
                        Instant.parse("2026-11-01T02:00:00Z"), Instant.parse("2026-11-20T02:15:42Z")));
        // One GET by name: the Role grants get on these names, not list or watch.
        assertThat(Objects.requireNonNull(server).getLastRequest().getPath()).isEqualTo(PATH);
    }

    @Test
    void shouldReadNoSuccessWhenTheCronJobHasNoStatusOrABlankTime() {
        answers(cronJob().build());
        assertThat(read()).contains(new BackupSchedules.Schedule(Instant.parse("2026-11-01T02:00:00Z"), null));

        answers(cronJob().withNewStatus().withLastSuccessfulTime("").endStatus().build());
        assertThat(read()).contains(new BackupSchedules.Schedule(Instant.parse("2026-11-01T02:00:00Z"), null));
    }

    @Test
    void shouldReadNothingWhenTheCronJobIsMissing() {
        Objects.requireNonNull(server)
                .expect()
                .get()
                .withPath(PATH)
                .andReturn(404, "")
                .once();

        assertThat(read()).isEmpty();
    }

    @Test
    void shouldFailWhenTheReadIsRefused() {
        Objects.requireNonNull(server)
                .expect()
                .get()
                .withPath(PATH)
                .andReturn(403, "")
                .once();

        assertThatThrownBy(this::read).isInstanceOf(KubernetesClientException.class);
    }
}

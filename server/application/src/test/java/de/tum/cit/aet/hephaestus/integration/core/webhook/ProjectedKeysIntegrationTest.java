package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.NatsTestContainer;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A projection that NATS lost heals at the writer's next rebuild, and a reader never creates the bucket: an empty set
 * that a reader made would read as "nothing allowed" instead of "unknown".
 */
@Tag("integration")
class ProjectedKeysIntegrationTest {

    private Connection nats;
    private String bucket;

    @BeforeEach
    void connect() throws Exception {
        nats = Nats.connect(NatsTestContainer.getServerUrl());
        bucket = "projected-" + UUID.randomUUID();
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        nats.close();
    }

    @Test
    void shouldRecreateTheBucketWhenNatsLostIt() throws Exception {
        ProjectedKeys writer = new ProjectedKeys(nats, bucket);
        ProjectedKeys reader = new ProjectedKeys(nats, bucket);
        writer.replaceAll(Set.of("T1.C1"));
        assertThat(reader.contains("T1.C1")).isTrue();

        nats.keyValueManagement().delete(bucket);

        assertThatThrownBy(() -> reader.contains("T1.C1")).isInstanceOf(ProjectedKeys.UnavailableException.class);
        writer.replaceAll(Set.of("T1.C1"));
        assertThat(reader.contains("T1.C1")).isTrue();
    }

    @Test
    void shouldReportAnUnknownSetWhenTheBucketIsMissing() throws Exception {
        assertThatThrownBy(() -> new ProjectedKeys(nats, bucket).contains("T1.C1"))
                .isInstanceOf(ProjectedKeys.UnavailableException.class);
        assertThat(nats.keyValueManagement().getBucketNames()).doesNotContain(bucket);
    }

    @Test
    void shouldHoldExactlyTheProjectedKeysWhenItRebuilds() {
        ProjectedKeys keys = new ProjectedKeys(nats, bucket);
        keys.replaceAll(Set.of("a", "b"));

        keys.replaceAll(Set.of("b", "c"));

        assertThat(keys.contains("a")).isFalse();
        assertThat(keys.contains("b")).isTrue();
        assertThat(keys.contains("c")).isTrue();
    }
}

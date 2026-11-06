package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.nats.client.ConsumerContext;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
class JetStreamRestoreIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String STREAM = "RESTORE";

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @SuppressWarnings("try") // NATS close can be interrupted; the test propagates interruption.
    void shouldResumeDurableConsumersFromAColdBackupWithoutLosingOrRepeatingAppliedEvents(@TempDir Path directory)
            throws Exception {
        DockerImageName image = releasedBrokerImage();
        Map<String, Integer> prefixes = Map.of("alpha", 3, "beta", 5);
        Map<String, ConsumerInfo> checkpoints = new LinkedHashMap<>();
        Set<String> applied = new HashSet<>();
        Set<String> expected = new HashSet<>();
        Path archive = directory.resolve("jetstream.tar");
        try (Broker original = new Broker(image, null)) {
            original.start();
            String originalId = original.getContainerId();
            try (var connection = Nats.connect(original.url())) {
                connection
                        .jetStreamManagement()
                        .addStream(StreamConfiguration.builder()
                                .name(STREAM)
                                .subjects("restore.*")
                                .storageType(StorageType.File)
                                // Acknowledged bodies must remain, so losing the consumer checkpoint cannot pass this
                                // test.
                                .retentionPolicy(RetentionPolicy.Limits)
                                .build());
                for (int number = 1; number <= 10; number++) {
                    for (String scope : prefixes.keySet()) {
                        String id = scope + "-" + number;
                        connection.jetStream().publish("restore." + scope, id.getBytes(UTF_8));
                        expected.add(id);
                    }
                }
                for (var prefix : prefixes.entrySet()) {
                    ConsumerContext consumer = connection
                            .getStreamContext(STREAM)
                            .createOrUpdateConsumer(ConsumerConfiguration.builder()
                                    .durable(prefix.getKey())
                                    .filterSubject("restore." + prefix.getKey())
                                    .ackPolicy(AckPolicy.Explicit)
                                    .ackWait(Duration.ofSeconds(1))
                                    .build());
                    apply(consumer, prefix.getValue(), applied);
                    Message inFlight = consumer.next(WAIT);
                    assertThat(inFlight).isNotNull();
                    assertThat(applied).doesNotContain(new String(inFlight.getData(), UTF_8));
                    checkpoints.put(prefix.getKey(), consumer.getConsumerInfo());
                    assertThat(checkpoints.get(prefix.getKey()).getNumAckPending())
                            .isEqualTo(1);
                }
            }
            original.getDockerClient()
                    .stopContainerCmd(originalId)
                    .withTimeout(30)
                    .exec();
            try (var snapshot = original.getDockerClient()
                    .copyArchiveFromContainerCmd(originalId, "/data")
                    .exec()) {
                Files.copy(snapshot, archive);
            }
            assertThat(Files.size(archive)).isPositive();
            original.close();

            try (Broker restored = new Broker(image, archive)) {
                restored.start();
                assertThat(restored.getContainerId()).isNotEqualTo(originalId);
                try (var connection = Nats.connect(restored.url())) {
                    var state = connection
                            .jetStreamManagement()
                            .getStreamInfo(STREAM)
                            .getStreamState();
                    assertThat(state.getMsgCount()).isEqualTo(20);
                    assertThat(state.getLastSequence()).isEqualTo(20);
                    for (var checkpoint : checkpoints.entrySet()) {
                        ConsumerContext consumer =
                                connection.getStreamContext(STREAM).getConsumerContext(checkpoint.getKey());
                        ConsumerInfo observed = consumer.getConsumerInfo();
                        ConsumerInfo before = checkpoint.getValue();
                        assertThat(observed.getAckFloor().getConsumerSequence())
                                .isEqualTo(before.getAckFloor().getConsumerSequence());
                        assertThat(observed.getAckFloor().getStreamSequence())
                                .isEqualTo(before.getAckFloor().getStreamSequence());
                        assertThat(observed.getNumAckPending()).isEqualTo(1);
                        assertThat(observed.getNumPending()).isEqualTo(before.getNumPending());
                        Integer acknowledged = prefixes.get(checkpoint.getKey());
                        assertThat(acknowledged).isNotNull();
                        apply(consumer, 10 - acknowledged, applied);

                        String id = checkpoint.getKey() + "-11";
                        connection.jetStream().publish("restore." + checkpoint.getKey(), id.getBytes(UTF_8));
                        expected.add(id);
                        apply(consumer, 1, applied);
                        assertThat(consumer.getConsumerInfo().getNumAckPending())
                                .isZero();
                        assertThat(consumer.getConsumerInfo().getNumPending()).isZero();
                    }
                    assertThat(applied).containsExactlyInAnyOrderElementsOf(expected);
                    assertThat(connection
                                    .jetStreamManagement()
                                    .getStreamInfo(STREAM)
                                    .getStreamState()
                                    .getLastSequence())
                            .isEqualTo(22);
                }
            }
        }
    }

    private static void apply(ConsumerContext consumer, int count, Set<String> applied) throws Exception {
        for (int index = 0; index < count; index++) {
            Message message = consumer.next(WAIT);
            assertThat(message).isNotNull();
            assertThat(applied.add(new String(message.getData(), UTF_8)))
                    .as("a committed event must not be processed twice")
                    .isTrue();
            message.ackSync(WAIT);
        }
    }

    private static DockerImageName releasedBrokerImage() throws IOException {
        // Gradle test tasks run from the application module; the release inventory owns the image pin.
        JsonNode inventory =
                JsonMapper.builder().build().readTree(Files.readString(Path.of("../../security/release-images.json")));
        for (JsonNode upstream : inventory.path("upstream")) {
            if ("nats".equals(upstream.path("name").asString())) {
                return DockerImageName.parse(upstream.path("repository").asString() + "@"
                        + upstream.path("digest").asString());
            }
        }
        throw new IllegalStateException("The release inventory must name the broker image");
    }

    private static final class Broker extends GenericContainer<Broker> {
        private final @Nullable Path archive;

        private Broker(DockerImageName image, @Nullable Path archive) {
            super(image);
            this.archive = archive;
            withCommand("-js", "-sd", "/data");
            withExposedPorts(4222);
            waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
        }

        private String url() {
            return "nats://" + getHost() + ":" + getMappedPort(4222);
        }

        @Override
        protected void containerIsCreated(String containerId) {
            if (archive != null) {
                try (var snapshot = Files.newInputStream(archive)) {
                    getDockerClient()
                            .copyArchiveToContainerCmd(containerId)
                            .withRemotePath("/")
                            .withTarInputStream(snapshot)
                            .exec();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
        }
    }
}

package de.tum.cit.aet.hephaestus.testconfig;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public final class NatsTestContainer {

    private static final Logger LOGGER = LoggerFactory.getLogger(NatsTestContainer.class);
    private static final DockerImageName IMAGE = DockerImageName.parse("nats:2.10-alpine");
    private static final int CLIENT_PORT = 4222;

    private static @Nullable GenericContainer<?> container;

    private NatsTestContainer() {}

    public static synchronized GenericContainer<?> getInstance() {
        GenericContainer<?> current = container;
        if (current == null) {
            current = createContainer();
            container = current;
        }
        return current;
    }

    public static String getServerUrl() {
        GenericContainer<?> c = getInstance();
        return "nats://" + c.getHost() + ":" + c.getMappedPort(CLIENT_PORT);
    }

    // Ryuk stops this singleton after JVM exit, keeping it available while Spring closes cached contexts.
    @SuppressWarnings("resource")
    private static GenericContainer<?> createContainer() {
        GenericContainer<?> newContainer = new GenericContainer<>(IMAGE)
                .withCommand("-js")
                .withExposedPorts(CLIENT_PORT)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

        newContainer.start();
        LOGGER.info(
                "Started NATS JetStream Testcontainer: server=nats://{}:{}",
                newContainer.getHost(),
                newContainer.getMappedPort(CLIENT_PORT));

        return newContainer;
    }
}

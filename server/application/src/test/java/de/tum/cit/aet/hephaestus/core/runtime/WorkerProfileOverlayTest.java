package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * Reads {@code application-worker.yml} itself, and refreshes a context over it: these are regressions
 * no test that sets its own properties can catch, because the mistake would be IN the file the
 * deployment loads.
 */
class WorkerProfileOverlayTest extends BaseUnitTest {

    private static final String OVERLAY = "application-worker.yml";

    private static PropertySource<?> workerOverlay() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(OVERLAY, new ClassPathResource(OVERLAY));
        assertThat(sources).as("the overlay must be a single YAML document").hasSize(1);
        return sources.get(0);
    }

    /**
     * The worker's application connector must not answer anything from a job network.
     */
    @Test
    void bindsAdministrativeHttpToLoopback() throws IOException {
        assertThat(InetAddress.getByName(resolvedOverlayValue("server.address")))
                .matches(InetAddress::isLoopbackAddress);
    }

    /**
     * The worker's own connector still has to be a real port — additional health probes answer there, and
     * {@code server.port: -1} would disable it while every bean stays wired and every context test
     * stays green.
     */
    @Test
    void servesTheWorkerOnARealPort() throws IOException {
        assertThat(Integer.parseInt(resolvedOverlayValue("server.port"))).isPositive();
    }

    @Test
    void shouldUseASeparateLoopbackManagementListenerByDefault() throws IOException {
        assertThat(baseValueOf("management.server.port")).isEqualTo("${MANAGEMENT_PORT:9090}");
        assertThat(baseValueOf("management.server.address")).isEqualTo("127.0.0.1");
        assertThat(workerOverlay().getProperty("management.server.port")).isNull();
        assertThat(workerOverlay().getProperty("management.server.address")).isNull();
    }

    /**
     * A worker inherits {@code AGENT_ENABLED} from the base configuration, where it is off. Pinning it on
     * in the overlay would make a pod started outside compose — with none of the env vars the operator was
     * told to set — claim jobs and spend LLM budget the operator believed inert.
     */
    @Test
    void doesNotTurnJobExecutionOnByItself() throws IOException {
        assertThat(workerOverlay().getProperty("hephaestus.agent.enabled")).isNull();
        assertThat(baseValueOf("hephaestus.agent.enabled")).isEqualTo("${AGENT_ENABLED:false}");
    }

    /** The value a worker boots with when the operator sets no environment variable for it. */
    private static String resolvedOverlayValue(String key) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(workerOverlay());
        return Objects.requireNonNull(
                new PropertySourcesPropertyResolver(sources).getProperty(key), () -> key + " is not set by " + OVERLAY);
    }

    private static @Nullable Object baseValueOf(String key) throws IOException {
        return new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml")).stream()
                        .map(source -> source.getProperty(key))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
    }
}

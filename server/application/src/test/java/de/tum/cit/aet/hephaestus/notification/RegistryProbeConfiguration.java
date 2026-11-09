package de.tum.cit.aet.hephaestus.notification;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * A listener that fails while told to, so a test can make failed event publications without depending on what any
 * production listener does with them.
 */
@TestConfiguration
public class RegistryProbeConfiguration {

    /** One probe delivery. An expired probe completes without being recorded, as an expired notification does. */
    public record ProbeEvent(String key, boolean expired) {}

    public static class ProbeListener {
        private final List<String> delivered = new CopyOnWriteArrayList<>();
        private volatile boolean failing;

        public void failing(boolean failing) {
            this.failing = failing;
        }

        public List<String> delivered() {
            return delivered;
        }

        @ApplicationModuleListener
        public void on(ProbeEvent event) {
            if (event.expired()) return;
            if (failing) throw new IllegalStateException("probe told to fail");
            delivered.add(event.key());
        }
    }

    @Bean
    ProbeListener registryProbeListener() {
        return new ProbeListener();
    }
}

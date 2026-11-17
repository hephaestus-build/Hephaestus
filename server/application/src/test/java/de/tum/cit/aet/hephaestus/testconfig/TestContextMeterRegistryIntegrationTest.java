package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * A test context's meter registries stay out of Micrometer's static registry. A registry there outlives its context
 * and keeps it reachable, so the context cache's limit would not limit the heap.
 */
class TestContextMeterRegistryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldKeepEveryMeterRegistryOfTheContextOutOfTheStaticRegistry() {
        var registries = context.getBeansOfType(MeterRegistry.class).values();

        assertThat(registries).isNotEmpty();
        assertThat(Metrics.globalRegistry.getRegistries()).doesNotContainAnyElementsOf(registries);
    }
}

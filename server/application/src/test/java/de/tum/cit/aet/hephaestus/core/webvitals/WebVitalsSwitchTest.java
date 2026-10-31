package de.tum.cit.aet.hephaestus.core.webvitals;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WebVitalsSwitchTest extends BaseUnitTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(SimpleMeterRegistry.class)
            .withUserConfiguration(WebVitalsMetrics.class, WebVitalsController.class);

    @Test
    void shouldHaveNoEndpointAndNoMetricsUntilTheOperatorTurnsThemOn() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(WebVitalsController.class);
            assertThat(context).doesNotHaveBean(WebVitalsMetrics.class);
        });
    }

    @Test
    void shouldReceiveMeasurementsOnceTheOperatorTurnsThemOn() {
        contextRunner
                .withPropertyValues("hephaestus.features.flags.page-experience-metrics=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(WebVitalsController.class);
                    assertThat(context).hasSingleBean(WebVitalsMetrics.class);
                });
    }
}

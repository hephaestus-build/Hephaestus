package de.tum.cit.aet.hephaestus.core.browserfaults;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BrowserFaultsSwitchTest extends BaseUnitTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(SimpleMeterRegistry.class)
            .withUserConfiguration(
                    BrowserFaultRecorder.class, BrowserFaultsController.class, BrowserFaultsHttpConfiguration.class);

    @Test
    void shouldHaveNoEndpointNoMeterAndNoBodyBoundUntilTheOperatorTurnsThemOn() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(BrowserFaultsController.class);
            assertThat(context).doesNotHaveBean(BrowserFaultRecorder.class);
            assertThat(context).doesNotHaveBean("browserFaultsPayloadSizeFilter");
        });
    }

    @Test
    void shouldReceiveFaultsOnceTheOperatorTurnsThemOn() {
        contextRunner
                .withPropertyValues("hephaestus.features.flags.browser-fault-reports=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(BrowserFaultsController.class);
                    assertThat(context).hasSingleBean(BrowserFaultRecorder.class);
                    assertThat(context).hasBean("browserFaultsPayloadSizeFilter");
                });
    }
}

package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CdsTrainingRunGuardTest extends BaseUnitTest {

    private static final String EXIT = "spring.context.exit";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.import=")
            .withUserConfiguration(CdsTrainingRunGuard.class);

    @Test
    void shouldRefuseTheTrainingProfileOutsideTheTrainingRun() {
        runner.withPropertyValues("spring.profiles.active=cds-training").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageStartingWith("The cds-training profile runs only in the image build's training run.");
        });
    }

    @Test
    void shouldRefuseAnExitSettingThatDoesNotMakeTheProcessExit() {
        // An environment variable or argument reaches the Spring environment, but not Spring's exit check.
        runner.withPropertyValues("spring.profiles.active=cds-training", EXIT + "=onRefresh")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldAllowTheTrainingRun() {
        runner.withSystemProperties(EXIT + "=onRefresh")
                .withPropertyValues("spring.profiles.active=cds-training")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "specs", "e2e", "test"})
    void shouldLeaveOtherProfilesAlone(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).hasNotFailed());
    }
}

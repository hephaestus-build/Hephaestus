package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BackgroundWorkGuardTest extends BaseUnitTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BackgroundWorkGuard.class);

    @Test
    void shouldRefuseAProductionServerWithoutBackgroundWork() {
        runner.withPropertyValues("spring.profiles.active=prod", ConditionalOnBackgroundWork.PROPERTY + "=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    ConditionalOnBackgroundWork.PROPERTY
                                            + " is for the test and build profiles only; a production server must leave it true.");
                });
    }

    @Test
    void shouldStartAProductionServerWithBackgroundWork() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shouldStartATestContextWithoutBackgroundWork() {
        runner.withPropertyValues("spring.profiles.active=test", ConditionalOnBackgroundWork.PROPERTY + "=false")
                .run(context -> assertThat(context).hasNotFailed());
    }
}

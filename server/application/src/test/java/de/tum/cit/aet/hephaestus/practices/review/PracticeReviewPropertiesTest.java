package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

class PracticeReviewPropertiesTest extends BaseUnitTest {

    @EnableConfigurationProperties(PracticeReviewProperties.class)
    static class TestConfiguration {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class, ValidationAutoConfiguration.class);

    @Test
    void shouldUseTwentySevenConcurrentPracticesByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PracticeReviewProperties.class).practiceConcurrency())
                    .isEqualTo(27);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8, 32})
    void shouldBindConfiguredConcurrencyWhenWithinTheSafetyCeiling(int concurrency) {
        runner.withPropertyValues("hephaestus.practice-review.practice-concurrency=" + concurrency)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PracticeReviewProperties.class).practiceConcurrency())
                            .isEqualTo(concurrency);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 33})
    void shouldRejectConcurrencyOutsideTheSafetyCeiling(int concurrency) {
        runner.withPropertyValues("hephaestus.practice-review.practice-concurrency=" + concurrency)
                .run(context -> assertThat(context).hasFailed());
    }
}

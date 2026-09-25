package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ConsentPropertiesTest extends BaseUnitTest {

    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldBindTrimmedOrganizationAtLedgerLimit() {
        String organization = "x".repeat(255);
        context.withPropertyValues("hephaestus.consent.research-organization= " + organization + " ")
                .run(result -> assertThat(
                                result.getBean(ConsentProperties.class).researchProgramme())
                        .isEqualTo(organization));
    }

    @Test
    void shouldRejectOrganizationBeyondLedgerLimitDuringBinding() {
        context.withPropertyValues("hephaestus.consent.research-organization=" + "x".repeat(256))
                .run(result -> assertThat(result.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .rootCause()
                        .hasMessageContaining("must not exceed 255 characters"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConsentProperties.class)
    static class TestConfiguration {}
}

package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;

class TestSchedulerSchemaConfigurationTest extends BaseUnitTest {

    @Test
    void shouldLeaveMigratedSchemasEntirelyToLiquibase() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestSchedulerSchemaConfiguration.class)
                .withPropertyValues("spring.profiles.active=test", "spring.liquibase.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DataSourceInitializer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "specs", "cds-training"})
    void shouldNeverInitializeTheFixtureOutsideTheTestProfile(String profile) {
        new ApplicationContextRunner()
                .withUserConfiguration(TestSchedulerSchemaConfiguration.class)
                .withPropertyValues("spring.profiles.active=" + profile, "spring.liquibase.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DataSourceInitializer.class));
    }
}

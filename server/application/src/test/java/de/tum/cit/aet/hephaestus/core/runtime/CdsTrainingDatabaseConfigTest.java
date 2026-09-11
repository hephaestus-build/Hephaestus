package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.HashMap;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CdsTrainingDatabaseConfigTest extends BaseUnitTest {

    @Test
    void shouldResolvePostgresDialectWithoutAConnectionOrExplicitDialect() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=cds-training",
                        "spring.config.import=",
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/cds-training")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = Binder.get(context.getEnvironment())
                            .bind("spring.jpa", JpaProperties.class)
                            .get();
                    assertThat(properties.getDatabasePlatform()).isNull();
                    try (var registry = new StandardServiceRegistryBuilder()
                            .applySettings(new HashMap<>(properties.getProperties()))
                            .build()) {
                        var environment = registry.getService(JdbcEnvironment.class);
                        assertThat(environment).isNotNull();
                        assertThat(environment.getDialect()).isInstanceOf(PostgreSQLDialect.class);
                    }
                });
    }
}

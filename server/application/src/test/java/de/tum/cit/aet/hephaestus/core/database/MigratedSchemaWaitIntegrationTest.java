package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.Application;
import de.tum.cit.aet.hephaestus.agent.handler.AgentHandlerTestDoubles;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.testconfig.SharedTestDoubles;
import de.tum.cit.aet.hephaestus.testconfig.StubInstallationRepositoryEnumerator;
import de.tum.cit.aet.hephaestus.testconfig.TestAsyncConfiguration;
import de.tum.cit.aet.hephaestus.testconfig.TestSecurityConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** The application started the way every role starts in production: Liquibase off, waiting for the migration. */
@Tag("integration")
class MigratedSchemaWaitIntegrationTest {

    @Test
    void shouldStartWithoutMigratingWhenTheSchemaIsCurrent() {
        TestDatabase database = PostgreSQLTestContainer.createMigratedDatabase("hephaestus_wait_current");

        try (ConfigurableApplicationContext context = start(database)) {
            assertThat(context.getBean(MigratedSchemaWait.class)).isNotNull();
        }
    }

    @Test
    void shouldStopBeforeTheSchemaIsUsedWhenTheMigrationHasNotRun() {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("hephaestus_wait_unmigrated");

        // Without the ordering, Hibernate's schema validation would fail first, naming a missing table.
        assertThatThrownBy(() -> start(database).close())
                .rootCause()
                .hasMessageContaining("The database schema is behind this release");
    }

    @Test
    void shouldWaitForADatabaseThatDoesNotAcceptAConnectionYet() {
        // Port 1 refuses at once, as PostgreSQL does while it starts beside the server of a first install.
        var unreachable = new TestDatabase("jdbc:postgresql://127.0.0.1:1/hephaestus", "hephaestus", "unused");

        assertThatThrownBy(() -> start(unreachable).close())
                .hasStackTraceContaining("The database refused a connection")
                .hasStackTraceContaining("The wait ended after PT1S");
    }

    @Test
    void shouldWaitForARoleThatTheMigrationHasNotCreatedYet() {
        // The migration Job creates the server's role, so until it runs PostgreSQL answers 28P01.
        TestDatabase database = PostgreSQLTestContainer.createMigratedDatabase("hephaestus_wait_role");
        var withoutRole = new TestDatabase(database.jdbcUrl(), "hephaestus_role_not_created", "unused");

        assertThatThrownBy(() -> start(withoutRole).close())
                .hasStackTraceContaining("The database refused a connection")
                .hasStackTraceContaining("Check the database address and credentials")
                .rootCause()
                .hasMessageContaining("hephaestus_role_not_created");
    }

    private static ConfigurableApplicationContext start(TestDatabase database) {
        var application = new SpringApplication(
                Application.class,
                TestSecurityConfig.class,
                TestAsyncConfiguration.class,
                RecordingScmEventListener.class,
                StubInstallationRepositoryEnumerator.class,
                SharedTestDoubles.class,
                AgentHandlerTestDoubles.class);
        application.setAdditionalProfiles("test");
        return application.run(
                "--spring.datasource.url=" + database.jdbcUrl(),
                "--spring.datasource.username=" + database.username(),
                "--spring.datasource.password=" + database.password(),
                "--spring.datasource.hikari.maximum-pool-size=4",
                "--spring.liquibase.enabled=false",
                "--hephaestus.database.migration-wait=1s",
                "--server.port=0",
                "--management.server.port=0",
                "--hephaestus.llm.display-currency=",
                "--spring.main.banner-mode=off");
    }
}

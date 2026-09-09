package de.tum.cit.aet.hephaestus.testconfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.regex.Pattern;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

public final class PostgreSQLTestContainer {

    private static final String DEFAULT_TEST_DB = "hephaestus_test";
    private static final String DEFAULT_TEST_USER = "test";
    private static final String DEFAULT_TEST_PASSWORD = "test";
    private static final String MIGRATED_TEMPLATE_DATABASE = "hephaestus_template_dev";
    private static final Pattern DATABASE_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgreSQLTestContainer.class);

    private static @Nullable PostgreSQLContainer<?> container;
    private static boolean migratedTemplateReady;

    private PostgreSQLTestContainer() {}

    public static synchronized PostgreSQLContainer<?> getInstance() {
        PostgreSQLContainer<?> current = container;
        if (current == null) {
            current = createContainer();
            container = current;
        }
        return current;
    }

    public static synchronized TestDatabase createDatabase(String name) {
        validateDatabaseName(name);
        PostgreSQLContainer<?> postgres = getInstance();
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + name + "\"");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create test database " + name, exception);
        }
        return database(postgres, name);
    }

    public static synchronized TestDatabase createMigratedDatabase(String name) {
        validateDatabaseName(name);
        if (!migratedTemplateReady) {
            TestDatabase template = createDatabase(MIGRATED_TEMPLATE_DATABASE);
            migrateDatabase(template, "dev");
            migratedTemplateReady = true;
        }
        return cloneDatabase(name, MIGRATED_TEMPLATE_DATABASE);
    }

    private static TestDatabase cloneDatabase(String name, String templateName) {
        PostgreSQLContainer<?> postgres = getInstance();
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + name + "\" TEMPLATE \"" + templateName + "\"");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to clone test database " + name, exception);
        }
        return database(postgres, name);
    }

    public static void migrateDatabase(TestDatabase testDatabase, String contexts) {
        String validationSetting = System.getProperty("liquibase.validateXmlChangelogFiles");
        System.setProperty("liquibase.validateXmlChangelogFiles", "false");
        try (Connection connection =
                DriverManager.getConnection(testDatabase.jdbcUrl(), testDatabase.username(), testDatabase.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(contexts));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to prepare migrated test database template", exception);
        } finally {
            if (validationSetting == null) {
                System.clearProperty("liquibase.validateXmlChangelogFiles");
            } else {
                System.setProperty("liquibase.validateXmlChangelogFiles", validationSetting);
            }
        }
    }

    public static String getLogs() {
        return getInstance().getLogs();
    }

    private static void validateDatabaseName(String name) {
        if (!DATABASE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid test database name: " + name);
        }
    }

    private static TestDatabase database(PostgreSQLContainer<?> postgres, String name) {
        String jdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + name;
        return new TestDatabase(jdbcUrl, postgres.getUsername(), postgres.getPassword());
    }

    public record TestDatabase(String jdbcUrl, String username, String password) {}

    @SuppressWarnings("resource") // Closed by the JVM shutdown hook.
    private static PostgreSQLContainer<?> createContainer() {
        PostgreSQLContainer<?> newContainer = new PostgreSQLContainer<>(DockerImageName.parse(new ImageFromDockerfile()
                                .withDockerfile(
                                        Path.of(System.getProperty("basedir", "."), "../../docker/postgres/Dockerfile"))
                                .get())
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(DEFAULT_TEST_DB)
                .withUsername(DEFAULT_TEST_USER)
                .withPassword(DEFAULT_TEST_PASSWORD)
                .withTmpFs(Map.of("/var/lib/postgresql", "rw"));

        newContainer.start();
        ensureExtensions(
                database(newContainer, "template1").jdbcUrl(), newContainer.getUsername(), newContainer.getPassword());
        ensureExtensions(newContainer.getJdbcUrl(), newContainer.getUsername(), newContainer.getPassword());

        LOGGER.info(
                "Started PostgreSQL Testcontainer: jdbcUrl={}, username={}, database={}",
                newContainer.getJdbcUrl(),
                newContainer.getUsername(),
                newContainer.getDatabaseName());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (newContainer.isRunning()) {
                newContainer.stop();
            }
        }));

        return newContainer;
    }

    // Hibernate-created schemas need citext without relying on Liquibase.
    private static void ensureExtensions(String jdbcUrl, String username, String password) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            connection.createStatement().execute("CREATE EXTENSION IF NOT EXISTS citext");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to enable required PostgreSQL extension 'citext' on the test database: "
                            + exception.getMessage(),
                    exception);
        }
    }
}

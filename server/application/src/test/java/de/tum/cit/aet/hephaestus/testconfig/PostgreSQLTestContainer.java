package de.tum.cit.aet.hephaestus.testconfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class PostgreSQLTestContainer {

    private static final String DEFAULT_TEST_DB = "hephaestus_test";
    private static final String DEFAULT_TEST_USER = "test";
    private static final String DEFAULT_TEST_PASSWORD = "test";
    private static final String MIGRATED_TEMPLATE_DATABASE = "hephaestus_template_dev";
    private static final Pattern DATABASE_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgreSQLTestContainer.class);

    private static @Nullable PostgreSQLContainer container;
    private static boolean migratedTemplateReady;
    private static final Set<String> RELEASED_TEMPLATES = new HashSet<>();

    private PostgreSQLTestContainer() {}

    public static synchronized PostgreSQLContainer getInstance() {
        PostgreSQLContainer current = container;
        if (current == null) {
            current = createContainer();
            container = current;
        }
        return current;
    }

    public static synchronized TestDatabase createDatabase(String name) {
        validateDatabaseName(name);
        PostgreSQLContainer postgres = getInstance();
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

    public static synchronized TestDatabase copyDatabase(String name, TestDatabase source) {
        validateDatabaseName(name);
        String templateName = source.jdbcUrl().substring(source.jdbcUrl().lastIndexOf('/') + 1);
        validateDatabaseName(templateName);
        return cloneDatabase(name, templateName);
    }

    private static TestDatabase cloneDatabase(String name, String templateName) {
        PostgreSQLContainer postgres = getInstance();
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + name + "\" TEMPLATE \"" + templateName + "\"");
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to clone test database " + name, exception);
        }
        return database(postgres, name);
    }

    public static synchronized TestDatabase createReleasedDatabase(String name, String contexts) {
        validateDatabaseName(name);
        String templateName = "hephaestus_released_" + contexts;
        validateDatabaseName(templateName);
        if (!RELEASED_TEMPLATES.contains(templateName)) {
            migrateReleasedDatabase(createDatabase(templateName), contexts);
            RELEASED_TEMPLATES.add(templateName);
        }
        return cloneDatabase(name, templateName);
    }

    public static void migrateReleasedDatabase(TestDatabase testDatabase, String contexts) {
        try (Connection connection =
                DriverManager.getConnection(testDatabase.jdbcUrl(), testDatabase.username(), testDatabase.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database)) {
                var selectedContexts = new Contexts(contexts);
                var labels = new LabelExpression();
                var pending = liquibase.listUnrunChangeSets(selectedContexts, labels);
                int releasedChanges = IntStream.range(0, pending.size())
                        .filter(index -> pending.get(index).getId().equals("1791486276211-v1-cutover"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Production changelog has no practice migration"));
                liquibase.update(releasedChanges, selectedContexts, labels);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to prepare the production released prefix", exception);
        }
    }

    public static void migrateDatabase(TestDatabase testDatabase, String contexts) {
        try (Connection connection =
                DriverManager.getConnection(testDatabase.jdbcUrl(), testDatabase.username(), testDatabase.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database)) {
                // Fixtures can seed a pre-cut-point history before completing the current schema.
                liquibase
                        .getDatabaseChangeLog()
                        .getPreconditions()
                        .getNestedPreconditions()
                        .clear();
                liquibase.update(new Contexts(contexts));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to prepare migrated test database template", exception);
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

    private static TestDatabase database(PostgreSQLContainer postgres, String name) {
        String jdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + name;
        return new TestDatabase(jdbcUrl, postgres.getUsername(), postgres.getPassword());
    }

    public record TestDatabase(String jdbcUrl, String username, String password) {}

    // CI resolves the published image once per job; without it the Dockerfile is built in this JVM.
    private static String image() {
        String resolved = System.getProperty("hephaestus.test.postgres-image", "");
        if (!resolved.isBlank()) {
            return resolved;
        }
        return new ImageFromDockerfile()
                .withDockerfile(Path.of(System.getProperty("basedir", "."), "../../docker/postgres/Dockerfile"))
                .get();
    }

    // Ryuk stops this singleton after JVM exit, keeping it available while Spring closes cached contexts.
    @SuppressWarnings("resource")
    private static PostgreSQLContainer createContainer() {
        PostgreSQLContainer newContainer = new PostgreSQLContainer(
                        DockerImageName.parse(image()).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(DEFAULT_TEST_DB)
                .withUsername(DEFAULT_TEST_USER)
                .withPassword(DEFAULT_TEST_PASSWORD)
                // Up to 32 cached test contexts each hold a pool of up to 10 connections, and one running test
                // can add pools of its own (role contexts up to the worker's 32, two replicas of 4). PostgreSQL's
                // default of 100 is below that bound.
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=400")
                .withTmpFs(Map.of("/var/lib/postgresql", "rw"));

        newContainer.start();
        LOGGER.info(
                "Started PostgreSQL Testcontainer: jdbcUrl={}, username={}, database={}",
                newContainer.getJdbcUrl(),
                newContainer.getUsername(),
                newContainer.getDatabaseName());

        return newContainer;
    }
}

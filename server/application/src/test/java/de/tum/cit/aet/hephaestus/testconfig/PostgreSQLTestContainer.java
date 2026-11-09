package de.tum.cit.aet.hephaestus.testconfig;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public final class PostgreSQLTestContainer {

    private static final String DEFAULT_TEST_DB = "hephaestus_test";
    private static final String DEFAULT_TEST_USER = "test";
    private static final String DEFAULT_TEST_PASSWORD = "test";
    /** The schema owner, as the chart's migration Job logs in (docs/admin/kubernetes.mdx § Database roles). */
    public static final String OWNER_USER = "hephaestus_owner";

    // Distinct per run, so that a test can search the statement statistics and the log for them.
    private static final String OWNER_PASSWORD = "owner-" + UUID.randomUUID();

    /** The login of the server and the worker, as the chart's roles log in. */
    public static final String APP_USER = "hephaestus_app";

    private static final String APP_PASSWORD = "app-" + UUID.randomUUID();

    /**
     * The roles of the chart, as its migration Job sets them up (docs/admin/kubernetes.mdx § Database roles): the
     * default database is migrated by the owner and used by the application role.
     */
    private static final String CHART_FILES = "/tmp/chart-files/";

    /** The chart's SQL files, as the migration Job and the monitor Job run them. */
    private static final List<String> CHART_SQL =
            List.of("database-quiet.sql", "database-roles.sql", "database-monitor.sql");

    private static final String MIGRATED_TEMPLATE_DATABASE = "hephaestus_template_dev";
    private static final Pattern DATABASE_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgreSQLTestContainer.class);

    private static @Nullable PostgreSQLContainer container;
    private static boolean migratedTemplateReady;

    private PostgreSQLTestContainer() {}

    public static synchronized PostgreSQLContainer getInstance() {
        PostgreSQLContainer current = container;
        if (current == null) {
            current = createContainer();
            container = current;
        }
        return current;
    }

    /** The default database as the application role: the login of every shared integration-test context. */
    public static TestDatabase application() {
        return asApplication(administrator());
    }

    /** The default database as the schema owner, which runs its migration. */
    public static TestDatabase owner() {
        return asOwner(administrator());
    }

    /**
     * The default database's schema owner, for a fixture that changes the schema: the application role that every
     * shared context uses cannot. Each call opens a connection per statement.
     */
    public static JdbcTemplate ownerJdbc() {
        TestDatabase owner = owner();
        return new JdbcTemplate(new DriverManagerDataSource(owner.jdbcUrl(), owner.username(), owner.password()));
    }

    /** The default database as the container's superuser. */
    public static TestDatabase administrator() {
        PostgreSQLContainer postgres = getInstance();
        return new TestDatabase(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    public static TestDatabase asApplication(TestDatabase database) {
        return new TestDatabase(database.jdbcUrl(), APP_USER, APP_PASSWORD);
    }

    public static TestDatabase asOwner(TestDatabase database) {
        return new TestDatabase(database.jdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    /** Runs the chart's {@code files/database-roles.sql} in a database, as the chart's migration Job runs it. */
    public static synchronized void applyDatabaseRoles(String database) {
        validateDatabaseName(database);
        PostgreSQLContainer postgres = getInstance();
        ExecResult result = runDatabaseRoles(postgres, database, postgres.getUsername());
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("database-roles.sql failed: " + result.getStderr());
        }
    }

    /**
     * Runs the chart's {@code files/database-roles.sql} in a database as another administrator, such as a role with
     * CREATEROLE and CREATEDB that a managed PostgreSQL service gives, and returns how psql ended.
     */
    public static synchronized ExecResult runDatabaseRolesAs(String database, String administrator) {
        validateDatabaseName(database);
        validateDatabaseName(administrator);
        return runDatabaseRoles(getInstance(), database, administrator);
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

    public static void migrateDatabase(TestDatabase testDatabase, String contexts) {
        try (Connection connection =
                DriverManager.getConnection(testDatabase.jdbcUrl(), testDatabase.username(), testDatabase.password())) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database)) {
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

    /** The quiet settings, then the roles, as the chart's init container runs them. */
    private static ExecResult runDatabaseRoles(PostgreSQLContainer postgres, String database, String administrator) {
        return runChartSql(
                postgres,
                database,
                administrator,
                Map.of("DATABASE_OWNER_PASSWORD", OWNER_PASSWORD, "DATABASE_APP_PASSWORD", APP_PASSWORD),
                List.of("database-quiet.sql", "database-roles.sql"));
    }

    /**
     * Runs files of the chart's {@code files/} in one psql session, with the given environment, and returns how psql
     * ended. psql reads each password from its environment, as the chart's Jobs give them.
     */
    public static synchronized ExecResult runChartSqlAs(
            String database, String administrator, Map<String, String> environment, List<String> files) {
        validateDatabaseName(database);
        validateDatabaseName(administrator);
        return runChartSql(getInstance(), database, administrator, environment, files);
    }

    private static ExecResult runChartSql(
            PostgreSQLContainer postgres,
            String database,
            String administrator,
            Map<String, String> environment,
            List<String> files) {
        var command = new ArrayList<>(
                List.of("psql", "--no-psqlrc", "--quiet", "--username=" + administrator, "--dbname=" + database));
        for (String file : files) {
            if (!CHART_SQL.contains(file)) {
                throw new IllegalArgumentException("Not a chart SQL file: " + file);
            }
            command.add("--file=" + CHART_FILES + file);
        }
        try {
            return postgres.execInContainer(ExecConfig.builder()
                    .command(command.toArray(String[]::new))
                    .envVars(environment)
                    .build());
        } catch (IOException exception) {
            throw new IllegalStateException("psql could not run " + files, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("psql was interrupted running " + files, exception);
        }
    }

    /**
     * Two privileges that only tests give the application role. The cleanup between tests empties every table of the
     * default database. A fixture writes rows that the triggers and foreign keys would refuse, in the test's own
     * transaction, with {@code SET LOCAL session_replication_role = replica}.
     */
    private static void grantTestFixtures(PostgreSQLContainer postgres) {
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER_USER
                    + " IN SCHEMA public GRANT TRUNCATE ON TABLES TO " + APP_USER);
            statement.execute("GRANT SET ON PARAMETER session_replication_role TO " + APP_USER);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to grant the test fixtures", exception);
        }
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
        for (String file : CHART_SQL) {
            newContainer.copyFileToContainer(
                    MountableFile.forHostPath(
                            Path.of(System.getProperty("basedir", "."), "../../deploy/chart/hephaestus/files", file)),
                    CHART_FILES + file);
        }
        ExecResult roles = runDatabaseRoles(newContainer, DEFAULT_TEST_DB, newContainer.getUsername());
        if (roles.getExitCode() != 0) {
            throw new IllegalStateException("database-roles.sql failed: " + roles.getStderr());
        }
        grantTestFixtures(newContainer);
        LOGGER.info(
                "Started PostgreSQL Testcontainer: jdbcUrl={}, username={}, database={}",
                newContainer.getJdbcUrl(),
                newContainer.getUsername(),
                newContainer.getDatabaseName());

        return newContainer;
    }
}

package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class PracticeMigrationFixture {
    private PracticeMigrationFixture() {}

    private static final Map<String, TestDatabase> NATIVE_TEMPLATES = new HashMap<>();

    /**
     * A current schema that holds practice history: the rows are loaded on the 1.0 baseline, whose schema they were
     * dumped from, and every later change set then runs over them, as it does on a database with real history.
     */
    public static synchronized TestDatabase migratedDatabase(String context) throws Exception {
        var template = NATIVE_TEMPLATES.get(context);
        if (template == null) {
            template = PostgreSQLTestContainer.createDatabase("practice_history_" + context);
            try (var connection = dataSource(template).getConnection();
                    var liquibase = new Liquibase(
                            "db/master.xml",
                            new ClassLoaderResourceAccessor(),
                            DatabaseFactory.getInstance()
                                    .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
                var contexts = new Contexts(context);
                liquibase.update("1.0.0-baseline", contexts, new LabelExpression());
                try (var input = Objects.requireNonNull(
                        PracticeMigrationFixture.class.getResourceAsStream("/db/practice-native/history-rows.sql"))) {
                    execute(template, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                liquibase.update(contexts);
            }
            NATIVE_TEMPLATES.put(context, template);
        }
        return PostgreSQLTestContainer.copyDatabase(
                "native_model_" + UUID.randomUUID().toString().replace("-", ""), template);
    }

    /** Applies every pending change set that comes before the first one of {@code changelog}. */
    static void upgradeBefore(TestDatabase database, String context, String changelog) throws Exception {
        try (var connection = dataSource(database).getConnection();
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            var contexts = new Contexts(context);
            var labels = new LabelExpression();
            var pending = liquibase.listUnrunChangeSets(contexts, labels);
            int before = IntStream.range(0, pending.size())
                    .filter(index -> pending.get(index).getFilePath().endsWith(changelog))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(changelog + " has no pending changeset"));
            liquibase.update(before, contexts, labels);
        }
    }

    /** Applies only the named changelog after {@link #upgradeBefore}, leaving every later migration pending. */
    static void upgradeOnly(Liquibase liquibase, String context, String changelog) throws LiquibaseException {
        var contexts = new Contexts(context);
        var labels = new LabelExpression();
        var pending = liquibase.listUnrunChangeSets(contexts, labels);
        int count = (int) pending.stream()
                .takeWhile(change -> change.getFilePath().endsWith(changelog))
                .count();
        long own = pending.stream()
                .filter(change -> change.getFilePath().endsWith(changelog))
                .count();
        if (count == 0 || count != own) {
            throw new IllegalStateException("The next pending changesets must belong to " + changelog);
        }
        liquibase.update(count, contexts, labels);
    }

    /** Rolls back only this changelog; refuses before any rollback if a later migration has run. */
    static void rollbackOnly(Liquibase liquibase, String context, String changelog) throws LiquibaseException {
        var history = liquibase.getDatabase().getRanChangeSetList();
        int count = 0;
        for (int index = history.size() - 1;
                index >= 0 && history.get(index).getChangeLog().endsWith(changelog);
                index--) {
            count++;
        }
        long own = history.stream()
                .filter(change -> change.getChangeLog().endsWith(changelog))
                .count();
        if (count == 0 || count != own) {
            throw new IllegalStateException("Cannot roll back " + changelog + " while later changesets remain");
        }
        liquibase.rollback(count, new Contexts(context), new LabelExpression());
    }

    private static DriverManagerDataSource dataSource(TestDatabase database) {
        return new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
    }

    static void execute(TestDatabase database, String sql) {
        new JdbcTemplate(dataSource(database)).execute(sql);
    }

    static List<@Nullable String> column(TestDatabase database, String sql) {
        return JdbcClient.create(dataSource(database))
                .sql(sql)
                .query((RowMapper<@Nullable String>) (rows, index) -> rows.getString(1))
                .list();
    }
}

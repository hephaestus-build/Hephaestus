package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Applies the actual data-only cutover to populated snapshots, including retained terminal jobs. */
@Tag("database")
class SandboxSnapshotMigrationTest {
    private static final String CHANGELOG = "db/changelog/next/20261031181109-kubernetes-only-sandboxes.xml";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void shouldPromoteOnlyVersionEightInEveryJobStateWithoutChangingFrozenPricesOrRetainedResults() throws Exception {
        var database = database();
        var current = snapshot();
        seed(database, 1, current.deepCopy().put("schemaVersion", 7).put("allowInternet", true), "COMPLETED");
        seed(database, 2, current, "COMPLETED");
        int id = 10;
        for (var status : ReviewJobStatus.values()) {
            seed(database, id++, current.deepCopy().put("schemaVersion", 8).put("allowInternet", false), status.name());
        }
        var before = rows(database);

        migrate(database);

        var after = rows(database);
        for (var entry : before.entrySet()) {
            ObjectNode expected = entry.getValue().deepCopy();
            ObjectNode config = (ObjectNode) expected.path("config_snapshot");
            if (config.path("schemaVersion").asInt() == 8) {
                config.remove("allowInternet");
                config.put("schemaVersion", 9);
                assertThat(ConfigSnapshot.fromJson(
                                Objects.requireNonNull(after.get(entry.getKey()))
                                        .path("config_snapshot"),
                                JSON))
                        .isEqualTo(ConfigSnapshot.fromJson(config, JSON));
            }
            assertThat(after.get(entry.getKey())).isEqualTo(expected);
        }
        migrate(database);
        assertThat(rows(database)).isEqualTo(after);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "null", "\"false\"", "missing"})
    void shouldHaltBeforeChangingAnyRowWhenAVersionEightInternetFlagIsNotFalse(String value) throws Exception {
        var database = database();
        ObjectNode bad = snapshot().put("schemaVersion", 8);
        if (!value.equals("missing")) bad.set("allowInternet", JSON.readTree(value));
        seed(database, 1, snapshot().put("schemaVersion", 8).put("allowInternet", false), "QUEUED");
        seed(database, 2, bad, "COMPLETED");
        var before = rows(database);

        assertThatThrownBy(() -> migrate(database)).hasStackTraceContaining("SQL Precondition failed");

        assertThat(rows(database)).isEqualTo(before);
    }

    @Test
    void shouldRefuseRollbackAndKeepThePromotedSnapshot() throws Exception {
        var database = database();
        seed(database, 1, snapshot().put("schemaVersion", 8).put("allowInternet", false), "COMPLETED");
        migrate(database);
        var after = rows(database);
        try (var connection = source(database).getConnection();
                var liquibase = new Liquibase(
                        CHANGELOG,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            assertThatThrownBy(() -> liquibase.rollback(1, new Contexts(), new LabelExpression()))
                    .hasStackTraceContaining("restore the database backup taken before this upgrade");
        }
        assertThat(rows(database)).isEqualTo(after);
    }

    private static PostgreSQLTestContainer.TestDatabase database() {
        var database = PostgreSQLTestContainer.createDatabase(
                "snapshot_cutover_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.execute(database, """
                CREATE TABLE review_job(id integer PRIMARY KEY, config_snapshot jsonb NOT NULL, status varchar NOT NULL,
                    admission_digest varchar NOT NULL, result jsonb NOT NULL)
                """);
        return database;
    }

    private static ObjectNode snapshot() {
        var price = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE,
                UsagePricingState.PRICED,
                31L,
                null,
                new BigDecimal("2.500000"),
                new BigDecimal("12.000000"),
                new BigDecimal("0.250000"),
                new BigDecimal("3.000000"));
        var model = new FrozenModel(
                LlmApiProtocol.OPENAI_RESPONSES,
                "https://models.example.invalid",
                "frozen-model",
                UsageFundingSource.INSTANCE,
                11L,
                21L,
                null,
                DataHandlingTier.IN_HOUSE,
                ReasoningEffort.HIGH,
                price,
                false);
        return (ObjectNode) new ConfigSnapshot(9, model, 16000, 1000, 600, Map.of(ModelKind.CHAT, model)).toJson(JSON);
    }

    private static DriverManagerDataSource source(PostgreSQLTestContainer.TestDatabase database) {
        return new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
    }

    private static void seed(PostgreSQLTestContainer.TestDatabase database, int id, JsonNode snapshot, String status)
            throws Exception {
        try (var connection = source(database).getConnection();
                var statement = connection.prepareStatement(
                        "INSERT INTO review_job VALUES (?,?::jsonb,?,repeat('a',64),'{\"retained\":true}')")) {
            statement.setInt(1, id);
            statement.setString(2, snapshot.toString());
            statement.setString(3, status);
            statement.executeUpdate();
        }
    }

    private static Map<Integer, ObjectNode> rows(PostgreSQLTestContainer.TestDatabase database) throws Exception {
        Map<Integer, ObjectNode> rows = new LinkedHashMap<>();
        try (var connection = source(database).getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT id,row_to_json(review_job)::text AS row FROM review_job ORDER BY id")) {
            while (result.next())
                rows.put(result.getInt("id"), (ObjectNode)
                        Objects.requireNonNull(JSON.readTree(result.getString("row"))));
        }
        return rows;
    }

    private static void migrate(PostgreSQLTestContainer.TestDatabase database) throws Exception {
        try (var connection = source(database).getConnection();
                var liquibase = new Liquibase(
                        CHANGELOG,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }
}

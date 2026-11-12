package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("database")
class EvidenceHomeMigrationTest {
    @Test
    void shouldClearPendingErasureIdentitiesAndKeepOnlyTheirSandboxCleanupIdentity() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("evidence_home_erasure");
        PracticeMigrationFixture.upgradeBefore(database, "prod", "20261112135237-role-evidence-home.xml");
        var sql = new JdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        UUID sandbox = UUID.randomUUID();
        var ids = new ArrayList<UUID>();
        for (String state : List.of("ERASE_REQUESTED", "PURGE_REQUESTED")) {
            for (boolean withSandbox : List.of(false, true)) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                String payload =
                        "{\"identities\":[{\"subject\":\"private-person\"}],\"repositories\":[42],\"dependencies\":\"COMPLETE\""
                                + (withSandbox ? ",\"sandboxId\":\"" + sandbox + "\"" : "") + "}";
                sql.update(
                        "INSERT INTO person_evidence_copy(id,workspace_id,job_id,store_id,state,payload) VALUES (?,1,?,?,?,CAST(? AS jsonb))",
                        id,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        state,
                        payload);
            }
        }
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts("prod"), new LabelExpression());
        }
        for (int i = 0; i < ids.size(); i++) {
            UUID id = ids.get(i);
            assertThat(sql.queryForObject("SELECT state FROM person_evidence_copy WHERE id=?", String.class, id))
                    .isEqualTo("ERASED");
            String expected = i % 2 == 0 ? "{}" : "{\"sandboxId\": \"" + sandbox + "\"}";
            assertThat(sql.queryForObject(
                            "SELECT payload::text FROM person_evidence_copy WHERE id=?", String.class, id))
                    .isEqualTo(expected);
        }
    }
}

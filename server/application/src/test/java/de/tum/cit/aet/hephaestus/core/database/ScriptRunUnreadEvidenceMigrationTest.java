package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.ScriptStopReason;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The check on a script run's stop reason holds every reason that the server records. */
@Tag("database")
class ScriptRunUnreadEvidenceMigrationTest {

    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();

    @Test
    void shouldAllowEveryStopReason() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("script_run_unread_evidence");
        try (Connection connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                Liquibase liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(CONTEXTS, LABELS);
            try (var statement = connection.prepareStatement("""
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE conname = 'ck_review_job_script_run_reason'""")) {
                var row = statement.executeQuery();
                assertThat(row.next()).isTrue();
                String check = row.getString(1);
                assertThat(Arrays.stream(ScriptStopReason.values()).map(Enum::name))
                        .allSatisfy(reason -> assertThat(check).contains("'" + reason + "'"));
            }
        }
    }
}

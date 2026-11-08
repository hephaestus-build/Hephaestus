package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class IssueCommentCountMigrationTest {
    private static final String CHANGELOG = "-cx3-fb7-schema-20261107233604.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("issue_comment_count");

    @Test
    void shouldCorrectOnlyAmbiguousIssueZerosAndRefuseAnInventedRollback() throws Exception {
        try (Connection connection = connect();
                Liquibase liquibase = liquibase(connection)) {
            var unrun = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int before = 0;
            while (!unrun.get(before).getFilePath().endsWith(CHANGELOG)) before++;
            liquibase.update(before, CONTEXTS, LABELS);
            try (Connection fixture = connect();
                    var statement = fixture.createStatement()) {
                statement.execute(
                        "INSERT INTO identity_provider(id,type,server_url) VALUES (90000,'GITHUB','https://count.example.com')");
                statement.execute(
                        "INSERT INTO repository(id,native_id,provider_id,name,name_with_owner,is_archived,is_disabled,is_private) VALUES(90000,1,90000,'repo','example/repo',false,false,false)");
                statement.execute(
                        "INSERT INTO issue(id,native_id,provider_id,repository_id,issue_type,number,title,state,is_locked,comments_count) VALUES (90001,1,90000,90000,'ISSUE',1,'Work','OPEN',false,0),(90002,2,90000,90000,'ISSUE',2,'Work','OPEN',false,7),(90003,3,90000,90000,'PULL_REQUEST',3,'Work','OPEN',false,0),(90004,4,90000,90000,'PULL_REQUEST',4,'Work','OPEN',false,7)");
            }

            liquibase.update(2, CONTEXTS, LABELS);

            assertThat(count(connection, 90001L)).isNull();
            assertThat(count(connection, 90002L)).isEqualTo(7);
            assertThat(count(connection, 90003L)).isZero();
            assertThat(count(connection, 90004L)).isEqualTo(7);
            try (Connection fixture = connect();
                    var statement = fixture.createStatement()) {
                statement.execute(
                        "INSERT INTO issue(id,native_id,provider_id,repository_id,issue_type,number,title,state,is_locked,comments_count) VALUES(90005,5,90000,90000,'ISSUE',5,'New work','OPEN',false,NULL)");
            }
            assertThat(count(connection, 90005L)).isNull();
            // The correction cannot distinguish a genuine zero from an omitted count after the fact.
            assertThatThrownBy(() -> liquibase.rollback(1, CONTEXTS, LABELS))
                    .hasStackTraceContaining("verified database backup");
            assertThat(count(connection, 90001L)).isNull();
            assertThat(count(connection, 90003L)).isZero();
            assertThat(count(connection, 90005L)).isNull();
        }
    }

    private static @Nullable Integer count(Connection connection, long id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT comments_count FROM issue WHERE id=?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getObject(1, Integer.class);
            }
        }
    }

    private static Liquibase liquibase(Connection connection) throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        var liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database);
        // The fixture deliberately pauses the history before the correction to seed populated rows.
        liquibase
                .getDatabaseChangeLog()
                .getPreconditions()
                .getNestedPreconditions()
                .clear();
        return liquibase;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }
}

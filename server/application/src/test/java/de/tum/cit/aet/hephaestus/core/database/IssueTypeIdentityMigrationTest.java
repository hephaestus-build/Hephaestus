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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A provider's issue type id is unique only per instance, so a type that two instances shared becomes two rows. */
@Tag("database")
class IssueTypeIdentityMigrationTest {
    private static final String CHANGELOG = "-fix-issue-type-identity-per-organization.xml";
    private static final String GID = "gid://gitlab/WorkItems::Type/1";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("issue_type_identity");

    @Test
    void shouldGiveEachInstanceItsOwnTypeAndKeepASubgroupOnItsRootGroupsType() throws Exception {
        try (Connection connection = connect();
                Liquibase liquibase = liquibase(connection)) {
            var unrun = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int before = 0;
            while (!unrun.get(before).getFilePath().endsWith(CHANGELOG)) before++;
            liquibase.update(before, CONTEXTS, LABELS);
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO identity_provider(id,type,server_url) VALUES"
                        + " (91000,'GITLAB','https://gitlab-a.example.com'),"
                        + " (91001,'GITLAB','https://gitlab-b.example.com')");
                // The same group path on both instances, and a subgroup on the first.
                statement.execute("INSERT INTO organization(id,native_id,login,provider_id) VALUES"
                        + " (91000,1,'group',91000), (91001,1,'group',91001), (91002,2,'group/sub',91000),"
                        + " (91003,2,'group/sub',91001)");
                statement.execute("INSERT INTO repository"
                        + "(id,native_id,provider_id,organization_id,name,name_with_owner,is_archived,is_disabled,is_private)"
                        + " VALUES (91000,1,91000,91000,'a','group/a',false,false,false),"
                        + " (91001,1,91001,91001,'b','group/b',false,false,false),"
                        + " (91002,2,91000,91002,'c','group/sub/c',false,false,false),"
                        + " (91003,2,91001,91003,'d','group/sub/d',false,false,false)");
                // The second instance's sync took over the first instance's row, as the old key allowed.
                statement.execute("INSERT INTO issue_type(id,name,color,is_enabled,organization_id) VALUES ('" + GID
                        + "','Issue','BLUE',true,91000)");
                statement.execute(
                        "INSERT INTO issue(id,native_id,provider_id,repository_id,issue_type,number,title,state,is_locked,issue_type_id)"
                                + " VALUES (91001,1,91000,91000,'ISSUE',1,'Root work','OPEN',false,'" + GID + "'),"
                                + " (91002,1,91001,91001,'ISSUE',1,'Other instance work','OPEN',false,'" + GID + "'),"
                                + " (91003,2,91000,91002,'ISSUE',1,'Subgroup work','OPEN',false,'" + GID + "'),"
                                + " (91004,2,91001,91003,'ISSUE',1,'Other subgroup work','OPEN',false,'" + GID + "')");
            }
            // Liquibase turns autocommit off and rolls back what is still open when it starts the next change set.
            if (!connection.getAutoCommit()) connection.commit();

            liquibase.update(1, CONTEXTS, LABELS);

            assertThat(typeOrganization(connection, 91001L)).isEqualTo(91000L);
            assertThat(typeOrganization(connection, 91002L)).isEqualTo(91001L);
            assertThat(typeOrganization(connection, 91003L)).isEqualTo(91000L);
            assertThat(nativeId(connection, 91002L)).isEqualTo(GID);
            // A subgroup on the second instance shares its root group's one copy.
            assertThat(typeOrganization(connection, 91004L)).isEqualTo(91001L);
            assertThat(typeId(connection, 91004L)).isEqualTo(typeId(connection, 91002L));
            assertThatThrownBy(() -> liquibase.rollback(1, CONTEXTS, LABELS))
                    .hasStackTraceContaining("Restore the database backup");
        }
    }

    private static long typeOrganization(Connection connection, long issueId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT t.organization_id FROM issue i JOIN issue_type t ON t.id = i.issue_type_id WHERE i.id = ?")) {
            statement.setLong(1, issueId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static long typeId(Connection connection, long issueId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT issue_type_id FROM issue WHERE id = ?")) {
            statement.setLong(1, issueId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static String nativeId(Connection connection, long issueId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT t.native_id FROM issue i JOIN issue_type t ON t.id = i.issue_type_id WHERE i.id = ?")) {
            statement.setLong(1, issueId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static Liquibase liquibase(Connection connection) throws Exception {
        var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        var liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database);
        // The fixture pauses the history before the change set to seed the rows it migrates.
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

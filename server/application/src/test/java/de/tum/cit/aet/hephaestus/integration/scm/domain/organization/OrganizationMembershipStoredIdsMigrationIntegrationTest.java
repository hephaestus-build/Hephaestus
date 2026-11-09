package de.tum.cit.aet.hephaestus.integration.scm.domain.organization;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The change set that moves memberships stored under GitHub's organization id runs this file. It runs here against a
 * fixture schema, because the migrated schema's foreign keys no longer allow such rows. It runs as the schema owner, as
 * the migration does: the application role cannot create the fixture schema.
 */
class OrganizationMembershipStoredIdsMigrationIntegrationTest extends BaseIntegrationTest {

    private static final String SCRIPT = "db/sql/organization-membership-stored-ids.sql";

    @Test
    void shouldMoveWebhookRowsToTheStoredOrganizationAndKeepTheSyncsRole() throws SQLException {
        var owner = PostgreSQLTestContainer.owner();
        try (Connection connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password())) {
            connection.setAutoCommit(false);
            var dataSource = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(dataSource);
            try {
                jdbc.execute("CREATE SCHEMA membership_remap_fixture");
                jdbc.execute("SET LOCAL search_path TO membership_remap_fixture");
                jdbc.execute("CREATE TABLE organization (id bigint PRIMARY KEY, native_id bigint NOT NULL)");
                jdbc.execute("CREATE TABLE \"user\" (id bigint PRIMARY KEY)");
                jdbc.execute("CREATE TABLE organization_membership (organization_id bigint, user_id bigint,"
                        + " role varchar(32), PRIMARY KEY (organization_id, user_id))");
                // Organization 1 is GitHub's 1001. Two providers share GitHub id 2002, so it names no single one.
                jdbc.execute("INSERT INTO organization VALUES (1, 1001), (2, 2002), (3, 2002)");
                jdbc.execute("INSERT INTO \"user\" VALUES (10), (11), (12)");
                jdbc.execute("INSERT INTO organization_membership VALUES"
                        // The sync wrote person 10 under the stored id, a webhook under GitHub's id: the sync wins.
                        + " (1, 10, 'ADMIN'), (1001, 10, 'MEMBER'),"
                        // Only a webhook wrote person 11: the row moves to the stored organization.
                        + " (1001, 11, 'MEMBER'),"
                        // GitHub id 2002 names two organizations: the row is not moved, and it names no stored one.
                        + " (2002, 12, 'MEMBER'),"
                        // A stored organization id stays as it is.
                        + " (2, 12, 'MEMBER'),"
                        // No organization and no person: removed.
                        + " (999, 10, 'MEMBER'), (2, 99, 'MEMBER')");

                ResourceDatabasePopulator script = new ResourceDatabasePopulator(new ClassPathResource(SCRIPT));
                // On the fixture's connection, inside its transaction: execute(DataSource) would commit, which ends
                // the fixture's search path and keeps the fixture schema.
                script.populate(connection);

                assertThat(jdbc.queryForList("SELECT organization_id, user_id, role FROM organization_membership"
                                + " ORDER BY organization_id, user_id"))
                        .containsExactly(
                                Map.of("organization_id", 1L, "user_id", 10L, "role", "ADMIN"),
                                Map.of("organization_id", 1L, "user_id", 11L, "role", "MEMBER"),
                                Map.of("organization_id", 2L, "user_id", 12L, "role", "MEMBER"));
            } finally {
                connection.rollback();
            }
        }
    }
}

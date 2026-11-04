package de.tum.cit.aet.hephaestus.integration.scm.domain.organization;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The change set that moves memberships stored under GitHub's organization id runs this file. It runs here against a
 * fixture schema, because the migrated schema's foreign keys no longer allow such rows.
 */
class OrganizationMembershipStoredIdsMigrationIntegrationTest extends BaseIntegrationTest {

    private static final String SCRIPT = "db/sql/organization-membership-stored-ids.sql";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void shouldMoveWebhookRowsToTheStoredOrganizationAndKeepTheSyncsRole() {
        var rows = transactionTemplate.execute(status -> {
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
                // The populator takes the transaction's connection, so it sees the fixture search path.
                script.execute(dataSource);

                return jdbc.queryForList("SELECT organization_id, user_id, role FROM organization_membership"
                        + " ORDER BY organization_id, user_id");
            } finally {
                status.setRollbackOnly();
            }
        });

        assertThat(rows)
                .containsExactly(
                        Map.of("organization_id", 1L, "user_id", 10L, "role", "ADMIN"),
                        Map.of("organization_id", 1L, "user_id", 11L, "role", "MEMBER"),
                        Map.of("organization_id", 2L, "user_id", 12L, "role", "MEMBER"));
    }
}

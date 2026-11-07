package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Links a commit's unlinked first author on GitHub where the row itself proves who it is. The proof is a noreply address
 * that holds the author's account id, or an email that the provider reports for exactly one user. Enrichment reads only
 * commits with no contributors, so it never returns to these rows.
 *
 * <p>{@code git_commit.author_id} alone proves nothing. Earlier releases could set it from an email guess, from the
 * commit's other actor, or from a noreply login, and no column records which. A login is no proof either: GitHub gives a
 * released login to a new account, so only the account id in the address counts. Every other row stays unlinked. GitLab
 * is out: a stored email there could itself come from a guess, and the linker fills those rows from pairs GitLab
 * resolved. Liquibase checksums only this class's name, so this statement must never change.
 */
@WorkspaceAgnostic("Upgrade-time correction of shared mirror rows across all workspaces")
public class CommitContributorAuthorRelinkChange implements CustomTaskChange {

    private static final Logger log = LoggerFactory.getLogger(CommitContributorAuthorRelinkChange.class);

    private static final String LINK = """
        WITH unique_email AS (
          SELECT provider_id, lower(email) AS email
          FROM "user"
          WHERE email <> ''
          GROUP BY provider_id, lower(email)
          HAVING count(*) = 1)
        UPDATE commit_contributor cc SET user_id = gc.author_id
        FROM git_commit gc, repository r, identity_provider rp, "user" u
        WHERE gc.id = cc.commit_id
          AND r.id = gc.repository_id
          AND rp.id = r.provider_id
          AND u.id = gc.author_id
          AND cc.user_id IS NULL
          AND cc.role = 'AUTHOR'
          AND cc.ordinal = 0
          AND cc.email = gc.author_email
          AND u.provider_id = r.provider_id
          AND rp.type = 'GITHUB'
          AND (
            substring(lower(cc.email) from '^([0-9]+)[+][^@]+@users[.]noreply[.]github[.]com$') = u.native_id::text
            OR (
              lower(u.email) = lower(cc.email)
              AND (u.provider_id, lower(u.email)) IN (SELECT provider_id, email FROM unique_email)))
        """;

    private int linked;

    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection)) {
            throw new CustomChangeException("Linking contributors requires a JDBC database");
        }
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection.getUnderlyingConnection(), true));
        try {
            linked = jdbc.update(LINK);
        } catch (RuntimeException exception) {
            throw new CustomChangeException("Contributors could not be linked", exception);
        }
        log.info("Linked first authors whose row proves their identity: count={}", linked);
    }

    @Override
    public String getConfirmationMessage() {
        return "Linked " + linked + " first authors whose row proves their identity";
    }

    @Override
    public void setUp() {}

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {}

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}

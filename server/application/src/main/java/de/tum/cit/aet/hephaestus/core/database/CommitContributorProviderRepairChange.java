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
 * Clears a contributor's link to a user on another kind of provider, such as a GitLab user on a GitHub commit. A
 * {@code user} row belongs to one provider, so that account cannot be the contributor, whichever code wrote the link.
 *
 * <p>The comparison is on the provider type, not its id: one instance can have several {@code identity_provider} rows
 * that differ only in the spelling of the URL. Every other unusual link stays, because the database cannot tell whether
 * it is right. Liquibase checksums only this class's name, so this statement must never change.
 */
@WorkspaceAgnostic("Upgrade-time correction of shared mirror rows across all workspaces")
public class CommitContributorProviderRepairChange implements CustomTaskChange {

    private static final Logger log = LoggerFactory.getLogger(CommitContributorProviderRepairChange.class);

    private static final String CLEAR = """
        UPDATE commit_contributor cc SET user_id = NULL
        FROM git_commit gc, repository r, identity_provider rp, "user" u, identity_provider up
        WHERE gc.id = cc.commit_id
          AND r.id = gc.repository_id
          AND rp.id = r.provider_id
          AND u.id = cc.user_id
          AND up.id = u.provider_id
          AND up.type <> rp.type
        """;

    private int cleared;

    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection)) {
            throw new CustomChangeException("Clearing contributor links requires a JDBC database");
        }
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection.getUnderlyingConnection(), true));
        try {
            cleared = jdbc.update(CLEAR);
        } catch (RuntimeException exception) {
            throw new CustomChangeException("Contributor links could not be cleared", exception);
        }
        log.info("Cleared contributor links to a user on another kind of provider: count={}", cleared);
    }

    @Override
    public String getConfirmationMessage() {
        return "Cleared " + cleared + " contributor links to a user on another kind of provider";
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

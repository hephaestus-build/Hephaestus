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
 * Queues one row for each email on each GitHub repository, with its newest commit as the representative. GitHub answers
 * for an email, so one commit stands for all of them. Liquibase checksums only this class's name, so this statement must
 * never change.
 */
@WorkspaceAgnostic("Upgrade-time seed of a queue over shared mirror rows across all workspaces")
public class CommitIdentityResolutionSeedChange implements CustomTaskChange {

    private static final Logger log = LoggerFactory.getLogger(CommitIdentityResolutionSeedChange.class);

    private static final String SEED = """
        INSERT INTO commit_identity_resolution (repository_id, commit_id, contributor_id, source, state, attempts)
        SELECT DISTINCT ON (e.repository_id, e.email)
               e.repository_id, e.commit_id, e.contributor_id, e.source, 'PENDING', 0
        FROM (
          SELECT gc.repository_id, gc.author_email AS email, gc.id AS commit_id,
                 CAST(NULL AS bigint) AS contributor_id, 'AUTHOR' AS source
          FROM git_commit gc
          UNION ALL
          SELECT gc.repository_id, gc.committer_email, gc.id, NULL, 'COMMITTER'
          FROM git_commit gc
          UNION ALL
          SELECT gc.repository_id, cc.email, gc.id, cc.id, 'CONTRIBUTOR'
          FROM commit_contributor cc JOIN git_commit gc ON gc.id = cc.commit_id
        ) e
        JOIN repository r ON r.id = e.repository_id
        JOIN identity_provider p ON p.id = r.provider_id
        WHERE p.type = 'GITHUB' AND e.email <> ''
        ORDER BY e.repository_id, e.email, e.commit_id DESC
        """;

    private int queued;

    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection)) {
            throw new CustomChangeException("Queueing commit emails requires a JDBC database");
        }
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection.getUnderlyingConnection(), true));
        try {
            queued = jdbc.update(SEED);
        } catch (RuntimeException exception) {
            throw new CustomChangeException("Commit emails could not be queued", exception);
        }
        log.info("Queued commit emails for provider resolution: count={}", queued);
    }

    @Override
    public String getConfirmationMessage() {
        return "Queued " + queued + " commit emails for provider resolution";
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

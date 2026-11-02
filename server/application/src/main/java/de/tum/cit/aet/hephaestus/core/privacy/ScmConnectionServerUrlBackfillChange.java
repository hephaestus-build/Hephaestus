package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import java.util.Objects;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Stores the server URL on every source control connection. Without one, API calls meant the configured GitLab, and
 * source namespaces meant gitlab.com, so the connection could not say which instance holds its work. API calls reached
 * the configured instance, so that is the instance a stored URL names.
 */
@WorkspaceAgnostic("Upgrade-time backfill of the connection table across all workspaces")
public class ScmConnectionServerUrlBackfillChange implements CustomTaskChange {
    private final String configuredGitLab;

    public ScmConnectionServerUrlBackfillChange() {
        this(System.getenv("GITLAB_DEFAULT_SERVER_URL"));
    }

    ScmConnectionServerUrlBackfillChange(@Nullable String configuredGitLab) {
        this.configuredGitLab = configuredGitLab == null || configuredGitLab.isBlank()
                ? "https://gitlab.com"
                : configuredGitLab.trim().replaceAll("/+$", "");
    }

    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection))
            throw new CustomChangeException("Connection server URLs require a JDBC database");
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection.getUnderlyingConnection(), true));
        try {
            store(jdbc, "GITHUB", PersonSourceNamespace.GITHUB_DEFAULT_URL);
            store(jdbc, "GITLAB", configuredGitLab);
        } catch (RuntimeException exception) {
            throw new CustomChangeException("Connection server URLs could not be stored", exception);
        }
    }

    private static void store(JdbcTemplate jdbc, String kind, String serverUrl) {
        int stored = jdbc.update("""
            UPDATE connection SET config = jsonb_set(config, '{serverUrl}', to_jsonb(?::text))
            WHERE kind = ? AND COALESCE(btrim(config ->> 'serverUrl'), '') = ''
            """, serverUrl, kind);
        if (stored == 0) return;
        jdbc.update("""
            INSERT INTO identity_provider(type,server_url,created_at) VALUES (?,?,now())
            ON CONFLICT(type,server_url) DO NOTHING
            """, kind, serverUrl);
        Long providerId = jdbc.queryForObject(
                "SELECT id FROM identity_provider WHERE type=? AND server_url=?", Long.class, kind, serverUrl);
        IdentityRestrictionService.inheritProviderControls(jdbc, Objects.requireNonNull(providerId));
    }

    @Override
    public String getConfirmationMessage() {
        return "Stored the server URL on every source control connection";
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

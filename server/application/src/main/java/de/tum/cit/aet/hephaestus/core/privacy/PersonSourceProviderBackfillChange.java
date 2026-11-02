package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import java.util.Objects;
import java.util.Optional;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Upgrade-time source registration uses known provider instances, never personal attribution. */
@WorkspaceAgnostic("Upgrade-time registration of exact source namespaces shared by all workspaces")
public class PersonSourceProviderBackfillChange implements CustomTaskChange {
    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection))
            throw new CustomChangeException("Source namespaces require a JDBC database");
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection.getUnderlyingConnection(), true));
        try {
            var sources = jdbc.query("SELECT id,kind,config->>'serverUrl' FROM connection ORDER BY id", (rs, row) -> {
                String kind = Objects.requireNonNull(rs.getString(2));
                // This released step ran before every source control connection stored its URL.
                String url = rs.getString(3);
                if (url == null && kind.equals("GITHUB")) url = PersonSourceNamespace.GITHUB_DEFAULT_URL;
                if (url == null && kind.equals("GITLAB")) url = "https://gitlab.com";
                var namespace = PersonSourceNamespace.from(kind, url);
                if (namespace.isEmpty()
                        && Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM" + " outline_document WHERE" + " connection_id=?)",
                                Boolean.class,
                                rs.getLong(1))))
                    throw new IllegalStateException("Outline connection "
                            + rs.getLong(1)
                            + " mirrors documents but has no exact provider"
                            + " instance");
                return namespace;
            });
            for (var optional : sources) {
                if (optional.isEmpty()) continue;
                var source = optional.orElseThrow();
                jdbc.update("""
                    INSERT INTO identity_provider(type,server_url,created_at) VALUES (?,?,now())
                    ON CONFLICT(type,server_url) DO NOTHING
                    """, source.providerType(), source.serverUrl());
                Long providerId = jdbc.queryForObject(
                        "SELECT id FROM identity_provider WHERE type=? AND server_url=?",
                        Long.class,
                        source.providerType(),
                        source.serverUrl());
                inheritSourceControls(jdbc, Objects.requireNonNull(providerId));
            }
        } catch (RuntimeException exception) {
            throw new CustomChangeException("Exact source namespaces could not be registered", exception);
        }
    }

    private static void inheritSourceControls(JdbcOperations jdbc, long providerId) {
        var instance = jdbc.query(
                "SELECT type,server_url FROM identity_provider WHERE id=?",
                (rs, row) -> new ProviderInstance(
                        Objects.requireNonNull(rs.getString(1)),
                        ScmOrigin.of(rs.getString(2))
                                .orElseThrow(() ->
                                        new IllegalStateException("Provider controls require an exact valid origin"))),
                providerId);
        if (instance.size() != 1) throw new IllegalStateException("The exact provider namespace does not exist");
        var target = instance.getFirst();
        var equivalent = jdbc
                .query(
                        """
                        SELECT DISTINCT p.id,p.server_url FROM identity_provider p
                        JOIN person_suppression s ON s.provider_id=p.id
                        WHERE p.type=? AND p.id<>?
                        """,
                        (rs, row) -> new ProviderAlias(rs.getLong(1), ScmOrigin.of(rs.getString(2))),
                        target.type(),
                        providerId)
                .stream()
                .filter(alias -> alias.origin().filter(target.origin()::equals).isPresent())
                .toList();
        for (var alias : equivalent) {
            jdbc.update("""
                INSERT INTO person_suppression(id,provider_id,subject,team_key,active_request_id)
                SELECT gen_random_uuid(),?,subject,team_key,active_request_id
                FROM person_suppression WHERE provider_id=?
                ON CONFLICT(provider_id,subject,team_key) DO NOTHING
                """, providerId, alias.id());
        }
    }

    private record ProviderInstance(String type, String origin) {}

    private record ProviderAlias(long id, Optional<String> origin) {}

    @Override
    public String getConfirmationMessage() {
        return "Registered exact source provider instances and inherited native controls";
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

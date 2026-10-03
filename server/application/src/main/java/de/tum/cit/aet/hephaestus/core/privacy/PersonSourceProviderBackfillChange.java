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
                var namespace = PersonSourceNamespace.from(Objects.requireNonNull(rs.getString(2)), rs.getString(3));
                if (namespace.isEmpty()
                        && Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM outline_document WHERE connection_id=?)",
                                Boolean.class,
                                rs.getLong(1))))
                    throw new IllegalStateException("Outline connection " + rs.getLong(1)
                            + " mirrors documents but has no exact provider instance");
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
                PersonSuppressionService.inheritProviderControls(jdbc, Objects.requireNonNull(providerId));
            }
        } catch (RuntimeException exception) {
            throw new CustomChangeException("Exact source namespaces could not be registered", exception);
        }
    }

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

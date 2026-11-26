package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Populates the SQL comparison keys with the same parser as a live Outline connection or identity. */
public final class OutlineOriginBackfillChange implements CustomTaskChange {
    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection))
            throw new CustomChangeException("Outline origins require a JDBC database");
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection.getUnderlyingConnection(), true));
        try {
            fill(jdbc, """
                SELECT id,config->>'serverUrl' FROM connection WHERE kind='OUTLINE'
                """, "UPDATE connection SET outline_origin=? WHERE id=?");
            fill(jdbc, """
                SELECT id,server_url FROM identity_provider WHERE type='OUTLINE'
                """, "UPDATE identity_provider SET outline_origin=? WHERE id=?");
        } catch (RuntimeException exception) {
            throw new CustomChangeException("Outline origins could not be stored", exception);
        }
    }

    private record StoredUrl(long id, String origin) {}

    private static void fill(JdbcTemplate jdbc, String select, String update) {
        var keys = jdbc.query(select, (rs, index) ->
                new StoredUrl(rs.getLong(1), ScmOrigin.of(rs.getString(2)).orElse("")));
        for (var key : keys) {
            if (!key.origin().isEmpty()) jdbc.update(update, key.origin(), key.id());
        }
    }

    @Override
    public String getConfirmationMessage() {
        return "Stored canonical Outline origin keys";
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

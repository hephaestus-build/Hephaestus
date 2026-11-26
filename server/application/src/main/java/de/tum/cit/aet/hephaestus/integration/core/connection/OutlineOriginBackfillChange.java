package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import java.sql.SQLException;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

/** Populates the SQL comparison keys with the same parser as a live Outline connection or identity. */
public final class OutlineOriginBackfillChange implements CustomTaskChange {
    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection))
            throw new CustomChangeException("Outline origins require a JDBC database");
        try {
            fill(connection, """
                SELECT id,config->>'serverUrl' FROM connection WHERE kind='OUTLINE'
                """, "UPDATE connection SET outline_origin=? WHERE id=?");
            fill(connection, """
                SELECT id,server_url FROM identity_provider WHERE type='OUTLINE'
                """, "UPDATE identity_provider SET outline_origin=? WHERE id=?");
        } catch (SQLException exception) {
            throw new CustomChangeException("Outline origins could not be stored", exception);
        }
    }

    private static void fill(JdbcConnection connection, String select, String update) throws SQLException {
        var jdbc = connection.getUnderlyingConnection();
        try (var reader = jdbc.prepareStatement(select);
                var rows = reader.executeQuery();
                var writer = jdbc.prepareStatement(update)) {
            while (rows.next()) {
                var origin = ScmOrigin.of(rows.getString(2));
                if (origin.isEmpty()) continue;
                writer.setString(1, origin.get());
                writer.setLong(2, rows.getLong(1));
                writer.addBatch();
            }
            writer.executeBatch();
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

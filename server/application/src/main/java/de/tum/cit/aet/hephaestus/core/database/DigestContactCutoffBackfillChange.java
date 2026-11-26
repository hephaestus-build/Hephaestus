package de.tum.cit.aet.hephaestus.core.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.jspecify.annotations.Nullable;

/** Records the fixed cutoff for digests that an older version can still have in flight. */
public class DigestContactCutoffBackfillChange implements CustomTaskChange {

    private @Nullable String selectOpenDigests;
    private @Nullable String recordCutoff;

    public void setSelectOpenDigests(String query) {
        selectOpenDigests = query;
    }

    public void setRecordCutoff(String statement) {
        recordCutoff = statement;
    }

    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection)) {
            throw new CustomChangeException("Recording digest cutoffs requires a JDBC database");
        }
        try {
            backfill(connection.getUnderlyingConnection());
        } catch (SQLException exception) {
            throw new CustomChangeException("Digest contact cutoffs could not be recorded", exception);
        }
    }

    private void backfill(Connection connection) throws SQLException {
        try (var read = connection.prepareStatement(Objects.requireNonNull(selectOpenDigests));
                var update = connection.prepareStatement(Objects.requireNonNull(recordCutoff));
                var rows = read.executeQuery()) {
            while (rows.next()) {
                Instant cutoff = contactCutoff(rows.getString(3), rows.getString(4));
                if (cutoff == null) continue;
                update.setString(1, cutoff.toString());
                update.setObject(2, rows.getObject(1, UUID.class));
                update.setLong(3, rows.getLong(2));
                update.executeUpdate();
            }
        }
    }

    private static @Nullable Instant contactCutoff(String weekStart, String zone) {
        try {
            // Earlier releases closed contact at 21:00 on the Monday after the week they summarized.
            LocalDate week = LocalDate.parse(weekStart);
            if (week.getDayOfWeek() != DayOfWeek.MONDAY) return null;
            return week.plusWeeks(1)
                    .atTime(LocalTime.of(21, 0))
                    .atZone(ZoneId.of(zone))
                    .toInstant();
        } catch (DateTimeException malformedWindow) {
            return null;
        }
    }

    @Override
    public String getConfirmationMessage() {
        return "Recorded the contact cutoffs of open weekly digests";
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

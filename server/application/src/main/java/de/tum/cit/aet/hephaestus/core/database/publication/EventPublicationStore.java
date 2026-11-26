package de.tum.cit.aet.hephaestus.core.database.publication;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads of the event publication registry that Modulith does not offer: the failed rows due for a retry after their
 * backoff, and the open rows by listener. Both use one failed-state predicate, so the gauge counts what the retry skips.
 */
@Component
@WorkspaceAgnostic("The publication registry is instance-wide; its rows name listeners and events, not workspaces")
public class EventPublicationStore {

    /** The rows that a retry may take: failed, or open in the state that an older Modulith wrote. */
    private static final String FAILED = "(status = 'FAILED' OR (status IS NULL AND completion_date IS NULL))";

    private final JdbcTemplate jdbc;

    public EventPublicationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One stored publication, as its columns hold it. */
    public record StoredPublication(
            UUID id,
            String serializedEvent,
            String eventType,
            String listenerId,
            Instant publicationDate,
            @Nullable Instant lastResubmissionDate,
            int completionAttempts,
            @Nullable String status,
            @Nullable Instant completionDate) {}

    /** The open publications of one listener: those exhausted, and the others still open past the overdue bound. */
    public record OpenPublications(String listenerId, long exhausted, long overdue) {}

    /**
     * The failed rows with attempts left whose backoff ended, the least recently tried first. The backoff counts from
     * the last attempt and doubles from {@code firstBackoff} up to {@code maxBackoff}. It applies before the batch
     * limit, and the database clock decides, so servers that take turns cannot retry a row early.
     *
     * @param publishedBefore only rows published before it, or null for any
     * @param limit the most rows, or -1 for all
     */
    public List<StoredPublication> failedDueForRetry(
            int maxAttempts,
            Duration firstBackoff,
            Duration maxBackoff,
            @Nullable Instant publishedBefore,
            long limit) {
        String sql = """
                SELECT id, serialized_event, event_type, listener_id, publication_date,
                       last_resubmission_date, completion_attempts, status, completion_date
                  FROM event_publication
                 WHERE %s
                   AND completion_attempts < ?
                   AND COALESCE(last_resubmission_date, publication_date)
                       + make_interval(mins => LEAST(? * power(2, GREATEST(completion_attempts - 1, 0)), ?)::int)
                       < clock_timestamp()
                """.formatted(FAILED);
        var arguments = new ArrayList<Object>();
        arguments.add(maxAttempts);
        arguments.add(firstBackoff.toMinutes());
        arguments.add(maxBackoff.toMinutes());
        if (publishedBefore != null) {
            sql += " AND publication_date < ?";
            arguments.add(Timestamp.from(publishedBefore));
        }
        sql += " ORDER BY COALESCE(last_resubmission_date, publication_date), publication_date, id";
        if (limit != -1) {
            sql += " LIMIT ?";
            arguments.add(limit);
        }
        return jdbc.query(sql, (row, index) -> stored(row), arguments.toArray());
    }

    /**
     * Counts the open rows by listener. It reads no payload, so it also counts a row whose event type an upgrade
     * removed. A failed row at {@code maxAttempts} is exhausted at once, however young; a running last attempt can
     * still end, so it is not.
     */
    public List<OpenPublications> openByListener(int maxAttempts, Duration overdueAfter) {
        String exhaustedRow = FAILED + " AND completion_attempts >= ?";
        String sql = """
                SELECT listener_id,
                       count(*) FILTER (WHERE %1$s) AS exhausted,
                       count(*) FILTER (WHERE NOT (%1$s)
                                          AND publication_date < clock_timestamp() - make_interval(mins => CAST(? AS integer)))
                           AS overdue
                  FROM event_publication
                 WHERE completion_date IS NULL
                 GROUP BY listener_id
                """.formatted(exhaustedRow);
        return jdbc.query(
                sql,
                (row, index) -> new OpenPublications(
                        Objects.requireNonNull(row.getString("listener_id")),
                        row.getLong("exhausted"),
                        row.getLong("overdue")),
                maxAttempts,
                maxAttempts,
                overdueAfter.toMinutes());
    }

    private static StoredPublication stored(ResultSet row) throws SQLException {
        return new StoredPublication(
                Objects.requireNonNull(row.getObject("id", UUID.class)),
                Objects.requireNonNull(row.getString("serialized_event")),
                Objects.requireNonNull(row.getString("event_type")),
                Objects.requireNonNull(row.getString("listener_id")),
                Objects.requireNonNull(row.getTimestamp("publication_date")).toInstant(),
                instant(row.getTimestamp("last_resubmission_date")),
                row.getInt("completion_attempts"),
                row.getString("status"),
                instant(row.getTimestamp("completion_date")));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEvent;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A position in the timeline, which is ordered by {@code occurredAt} then {@code id}, both descending. A page
 * lists the events after it, so events recorded while someone scrolls cannot shift a later page.
 */
public record ActivityTimelineCursor(Instant occurredAt, UUID id) {

    private static final char SEPARATOR = '|';

    /** Where the first page starts: every event in the range precedes it, since a range excludes its end. */
    static ActivityTimelineCursor startOf(ActivityRange range) {
        return new ActivityTimelineCursor(range.to(), new UUID(-1L, -1L));
    }

    static ActivityTimelineCursor at(ActivityEvent event) {
        return new ActivityTimelineCursor(event.getOccurredAt(), event.getId());
    }

    /** The opaque form clients hand back; they never read it. */
    String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((occurredAt.toString() + SEPARATOR + id).getBytes(StandardCharsets.UTF_8));
    }

    static ActivityTimelineCursor decode(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(SEPARATOR);
            if (separator < 0) {
                throw invalid(null);
            }
            return new ActivityTimelineCursor(
                    Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeException e) {
            throw invalid(e);
        }
    }

    private static ResponseStatusException invalid(@Nullable Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor", cause);
    }
}

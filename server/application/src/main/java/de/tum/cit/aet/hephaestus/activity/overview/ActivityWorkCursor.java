package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.overview.ActivityQueryRepository.WorkGroup;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A position in the work list, which is ordered by each group's latest activity then by its id, both descending.
 * It carries the end of the range the first page was read in, and every later page reads the same range: activity
 * recorded while someone scrolls would otherwise move a group they have not reached yet ahead of the cursor, and
 * that group would never be listed.
 */
public record ActivityWorkCursor(Instant to, Instant lastOccurredAt, String id) {

    /** Where the first page starts: every group in the range precedes it, since a range excludes its end. */
    static ActivityWorkCursor startOf(ActivityRange range) {
        return new ActivityWorkCursor(range.to(), range.to(), "");
    }

    /** Where the page after {@code last} starts. */
    static ActivityWorkCursor after(ActivityRange range, WorkGroup last) {
        return new ActivityWorkCursor(range.to(), last.getLastOccurredAt(), last.getId());
    }

    /** The opaque form clients hand back; they never read it. */
    String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((to + "|" + lastOccurredAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    static ActivityWorkCursor decode(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 3);
            if (parts.length != 3) {
                throw invalid(null);
            }
            return new ActivityWorkCursor(Instant.parse(parts[0]), Instant.parse(parts[1]), parts[2]);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw invalid(e);
        }
    }

    private static ResponseStatusException invalid(@Nullable Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor", cause);
    }
}

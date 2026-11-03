package de.tum.cit.aet.hephaestus.core.web;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A position in a list ordered by time and then id, both descending. */
public record TimeIdCursor(Instant at, long id) {

    /** The opaque form clients hand back; they never read it. */
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((at + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** The position a client handed back, or null for the first page. */
    public static @Nullable TimeIdCursor decode(@Nullable String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            if (parts.length != 2) {
                throw invalid(null);
            }
            return new TimeIdCursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (IllegalArgumentException | DateTimeException e) {
            throw invalid(e);
        }
    }

    private static ResponseStatusException invalid(@Nullable Throwable cause) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "The cursor is not valid. Reload the list and try again.", cause);
    }
}

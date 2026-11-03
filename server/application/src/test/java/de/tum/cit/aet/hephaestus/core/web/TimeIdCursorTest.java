package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TimeIdCursorTest extends BaseUnitTest {

    @Test
    void shouldReadBackThePositionItWrote() {
        var cursor = new TimeIdCursor(Instant.parse("2026-11-03T10:15:30.123456Z"), 42);

        assertThat(TimeIdCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void shouldReadNoCursorAsTheFirstPage() {
        assertThat(TimeIdCursor.decode(null)).isNull();
    }

    @Test
    void shouldRefuseACursorItDidNotWrite() {
        for (String cursor : new String[] {
            "not base64 !", encoded("no separator"), encoded("yesterday|1"), encoded("2026-11-03T10:15:30Z|many")
        }) {
            assertThatThrownBy(() -> TimeIdCursor.decode(cursor))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    private static String encoded(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}

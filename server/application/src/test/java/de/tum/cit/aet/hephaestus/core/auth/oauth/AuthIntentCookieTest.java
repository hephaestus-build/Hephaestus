package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthIntentCookieTest extends BaseUnitTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    private static AuthIntentCookie at(Instant when) {
        return new AuthIntentCookie(KEY, Clock.fixed(when, ZoneOffset.UTC));
    }

    private static Cookie seal(AuthIntentCookie.Intent intent) {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new AuthIntentCookie(KEY).write(res, intent);
        Cookie cookie = res.getCookie(AuthIntentCookie.COOKIE_NAME);
        assertNotNull(cookie);
        return cookie;
    }

    private static AuthIntentCookie.@Nullable Intent readBack(AuthIntentCookie reader, Cookie cookie) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(cookie);
        return reader.read(req);
    }

    private static AuthIntentCookie.Intent intentAt(long issuedAtMillis) {
        return new AuthIntentCookie.Intent("ws", "/x", AuthIntentCookie.Intent.Mode.LOGIN, null, issuedAtMillis, null);
    }

    @Test
    void freshCookie_roundTrips() {
        Cookie cookie = seal(intentAt(T0.toEpochMilli()));
        AuthIntentCookie.Intent read = readBack(at(T0), cookie);
        assertThat(read).isNotNull();
        assertThat(read.workspaceSlug()).isEqualTo("ws");
        assertThat(read.returnTo()).isEqualTo("/x");
        assertThat(read.mode()).isEqualTo(AuthIntentCookie.Intent.Mode.LOGIN);
    }

    @Test
    void cookieJustWithinTtl_returnsIntent() {
        Cookie cookie = seal(intentAt(T0.toEpochMilli()));
        assertThat(readBack(at(T0.plusSeconds(599)), cookie)).isNotNull();
    }

    @Test
    void cookieAtTtlBoundary_returnsIntent() {
        Cookie cookie = seal(intentAt(T0.toEpochMilli()));
        assertThat(readBack(at(T0.plusSeconds(600)), cookie)).isNotNull();
    }

    @Test
    void cookieOlderThanTtl_returnsNull() {
        Cookie cookie = seal(intentAt(T0.toEpochMilli()));
        assertThat(readBack(at(T0.plusSeconds(600).plusMillis(1)), cookie)).isNull();
    }

    @Test
    void futureDatedCookie_returnsNull() {
        Cookie cookie = seal(intentAt(T0.toEpochMilli()));
        assertThat(readBack(at(T0.minusMillis(1)), cookie)).isNull();
    }

    @Test
    void writesHardenedCookieWithFreshCiphertext() {
        AuthIntentCookie writer = at(T0);
        AuthIntentCookie.Intent intent = intentAt(T0.toEpochMilli());
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        writer.write(first, intent);
        writer.write(second, intent);

        Cookie cookie = first.getCookie(AuthIntentCookie.COOKIE_NAME);
        Cookie secondCookie = second.getCookie(AuthIntentCookie.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(secondCookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(600);
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.getValue()).isNotEqualTo(secondCookie.getValue());
    }

    @Test
    void clearsWithAnExpiredHardenedCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        at(T0).clear(response);

        Cookie cookie = response.getCookie(AuthIntentCookie.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isZero();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    void legacyCookieWithoutIssuedAt_isTreatedAsStale() {
        Cookie cookie = seal(intentAt(0L));
        assertThat(readBack(at(T0), cookie)).isNull();
    }

    @Test
    void tamperedCookie_returnsNull() {
        Cookie cookie = seal(intentAt(T0.toEpochMilli()));
        String v = cookie.getValue();
        Cookie tampered = new Cookie(AuthIntentCookie.COOKIE_NAME, (v.charAt(0) == 'A' ? 'B' : 'A') + v.substring(1));
        assertThat(readBack(at(T0), tampered)).isNull();
    }
}

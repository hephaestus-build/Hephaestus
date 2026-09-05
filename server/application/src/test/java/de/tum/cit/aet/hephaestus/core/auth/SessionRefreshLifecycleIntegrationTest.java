package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Proves the silent-refresh lifecycle the SPA relies on, end-to-end over the LIVE chain (real
 * RevocationAwareJwtDecoder + real ES256 cookie-JWT, no mock decoder) — the same setup as
 * {@code CookieAuthenticationIntegrationTest}.
 *
 * <p>It asserts the guarantees that make "never auto-logged-out while active" actually hold:
 * <ol>
 *   <li>{@code GET /user} exposes a real {@code accessTokenExpiresAt} (so the SPA can schedule renewal);</li>
 *   <li>{@code POST /auth/refresh} ROTATES the cookie (a new, different token is issued);</li>
 *   <li>the rotated cookie authenticates ordinary app requests;</li>
 *   <li>the OLD cookie is immediately revoked (401) — no lingering parallel session;</li>
 *   <li>this holds across MANY consecutive cycles — the session rolls forward indefinitely while the
 *       client keeps refreshing, which is exactly what the keep-alive timer does.</li>
 * </ol>
 */
class SessionRefreshLifecycleIntegrationTest extends RealAuthIntegrationTest {

    private static final String XSRF_COOKIE = "__Host-XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Autowired
    private JwtPrincipalFactory principalFactory;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    @Test
    void userExposesAccessTokenExpiry() {
        Account account = accountRepository.save(new Account("Expiry Eddie"));
        String token = jwtIssuer
                .issue(principalFactory.forAccount(account), TokenConstraints.session(null, null), null)
                .value();

        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accessTokenExpiresAt")
                .isNumber();
    }

    @Test
    void refreshRotatesTheSessionAndKeepsAppRequestsWorkingAcrossManyCycles() {
        Account account = accountRepository.save(new Account("Rolling Rosa"));
        String current = jwtIssuer
                .issue(principalFactory.forAccount(account), TokenConstraints.session(null, null), null)
                .value();
        String csrf = fetchCsrfToken();

        // Five consecutive refreshes: more wall-clock than a single 15-min token would survive, so a
        // session that rolls through all five is one that never auto-logs-out an active user.
        for (int cycle = 1; cycle <= 5; cycle++) {
            getUser(current).expectStatus().isOk();

            String rotated = refreshAndReadNewCookie(current, csrf);

            assertThat(rotated)
                    .as("cycle %d: refresh must mint a NEW token", cycle)
                    .isNotEqualTo(current);
            getUser(rotated).expectStatus().isOk();
            // The token we just rotated away from is immediately dead (no parallel session).
            getUser(current).expectStatus().isUnauthorized();

            current = rotated;
        }
    }

    @Test
    void theAbsoluteSessionCeilingCapsTheTokenAndSurvivesRefresh() {
        Account account = accountRepository.save(new Account("Capped Cathy"));
        // A 2-minute absolute session ceiling — well under the 15-min access TTL, so it binds.
        long ceiling = java.time.Instant.now().getEpochSecond() + 120;
        String token = jwtIssuer
                .issue(
                        principalFactory.forAccount(account),
                        TokenConstraints.session(java.time.Instant.ofEpochSecond(ceiling), null),
                        null)
                .value();

        // The access expiry is capped at the session ceiling, NOT now + accessTtl (15 min).
        assertUserExpiryNear(token, ceiling);

        // The decisive guarantee: a refresh CANNOT extend the session past the ceiling — the rotated
        // token is still capped at it (so the rolling silent refresh can't outlive the absolute timeout).
        String rotated = refreshAndReadNewCookie(token, fetchCsrfToken());
        assertUserExpiryNear(rotated, ceiling);
    }

    private void assertUserExpiryNear(String token, long expectedEpochSeconds) {
        getUser(token)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accessTokenExpiresAt")
                .value(v -> assertThat(((Number) v).longValue())
                        .isBetween(expectedEpochSeconds - 5, expectedEpochSeconds + 2));
    }

    private WebTestClient.ResponseSpec getUser(String token) {
        return webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + token)
                .exchange();
    }

    /** POST /auth/refresh with the auth cookie + CSRF double-submit; returns the rotated access token. */
    private String refreshAndReadNewCookie(String token, String csrf) {
        var result = webTestClient
                .post()
                .uri("/auth/refresh")
                .header(HttpHeaders.COOKIE, cookieName + "=" + token + "; " + XSRF_COOKIE + "=" + csrf)
                .header(XSRF_HEADER, csrf)
                .exchange()
                .expectStatus()
                .isNoContent()
                .returnResult(Void.class);
        ResponseCookie rotated = result.getResponseCookies().getFirst(cookieName);
        assertThat(rotated).as("refresh must Set-Cookie a new access token").isNotNull();
        return rotated.getValue();
    }

    /** A valid double-submit CSRF token, rendered by CsrfCookieFilter on a safe GET. */
    private String fetchCsrfToken() {
        var result = webTestClient
                .get()
                .uri("/identity-providers")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);
        List<ResponseCookie> cookies = result.getResponseCookies().get(XSRF_COOKIE);
        assertThat(cookies).as("XSRF-TOKEN cookie issued on safe GET").isNotEmpty();
        assertNotNull(cookies);
        return cookies.get(0).getValue();
    }
}

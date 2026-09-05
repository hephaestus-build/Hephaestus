package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Proves StaleAuthCookieFilter: a logged-out browser still presenting an INVALID access cookie must
 * not be 401'd on a public endpoint (or the login page can't load its sign-in options — the symptom
 * this filter exists to kill), while a protected endpoint still 401s and a VALID cookie is untouched.
 *
 * <p>Runs over the LIVE security chain (real RevocationAwareJwtDecoder + real ES256 cookie-JWT), the
 * same setup as {@code SessionRefreshLifecycleIntegrationTest}.
 */
class StaleAuthCookieEvictionIntegrationTest extends RealAuthIntegrationTest {

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
    void staleCookieDoesNotBlockPublicEndpointAndIsCleared() {
        var result = webTestClient
                .get()
                .uri("/identity-providers")
                .header(HttpHeaders.COOKIE, cookieName + "=not-a-valid-jwt")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);

        // The dead cookie is cleared so the browser stops resending it — self-healing. A clear is an
        // empty value with a non-positive Max-Age (Tomcat may serialise it as Max-Age=0 or as a past
        // Expires, surfaced as Duration.ZERO or -1s respectively — both mean "drop it now").
        ResponseCookie cleared = result.getResponseCookies().getFirst(cookieName);
        assertThat(cleared).as("stale cookie must be cleared").isNotNull();
        assertThat(cleared.getValue()).as("clearing cookie has an empty value").isEmpty();
        assertThat(cleared.getMaxAge())
                .as("clearing cookie is not kept alive")
                .isLessThanOrEqualTo(java.time.Duration.ZERO);
    }

    @Test
    void staleCookieOnProtectedEndpointStill401sAsUnauthenticated() {
        // The eviction must NOT silently authenticate: a protected endpoint still rejects the request
        // (401 = the correct "logged out" signal the SPA expects, not a 500 bad-token error).
        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=not-a-valid-jwt")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void validCookieIsUntouchedAndStillAuthenticates() {
        Account account = accountRepository.save(new Account("Valid Vera"));
        String token = jwtIssuer
                .issue(principalFactory.forAccount(account), TokenConstraints.session(null, Instant.now()), null)
                .value();

        var result = webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + token)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);

        assertThat(result.getResponseCookies().getFirst(cookieName))
                .as("a valid cookie is left untouched (no clearing Set-Cookie)")
                .isNull();
    }
}

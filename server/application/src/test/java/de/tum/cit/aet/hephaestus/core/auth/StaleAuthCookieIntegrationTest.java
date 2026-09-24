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
import org.springframework.test.web.reactive.server.WebTestClient;

/** Invalid cookies leave public reads usable without allowing access to protected endpoints. */
class StaleAuthCookieIntegrationTest extends RealAuthIntegrationTest {

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
    void shouldIgnoreStaleCookieWithoutOverwritingANewerSession() {
        var result = webTestClient
                .get()
                .uri("/identity-providers")
                .header(HttpHeaders.COOKIE, cookieName + "=not-a-valid-jwt")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);

        assertThat(result.getResponseCookies().getFirst(cookieName)).isNull();
    }

    @Test
    void staleCookieOnProtectedEndpointStill401sAsUnauthenticated() {
        var result = webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=not-a-valid-jwt")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .returnResult(Void.class);
        assertThat(result.getResponseCookies().getFirst(cookieName)).isNull();
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

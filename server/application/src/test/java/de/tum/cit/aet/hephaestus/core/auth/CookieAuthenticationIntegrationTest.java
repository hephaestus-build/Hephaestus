package de.tum.cit.aet.hephaestus.core.auth;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the cookie-session bearer-token resolution wired on the resource-server chain (ADR 0017).
 *
 * <p>The SPA authenticates by an HttpOnly {@code __Host-HEPHAESTUS_AT} cookie and never sends an
 * {@code Authorization} header. The framework default resolver reads ONLY the header, so a
 * cookie-only request proves the custom resolver is wired.
 *
 * <p>Unlike most integration tests this one deliberately uses the <b>real</b>
 * {@code RevocationAwareJwtDecoder} (it does NOT import {@code TestSecurityConfig}'s mock decoder)
 * and mints a genuine ES256 cookie-JWT through {@link HephaestusJwtIssuer}, so the full
 * resolver → decoder → revocation → controller path is exercised end-to-end.
 */
class CookieAuthenticationIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Autowired
    private JwtPrincipalFactory principalFactory;

    @Autowired
    private IssuedJwtRepository issuedJwtRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    @Test
    void cookieOnlyRequestAuthenticatesAndGetUserReturnsAccount() {
        IssuedAccount issued = issueRealTokenForNewAccount("Cookie Cat");

        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + issued.token())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(issued.accountId())
                .jsonPath("$.displayName")
                .isEqualTo("Cookie Cat");
    }

    @Test
    void bearerHeaderWithSameTokenAlsoAuthenticates() {
        IssuedAccount issued = issueRealTokenForNewAccount("Bearer Bear");

        webTestClient
                .get()
                .uri("/user")
                .headers(headers -> headers.setBearerAuth(issued.token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(issued.accountId());
    }

    @Test
    void noCredentialsIsUnauthorized() {
        webTestClient.get().uri("/user").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void revokedTokenIsRejectedWith401ThroughTheLiveChain() {
        // The headline guarantee of the revocation design (sign-out-everywhere), proven end-to-end:
        // the RevocationAwareJwtDecoder NEVER caches an ACTIVE token — it re-reads the issued_jwt row
        // on every request — so a revocation takes effect immediately on the next request.
        IssuedAccount issued = issueRealTokenForNewAccount("Revoked Rita");

        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + issued.token())
                .exchange()
                .expectStatus()
                .isOk();

        // Revoke every session for the account (the issuer persisted the issued_jwt row). The
        // @Modifying query needs an active, COMMITTED tx so the server thread's next read sees it.
        new TransactionTemplate(txManager)
                .executeWithoutResult(status -> issuedJwtRepository.revokeAllForAccount(
                        issued.accountId(), Instant.now(), IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE));

        // The SAME cookie now fails closed: 401 via the resource-server chain.
        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + issued.token())
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    // NOTE on CSRF: the cookie-style POST-without-token → 403 contract is covered by
    // CsrfProtectionIntegrationTest (it asserts the 403→401 transition on /auth/logout). It is not
    // re-asserted here because, once the cookie authenticates, a logout/refresh proceeds to an audit
    // write whose partitioned auth_event schema is not materialized under the test profile's
    // ddl-auto:create (Liquibase is disabled for tests) — that would mask CSRF behind a 500. Keeping
    // this slice focused on resolution avoids that test-environment entanglement.

    private record IssuedAccount(String token, long accountId) {}

    private IssuedAccount issueRealTokenForNewAccount(String displayName) {
        Account account = accountRepository.save(new Account(displayName));
        HephaestusJwtIssuer.Token token = jwtIssuer.issue(
                principalFactory.forAccount(account), TokenConstraints.session(null, Instant.now()), null);
        return new IssuedAccount(token.value(), persistedId(account.getId()));
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}

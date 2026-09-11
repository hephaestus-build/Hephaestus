package de.tum.cit.aet.hephaestus.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
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
        webTestClient
                .get()
                .uri("/user")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
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
                .isOk()
                .expectBody(Void.class);

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
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldAllowSignInDiscoveryWhenBrowserPresentsRevokedCredentials() {
        IssuedAccount issued = issueRealTokenForNewAccount("Revoked Discovery");
        new TransactionTemplate(txManager)
                .executeWithoutResult(status -> issuedJwtRepository.revokeAllForAccount(
                        issued.accountId(), Instant.now(), IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE));

        webTestClient
                .get()
                .uri("/identity-providers")
                .header(HttpHeaders.COOKIE, cookieName + "=" + issued.token())
                .headers(headers -> headers.setBearerAuth(issued.token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    @Test
    void shouldRejectCookieAuthenticatedMutationWhenCsrfTokenIsMissing() {
        IssuedAccount issued = issueRealTokenForNewAccount("Cookie CSRF");
        webTestClient
                .post()
                .uri("/auth/logout")
                .header(HttpHeaders.COOKIE, cookieName + "=" + issued.token())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldRejectCookieAuthenticatedMutationWhenBearerHeaderAlsoExists() {
        IssuedAccount issued = issueRealTokenForNewAccount("Mixed CSRF");
        webTestClient
                .post()
                .uri("/auth/logout")
                .header(HttpHeaders.COOKIE, cookieName + "=" + issued.token())
                .headers(headers -> headers.setBearerAuth(issued.token()))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldReachAuthenticationWithoutCsrfWhenOnlyBearerHeaderExists() {
        webTestClient
                .post()
                .uri("/auth/logout")
                .headers(headers -> headers.setBearerAuth("invalid-token"))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldKeepCsrfCookieStableWhenValidatingAnExistingSession() {
        IssuedAccount issued = issueRealTokenForNewAccount("Stable CSRF");
        String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);
        var response = webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + issued.token() + "; __Host-XSRF-TOKEN=" + csrf)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);
        assertNull(response.getResponseCookies().getFirst("__Host-XSRF-TOKEN"));
    }

    @Test
    void shouldEndSessionAndCommitRevocationWhenAccountIsSuspendedBeforeRefresh() {
        IssuedAccount issued = issueRealTokenForNewAccount("Suspended Refresh");
        Account account = accountRepository.findById(issued.accountId()).orElseThrow();
        account.setStatus(Account.Status.SUSPENDED);
        accountRepository.saveAndFlush(account);
        String csrf = TestAuthUtils.fetchCsrfToken(webTestClient);

        var response = webTestClient
                .post()
                .uri("/auth/refresh")
                .header(HttpHeaders.COOKIE, cookieName + "=" + issued.token() + "; __Host-XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .returnResult(Void.class);
        var cleared = response.getResponseCookies().getFirst(cookieName);
        assertNotNull(cleared);
        assertEquals("", cleared.getValue());

        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + issued.token())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

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

package de.tum.cit.aet.hephaestus.core.auth;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.dto.ExportCreatedDTO;
import de.tum.cit.aet.hephaestus.core.auth.export.dto.ExportStatusDTO;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.ApiWebTestClient;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
    private IssuedJwtRepository issuedJwtRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private ApplicationContext context;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    @Test
    void shouldDownloadTheSignedInAccountsReadyExport() {
        IssuedAccount owner = issueRealTokenForNewAccount("Export download");
        String payload = "{\"export\":\"ready\"}";
        long id = Objects.requireNonNull(PostgreSQLTestContainer.ownerJdbc()
                .queryForObject("""
                INSERT INTO account_export(account_id, status, expires_at, payload)
                VALUES (?, 'READY', now() + interval '1 day', ?) RETURNING id
                """, Long.class, owner.accountId(), payload.getBytes(UTF_8)));

        webTestClient
                .get()
                .uri("/user/exports/{id}/download", id)
                .headers(headers -> headers.setBearerAuth(owner.token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .json(payload);
    }

    @Test
    void shouldFollowTheCanonicalExportLocationWithWorkspaceSubdomainsEnabled() {
        IssuedAccount issued = issueRealTokenForNewAccount("Export Location");
        var response = webTestClient
                .post()
                .uri("/user/exports")
                // A host that is not the apex. A workspace host never reaches the API: the host rules refuse it there.
                .header(HttpHeaders.HOST, "server.cluster.internal")
                .headers(headers -> headers.setBearerAuth(issued.token()))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(ExportCreatedDTO.class)
                .returnResult();
        var created = Objects.requireNonNull(response.getResponseBody());
        URI location = Objects.requireNonNull(response.getResponseHeaders().getLocation());
        assertEquals(URI.create("https://hephaestus.build/api/user/exports/" + created.id()), location);

        // The Gateway removes /api before the request reaches this application listener.
        webTestClient
                .get()
                .uri(location.getRawPath().substring("/api".length()))
                .headers(headers -> headers.setBearerAuth(issued.token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ExportStatusDTO.class)
                .value(status -> assertEquals(created.id(), status.id()));
    }

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
    void noCredentialsIsUnauthorizedWithTheBearerChallengeAndAProblem() {
        webTestClient
                .get()
                .uri("/user")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer.*")
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(401)
                .jsonPath("$.title")
                .isEqualTo("Unauthorized")
                .jsonPath("$.properties")
                .doesNotExist();
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

        // The SAME cookie now fails closed: 401 via the resource-server chain, with the challenge and a problem.
        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + issued.token())
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\".*")
                .expectHeader()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(401)
                .jsonPath("$.detail")
                .isEqualTo("Sign in, then try again.");
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
    void shouldServeTheWebAppWhenBrowserPresentsRevokedCredentials() {
        IssuedAccount issued = issueRealTokenForNewAccount("Revoked Web App");
        new TransactionTemplate(txManager)
                .executeWithoutResult(status -> issuedJwtRepository.revokeAllForAccount(
                        issued.accountId(), Instant.now(), IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE));

        // The page and its files must load, or the browser cannot show the sign-in that replaces the cookie.
        for (String path : new String[] {"/", "/w/acme/practices", "/assets/index-abc123.js"}) {
            webTestClient
                    .get()
                    .uri(ApiWebTestClient.root(context, path))
                    .header(HttpHeaders.COOKIE, cookieName + "=" + issued.token())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
        }
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
        account.block();
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
                Objects.requireNonNull(account.getId()), TokenConstraints.session(null, Instant.now()), null);
        return new IssuedAccount(token.value(), persistedId(account.getId()));
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}

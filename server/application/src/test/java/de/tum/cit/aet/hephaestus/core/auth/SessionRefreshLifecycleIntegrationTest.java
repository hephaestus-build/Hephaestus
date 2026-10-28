package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nimbusds.jwt.SignedJWT;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.reactive.server.WebTestClient;

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
    private IssuedJwtRepository issuedJwtRepository;

    @Autowired
    private AuthEventRepository authEventRepository;

    @Autowired
    private AuthSessionService sessions;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    @Test
    void userExposesAccessTokenExpiry() {
        Account account = accountRepository.save(new Account("Expiry Eddie"));
        String token = jwtIssuer
                .issue(Objects.requireNonNull(account.getId()), TokenConstraints.session(null, null), null)
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
                .issue(
                        Objects.requireNonNull(account.getId()),
                        TokenConstraints.session(Instant.now().plus(Duration.ofHours(12)), null),
                        null)
                .value();
        String csrf = fetchCsrfToken();

        for (int cycle = 1; cycle <= 5; cycle++) {
            getUser(current).expectStatus().isOk().expectBody(Void.class);

            String rotated = refreshAndReadNewCookie(current, csrf);

            assertThat(rotated)
                    .as("cycle %d: refresh must mint a NEW token", cycle)
                    .isNotEqualTo(current);
            getUser(rotated).expectStatus().isOk().expectBody(Void.class);
            getUser(current).expectStatus().isUnauthorized().expectBody(Void.class);

            current = rotated;
        }
    }

    /**
     * A reload or navigation can cut the refresh response after the rotation committed, so the browser
     * never stores the new cookie and still sends the one it had.
     */
    @Test
    void aSessionSurvivesARefreshWhoseResponseNeverArrived() {
        Account account = accountRepository.save(new Account("Cut-off Carla"));
        String held = jwtIssuer
                .issue(
                        Objects.requireNonNull(account.getId()),
                        TokenConstraints.session(Instant.now().plus(Duration.ofHours(12)), null),
                        null)
                .value();
        String csrf = fetchCsrfToken();

        // The rotation commits, but its Set-Cookie never reaches the browser.
        refreshAndReadNewCookie(held, csrf);

        getUser(held).expectStatus().isOk().expectBody(Void.class);
        String recovered = refreshAndReadNewCookie(held, csrf);
        getUser(recovered).expectStatus().isOk().expectBody(Void.class);
    }

    @Test
    void shouldReturnTheSameUnusedSuccessorOnRepeatedRefresh() throws ParseException {
        Long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Idempotent refresh")).getId());
        var held = jwtIssuer.issue(
                accountId, TokenConstraints.session(Instant.now().plusSeconds(3600), null), null);
        String csrf = fetchCsrfToken();
        var first = SignedJWT.parse(refreshAndReadNewCookie(held.value(), csrf)).getJWTClaimsSet();
        var second =
                SignedJWT.parse(refreshAndReadNewCookie(held.value(), csrf)).getJWTClaimsSet();

        assertThat(second.getJWTID()).isEqualTo(first.getJWTID());
        assertThat(second.getExpirationTime()).isEqualTo(first.getExpirationTime());
        assertThat(issuedJwtRepository
                        .findById(UUID.fromString(first.getJWTID()))
                        .orElseThrow()
                        .getRevokedAt())
                .isNull();
    }

    @Test
    void aRefreshWithTheHeldTokenReissuesTheSuccessorThatNeverArrived() {
        Instant since = Instant.now();
        Long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Replaced Rita")).getId());
        HephaestusJwtIssuer.Token held = jwtIssuer.issue(
                accountId, TokenConstraints.session(Instant.now().plus(Duration.ofHours(12)), null), null);
        String csrf = fetchCsrfToken();

        String lost = refreshAndReadNewCookie(held.value(), csrf);
        String replacement = refreshAndReadNewCookie(held.value(), csrf);

        getUser(lost).expectStatus().isOk().expectBody(Void.class);
        getUser(replacement).expectStatus().isOk().expectBody(Void.class);
        assertThat(authEventRepository.findByAccountSince(accountId, since))
                .filteredOn(event -> event.getEventType() == AuthEvent.EventType.TOKEN_REFRESH)
                .extracting(AuthEvent::getDetails)
                .containsExactlyInAnyOrder(null, "{\"reason\":\"SUCCESSOR_REISSUED\"}");
    }

    @Test
    void aSuccessorsFirstUseRetiresItsPredecessorAndALateRequestIsRecordedOnce() {
        Instant since = Instant.now();
        Long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Reused Rudi")).getId());
        HephaestusJwtIssuer.Token held = jwtIssuer.issue(
                accountId, TokenConstraints.session(Instant.now().plus(Duration.ofHours(12)), null), null);
        String successor = refreshAndReadNewCookie(held.value(), fetchCsrfToken());

        getUser(held.value()).expectStatus().isOk().expectBody(Void.class);
        getUser(successor).expectStatus().isOk().expectBody(Void.class);

        IssuedJwt predecessorRow = issuedJwtRepository.findById(held.jti()).orElseThrow();
        assertThat(predecessorRow.getRevokedReason()).isEqualTo(IssuedJwt.RevokedReason.ROTATE);
        assertThat(issuedJwtRepository.findActiveByAccountId(accountId, Instant.now()))
                .singleElement()
                .satisfies(row -> assertThat(row.getPredecessorJti()).isNull());

        getUser(held.value()).expectStatus().isUnauthorized().expectBody(Void.class);
        getUser(held.value()).expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(authEventRepository.findByAccountSince(accountId, since))
                .filteredOn(event -> event.getEventType() == AuthEvent.EventType.JWT_REVOKED)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getResult()).isEqualTo(AuthEvent.Result.FAILURE);
                    assertThat(event.getFailureReason()).isEqualTo("rotated_token_presented");
                });
    }

    @Test
    void theAbsoluteSessionCeilingCapsTheTokenAndSurvivesRefresh() {
        Account account = accountRepository.save(new Account("Capped Cathy"));
        long ceiling = Instant.now().getEpochSecond() + 120;
        String token = jwtIssuer
                .issue(
                        Objects.requireNonNull(account.getId()),
                        TokenConstraints.session(Instant.ofEpochSecond(ceiling), null),
                        null)
                .value();

        assertUserExpiryNear(token, ceiling);

        String rotated = refreshAndReadNewCookie(token, fetchCsrfToken());
        assertUserExpiryNear(rotated, ceiling);
    }

    @Test
    void shouldRefuseRefreshWithAStolenPredecessorAfterTheBrowserUsesItsSuccessor() {
        Long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Replay")).getId());
        var held = jwtIssuer.issue(
                accountId, TokenConstraints.session(Instant.now().plusSeconds(3600), null), null);
        String csrf = fetchCsrfToken();
        String current = refreshAndReadNewCookie(held.value(), csrf);
        getUser(current).expectStatus().isOk().expectBody(Void.class);

        getUser(held.value()).expectStatus().isUnauthorized().expectBody(Void.class);
        webTestClient
                .post()
                .uri("/auth/refresh")
                .header(HttpHeaders.COOKIE, cookieName + "=" + held.value() + "; " + XSRF_COOKIE + "=" + csrf)
                .header(XSRF_HEADER, csrf)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody(Void.class);

        getUser(current).expectStatus().isOk().expectBody(Void.class);
        assertThat(authEventRepository.findByAccountSince(accountId, Instant.EPOCH))
                .filteredOn(event -> event.getEventType() == AuthEvent.EventType.JWT_REVOKED)
                .extracting(AuthEvent::getFailureReason)
                .containsExactlyInAnyOrder("rotated_token_presented", "browser_token_reuse");
    }

    @Test
    void shouldNeverExtendThePredecessorsOwnExpiryWhileItsSuccessorIsUnused() {
        Long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Expiry boundary")).getId());
        var held = jwtIssuer.issue(
                accountId, TokenConstraints.session(Instant.now().plusSeconds(3600), null), null);
        String successor = refreshAndReadNewCookie(held.value(), fetchCsrfToken());
        IssuedJwt row = issuedJwtRepository.findById(held.jti()).orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(1));
        issuedJwtRepository.saveAndFlush(row);

        getUser(held.value()).expectStatus().isUnauthorized().expectBody(Void.class);
        issuedJwtRepository.deleteById(held.jti());
        getUser(successor).expectStatus().isOk().expectBody(Void.class);
    }

    @Test
    void shouldEndAnUnusedSuccessorWhenTheBrowserLogsOutWithItsPredecessor() {
        Long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Logout")).getId());
        var held = jwtIssuer.issue(
                accountId, TokenConstraints.session(Instant.now().plusSeconds(3600), null), null);
        String successor = refreshAndReadNewCookie(held.value(), fetchCsrfToken());

        sessions.logout(accountId, held.jti(), null, new MockHttpServletResponse());

        getUser(held.value()).expectStatus().isUnauthorized().expectBody(Void.class);
        getUser(successor).expectStatus().isUnauthorized().expectBody(Void.class);
    }

    @Test
    void shouldEndOnlyTheOwnedBrowserAndItsUnusedSuccessorWhenASessionIsRevoked() {
        Long accountId = Objects.requireNonNull(
                accountRepository.save(new Account("Session owner")).getId());
        Long otherId = Objects.requireNonNull(
                accountRepository.save(new Account("Other account")).getId());
        var held = jwtIssuer.issue(
                accountId, TokenConstraints.session(Instant.now().plusSeconds(3600), null), null);
        String successor = refreshAndReadNewCookie(held.value(), fetchCsrfToken());

        sessions.revokeSession(otherId, held.jti());
        getUser(held.value()).expectStatus().isOk().expectBody(Void.class);
        sessions.revokeSession(accountId, held.jti());

        getUser(held.value()).expectStatus().isUnauthorized().expectBody(Void.class);
        getUser(successor).expectStatus().isUnauthorized().expectBody(Void.class);
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

package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import static de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInFlow.CALLBACK;
import static de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInFlow.CLIENT_ID;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.AccountPurger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Installed-client sign-in end to end over real HTTP, against the real resource-server chain and
 * {@code RevocationAwareJwtDecoder}: the dev door, the exchange, bearer access without a cookie, strict
 * rotation, full-lineage revocation and the boundaries that keep a client token out of the browser
 * session paths.
 */
@TestPropertySource(properties = "hephaestus.auth.dev-login-enabled=true")
class ClientSessionIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IssuedJwtRepository issuedJwtRepository;

    @Autowired
    private ClientSessionRepository sessionRepository;

    @Autowired
    private ClientSignInHandoffRepository handoffRepository;

    @Autowired
    private ClientSessionPruner pruner;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private AccountPurger accountPurger;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    private ClientSignInFlow flow;

    @BeforeEach
    void setUpFlow() {
        flow = new ClientSignInFlow(webTestClient);
    }

    private UUID sidOf(String accessToken) {
        return ClientSignInFlow.sid(accessToken);
    }

    private UUID jtiOf(String accessToken) {
        return ClientSignInFlow.jti(accessToken);
    }

    private ClientSession session(UUID sid) {
        return sessionRepository.findById(sid).orElseThrow();
    }

    private long activeTokensOf(UUID sid) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM issued_jwt WHERE session_id = ? AND revoked_at IS NULL", Long.class, sid));
    }

    @Test
    void shouldAuthenticateWithTheBearerAloneAndRotateWhenAClientSignsInAndRefreshes() {
        ClientSignInFlow.Tokens first = flow.signIn("ext-alice");

        flow.assertAccepted(first.accessToken());
        UUID sid = sidOf(first.accessToken());
        assertThat(session(sid).getClientKind()).isEqualTo(InstalledClientKind.BROWSER_EXTENSION);
        assertThat(session(sid).getClientId()).isEqualTo(CLIENT_ID);

        ClientSignInFlow.Tokens second = flow.rotate(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(sidOf(second.accessToken())).isEqualTo(sid);
        assertThat(second.sessionExpiresAt()).isEqualTo(first.sessionExpiresAt());
        flow.assertAccepted(second.accessToken());
        flow.assertRejected(first.accessToken());
        assertThat(activeTokensOf(sid)).isEqualTo(1);
    }

    @Test
    void shouldEndTheWholeSessionAndCommitThatWhenARotatedSecretIsPresentedAgain() {
        ClientSignInFlow.Tokens first = flow.signIn("ext-reuse");
        ClientSignInFlow.Tokens second = flow.rotate(first.refreshToken());
        UUID sid = sidOf(second.accessToken());

        flow.assertRefreshRefused(first.refreshToken());

        ClientSession ended = session(sid);
        assertThat(ended.getRevokedAt()).isNotNull();
        assertThat(ended.getRevokedReason()).isEqualTo(IssuedJwt.RevokedReason.REFRESH_REUSE);
        assertThat(activeTokensOf(sid)).isZero();
        flow.assertRejected(second.accessToken());
        flow.assertRefreshRefused(second.refreshToken());
        Long reuseEvents = jdbc.queryForObject(
                "SELECT count(*) FROM auth_event WHERE event_type = 'JWT_REVOKED' AND failure_reason = 'client_refresh_reuse'",
                Long.class);
        assertThat(reuseEvents).isPositive();
    }

    @Test
    void shouldRevokeTheLiveSessionWhenTheOldestSecretIsReplayedAfterTwoRotations() {
        ClientSignInFlow.Tokens r0 = flow.signIn("ext-lineage");
        ClientSignInFlow.Tokens r1 = flow.rotate(r0.refreshToken());
        ClientSignInFlow.Tokens r2 = flow.rotate(r1.refreshToken());
        flow.assertAccepted(r2.accessToken());

        flow.assertRefreshRefused(r0.refreshToken());

        flow.assertRejected(r2.accessToken());
        flow.assertRefreshRefused(r2.refreshToken());
    }

    @Test
    void shouldEndTheLiveSessionWhenLoggingOutWithTheOldestSecretAfterTwoRotations() {
        ClientSignInFlow.Tokens r0 = flow.signIn("ext-logout-old");
        ClientSignInFlow.Tokens r2 = flow.rotate(flow.rotate(r0.refreshToken()).refreshToken());

        flow.logout(r0.refreshToken()).expectStatus().isNoContent().expectBody(Void.class);

        flow.assertRejected(r2.accessToken());
        flow.assertRefreshRefused(r2.refreshToken());
        assertThat(session(sidOf(r2.accessToken())).getRevokedReason()).isEqualTo(IssuedJwt.RevokedReason.LOGOUT);
    }

    @Test
    void shouldAnswerNoContentAndChangeNothingWhenLoggingOutWithAnUnknownSecret() {
        ClientSignInFlow.Tokens tokens = flow.signIn("ext-logout-unknown");

        flow.logout(Pkce.newSecret()).expectStatus().isNoContent().expectBody(Void.class);

        flow.assertAccepted(tokens.accessToken());
    }

    @Test
    void shouldEndTheSessionWhenAStaleSessionListEntryIsRevokedAfterTwoRotations() {
        ClientSignInFlow.Tokens r0 = flow.signIn("ext-stale-list");
        UUID j0 = jtiOf(r0.accessToken());
        ClientSignInFlow.Tokens r2 = flow.rotate(flow.rotate(r0.refreshToken()).refreshToken());
        ClientSignInFlow.Tokens other = flow.signIn("ext-stale-list");

        webTestClient
                .delete()
                .uri("/user/sessions/{jti}", j0)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        flow.assertRejected(r2.accessToken());
        flow.assertRefreshRefused(r2.refreshToken());
        flow.assertAccepted(other.accessToken());
    }

    @Test
    void shouldListAClientSessionOnceWithItsDeadlineWhenItHasRotated() {
        ClientSignInFlow.Tokens r0 = flow.signIn("ext-list");
        ClientSignInFlow.Tokens r2 = flow.rotate(flow.rotate(r0.refreshToken()).refreshToken());
        UUID sid = sidOf(r2.accessToken());

        List<Map<String, Object>> sessions = webTestClient
                .get()
                .uri("/user/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + r2.accessToken())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(sessions).hasSize(1);
        Map<String, Object> entry = Objects.requireNonNull(sessions).getFirst();
        assertThat(entry).containsEntry("client", "BROWSER_EXTENSION").containsEntry("current", true);
        assertThat(entry.get("jti")).isEqualTo(jtiOf(r2.accessToken()).toString());
        assertThat(String.valueOf(entry.get("expiresAt"))).isEqualTo(r2.sessionExpiresAt());
        assertThat(session(sid).getRevokedAt()).isNull();
    }

    @Test
    void shouldBurnTheCodeAndCommitThatWhenTheVerifierIsWrong() {
        ClientSignInFlow.Handoff handoff = flow.handoff("ext-verifier");

        flow.exchange(CLIENT_ID, CALLBACK, handoff.code(), Pkce.newSecret())
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);

        assertThat(handoffRepository
                        .findById(Pkce.hash(handoff.code()))
                        .orElseThrow()
                        .getConsumedAt())
                .isNotNull();
        flow.exchange(CLIENT_ID, CALLBACK, handoff.code(), handoff.verifier())
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseAReplayedCodeWhenItWasAlreadyRedeemed() {
        ClientSignInFlow.Handoff handoff = flow.handoff("ext-replay");
        flow.exchange(CLIENT_ID, CALLBACK, handoff.code(), handoff.verifier())
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        flow.exchange(CLIENT_ID, CALLBACK, handoff.code(), handoff.verifier())
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseAnExpiredCodeWhenItIsRedeemedLate() {
        Account account = accountRepository.save(new Account("Late Larry"));
        String code = Pkce.newSecret();
        String verifier = Pkce.newSecret();
        InstalledClient client = new InstalledClient(
                InstalledClientKind.BROWSER_EXTENSION, CLIENT_ID, CALLBACK, "chrome-extension://" + CLIENT_ID);
        Instant now = Instant.now();
        handoffRepository.save(new ClientSignInHandoff(
                Pkce.hash(code),
                Objects.requireNonNull(account.getId()),
                client,
                ClientSignInFlow.challengeOf(verifier),
                now.plus(Duration.ofDays(7)),
                now,
                now.minusSeconds(1)));

        flow.exchange(CLIENT_ID, CALLBACK, code, verifier)
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    void shouldBurnTheCodeWhenAnotherClientOrCallbackRedeemsIt() {
        ClientSignInFlow.Handoff handoff = flow.handoff("ext-mismatch");

        flow.exchange(CLIENT_ID, CALLBACK + "x", handoff.code(), handoff.verifier())
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        flow.exchange(CLIENT_ID, CALLBACK, handoff.code(), handoff.verifier())
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);

        ClientSignInFlow.Handoff other = flow.handoff("ext-mismatch");
        flow.exchange("abcdefghijklmnopabcdefghijklmnop", CALLBACK, other.code(), other.verifier())
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        flow.exchange(CLIENT_ID, CALLBACK, other.code(), other.verifier())
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    void shouldNeverRedirectToAnUnregisteredCallbackWhenASignInStarts() {
        String challenge = ClientSignInFlow.challengeOf(Pkce.newSecret());
        String otherId = "abcdefghijklmnopabcdefghijklmnop";

        for (URI location : List.of(
                flow.devLoginRedirect(
                        "ext-x", otherId, "https://" + otherId + ".chromiumapp.org/callback", challenge, "s"),
                flow.devLoginRedirect("ext-x", CLIENT_ID, "https://evil.example/callback", challenge, "s"),
                flow.devLoginRedirect("ext-x", CLIENT_ID, CALLBACK + "/", challenge, "s"))) {
            assertThat(location.toString()).endsWith("/auth/error?code=client_not_registered");
        }
        URI federated = webTestClient
                .get()
                .uri(builder -> builder.path("/auth/login")
                        .queryParam("provider", "github")
                        .queryParam("mode", "client")
                        .queryParam("client_id", CLIENT_ID)
                        .queryParam("redirect_uri", "https://evil.example/callback")
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("state", "s")
                        .build())
                .exchange()
                .expectStatus()
                .is3xxRedirection()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation();
        assertThat(Objects.requireNonNull(federated).toString()).endsWith("/auth/error?code=client_not_registered");
    }

    @Test
    void shouldRefuseTheClientEndpointsWhenTheRequestCarriesTheBrowserSessionCookie() {
        ClientSignInFlow.Tokens tokens = flow.signIn("ext-cookie");
        String webCookie = webLogin("ext-cookie");

        webTestClient
                .post()
                .uri("/auth/client/refresh")
                .header(HttpHeaders.COOKIE, cookieName + "=" + webCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", tokens.refreshToken()))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(403);

        String csrf = csrfToken();
        webTestClient
                .post()
                .uri("/auth/client/refresh")
                .header(HttpHeaders.COOKIE, cookieName + "=" + webCookie + "; __Host-XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", tokens.refreshToken()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);

        flow.rotate(tokens.refreshToken());
    }

    /**
     * A client's auth window shares the browser's cookies, so it can carry a web session this instance has
     * since revoked. The sign-in must neither be refused for it nor touch it, and the stale session must
     * stay refused everywhere it would authenticate.
     */
    @Test
    void shouldSignTheClientInWithoutTouchingTheCookieWhenTheBrowserHoldsARevokedWebSession() {
        String staleCookie = webLogin("web-stale");
        Long staleAccountId = ClientSignInFlow.accountId(staleCookie);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> issuedJwtRepository.revokeAllForAccount(
                        staleAccountId, Instant.now(), IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE));
        String verifier = Pkce.newSecret() + "-verifier";

        URI location = webTestClient
                .get()
                .uri(builder -> builder.path("/auth/dev-login/client")
                        .queryParam("username", "ext-fresh")
                        .queryParam("client_id", CLIENT_ID)
                        .queryParam("redirect_uri", CALLBACK)
                        .queryParam("code_challenge", ClientSignInFlow.challengeOf(verifier))
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("state", "s-stale")
                        .build())
                .header(HttpHeaders.COOKIE, cookieName + "=" + staleCookie)
                .exchange()
                .expectStatus()
                .is3xxRedirection()
                .expectCookie()
                .doesNotExist(cookieName)
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation();

        assertThat(Objects.requireNonNull(location).toString()).startsWith(CALLBACK + "?code=");
        Map<String, String> query =
                UriComponentsBuilder.fromUri(location).build().getQueryParams().toSingleValueMap();
        assertThat(query).containsEntry("state", "s-stale");
        ClientSignInFlow.Tokens tokens = ClientSignInFlow.tokens(
                flow.exchange(CLIENT_ID, CALLBACK, Objects.requireNonNull(query.get("code")), verifier));
        flow.assertAccepted(tokens.accessToken());
        assertThat(ClientSignInFlow.accountId(tokens.accessToken())).isNotEqualTo(staleAccountId);

        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + staleCookie)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        String csrf = csrfToken();
        webTestClient
                .post()
                .uri("/auth/refresh")
                .header(HttpHeaders.COOKIE, cookieName + "=" + staleCookie + "; __Host-XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectCookie()
                .doesNotExist(cookieName)
                .expectBody(Void.class);
    }

    @Test
    void shouldRefuseTheCookieRefreshWhenTheBearerBelongsToAClientSession() {
        ClientSignInFlow.Tokens tokens = flow.signIn("ext-web-refresh");

        webTestClient
                .post()
                .uri("/auth/refresh")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectCookie()
                .doesNotExist(cookieName)
                .expectBody(Void.class);

        flow.assertAccepted(tokens.accessToken());
    }

    @Test
    void shouldEndTheWholeSessionWhenLoggingOutWithAClientAccessToken() {
        ClientSignInFlow.Tokens tokens = flow.signIn("ext-bearer-logout");

        webTestClient
                .post()
                .uri("/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        flow.assertRejected(tokens.accessToken());
        flow.assertRefreshRefused(tokens.refreshToken());
    }

    @Test
    void shouldRefuseRefreshAndExchangeWhenTheAccountIsNoLongerActive() {
        ClientSignInFlow.Tokens tokens = flow.signIn("ext-suspended");
        ClientSignInFlow.Handoff pending = flow.handoff("ext-suspended");
        UUID sid = sidOf(tokens.accessToken());
        Account account =
                accountRepository.findById(session(sid).getAccountId()).orElseThrow();
        account.setStatus(Account.Status.SUSPENDED);
        accountRepository.save(account);

        flow.assertRefreshRefused(tokens.refreshToken());
        flow.exchange(CLIENT_ID, CALLBACK, pending.code(), pending.verifier())
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        URI refused = flow.devLoginRedirect(
                "ext-suspended", CLIENT_ID, CALLBACK, ClientSignInFlow.challengeOf(Pkce.newSecret()), "s9");
        assertThat(refused.toString()).isEqualTo(CALLBACK + "?error=account_inactive&state=s9");
    }

    @Test
    void shouldEndAtTheCallbackWithInvalidRequestWhenThePkceMethodIsNotS256() {
        URI location = webTestClient
                .get()
                .uri(builder -> builder.path("/auth/dev-login/client")
                        .queryParam("username", "ext-plain")
                        .queryParam("client_id", CLIENT_ID)
                        .queryParam("redirect_uri", CALLBACK)
                        .queryParam("code_challenge", ClientSignInFlow.challengeOf(Pkce.newSecret()))
                        .queryParam("code_challenge_method", "plain")
                        .queryParam("state", "s2")
                        .build())
                .exchange()
                .expectStatus()
                .is3xxRedirection()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation();

        assertThat(Objects.requireNonNull(location).toString()).isEqualTo(CALLBACK + "?error=invalid_request&state=s2");
    }

    @Test
    void shouldRejectMalformedBodiesBeforeTouchingTheDatabase() {
        webTestClient
                .post()
                .uri("/auth/client/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", "short"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        webTestClient
                .post()
                .uri("/auth/client/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("clientId", CLIENT_ID, "redirectUri", CALLBACK, "code", Pkce.newSecret()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    void shouldAnswerEveryRefusalWithAProblemDetailAndNeverWithTokensWhenTheClientEndpointsRefuse() {
        ClientSignInFlow.Handoff handoff = flow.handoff("ext-problem");
        List<WebTestClient.ResponseSpec> refusals = List.of(
                flow.refresh(Pkce.newSecret()),
                flow.exchange(CLIENT_ID, CALLBACK, handoff.code(), Pkce.newSecret()),
                webTestClient
                        .post()
                        .uri("/auth/client/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("refreshToken", "short"))
                        .exchange());
        List<Integer> statuses = List.of(401, 400, 400);

        for (int i = 0; i < refusals.size(); i++) {
            refusals.get(i)
                    .expectStatus()
                    .isEqualTo(statuses.get(i))
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo(statuses.get(i))
                    .jsonPath("$.accessToken")
                    .doesNotExist()
                    .jsonPath("$.refreshToken")
                    .doesNotExist();
        }
    }

    @Test
    void shouldReportWhetherAClientIsRegisteredWhenAskedForTheConfiguration() {
        webTestClient
                .get()
                .uri("/auth/client/configuration?clientId=" + CLIENT_ID)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.registered")
                .isEqualTo(true);
        webTestClient
                .get()
                .uri("/auth/client/configuration?clientId=abcdefghijklmnopabcdefghijklmnop")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.registered")
                .isEqualTo(false);
    }

    @Test
    void shouldAllowTheRegisteredExtensionOriginAndRefuseAnyOtherWhenTheBrowserChecksCors() {
        String allowed = "chrome-extension://" + CLIENT_ID;
        webTestClient
                .options()
                .uri("/auth/client/refresh")
                .header(HttpHeaders.ORIGIN, allowed)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowed)
                .expectBody(Void.class);
        ClientSignInFlow.Tokens tokens = flow.signIn("ext-cors");
        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.ORIGIN, allowed)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowed)
                .expectBody(Void.class);

        String other = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";
        webTestClient
                .options()
                .uri("/auth/client/refresh")
                .header(HttpHeaders.ORIGIN, other)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .expectBody(Void.class);
        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.ORIGIN, other)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldKeepEveryTokenRowOfALiveSessionWhenExpiredTokensArePruned() {
        ClientSignInFlow.Tokens r0 = flow.signIn("ext-retention");
        UUID j0 = jtiOf(r0.accessToken());
        ClientSignInFlow.Tokens r1 = flow.rotate(r0.refreshToken());
        jdbc.update(
                "UPDATE issued_jwt SET expires_at = ? WHERE jti = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                j0);

        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> issuedJwtRepository.deleteExpiredBefore(Instant.now()));

        assertThat(issuedJwtRepository.findById(j0)).isPresent();
        flow.assertRefreshRefused(r0.refreshToken());
        flow.assertRejected(r1.accessToken());
    }

    @Test
    void shouldDeleteEndedSessionsWithTheirWholeFamilyAndKeepLiveOnesWhenTheCleanupRuns() {
        ClientSignInFlow.Tokens ended = flow.signIn("ext-cleanup");
        ClientSignInFlow.Tokens live = flow.signIn("ext-cleanup");
        UUID endedSid = sidOf(ended.accessToken());
        flow.logout(ended.refreshToken()).expectStatus().isNoContent().expectBody(Void.class);
        jdbc.update(
                "UPDATE client_session SET revoked_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(2))),
                endedSid);

        pruner.prune();

        assertThat(sessionRepository.findById(endedSid)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issued_jwt WHERE session_id = ?", Long.class, endedSid))
                .isZero();
        flow.assertAccepted(live.accessToken());
        flow.rotate(live.refreshToken());
    }

    @Test
    void shouldEraseSessionsAndHandoffsWhenTheAccountIsPurged() {
        ClientSignInFlow.Tokens tokens = flow.signIn("ext-purge");
        flow.handoff("ext-purge");
        Long accountId = session(sidOf(tokens.accessToken())).getAccountId();

        accountPurger.purge(accountId);

        for (String table : List.of("client_session", "client_sign_in_handoff", "issued_jwt")) {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM " + table + " WHERE account_id = ?", Long.class, accountId))
                    .as(table)
                    .isZero();
        }
        flow.assertRejected(tokens.accessToken());
    }

    private String webLogin(String username) {
        ResponseCookie cookie = webTestClient
                .post()
                .uri("/auth/dev-login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "admin", false))
                .exchange()
                .expectStatus()
                .isNoContent()
                .returnResult(Void.class)
                .getResponseCookies()
                .getFirst(cookieName);
        return Objects.requireNonNull(cookie).getValue();
    }

    private String csrfToken() {
        ResponseCookie cookie = webTestClient
                .get()
                .uri("/identity-providers")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class)
                .getResponseCookies()
                .getFirst("__Host-XSRF-TOKEN");
        return Objects.requireNonNull(cookie).getValue();
    }
}

package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.AuthSessionService;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtCleanupJob;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The native session lifecycle over real HTTP, the real decoder and PostgreSQL: the handoff, rotation,
 * strict refresh reuse detection, and every existing revocation path ending the session.
 * Sign-in runs through the dev handoff, which ends in the same exchange a federated sign-in ends in.
 */
@TestPropertySource(properties = "hephaestus.auth.dev-login-enabled=true")
class NativeSessionIntegrationTest extends RealAuthIntegrationTest {

    private static final String REDIRECT = "build.hephaestus.app.dev:/auth/callback";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private NativeSessionService nativeSessionService;

    @Autowired
    private NativeSessionRepository nativeSessionRepository;

    @Autowired
    private IssuedJwtRepository issuedJwtRepository;

    @Autowired
    private IssuedJwtCleanupJob issuedJwtCleanupJob;

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthSessionService authSessionService;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    private record Tokens(String accessToken, String refreshToken, UUID nativeSessionId) {}

    @Value("${hephaestus.webapp.url}")
    private String webappUrl;

    @Test
    void shouldExposeTheConfiguredWebAddressWhenDiscoveringWithoutSignIn() {
        webTestClient
                .get()
                .uri("/auth/native/configuration")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.minimumAppVersion")
                .isEqualTo("")
                .jsonPath("$.webappUrl")
                .isEqualTo(webappUrl);
    }

    @Test
    void shouldSignInThroughTheHandoffWhenTheVerifierMatches() {
        Tokens tokens = signIn("native-nora");

        getUser(tokens.accessToken()).expectStatus().isOk().expectBody(Void.class);
        NativeSession session = sessionFor(tokens);
        assertThat(session.getRevokedAt()).isNull();
        assertThat(tokens.nativeSessionId()).isEqualTo(session.getId());
        assertThat(issuedJwtRepository.findActive(session.getCurrentJti(), Instant.now()))
                .isPresent();
        assertThat(JsonPath.<String>read(claims(tokens.accessToken()), "$.sid"))
                .isEqualTo(session.getId().toString());
    }

    @Test
    void shouldRefuseTheHandoffWhenTheVerifierDoesNotMatchAndBurnTheCode() {
        String verifier = verifier();
        String code = handoffCode("native-ned", challenge(verifier), "s1");

        exchange(code, verifier()).expectStatus().isBadRequest().expectBody(Void.class);
        exchange(code, verifier).expectStatus().isBadRequest().expectBody(Void.class);
    }

    @Test
    void shouldRedeemAHandoffOnlyOnceWhenTwoRequestsRace() throws Exception {
        String verifier = verifier();
        String code = handoffCode("native-rita", challenge(verifier), "s1");

        List<Optional<NativeSessionService.NativeTokens>> results =
                race(() -> nativeSessionService.exchange(code, verifier, null));

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
    }

    @Test
    void shouldRefuseAHandoffWhenItHasExpired() {
        String verifier = verifier();
        String code = handoffCode("native-eve", challenge(verifier), "s1");
        jdbcTemplate.update("UPDATE native_sign_in_handoff SET expires_at = now() - interval '1 second'");

        exchange(code, verifier).expectStatus().isBadRequest().expectBody(Void.class);
    }

    @Test
    void shouldRotateBothSecretsAndRevokeTheOldAccessTokenWhenRefreshing() {
        Tokens first = signIn("native-rosa");

        Tokens second = refresh(first.refreshToken()).orElseThrow();

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.nativeSessionId()).isEqualTo(first.nativeSessionId());
        getUser(first.accessToken()).expectStatus().isUnauthorized().expectBody(Void.class);
        getUser(second.accessToken()).expectStatus().isOk().expectBody(Void.class);
    }

    @Test
    void shouldStillRefreshWhenTheAccessTokenExpiredAndTheCleanupRan() {
        Tokens tokens = signIn("native-idle");
        NativeSession session = sessionFor(tokens);
        jdbcTemplate.update(
                "UPDATE issued_jwt SET expires_at = now() - interval '2 days' WHERE jti = ?", session.getCurrentJti());

        issuedJwtCleanupJob.cleanupExpired();

        getUser(tokens.accessToken()).expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(issuedJwtRepository.findById(session.getCurrentJti())).isPresent();
        assertThat(refresh(tokens.refreshToken())).isPresent();
    }

    @Test
    void shouldEndTheWholeSessionWhenAnyReplacedSecretIsReused() {
        Tokens first = signIn("native-thief");
        Tokens second = refresh(first.refreshToken()).orElseThrow();
        Tokens third = refresh(second.refreshToken()).orElseThrow();
        Tokens fourth = refresh(third.refreshToken()).orElseThrow();

        assertThat(refresh(first.refreshToken())).isEmpty();

        getUser(second.accessToken()).expectStatus().isUnauthorized().expectBody(Void.class);
        getUser(fourth.accessToken()).expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(refresh(fourth.refreshToken())).isEmpty();
        assertThat(nativeSessionRepository.findById(sessionFor(second).getId()))
                .get()
                .extracting(NativeSession::getRevokedReason)
                .isEqualTo(NativeSession.RevokedReason.REFRESH_REUSE);
    }

    @Test
    void shouldEndTheSessionWhenConcurrentRefreshesReuseOneSecret() throws Exception {
        Tokens tokens = signIn("native-twice");

        List<Optional<NativeSessionService.NativeTokens>> results =
                race(() -> nativeSessionService.refresh(tokens.refreshToken(), null));

        List<String> live = results.stream()
                .flatMap(Optional::stream)
                .map(NativeSessionService.NativeTokens::accessToken)
                .filter(token -> isLive(token))
                .toList();
        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(live).isEmpty();
    }

    @Test
    void shouldEndTheSessionWhenTheAccessTokenIsLoggedOut() {
        Tokens tokens = signIn("native-bye");

        webTestClient
                .post()
                .uri("/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(refresh(tokens.refreshToken())).isEmpty();
    }

    @Test
    void shouldEndTheSessionWhenItIsSignedOutWithAReplacedSecret() {
        Tokens first = signIn("native-queued");
        Tokens second = refresh(first.refreshToken()).orElseThrow();

        Tokens third = refresh(second.refreshToken()).orElseThrow();
        Tokens fourth = refresh(third.refreshToken()).orElseThrow();
        nativeLogout(first.refreshToken());

        getUser(fourth.accessToken()).expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(refresh(second.refreshToken())).isEmpty();
    }

    @Test
    void shouldAnswerSignOutAlikeWhenTheSecretIsUnknown() {
        nativeLogout("unknown-secret");
    }

    @Test
    void shouldListAnIdleNativeSessionAndEndItWhenRevokedFromTheList() {
        Tokens tokens = signIn("native-listed");
        NativeSession session = sessionFor(tokens);
        jdbcTemplate.update(
                "UPDATE issued_jwt SET expires_at = now() - interval '1 hour' WHERE jti = ?", session.getCurrentJti());
        String web = signInOnTheWeb("native-listed");

        webTestClient
                .get()
                .uri("/user/sessions")
                .header(HttpHeaders.COOKIE, cookieName + "=" + web)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.jti == '" + session.getCurrentJti() + "')].nativeApp")
                .isEqualTo(List.of(true));

        webTestClient
                .delete()
                .uri("/user/sessions/{jti}", session.getCurrentJti())
                .header(HttpHeaders.COOKIE, cookieName + "=" + web)
                .header("X-XSRF-TOKEN", "t")
                .cookie("__Host-XSRF-TOKEN", "t")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(refresh(tokens.refreshToken())).isEmpty();
    }

    @Test
    void shouldEndTheSessionWhenTheListedTokenWasRotatedAwayBeforeTheRevoke() {
        Tokens first = signIn("native-moved");
        UUID listedJti = sessionFor(first).getCurrentJti();
        Tokens second = refresh(first.refreshToken()).orElseThrow();
        Tokens third = refresh(second.refreshToken()).orElseThrow();
        Long accountId = sessionFor(third).getAccountId();
        jdbcTemplate.update("DELETE FROM issued_jwt WHERE jti = ?", listedJti);
        assertThat(issuedJwtRepository.findById(listedJti)).isEmpty();

        authSessionService.revokeSession(accountId, listedJti);

        getUser(third.accessToken()).expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(refresh(second.refreshToken())).isEmpty();
    }

    @Test
    void shouldEndTheSessionWhenAllSessionsAreRevokedOrTheAccountIsDeleted() {
        Tokens revoked = signIn("native-everywhere");
        Long revokedAccount = sessionFor(revoked).getAccountId();
        accountService.adminRevokeAllSessions(revokedAccount, revokedAccount);
        assertThat(refresh(revoked.refreshToken())).isEmpty();

        Tokens deleted = signIn("native-deleted");
        accountService.softDelete(sessionFor(deleted).getAccountId());
        assertThat(refresh(deleted.refreshToken())).isEmpty();
    }

    @Test
    void shouldEndTheSessionAtItsAbsoluteDeadline() {
        Tokens tokens = signIn("native-deadline");
        jdbcTemplate.update(
                "UPDATE native_session SET session_expires_at = now() - interval '1 second' WHERE id = ?",
                sessionFor(tokens).getId());

        assertThat(refresh(tokens.refreshToken())).isEmpty();
    }

    @Test
    void shouldRefuseTheCookieRefreshForANativeToken() {
        Tokens tokens = signIn("native-cookie");

        webTestClient
                .post()
                .uri("/auth/refresh")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        getUser(tokens.accessToken()).expectStatus().isOk().expectBody(Void.class);
    }

    @Test
    void shouldRefuseANativeEndpointWhenTheRequestCarriesTheSessionCookie() {
        Tokens tokens = signIn("native-mixed");
        String web = signInOnTheWeb("native-mixed");

        webTestClient
                .post()
                .uri("/auth/native/refresh")
                .header(HttpHeaders.COOKIE, cookieName + "=" + web)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", tokens.refreshToken()))
                .exchange()
                .expectStatus()
                .is4xxClientError()
                .expectBody(Void.class);
        assertThat(refresh(tokens.refreshToken())).isPresent();
    }

    @Test
    void shouldRefuseANativeSignInWhenTheRedirectIsNotAllowlisted() {
        webTestClient
                .get()
                .uri(uri -> uri.path("/auth/dev-login/native")
                        .queryParam("username", "native-evil")
                        .queryParam("code_challenge", challenge(verifier()))
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("state", "s1")
                        .queryParam("redirect_uri", "https://evil.example/callback")
                        .build())
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    @Test
    void shouldLeaveNoNativeAuthorityWhenRefreshRacesAccountWideRevocation() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            Tokens tokens = signIn("native-revoke-race-" + attempt);
            Long accountId = sessionFor(tokens).getAccountId();
            List<Optional<NativeSessionService.NativeTokens>> results =
                    race(() -> nativeSessionService.refresh(tokens.refreshToken(), null), () -> {
                        accountService.adminRevokeAllSessions(accountId, accountId);
                        return Optional.empty();
                    });
            for (var result : results) {
                if (result.isPresent()) {
                    var returned = result.orElseThrow();
                    getUser(returned.accessToken())
                            .expectStatus()
                            .isUnauthorized()
                            .expectBody(Void.class);
                    assertThat(refresh(returned.refreshToken())).isEmpty();
                }
            }
            assertThat(refresh(tokens.refreshToken())).isEmpty();
        }
    }

    @Test
    void shouldLeaveNoNativeAuthorityWhenRefreshRacesAStaleSessionListRevoke() throws Exception {
        Tokens tokens = signIn("native-list-race");
        NativeSession session = sessionFor(tokens);
        UUID listedJti = session.getCurrentJti();
        Tokens rotated = refresh(tokens.refreshToken()).orElseThrow();
        race(() -> nativeSessionService.refresh(rotated.refreshToken(), null), () -> {
            authSessionService.revokeSession(session.getAccountId(), listedJti);
            return Optional.empty();
        });
        assertThat(nativeSessionRepository
                        .findById(session.getId())
                        .orElseThrow()
                        .getRevokedAt())
                .isNotNull();
        assertThat(issuedJwtRepository.findActiveByAccountId(session.getAccountId(), Instant.now()))
                .isEmpty();
    }

    @Test
    void shouldPreserveOnlyThePresentingNativeSessionWhenItRotatedBeforeSignOutOthers() {
        Tokens keep = signIn("native-keep");
        Tokens other = signIn("native-keep");
        NativeSession old = sessionFor(keep);
        Tokens rotated = refresh(keep.refreshToken()).orElseThrow();
        authSessionService.revokeAllExcept(old.getAccountId(), old.getCurrentJti());
        getUser(rotated.accessToken()).expectStatus().isOk().expectBody(Void.class);
        assertThat(refresh(other.refreshToken())).isEmpty();
        assertThat(refresh(rotated.refreshToken())).isPresent();
    }

    private Tokens signIn(String username) {
        String verifier = verifier();
        String code = handoffCode(username, challenge(verifier), "state-" + username);
        byte[] body = exchange(code, verifier)
                .expectStatus()
                .isOk()
                .expectHeader()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody()
                .returnResult()
                .getResponseBody();
        return tokens(body);
    }

    private String handoffCode(String username, String challenge, String state) {
        URI location = webTestClient
                .get()
                .uri(uri -> uri.path("/auth/dev-login/native")
                        .queryParam("username", username)
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("state", state)
                        .queryParam("redirect_uri", REDIRECT)
                        .build())
                .exchange()
                .expectStatus()
                .isFound()
                .expectCookie()
                .doesNotExist(cookieName)
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation();
        assertThat(location).isNotNull();
        assertThat(location.toString()).startsWith(REDIRECT + "?");
        var params = UriComponentsBuilder.fromUri(location).build().getQueryParams();
        assertThat(params.getFirst("state")).isEqualTo(state);
        String code = params.getFirst("code");
        assertThat(code).isNotBlank();
        return java.util.Objects.requireNonNull(code);
    }

    private WebTestClient.ResponseSpec exchange(String code, String verifier) {
        return webTestClient
                .post()
                .uri("/auth/native/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", code, "codeVerifier", verifier))
                .exchange();
    }

    private Optional<Tokens> refresh(String refreshToken) {
        var result = webTestClient
                .post()
                .uri("/auth/native/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectBody()
                .returnResult();
        if (result.getStatus().value() == 401) {
            return Optional.empty();
        }
        assertThat(result.getStatus().value()).isEqualTo(200);
        return Optional.of(tokens(result.getResponseBody()));
    }

    private void nativeLogout(String refreshToken) {
        webTestClient
                .post()
                .uri("/auth/native/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
    }

    private String signInOnTheWeb(String username) {
        var cookie = webTestClient
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
        assertThat(cookie).isNotNull();
        return java.util.Objects.requireNonNull(cookie).getValue();
    }

    private WebTestClient.ResponseSpec getUser(String accessToken) {
        return webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private boolean isLive(String accessToken) {
        String jti = JsonPath.read(claims(accessToken), "$.jti");
        return issuedJwtRepository
                .findActive(UUID.fromString(jti), Instant.now())
                .isPresent();
    }

    private NativeSession sessionFor(Tokens tokens) {
        String sid = JsonPath.read(claims(tokens.accessToken()), "$.sid");
        return nativeSessionRepository.findById(UUID.fromString(sid)).orElseThrow();
    }

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private static Tokens tokens(byte @org.jspecify.annotations.Nullable [] body) {
        assertThat(body).isNotNull();
        String json = new String(java.util.Objects.requireNonNull(body), StandardCharsets.UTF_8);
        return new Tokens(
                JsonPath.read(json, "$.accessToken"),
                JsonPath.read(json, "$.refreshToken"),
                UUID.fromString(JsonPath.read(json, "$.nativeSessionId")));
    }

    private static <T> List<T> race(Callable<T> task) throws Exception {
        return race(task, task);
    }

    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(15, java.util.concurrent.TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private static String verifier() {
        return Pkce.newSecret();
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Drives the installed-client sign-in over real HTTP, as the extension does. */
final class ClientSignInFlow {

    static final String CLIENT_ID = "ijkajblcbajjpjbknfgdiiiljipafiko";
    static final String CALLBACK = "https://" + CLIENT_ID + ".chromiumapp.org/callback";

    record Tokens(String accessToken, String refreshToken, String sessionExpiresAt) {}

    /** A started sign-in: the handoff code the callback received and the verifier that redeems it. */
    record Handoff(String code, String verifier) {}

    private final WebTestClient client;

    ClientSignInFlow(WebTestClient client) {
        this.client = client;
    }

    /**
     * A token's claims read without the revocation check, for identifying tokens a test has just watched
     * being revoked. Authentication itself is always asserted over HTTP.
     */
    static com.nimbusds.jwt.JWTClaimsSet claims(String token) {
        try {
            return com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet();
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    static java.util.UUID sid(String token) {
        try {
            return java.util.UUID.fromString(
                    Objects.requireNonNull(claims(token).getStringClaim("sid")));
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    static java.util.UUID jti(String token) {
        return java.util.UUID.fromString(Objects.requireNonNull(claims(token).getJWTID()));
    }

    static Long accountId(String token) {
        return Long.parseLong(Objects.requireNonNull(claims(token).getSubject()));
    }

    static String challengeOf(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The redirect {@code GET /auth/dev-login/client} answers with, for any parameters. */
    URI devLoginRedirect(String username, String clientId, String redirectUri, String challenge, String state) {
        URI location = client.get()
                .uri(builder -> builder.path("/auth/dev-login/client")
                        .queryParam("username", username)
                        .queryParam("client_id", clientId)
                        .queryParam("redirect_uri", redirectUri)
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("state", state)
                        .build())
                .exchange()
                .expectStatus()
                .is3xxRedirection()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation();
        return Objects.requireNonNull(location);
    }

    Handoff handoff(String username) {
        String verifier = Pkce.newSecret() + "-verifier";
        URI location = devLoginRedirect(username, CLIENT_ID, CALLBACK, challengeOf(verifier), "state-1");
        assertThat(location.toString()).startsWith(CALLBACK + "?code=");
        Map<String, String> query =
                UriComponentsBuilder.fromUri(location).build().getQueryParams().toSingleValueMap();
        assertThat(query).containsEntry("state", "state-1");
        return new Handoff(Objects.requireNonNull(query.get("code")), verifier);
    }

    WebTestClient.ResponseSpec exchange(String clientId, String redirectUri, String code, String verifier) {
        return client.post()
                .uri("/auth/client/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "clientId", clientId, "redirectUri", redirectUri, "code", code, "codeVerifier", verifier))
                .exchange();
    }

    Tokens signIn(String username) {
        Handoff handoff = handoff(username);
        return tokens(exchange(CLIENT_ID, CALLBACK, handoff.code(), handoff.verifier()));
    }

    WebTestClient.ResponseSpec refresh(String refreshToken) {
        return client.post()
                .uri("/auth/client/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange();
    }

    Tokens rotate(String refreshToken) {
        return tokens(refresh(refreshToken));
    }

    void assertRefreshRefused(String refreshToken) {
        refresh(refreshToken).expectStatus().isUnauthorized().expectBody(Void.class);
    }

    WebTestClient.ResponseSpec logout(String refreshToken) {
        return client.post()
                .uri("/auth/client/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange();
    }

    WebTestClient.ResponseSpec getUser(String accessToken) {
        return client.get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    void assertAccepted(String accessToken) {
        getUser(accessToken).expectStatus().isOk().expectBody(Void.class);
    }

    void assertRejected(String accessToken) {
        getUser(accessToken).expectStatus().isUnauthorized().expectBody(Void.class);
    }

    static Tokens tokens(WebTestClient.ResponseSpec response) {
        Map<?, ?> body = response.expectStatus()
                .isOk()
                .expectHeader()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        Objects.requireNonNull(body);
        return new Tokens(
                (String) Objects.requireNonNull(body.get("accessToken")),
                (String) Objects.requireNonNull(body.get("refreshToken")),
                String.valueOf(body.get("sessionExpiresAt")));
    }
}

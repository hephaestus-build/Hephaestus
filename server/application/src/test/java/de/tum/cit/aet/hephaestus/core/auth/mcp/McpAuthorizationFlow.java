package de.tum.cit.aet.hephaestus.core.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.testconfig.StubClientMetadataSource;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Drives an MCP client's OAuth flow over real HTTP, as a client and a signed-in browser would. */
public final class McpAuthorizationFlow {

    public static final String ISSUER = "https://hephaestus.build";
    public static final String CLIENT_ID = "https://client.example/mcp-client.json";
    public static final String LOOPBACK_REDIRECT = "http://127.0.0.1/callback";
    public static final String WEB_REDIRECT = "https://client.example/callback";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Pkce(String verifier, String challenge) {}

    public record Tokens(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            String scope) {}

    public record TokenError(String error) {}

    public record Redirect(String redirectUri) {}

    private final WebTestClient client;
    private final String cookieName;

    public McpAuthorizationFlow(WebTestClient client, String cookieName) {
        this.client = client;
        this.cookieName = cookieName;
        StubClientMetadataSource.serve(CLIENT_ID, """
                {"client_id": "%s", "client_name": "Test client", "token_endpoint_auth_method": "none",
                 "redirect_uris": ["%s", "%s"], "grant_types": ["authorization_code", "refresh_token"]}
                """.formatted(CLIENT_ID, LOOPBACK_REDIRECT, WEB_REDIRECT));
    }

    public static String resource(String slug) {
        return ISSUER + "/api/workspaces/" + slug + "/mcp";
    }

    public static Pkce newPkce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String signIn(String username) {
        var cookie = client.post()
                .uri("/auth/dev-login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"" + username + "\",\"admin\":false}")
                .exchange()
                .expectStatus()
                .isNoContent()
                .returnResult(Void.class)
                .getResponseCookies()
                .getFirst(cookieName);
        return Objects.requireNonNull(cookie).getValue();
    }

    public static long accountId(String jwt) {
        return Long.parseLong(payload(jwt).path("sub").asString());
    }

    /** A JWT's payload, read without verifying it: the server verified it when it issued it. */
    public static JsonNode payload(String jwt) {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.substring(jwt.indexOf('.') + 1, jwt.lastIndexOf('.'))), StandardCharsets.UTF_8);
        return JSON.readTree(payload);
    }

    public Map<String, String> parameters(String slug, Pkce pkce, String redirectUri) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("response_type", "code");
        parameters.put("client_id", CLIENT_ID);
        parameters.put("redirect_uri", redirectUri);
        parameters.put("code_challenge", pkce.challenge());
        parameters.put("code_challenge_method", "S256");
        parameters.put("state", "state-" + UUID.randomUUID());
        parameters.put("resource", resource(slug));
        parameters.put("scope", "practices:read");
        return parameters;
    }

    /** The authorization endpoint's 302 target. */
    public URI authorize(@Nullable String cookie, Map<String, String> parameters) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/mcp/oauth/authorize");
        parameters.forEach(uri::queryParam);
        var request = client.get().uri(uri.encode().build().toUriString());
        if (cookie != null) {
            request = request.header(HttpHeaders.COOKIE, cookieName + "=" + cookie);
        }
        var location = request.exchange()
                .expectStatus()
                .isFound()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation();
        return Objects.requireNonNull(location);
    }

    public static UUID consentRequest(URI location) {
        assertThat(location.toString()).startsWith(ISSUER + "/mcp/authorize?request=");
        return UUID.fromString(query(location).get("request"));
    }

    public WebTestClient.ResponseSpec approval(String cookie, UUID requestId) {
        String csrf = TestAuthUtils.fetchCsrfToken(client);
        return client.put()
                .uri("/mcp/oauth/requests/{id}/approval", requestId)
                .header(HttpHeaders.COOKIE, cookieName + "=" + cookie, "__Host-XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .exchange();
    }

    public URI approve(String cookie, UUID requestId) {
        Redirect redirect = approval(cookie, requestId)
                .expectStatus()
                .isOk()
                .expectBody(Redirect.class)
                .returnResult()
                .getResponseBody();
        return URI.create(Objects.requireNonNull(redirect).redirectUri());
    }

    public URI refuse(String cookie, UUID requestId) {
        String csrf = TestAuthUtils.fetchCsrfToken(client);
        Redirect redirect = client.delete()
                .uri("/mcp/oauth/requests/{id}", requestId)
                .header(HttpHeaders.COOKIE, cookieName + "=" + cookie, "__Host-XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Redirect.class)
                .returnResult()
                .getResponseBody();
        return URI.create(Objects.requireNonNull(redirect).redirectUri());
    }

    public WebTestClient.ResponseSpec token(Map<String, String> form) {
        var body = new LinkedMultiValueMap<String, String>();
        form.forEach(body::add);
        return client.post()
                .uri("/mcp/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(body))
                .exchange();
    }

    public Map<String, String> codeForm(String code, Pkce pkce, String slug, String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", CLIENT_ID);
        form.put("code_verifier", pkce.verifier());
        form.put("resource", resource(slug));
        return form;
    }

    public Map<String, String> refreshForm(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", CLIENT_ID);
        return form;
    }

    public Tokens tokens(WebTestClient.ResponseSpec response) {
        return Objects.requireNonNull(response.expectStatus()
                .isOk()
                .expectHeader()
                .cacheControl(CacheControl.noStore())
                .expectBody(Tokens.class)
                .returnResult()
                .getResponseBody());
    }

    public String tokenError(WebTestClient.ResponseSpec response) {
        return Objects.requireNonNull(response.expectStatus()
                        .isBadRequest()
                        .expectBody(TokenError.class)
                        .returnResult()
                        .getResponseBody())
                .error();
    }

    /** Signs in through consent and exchanges the code: a client's first session. */
    public Tokens connect(String cookie, String slug) {
        Pkce pkce = newPkce();
        URI consent = authorize(cookie, parameters(slug, pkce, LOOPBACK_REDIRECT));
        URI callback = approve(cookie, consentRequest(consent));
        return tokens(token(codeForm(code(callback), pkce, slug, LOOPBACK_REDIRECT)));
    }

    public WebTestClient.ResponseSpec callTool(String accessToken, String slug, String body) {
        return client.post()
                .uri("/workspaces/{slug}/mcp", slug)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchange();
    }

    /** The authorization code that a callback carries. */
    public static String code(URI callback) {
        return Objects.requireNonNull(query(callback).get("code"), "the callback carries no code");
    }

    private static final Pattern QUERY_SEPARATOR = Pattern.compile("&");

    public static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return values;
        }
        for (String pair : (Iterable<String>) QUERY_SEPARATOR.splitAsStream(raw)::iterator) {
            int split = pair.indexOf('=');
            String key = URLDecoder.decode(split < 0 ? pair : pair.substring(0, split), StandardCharsets.UTF_8);
            String value = split < 0 ? "" : URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }
}

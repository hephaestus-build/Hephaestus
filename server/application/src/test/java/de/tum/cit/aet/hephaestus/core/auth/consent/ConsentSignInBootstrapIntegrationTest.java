package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UriComponentsBuilder;

/** The browser may carry another account's unfinished onboarding into an installed-client sign-in. */
@TestPropertySource(properties = "hephaestus.auth.dev-login-enabled=true")
@Import(ConsentSignInBootstrapIntegrationTest.ConsentGateConfiguration.class)
class ConsentSignInBootstrapIntegrationTest extends RealAuthIntegrationTest {

    private static final String CLIENT_ID = "ijkajblcbajjpjbknfgdiiiljipafiko";
    private static final String CALLBACK = "https://" + CLIENT_ID + ".chromiumapp.org/callback";

    @Autowired
    private WebTestClient client;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    @TestConfiguration(proxyBeanMethods = false)
    static class ConsentGateConfiguration {
        @Bean
        WebMvcConfigurer enableConsentGate(ConsentGateInterceptor interceptor) {
            // The ordinary test profile omits ConsentWebConfiguration; this regression needs the
            // real gate as well as the real cookie authentication used by the browser.
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(interceptor);
                }
            };
        }
    }

    @Test
    void shouldSwitchInstalledClientAccountWhenBrowserAccountHasNotAcceptedNotice() throws Exception {
        String pendingCookie = signIn("pending-browser", null);
        assertProtectedContentBlocked(pendingCookie);

        client.get()
                .uri("/auth/client/configuration?clientId=" + CLIENT_ID)
                .cookie(cookieName, pendingCookie)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.registered")
                .isEqualTo(true);

        String verifier = "consent-regression-verifier-000000000000000000000000";
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        URI callback = Objects.requireNonNull(client.get()
                .uri(builder -> builder.path("/auth/dev-login/client")
                        .queryParam("username", "installed-other-account")
                        .queryParam("client_id", CLIENT_ID)
                        .queryParam("redirect_uri", CALLBACK)
                        .queryParam("code_challenge", challenge)
                        .queryParam("code_challenge_method", "S256")
                        .queryParam("state", "consent-regression-state")
                        .build())
                .cookie(cookieName, pendingCookie)
                .exchange()
                .expectStatus()
                .is3xxRedirection()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation());
        assertThat(callback.getHost()).isEqualTo(CLIENT_ID + ".chromiumapp.org");
        assertThat(callback.getPath()).isEqualTo("/callback");
        var query = UriComponentsBuilder.fromUri(callback).build().getQueryParams();
        String code = Objects.requireNonNull(query.getFirst("code"));
        assertThat(query.getFirst("state")).isEqualTo("consent-regression-state");
        Map<?, ?> tokens = Objects.requireNonNull(client.post()
                .uri("/auth/client/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        Map.of("clientId", CLIENT_ID, "redirectUri", CALLBACK, "code", code, "codeVerifier", verifier))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody());
        String bearer = "Bearer " + Objects.requireNonNull(tokens.get("accessToken"));
        client.get()
                .uri("/user")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.displayName")
                .isEqualTo("installed-other-account");
        client.get()
                .uri("/workspaces")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody(Void.class);
        assertProtectedContentBlocked(pendingCookie);
    }

    @Test
    void shouldSwitchDevelopmentBrowserAccountWithoutAcceptingEitherAccountsNotice() {
        String pendingCookie = signIn("pending-first-account", null);
        String replacement = signIn("pending-second-account", pendingCookie);
        client.get()
                .uri("/user")
                .cookie(cookieName, replacement)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.displayName")
                .isEqualTo("pending-second-account");
        assertProtectedContentBlocked(pendingCookie);
        assertProtectedContentBlocked(replacement);
    }

    private String signIn(String username, @org.jspecify.annotations.Nullable String ambientCookie) {
        var request = client.post().uri("/auth/dev-login").contentType(MediaType.APPLICATION_JSON);
        if (ambientCookie != null) request.cookie(cookieName, ambientCookie);
        var response = request.bodyValue(Map.of("username", username, "admin", false))
                .exchange()
                .expectStatus()
                .isNoContent()
                .returnResult(Void.class);
        return Objects.requireNonNull(response.getResponseCookies().getFirst(cookieName))
                .getValue();
    }

    private void assertProtectedContentBlocked(String cookie) {
        client.get()
                .uri("/workspaces")
                .cookie(cookieName, cookie)
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody(Void.class);
    }
}

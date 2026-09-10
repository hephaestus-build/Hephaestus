package de.tum.cit.aet.hephaestus.core.auth.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;

import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * End-to-end contract of the passwordless dev sign-in with the flag ENABLED. Deliberately uses the
 * REAL resource-server chain + {@code RevocationAwareJwtDecoder} (no {@code TestSecurityConfig} mock),
 * so {@code POST /auth/dev-login} → {@code Set-Cookie} → {@code GET /user} proves the minted cookie is
 * accepted by the live decoder — exactly what {@code TestAuthUtils}/MockMvc cannot prove. The disabled
 * (default) contract is covered by {@link DevLoginDisabledIntegrationTest}.
 */
@TestPropertySource(properties = "hephaestus.auth.dev-login-enabled=true")
class DevLoginIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    @Test
    void devLoginMintsAcceptedCookie_andGetUserReturnsTheDevAccount() {
        // No XSRF token is sent: success here also proves the CSRF carve-out for the pre-auth POST.
        var responseCookie = webTestClient
                .post()
                .uri("/auth/dev-login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\",\"displayName\":\"Alice Dev\",\"admin\":false}")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectCookie()
                .exists(cookieName)
                .returnResult(Void.class)
                .getResponseCookies()
                .getFirst(cookieName);
        org.junit.jupiter.api.Assertions.assertNotNull(responseCookie);
        String cookie = responseCookie.getValue();

        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + cookie)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.displayName")
                .isEqualTo("Alice Dev")
                .jsonPath("$.appRole")
                .isEqualTo("USER");
    }

    @Test
    void adminFlagYieldsAppAdminSession() {
        String cookie = devLogin("{\"username\":\"root\",\"admin\":true}");

        webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + cookie)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.appRole")
                .isEqualTo("APP_ADMIN")
                .jsonPath("$.roles")
                .value((java.util.List<String> value) ->
                        org.hamcrest.MatcherAssert.assertThat(value, hasItem("app_admin")));
    }

    @Test
    void repeatLoginIsIdempotent_sameAccountId() {
        long first = accountIdFrom(devLogin("{\"username\":\"sam\",\"admin\":false}"));
        long second = accountIdFrom(devLogin("{\"username\":\"sam\",\"admin\":false}"));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void discoveryAdvertisesTheDevRowWhenEnabled() {
        webTestClient
                .get()
                .uri("/identity-providers")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.registrationId == 'dev')].providerType")
                .isEqualTo("DEV");
    }

    private String devLogin(String json) {
        var cookie = webTestClient
                .post()
                .uri("/auth/dev-login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus()
                .isNoContent()
                .returnResult(Void.class)
                .getResponseCookies()
                .getFirst(cookieName);
        org.junit.jupiter.api.Assertions.assertNotNull(cookie);
        return cookie.getValue();
    }

    private long accountIdFrom(String cookie) {
        Map<?, ?> body = webTestClient
                .get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookieName + "=" + cookie)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        org.junit.jupiter.api.Assertions.assertNotNull(body);
        Object id = body.get("id");
        org.junit.jupiter.api.Assertions.assertNotNull(id);
        assertThat(id).isInstanceOf(Number.class);
        return ((Number) id).longValue();
    }
}

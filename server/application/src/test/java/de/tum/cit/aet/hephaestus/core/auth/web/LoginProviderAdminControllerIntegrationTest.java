package de.tum.cit.aet.hephaestus.core.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Access-control + happy-path for {@code /admin/login-providers}: only an instance admin
 * ({@code app_admin}) may manage login providers, the create response carries the upstream redirect
 * URI, and the sealed client secret is never returned.
 */
@org.springframework.test.context.TestPropertySource(
        properties = "hephaestus.auth.oidc.allowed-issuers=https://identity.example.com/realms/team")
@Sql(scripts = "/db/auth-event-sequence.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class LoginProviderAdminControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AuthEventRepository authEventRepository;

    @Test
    @WithUser
    void nonAdminCannotListLoginProviders() {
        webTestClient
                .get()
                .uri("/admin/login-providers")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithUser
    void nonAdminCannotMutateLoginProviders() {
        // The app_admin gate must guard the destructive endpoints too, not just the list — it fires
        // before any provider lookup, so the result is 403 regardless of whether "github" exists.
        webTestClient
                .patch()
                .uri("/admin/login-providers/github")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        webTestClient
                .delete()
                .uri("/admin/login-providers/github")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    @WithAdminUser
    void adminCanCreateAndListWithoutLeakingTheSecret() {
        Map<String, Object> body = Map.of(
                "registrationId",
                "gitlab-actest",
                "type",
                "GITLAB",
                "displayName",
                "ACME GitLab",
                "baseUrl",
                "https://gitlab.acme.test",
                "clientId",
                "acme-client-id",
                "clientSecret",
                "super-secret-value",
                "scopes",
                "read_user");

        webTestClient
                .post()
                .uri("/admin/login-providers")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectHeader()
                .value("Location", location -> assertThat(location).endsWith("/admin/login-providers/gitlab-actest"))
                .expectBody()
                .jsonPath("$.registrationId")
                .isEqualTo("gitlab-actest")
                .jsonPath("$.baseUrl")
                .isEqualTo("https://gitlab.acme.test")
                .jsonPath("$.redirectUri")
                .value(uri -> assertThat((String) uri).endsWith("/login/oauth2/code/gitlab-actest"))
                .jsonPath("$.clientSecret")
                .doesNotExist();

        webTestClient
                .get()
                .uri("/admin/login-providers")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.registrationId == 'gitlab-actest')]")
                .exists();
    }

    /**
     * Moving a provider onto another's base URL violates {@code uq_login_provider_type_base_url}. The
     * change is flushed at commit, long after the handler has recorded a SUCCESS in its own
     * {@code REQUIRES_NEW} transaction — so without an explicit flush the trail keeps a row describing
     * a change that never happened.
     */
    @Test
    @WithAdminUser
    void aRefusedUpdateLeavesNoSuccessOnTheTrail() {
        createGitLabProvider("gitlab-taken", "https://gitlab.taken.test")
                .expectStatus()
                .isCreated()
                .expectBody(Void.class);
        createGitLabProvider("gitlab-mover", "https://gitlab.mover.test")
                .expectStatus()
                .isCreated()
                .expectBody(Void.class);

        webTestClient
                .patch()
                .uri("/admin/login-providers/gitlab-mover")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("baseUrl", "https://gitlab.taken.test"))
                .exchange()
                .expectStatus()
                .is4xxClientError()
                .expectBody(Void.class);

        webTestClient
                .get()
                .uri("/admin/login-providers")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.registrationId == 'gitlab-mover')].baseUrl")
                .isEqualTo("https://gitlab.mover.test");

        assertThat(authEventRepository.findAll())
                .filteredOn(e -> e.getEventType() == AuthEvent.EventType.LOGIN_PROVIDER_UPDATED)
                .isEmpty();
    }

    @Test
    @WithAdminUser
    void shouldConfigureRotateDisableAndRestoreAnApprovedOrganizationalProvider() {
        createGitLabProvider("fallback", "https://gitlab.fallback.test")
                .expectStatus()
                .isCreated();
        webTestClient
                .post()
                .uri("/admin/login-providers")
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of(
                        "registrationId",
                        "organization",
                        "type",
                        "OIDC",
                        "displayName",
                        "Organization",
                        "baseUrl",
                        "https://identity.example.com/realms/team",
                        "clientId",
                        "organization-client",
                        "clientSecret",
                        "original-secret"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.baseUrl")
                .isEqualTo("https://identity.example.com/realms/team")
                .jsonPath("$.clientSecret")
                .doesNotExist();
        webTestClient
                .patch()
                .uri("/admin/login-providers/organization")
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of("clientSecret", "rotated-secret", "enabled", false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(false)
                .jsonPath("$.clientSecret")
                .doesNotExist();
        webTestClient
                .patch()
                .uri("/admin/login-providers/organization")
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of("enabled", true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(true);
        webTestClient
                .patch()
                .uri("/admin/login-providers/organization")
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of("baseUrl", "https://identity.example.com/realms/replacement"))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
        webTestClient
                .delete()
                .uri("/admin/login-providers/organization")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    @WithAdminUser
    void shouldRejectAnOrganizationalIssuerOutsideTheOperatorAllowlist() {
        webTestClient
                .post()
                .uri("/admin/login-providers")
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of(
                        "registrationId",
                        "untrusted",
                        "type",
                        "OIDC",
                        "displayName",
                        "Untrusted",
                        "baseUrl",
                        "https://identity.example.com/realms/unapproved",
                        "clientId",
                        "client",
                        "clientSecret",
                        "secret"))
                .exchange()
                .expectStatus()
                .isEqualTo(422);
    }

    private WebTestClient.ResponseSpec createGitLabProvider(String registrationId, String baseUrl) {
        return webTestClient
                .post()
                .uri("/admin/login-providers")
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "registrationId",
                        registrationId,
                        "type",
                        "GITLAB",
                        "displayName",
                        "Duplicate GitLab",
                        "baseUrl",
                        baseUrl,
                        "clientId",
                        "acme-client-id",
                        "clientSecret",
                        "super-secret-value",
                        "scopes",
                        "read_user"))
                .exchange();
    }
}

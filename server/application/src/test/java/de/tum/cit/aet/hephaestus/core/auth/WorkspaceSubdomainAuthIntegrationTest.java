package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.web.CsrfController;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceService;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceSubdomainAuthIntegrationTest extends RealAuthIntegrationTest {
    private static final String ORIGIN = "https://auth-tenant.hephaestus.build";
    private static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

    @Autowired
    private WebTestClient client;

    @Autowired
    private WorkspaceService workspaces;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private HephaestusJwtIssuer issuer;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldKeepPublicApiPrefixWhenOldWorkspacePathRedirects() {
        var workspace = persistWorkspace("prefix-tenant");
        var account = accounts.save(new Account("Path alias member"));
        jdbc.update(
                "INSERT INTO identity_provider(id,type,server_url,created_at) VALUES(993101,'GITHUB','https://prefix.example',now())");
        jdbc.update("""
                INSERT INTO "user"(id,provider_id,native_id,login,type,avatar_url,html_url)
                VALUES(993102,993101,993102,'prefix-member','USER','','https://prefix.example/prefix-member')
                """);
        jdbc.update(
                "INSERT INTO identity_link(account_id,provider_id,subject,linked_at) VALUES(?,993101,'993102',now())",
                account.getId());
        jdbc.update(
                "INSERT INTO workspace_membership(workspace_id,user_id,role,created_at) VALUES(?,993102,'MEMBER',now())",
                workspace.getId());
        var session = issuer.issue(Objects.requireNonNull(account.getId()), TokenConstraints.session(null, null), null)
                .value();
        workspaces.renameSlug(workspace.getId(), "prefix-current");
        client.get()
                .uri("/workspaces/prefix-tenant?range=1y")
                .cookie(AuthProperties.DEFAULT_COOKIE_NAME, session)
                .header(HttpHeaders.ORIGIN, "https://prefix-tenant.hephaestus.build")
                .exchange()
                .expectStatus()
                .isPermanentRedirect()
                .expectHeader()
                .valueEquals(HttpHeaders.LOCATION, "/api/workspaces/prefix-current?range=1y")
                .expectBody(Void.class);
        client.get()
                .uri("/workspaces/prefix-current")
                .cookie(AuthProperties.DEFAULT_COOKIE_NAME, session)
                .header(HttpHeaders.ORIGIN, "https://prefix-current.hephaestus.build")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.workspaceAddress")
                .isEqualTo("https://prefix-current.hephaestus.build");
    }

    @Test
    void shouldFetchAndReplayRawTokenAcrossSessionLifecycleWhenSpaUsesTenantOrigin() {
        var workspace = persistWorkspace("auth-tenant");
        workspaces.renameSlug(workspace.getId(), "auth-tenant-renamed");
        var anonymous = client.get()
                .uri("/auth/csrf")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN)
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader()
                .valueMatches(HttpHeaders.VARY, ".*Origin.*")
                .expectBody(CsrfController.CsrfTokenDTO.class)
                .returnResult();
        var before = anonymous.getResponseBody();
        assertThat(before).isNotNull();
        var cookie = anonymous.getResponseCookies().getFirst(CSRF_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(before.token()).isEqualTo(cookie.getValue());

        var account = accounts.save(new Account("Tenant session"));
        String session = issuer.issue(
                        Objects.requireNonNull(account.getId()), TokenConstraints.session(null, null), null)
                .value();
        var signedIn = client.get()
                .uri("/auth/csrf")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(AuthProperties.DEFAULT_COOKIE_NAME, session)
                .cookie(CSRF_COOKIE, before.token())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(CsrfController.CsrfTokenDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(signedIn).isNotNull();
        var logout = client.post()
                .uri("/auth/logout")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(AuthProperties.DEFAULT_COOKIE_NAME, session)
                .cookie(CSRF_COOKIE, signedIn.token())
                .header(signedIn.headerName(), signedIn.token())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class)
                .returnResult();
        var cleared = logout.getResponseCookies().getFirst(CSRF_COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getValue()).isEmpty();
        client.get()
                .uri("/auth/csrf")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(CsrfController.CsrfTokenDTO.class)
                .value(after -> assertThat(after.token()).isNotBlank().isNotEqualTo(signedIn.token()));
        client.get()
                .uri("/user")
                .cookie(AuthProperties.DEFAULT_COOKIE_NAME, session)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldRecoverWithOneRefetchWhenAnotherResponseReplacedTheCsrfCookie() {
        var first = fetchCsrf(null);
        var last = fetchCsrf(null);
        assertThat(last.token()).isNotEqualTo(first.token());
        var account = accounts.save(new Account("CSRF recovery"));
        String session = issuer.issue(
                        Objects.requireNonNull(account.getId()), TokenConstraints.session(null, null), null)
                .value();
        client.post()
                .uri("/auth/logout")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(AuthProperties.DEFAULT_COOKIE_NAME, session)
                .cookie(CSRF_COOKIE, last.token())
                .header(first.headerName(), first.token())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        var refreshed = fetchCsrf(last.token());
        assertThat(refreshed.token()).isEqualTo(last.token());
        client.post()
                .uri("/auth/logout")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(AuthProperties.DEFAULT_COOKIE_NAME, session)
                .cookie(CSRF_COOKIE, last.token())
                .header(refreshed.headerName(), refreshed.token())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
    }

    private CsrfController.CsrfTokenDTO fetchCsrf(@Nullable String cookie) {
        var request = client.get().uri("/auth/csrf").header(HttpHeaders.ORIGIN, ORIGIN);
        if (cookie != null) {
            request.cookie(CSRF_COOKIE, cookie);
        }
        return Objects.requireNonNull(request.exchange()
                .expectStatus()
                .isOk()
                .expectBody(CsrfController.CsrfTokenDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private Workspace persistWorkspace(String slug) {
        var workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(slug);
        workspace.setAccountLogin(slug);
        workspace.setAccountType(AccountType.ORG);
        return workspaceRepository.saveAndFlush(workspace);
    }

    @Test
    void shouldAllowCsrfDiscoveryWithoutRevealingWorkspaceExistence() {
        client.get()
                .uri("/auth/csrf")
                .header(HttpHeaders.ORIGIN, "https://unknown.hephaestus.build")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://unknown.hephaestus.build")
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectBody()
                .jsonPath("$.token")
                .isNotEmpty();
    }

    @Test
    void shouldDenyCsrfDiscoveryWhenOriginIsInvalidOrReserved() {
        for (String origin : List.of("https://a--b.hephaestus.build", "https://docs.hephaestus.build")) {
            client.get()
                    .uri("/auth/csrf")
                    .header(HttpHeaders.ORIGIN, origin)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectHeader()
                    .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                    .expectBody(Void.class);
        }
    }
}

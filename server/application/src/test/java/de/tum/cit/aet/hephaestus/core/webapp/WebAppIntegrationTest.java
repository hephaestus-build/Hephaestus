package de.tum.cit.aet.hephaestus.core.webapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.aet.hephaestus.core.settings.spi.PublicUsagePolicy;
import de.tum.cit.aet.hephaestus.testconfig.ApiWebTestClient;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import jakarta.servlet.Filter;
import java.net.URI;
import java.util.HashSet;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The server serves the built web app (ADR 0100): the page with its config record for every client route, the files
 * with their cache rules, the legal pages, the two discovery documents, and the host rules of workspace subdomains.
 * The base class turns workspace subdomains on under {@code hephaestus.build}, and the test profile serves the fixture in
 * {@code src/test/resources/web-app-fixture}, also when a built web app is on the classpath.
 */
class WebAppIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebApplicationContext application;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PublicUsagePolicy publicUsage;

    /** Runs the background read of the switch, as the scheduler does on a server; tests run no scheduler. */
    private void readTheSwitchInTheBackground() {
        Object policy = AopTestUtils.getTargetObject(publicUsage);
        ReflectionTestUtils.invokeMethod(policy, "refreshLastKnownAllowed");
    }

    /** MockMvc runs only the filters it is given: the host rules, then Spring Security. */
    @Autowired
    private FilterRegistrationBean<OncePerRequestFilter> workspaceHostGuardFilter;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(application)
                .addFilter(Objects.requireNonNull(workspaceHostGuardFilter.getFilter()))
                .addFilter(application.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    private static MockHttpServletRequestBuilder onHost(MockHttpServletRequestBuilder request, String host) {
        return request.with(servletRequest -> {
            servletRequest.setServerName(host);
            return servletRequest;
        });
    }

    @Test
    void shouldServeThePageWithItsConfigRecordWhenAClientRouteIsRead() throws Exception {
        for (String path : new String[] {"/", "/index.html", "/w/acme/practices", "/w/acme/profile/jane.doe"}) {
            mvc().perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", startsWith("text/html")))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Content-Security-Policy", containsString("'strict-dynamic'")))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(content()
                            .string(allOf(
                                    containsString("<script type=\"application/json\" id=\"hephaestus-config\">"),
                                    containsString("\"serverUrl\":\"https://hephaestus.build/api\""),
                                    containsString("\"version\":\"0.0.0-development\""),
                                    containsString(
                                            "\"workspaceSubdomains\":{\"enabled\":true,\"baseDomain\":\"hephaestus.build\"}"),
                                    containsString("\"xsrfCookieName\":\"__Host-XSRF-TOKEN\""))));
        }
        mvc().perform(head("/w/acme"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("'strict-dynamic'")));
    }

    private void switchPublicUsage(boolean on) {
        jdbc.update(
                "INSERT INTO instance_settings (id, silent_mode_engaged, version, public_usage_allowed)"
                        + " VALUES (1, true, 0, ?) ON CONFLICT (id) DO UPDATE SET public_usage_allowed = ?",
                on,
                on);
    }

    private void expectPublicUsage(boolean on) throws Exception {
        for (var request : new MockHttpServletRequestBuilder[] {get("/"), get("/w/acme/practices")}) {
            mvc().perform(request)
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\"publicUsage\":" + on + "}")));
        }
    }

    @Test
    void shouldTellThePageWhetherHephaestusInNumbersIsOnFromTheBackgroundRead() throws Exception {
        switchPublicUsage(true);
        readTheSwitchInTheBackground();
        expectPublicUsage(true);

        // A page load never reads the database: the switch shows only after the next background read.
        switchPublicUsage(false);
        expectPublicUsage(true);
        readTheSwitchInTheBackground();
        expectPublicUsage(false);
    }

    @Test
    void shouldGiveEveryPageItsOwnScriptNonceAndAllowOnlyTheScriptsThatCarryIt() throws Exception {
        var nonces = new HashSet<String>();
        for (int read = 0; read < 2; read++) {
            MvcResult result = mvc().perform(get("/w/acme/practices"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(
                                    "Content-Security-Policy-Report-Only", "require-trusted-types-for 'script'"))
                    .andReturn();
            String policy = result.getResponse().getHeader("Content-Security-Policy");
            Matcher scripts = Pattern.compile("script-src 'nonce-([A-Za-z0-9+/=]{24})' 'strict-dynamic';")
                    .matcher(Objects.requireNonNull(policy));
            assertThat(scripts.find()).as(policy).isTrue();
            String nonce = scripts.group(1);
            String page = result.getResponse().getContentAsString();
            // The module script and the meta tag that Vite's preloads read both carry this response's nonce.
            assertThat(page)
                    .contains("src=\"/assets/index-abc123.js\" nonce=\"" + nonce + "\"")
                    .contains("<meta property=\"csp-nonce\" nonce=\"" + nonce + "\"")
                    .doesNotContain(WebAppIndex.NONCE_PLACEHOLDER);
            assertThat(policy).doesNotContain("script-src 'self'");
            nonces.add(nonce);
        }
        assertThat(nonces).as("a nonce per response").hasSize(2);
    }

    @Test
    void shouldKeepTheSelfScriptPolicyAndNoTrustedTypesReportOnAFileThatIsNotThePage() throws Exception {
        mvc().perform(get("/manifest.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self';")))
                .andExpect(header().doesNotExist("Content-Security-Policy-Report-Only"));
    }

    @Test
    void shouldAnswerAnAnonymousProbeWithItsStatusOnlyOnAWorkspaceHost() {
        // Every path reaches the server, so the probes answer on every host. A caller without authority sees no
        // component and no detail.
        for (String path : new String[] {"/livez", "/readyz"}) {
            webTestClient
                    .get()
                    .uri(ApiWebTestClient.root(application, path))
                    .header(HttpHeaders.HOST, "acme.hephaestus.build")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .json("{\"status\":\"UP\"}", JsonCompareMode.STRICT);
        }
    }

    @Test
    void shouldServeAPrecompressedAssetForAYearWhenTheBrowserAcceptsBrotli() throws Exception {
        mvc().perform(get("/assets/index-abc123.js").header("Accept-Encoding", "br, gzip"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Encoding", "br"))
                .andExpect(header().string("Vary", containsString("Accept-Encoding")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"))
                .andExpect(content().string("brotli bytes of the asset\n"));
        mvc().perform(get("/assets/index-abc123.js"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andExpect(content().string("console.log(\"app\");\n"));
    }

    @Test
    void shouldRevalidateAFileThatIsNotAnAsset() throws Exception {
        mvc().perform(get("/manifest.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void shouldAnswerNotFoundWhenAPathUnderAServerRootNamesNothing() throws Exception {
        for (String path : new String[] {"/assets/missing.js", "/legal/_disclaimer/missing.md"}) {
            mvc().perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("Content-Type", not(startsWith("text/html"))));
        }
        // The server's own roots are not the web app: an unknown path there is not public and is never the page.
        mvc().perform(get("/.well-known/missing")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldKeepTheApiAuthenticatedWhenItsPathIsEncoded() throws Exception {
        // Spring MVC decodes `/%61pi/user` to the API's `/api/user`; the web app's permit must not reach it.
        mvc().perform(get(URI.create("/%61pi/user"))).andExpect(status().isUnauthorized());
        mvc().perform(onHost(get(URI.create("/%61pi/identity-providers")), "acme.hephaestus.build"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldAnswerNotFoundWhenAMissingImageIsRead() throws Exception {
        mvc().perform(get("/missing.png")).andExpect(status().isNotFound());
        // A dotted path that is no image stays a client route: a login can contain a dot.
        mvc().perform(get("/w/acme/profile/jane.md")).andExpect(status().isOk());
    }

    @Test
    void shouldServeALegalPageAsMarkdownForThisDeploymentOnly() throws Exception {
        mvc().perform(get("/legal/_disclaimer/imprint.md"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/markdown")))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("Cache-Control", "max-age=300, must-revalidate"));
        // No override directory is configured, so the web app falls back to the placeholder.
        mvc().perform(get("/legal-overrides/imprint.md")).andExpect(status().isNotFound());
    }

    @Test
    void shouldPublishTheInstanceMetadataWithoutStoringIt() throws Exception {
        mvc().perform(get("/.well-known/hephaestus.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.apexOrigin").value("https://hephaestus.build"))
                .andExpect(jsonPath("$.baseDomain").value("hephaestus.build"))
                .andExpect(jsonPath("$.workspaceSubdomainsEnabled").value(true))
                // A build from source is no release, so the document names none.
                .andExpect(jsonPath("$.version").value(""));
    }

    @Test
    void shouldPublishTheSecurityContactWithoutACanonicalLineWhenWorkspacesHaveHosts() throws Exception {
        mvc().perform(get("/.well-known/security.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/plain")))
                .andExpect(content()
                        .string(allOf(
                                startsWith("Contact: mailto:security@hephaestus.build\n"),
                                containsString("\nExpires: "),
                                not(containsString("Canonical:")))));
    }

    @Test
    void shouldServeOnlyTheWebAppWhenTheHostIsAWorkspaceHost() throws Exception {
        mvc().perform(onHost(get("/practices"), "acme.hephaestus.build")).andExpect(status().isOk());
        for (String path : new String[] {
            "/api/identity-providers",
            "/login",
            "/webhooks/github",
            "/actuator/health",
            "/.well-known/oauth-authorization-server"
        }) {
            mvc().perform(onHost(get(path), "acme.hephaestus.build")).andExpect(status().isNotFound());
        }
    }

    @Test
    void shouldAnswerNotFoundWhenTheHostNamesNoPossibleWorkspace() throws Exception {
        for (String host : new String[] {"www.hephaestus.build", "a--b.hephaestus.build", "x.y.hephaestus.build"}) {
            mvc().perform(onHost(get("/"), host)).andExpect(status().isNotFound());
        }
    }

    @Test
    void shouldMoveAWorkspacePathToItsOwnHostWhenItIsReadOnTheApex() throws Exception {
        mvc().perform(onHost(get("/w/acme/practices?tab=all"), "hephaestus.build"))
                .andExpect(status().isPermanentRedirect())
                .andExpect(header().string("Location", "https://acme.hephaestus.build/practices?tab=all"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc().perform(onHost(get("/w/acme"), "hephaestus.build"))
                .andExpect(header().string("Location", "https://acme.hephaestus.build/"));
    }

    @Test
    void shouldSendTheHeaderSetWhenTheHostRulesAnswerThemselves() throws Exception {
        // A real server, so the 404 also passes the container's error dispatch. The referrer policy follows the
        // request: `/` is the web app's, and the API's path is not.
        for (String[] request : new String[][] {
            {"acme.hephaestus.build", "/api/identity-providers", "strict-origin-when-cross-origin"},
            {"www.hephaestus.build", "/", "no-referrer"}
        }) {
            assertHeaderSet(rootRequest(HttpMethod.GET, request[0], request[1])
                            .exchange()
                            .expectStatus()
                            .isNotFound())
                    .expectHeader()
                    .valueEquals("Referrer-Policy", request[2])
                    .expectBody(Void.class);
        }
        assertHeaderSet(rootRequest(HttpMethod.GET, "hephaestus.build", "/w/acme/practices")
                        .exchange()
                        .expectStatus()
                        .isPermanentRedirect())
                .expectHeader()
                .valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader()
                .valueEquals("Location", "https://acme.hephaestus.build/practices")
                .expectBody(Void.class);
        // HSTS goes only on a secure request, as on every other response.
        mvc().perform(onHost(get("/api/identity-providers").secure(true), "acme.hephaestus.build"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=2592000")));
    }

    @Test
    void shouldMoveAWorkspacePathOnHeadAndNeverMoveAWrite() {
        rootRequest(HttpMethod.HEAD, "hephaestus.build", "/w/acme/practices")
                .exchange()
                .expectStatus()
                .isPermanentRedirect()
                .expectHeader()
                .valueEquals("Location", "https://acme.hephaestus.build/practices")
                .expectBody(Void.class);
        // A 308 keeps the method and body, so a moved POST would replay a write on another origin.
        rootRequest(HttpMethod.POST, "hephaestus.build", "/w/acme/practices")
                .exchange()
                .expectStatus()
                .is4xxClientError()
                .expectHeader()
                .doesNotExist("Location")
                .expectBody(Void.class);
    }

    private WebTestClient.RequestHeadersSpec<?> rootRequest(HttpMethod method, String host, String path) {
        return webTestClient
                .method(method)
                .uri(ApiWebTestClient.root(application, path))
                .header(HttpHeaders.HOST, host);
    }

    private static WebTestClient.ResponseSpec assertHeaderSet(WebTestClient.ResponseSpec response) {
        return response.expectHeader()
                .value("Content-Security-Policy", value -> assertThat(value).contains("script-src 'self';"))
                .expectHeader()
                .valueEquals("X-Frame-Options", "DENY")
                .expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader()
                .valueEquals("Cross-Origin-Opener-Policy", "same-origin")
                .expectHeader()
                .value("Permissions-Policy", value -> assertThat(value).contains("camera=()"));
    }
}

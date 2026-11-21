package de.tum.cit.aet.hephaestus.core.webapp;

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

import de.tum.cit.aet.hephaestus.testconfig.ApiWebTestClient;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import jakarta.servlet.Filter;
import java.net.URI;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The server serves the built web app (ADR 0100): the page with its config record for every client route, the files
 * with their cache rules, the legal pages, the two discovery documents, and the host rules of workspace subdomains.
 * The base class turns workspace subdomains on under {@code hephaestus.build}, and the test profile serves the fixture in
 * {@code src/test/resources/web-app}.
 */
class WebAppIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebApplicationContext application;

    @Autowired
    private WebTestClient webTestClient;

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
                    .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self';")))
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
        mvc().perform(head("/w/acme")).andExpect(status().isOk());
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
}

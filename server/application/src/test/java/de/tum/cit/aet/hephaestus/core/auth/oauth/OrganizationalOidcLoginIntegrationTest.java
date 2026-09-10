package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderClientRegistrationRepository;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Real HTTP authorization and callbacks, framework nonce/PKCE validation and signed RSA ID tokens; no external IdP. */
@TestPropertySource(
        properties = {
            "hephaestus.auth.oidc.allowed-issuers=https://identity.example.com/realms/team,https://identity.example.com/realms/other",
            "hephaestus.auth.api-base-path=",
            "hephaestus.webapp.url=https://app.example.com"
        })
class OrganizationalOidcLoginIntegrationTest extends RealAuthIntegrationTest {
    private static final String ISSUER = "https://identity.example.com/realms/team";
    private static final String OTHER = "https://identity.example.com/realms/other";

    @Autowired
    private WebTestClient client;

    @Autowired
    private LoginProviderRepository providers;

    @Autowired
    private LoginProviderClientRegistrationRepository registrations;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityLinkRepository links;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Autowired
    private JwtPrincipalFactory principals;

    @MockitoBean(name = "oidcRequestFactory")
    private ClientHttpRequestFactory requests;

    private RSAKey key;
    private String idToken = "";
    private String expectedChallenge = "";

    @BeforeEach
    void fixtureIssuer() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
        provider("organization", ISSUER);
        provider("other", OTHER);
        registrations.evict("organization");
        registrations.evict("other");
        when(requests.createRequest(any(URI.class), any(HttpMethod.class))).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            HttpMethod method = invocation.getArgument(1);
            return new MockClientHttpRequest(method, uri) {
                @Override
                protected ClientHttpResponse executeInternal() {
                    String body;
                    if (uri.getPath().endsWith("/.well-known/openid-configuration")) {
                        String issuer = uri.toString().replace("/.well-known/openid-configuration", "");
                        body = """
                            {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",
                             "jwks_uri":"%s/keys","response_types_supported":["code"],"subject_types_supported":["public"],
                             "id_token_signing_alg_values_supported":["RS256"],"token_endpoint_auth_methods_supported":["client_secret_basic"]}
                            """.formatted(issuer, issuer, issuer, issuer);
                    } else if (uri.getPath().endsWith("/keys")) {
                        body = new JWKSet(key.toPublicJWK()).toString();
                    } else if (uri.getPath().endsWith("/token")) {
                        assertThat(method).isEqualTo(HttpMethod.POST);
                        var verifier = query("https://fixture.example/?" + getBodyAsString(), "code_verifier");
                        try {
                            assertThat(Base64.getUrlEncoder()
                                            .withoutPadding()
                                            .encodeToString(MessageDigest.getInstance("SHA-256")
                                                    .digest(verifier.getBytes(StandardCharsets.US_ASCII))))
                                    .isEqualTo(expectedChallenge);
                        } catch (java.security.NoSuchAlgorithmException exception) {
                            throw new IllegalStateException(exception);
                        }
                        body =
                                "{\"access_token\":\"fixture-access-token\",\"token_type\":\"Bearer\",\"expires_in\":300,\"id_token\":\""
                                        + idToken + "\"}";
                    } else throw new AssertionError("Unexpected OIDC endpoint: " + uri);
                    var response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return response;
                }
            };
        });
    }

    @Test
    void shouldKeepInstitutionalAndLinkOnlyCatalogBehindAuthentication() {
        client.get()
                .uri("/identity-providers")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.providerType == 'OIDC')]")
                .isEmpty()
                .jsonPath("$[?(@.providerType == 'SLACK')]")
                .isEmpty()
                .jsonPath("$[?(@.providerType == 'OUTLINE')]")
                .isEmpty();
        client.get().uri("/user/identity-providers").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void shouldSignInAnOrganizationalAccountThroughTheCompleteCallback() throws Exception {
        var flow = begin("organization", null);
        var result = finish(flow, "member-1", ISSUER, flow.nonce(), null);
        assertThat(String.valueOf(result.getResponseHeaders().getLocation())).endsWith("/settings");
        var access = result.getResponseCookies().getFirst("__Host-HEPHAESTUS_AT");
        assertThat(access).isNotNull();
        client.get()
                .uri("/user")
                .headers(headers -> headers.setBearerAuth(access.getValue()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.displayName")
                .isEqualTo("Organization member");
        assertThat(accounts.findAll())
                .singleElement()
                .satisfies(account -> assertThat(account.getPrimaryEmail()).isEqualTo("shared@example.com"));
    }

    @Test
    void shouldKeepIdenticalSubjectsAndEmailsInDifferentRealmsSeparate() throws Exception {
        var first = begin("organization", null);
        finish(first, "same-subject", ISSUER, first.nonce(), null);
        var other = begin("other", null);
        finish(other, "same-subject", OTHER, other.nonce(), null);
        assertThat(accounts.findAll()).hasSize(2);
        assertThat(links.findAll())
                .extracting(link -> link.getAccount().getId())
                .doesNotHaveDuplicates();
        assertThat(links.findAll()).extracting(link -> link.getProviderId()).doesNotHaveDuplicates();
    }

    @Test
    void shouldLinkToTheAuthenticatedAccountRatherThanAnEmailMatch() throws Exception {
        var original = accounts.saveAndFlush(new Account("Existing account"));
        var session = jwtIssuer
                .issue(principals.forAccount(original), TokenConstraints.session(null, Instant.now()), null)
                .value();
        var flow = begin("organization", session);
        var result = finish(flow, "linked-member", ISSUER, flow.nonce(), session);
        assertThat(String.valueOf(result.getResponseHeaders().getLocation())).endsWith("/settings");
        assertThat(result.getResponseCookies().getFirst("__Host-HEPHAESTUS_AT")).isNull();
        assertThat(links.findActiveByAccountId(Objects.requireNonNull(original.getId())))
                .singleElement()
                .satisfies(link -> assertThat(link.getSubject()).isEqualTo("linked-member"));
        assertThat(accounts.findAll()).hasSize(1);
    }

    @Test
    void shouldRejectAValidlySignedTokenFromAnotherIssuer() throws Exception {
        var flow = begin("organization", null);
        var result = finish(flow, "member", OTHER, flow.nonce(), null);
        assertThat(String.valueOf(result.getResponseHeaders().getLocation()))
                .endsWith("/auth/error?code=oauth_failure");
        assertThat(accounts.findAll()).isEmpty();
    }

    @Test
    void shouldRejectANonceMismatchWithoutProvisioningAnAccount() throws Exception {
        var flow = begin("organization", null);
        var result = finish(flow, "member", ISSUER, "different-nonce", null);
        assertThat(String.valueOf(result.getResponseHeaders().getLocation()))
                .endsWith("/auth/error?code=oauth_failure");
        assertThat(accounts.findAll()).isEmpty();
    }

    @Test
    void shouldRejectACallbackWithoutItsStateCookie() {
        client.get()
                .uri("/login/oauth2/code/organization?code=fixture-code&state=unbound")
                .exchange()
                .expectStatus()
                .is3xxRedirection();
        assertThat(accounts.findAll()).isEmpty();
    }

    private LoginProvider provider(String registrationId, String issuer) {
        var provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(LoginProvider.ProviderType.OIDC);
        provider.setBaseUrl(issuer);
        provider.setDisplayName(registrationId);
        provider.setClientId("fixture-client");
        provider.setClientSecret("fixture-secret");
        provider.setScopes("openid profile email");
        return providers.saveAndFlush(provider);
    }

    private Flow begin(String registrationId, @Nullable String session) {
        var kickoff = client.get()
                .uri(uri -> uri.path("/auth/login")
                        .queryParam("provider", registrationId)
                        .queryParam("returnTo", "/settings")
                        .queryParam("mode", session == null ? "login" : "link")
                        .build());
        if (session != null) kickoff.headers(headers -> headers.setBearerAuth(session));
        var start = kickoff.exchange()
                .expectStatus()
                .is3xxRedirection()
                .expectBody()
                .returnResult();
        Map<String, String> cookies = new HashMap<>();
        start.getResponseCookies()
                .forEach((name, values) -> cookies.put(name, values.getFirst().getValue()));
        var redirect = client.get()
                .uri(Objects.requireNonNull(start.getResponseHeaders().getLocation())
                        .toString())
                .cookies(jar -> cookies.forEach(jar::set))
                .exchange()
                .expectStatus()
                .is3xxRedirection()
                .expectBody()
                .returnResult();
        redirect.getResponseCookies()
                .forEach((name, values) -> cookies.put(name, values.getFirst().getValue()));
        String location = Objects.requireNonNull(redirect.getResponseHeaders().getLocation())
                .toString();
        assertThat(query(location, "code_challenge_method")).isEqualTo("S256");
        expectedChallenge = query(location, "code_challenge");
        return new Flow(registrationId, cookies, query(location, "state"), query(location, "nonce"));
    }

    private EntityExchangeResult<byte[]> finish(
            Flow flow, String subject, String issuer, String nonce, @Nullable String session) throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture-key").build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject(subject)
                        .audience("fixture-client")
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(300)))
                        .claim("nonce", nonce)
                        .claim("name", "Organization member")
                        .claim("email", "shared@example.com")
                        .claim("email_verified", true)
                        .build());
        jwt.sign(new RSASSASigner(key));
        idToken = jwt.serialize();
        var callback = client.get()
                .uri(uri -> uri.path("/login/oauth2/code/" + flow.registrationId())
                        .queryParam("code", "fixture-code")
                        .queryParam("state", flow.state())
                        .build())
                .cookies(jar -> flow.cookies().forEach(jar::set));
        if (session != null) callback.headers(headers -> headers.setBearerAuth(session));
        return callback.exchange()
                .expectStatus()
                .is3xxRedirection()
                .expectBody()
                .returnResult();
    }

    private static String query(String uri, String key) {
        return org.springframework.web.util.UriUtils.decode(
                Objects.requireNonNull(UriComponentsBuilder.fromUriString(uri)
                        .build()
                        .getQueryParams()
                        .getFirst(key)),
                StandardCharsets.UTF_8);
    }

    private record Flow(String registrationId, Map<String, String> cookies, String state, String nonce) {}
}

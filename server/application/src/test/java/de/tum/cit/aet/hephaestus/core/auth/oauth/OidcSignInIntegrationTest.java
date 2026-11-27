package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderService;
import de.tum.cit.aet.hephaestus.core.security.ScmEgressProperties;
import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.testconfig.ApiWebTestClient;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Signs in through an OpenID Connect provider over real HTTP: discovery, the authorization redirect with PKCE and a
 * nonce, the code exchange, the key set and the ID token, against a test issuer on loopback. The guarded transport
 * reaches it through the e2e simulator exemption, which admits that one loopback port and nothing else.
 */
@TestPropertySource(properties = "hephaestus.auth.bootstrap-admins=school:@ada,school:admin-subject")
@Import(OidcSignInIntegrationTest.LoopbackIssuer.class)
class OidcSignInIntegrationTest extends RealAuthIntegrationTest {

    private static final TestIssuer IDP = TestIssuer.start();
    private static final String CLIENT_ID = "hephaestus";
    private static final String CLIENT_SECRET = "test-client-secret";

    @TestConfiguration(proxyBeanMethods = false)
    static class LoopbackIssuer {
        @Bean
        @Primary
        ScmServerEndpointPolicy loopbackIssuerEndpoints(WebClient.Builder webClients, ScmEgressProperties egress) {
            var environment = new MockEnvironment();
            environment.setActiveProfiles("e2e");
            environment.setProperty("hephaestus.e2e.scm-origin", IDP.origin());
            return new ScmServerEndpointPolicy(environment, webClients, egress);
        }
    }

    @Autowired
    private WebTestClient web;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private LoginProviderService loginProviders;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthProperties authProperties;

    /** The test issuer's realm of each registration: each provider row needs its own issuer. */
    private final Map<String, String> realms = new HashMap<>();

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @AfterAll
    static void stopIssuer() {
        IDP.server.close();
    }

    @Test
    void shouldCreateOneAccountKeyedOnTheIssuerAndSubjectAndFindItOnTheNextSignIn() {
        String registration = provider("school");
        String subject = "s-" + UUID.randomUUID();

        SignIn first = signIn(
                registration,
                null,
                claims -> claims.claim("email", subject + "@example.org")
                        .claim("email_verified", true)
                        .claim("tid", "a-tenant"),
                subject);
        SignIn second = signIn(registration, null, UnaryOperator.identity(), subject);

        assertThat(first.location()).doesNotContain("/auth/error");
        assertThat(first.sessionCookie()).isTrue();
        IdentityRow identity = onlyIdentity(registration, subject);
        // The tenant claim does not split the issuer's subject namespace.
        assertThat(identity.teamKey()).isEmpty();
        assertThat(accounts.findById(identity.accountId()).orElseThrow().getPrimaryEmailVerifiedAt())
                .isNotNull();
        assertThat(second.location()).doesNotContain("/auth/error");
        assertThat(second.sessionCookie()).isTrue();
        assertThat(onlyIdentity(registration, subject).accountId()).isEqualTo(identity.accountId());
    }

    @Test
    void shouldCreateANewAccountWhenAnExistingAccountHasTheSameVerifiedEmail() {
        String registration = provider("email");
        String email = "shared-" + UUID.randomUUID() + "@example.org";
        String firstSubject = signIn(
                        registration,
                        null,
                        claims -> claims.claim("email", email).claim("email_verified", true))
                .subject();

        String secondSubject = signIn(
                        registration,
                        null,
                        claims -> claims.claim("email", email).claim("email_verified", true))
                .subject();

        assertThat(onlyIdentity(registration, secondSubject).accountId())
                .isNotEqualTo(onlyIdentity(registration, firstSubject).accountId());
    }

    @Test
    void shouldKeepTheSameSubjectAtAnotherIssuerOnTheSameHostApart() {
        String first = provider("realm-a");
        String second = provider("realm-b");
        String subject = "shared-" + UUID.randomUUID();

        signIn(first, null, UnaryOperator.identity(), subject);
        signIn(second, null, UnaryOperator.identity(), subject);

        IdentityRow a = onlyIdentity(first, subject);
        IdentityRow b = onlyIdentity(second, subject);
        assertThat(a.providerId()).isNotEqualTo(b.providerId());
        assertThat(a.accountId()).isNotEqualTo(b.accountId());
    }

    @Test
    @SuppressWarnings("JavaUtilDate") // Nimbus claim sets take java.util.Date.
    void shouldRefuseATokenThatFailsAnyCheckAndWriteNoAccount() {
        String registration = provider("refusals");
        Map<String, UnaryOperator<JWTClaimsSet.Builder>> refusals = Map.of(
                "another issuer", claims -> claims.issuer(IDP.issuer("elsewhere")),
                "another audience", claims -> claims.audience("someone-else"),
                "another nonce", claims -> claims.claim("nonce", "replayed"),
                "expired",
                        claims -> claims.issueTime(Date.from(Instant.now().minusSeconds(600)))
                                .expirationTime(Date.from(Instant.now().minusSeconds(300))));
        refusals.forEach((reason, change) -> {
            SignIn refused = signIn(registration, null, change);
            assertThat(refused.location()).as(reason).contains("/auth/error");
            assertThat(refused.sessionCookie()).as(reason).isFalse();
            assertThat(identitiesOf(registration, refused.subject())).as(reason).isEmpty();
        });

        SignIn forged = signIn(registration, null, UnaryOperator.identity(), "forged-" + UUID.randomUUID(), true);
        assertThat(forged.location()).contains("/auth/error");
        assertThat(forged.sessionCookie()).isFalse();
        assertThat(identitiesOf(registration, forged.subject())).isEmpty();
    }

    @Test
    void shouldGrantBootstrapAdminBySubjectButNotByAnAllowListedUsername() {
        // The allow list names the registration id.
        String registration = provider("school", "school");

        String username = signIn(registration, null, claims -> claims.claim("preferred_username", "ada"))
                .subject();
        signIn(registration, null, UnaryOperator.identity(), "admin-subject");

        assertThat(roleOf(onlyIdentity(registration, "admin-subject"))).isEqualTo(Account.AppRole.APP_ADMIN);
        assertThat(roleOf(onlyIdentity(registration, username))).isNotEqualTo(Account.AppRole.APP_ADMIN);
    }

    @Test
    void shouldLinkTheIdentityToTheSignedInAccountAndRefuseItToAnotherClaimedAccount() {
        String registration = provider("link");
        Account owner = accounts.saveAndFlush(new Account("Link owner"));
        String subject = "linked-" + UUID.randomUUID();

        SignIn linked = signIn(registration, owner, UnaryOperator.identity(), subject);
        Account other = accounts.saveAndFlush(new Account("Someone else"));
        SignIn conflict = signIn(registration, other, UnaryOperator.identity(), subject);

        assertThat(linked.location()).doesNotContain("/auth/error");
        IdentityRow identity = onlyIdentity(registration, subject);
        assertThat(identity.accountId()).isEqualTo(owner.getId());
        assertThat(identity.linkedVia()).isEqualTo("MANUAL");
        assertThat(conflict.location()).contains("/auth/error");
        assertThat(onlyIdentity(registration, subject).accountId()).isEqualTo(owner.getId());
    }

    @Test
    void shouldRefuseAnIssuerWhoseDiscoveryDocumentNamesAnotherIssuer() {
        String registration = "liar-" + UUID.randomUUID().toString().substring(0, 8);
        IDP.liars.add(registration);

        assertThatThrownBy(() -> loginProviders.create(draft(registration, registration)))
                .hasMessageContaining("another issuer");
    }

    /** Creates an OIDC provider for its own realm of the test issuer through the admin service, which reads discovery. */
    private String provider(String name) {
        return provider(name + "-" + UUID.randomUUID().toString().substring(0, 8), name);
    }

    private String provider(String registration, String name) {
        String realm = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        loginProviders.create(draft(registration, realm));
        realms.put(registration, realm);
        return registration;
    }

    private static LoginProviderService.Draft draft(String registration, String realm) {
        return new LoginProviderService.Draft(
                registration,
                LoginProvider.ProviderType.OIDC,
                "School Login",
                IDP.issuer(realm),
                CLIENT_ID,
                CLIENT_SECRET,
                null);
    }

    private SignIn signIn(String registration, @Nullable Account linking, UnaryOperator<JWTClaimsSet.Builder> claims) {
        return signIn(registration, linking, claims, "s-" + UUID.randomUUID());
    }

    private SignIn signIn(
            String registration,
            @Nullable Account linking,
            UnaryOperator<JWTClaimsSet.Builder> claims,
            String subject) {
        return signIn(registration, linking, claims, subject, false);
    }

    /** The whole browser round trip: begin, authorization redirect, the issuer's answer, the callback. */
    @SuppressWarnings("JavaUtilDate") // Nimbus claim sets take java.util.Date.
    private SignIn signIn(
            String registration,
            @Nullable Account linking,
            UnaryOperator<JWTClaimsSet.Builder> claims,
            String subject,
            boolean forged) {
        MultiValueMap<String, String> jar = new LinkedMultiValueMap<>();
        // A browser sends its session cookie on every request of the flow, the callback included.
        if (linking != null) jar.add(authProperties.cookieName(), sessionOf(linking));
        String begin =
                "/auth/login?provider=" + registration + (linking == null ? "" : "&mode=link&returnTo=/settings");
        var started = web.get()
                .uri(begin)
                .cookies(cookies -> cookies.addAll(jar))
                .exchange()
                .expectStatus()
                .is3xxRedirection()
                .expectBody(Void.class)
                .returnResult();
        keep(jar, started);
        var authorize = web.get()
                .uri(root(location(started)))
                .cookies(cookies -> cookies.addAll(jar))
                .exchange()
                .expectStatus()
                .is3xxRedirection()
                .expectBody(Void.class)
                .returnResult();
        keep(jar, authorize);
        Map<String, String> request = query(location(authorize));
        assertThat(request).containsEntry("code_challenge_method", "S256").containsKeys("nonce", "state");
        String realm = location(authorize).getPath().split("/", -1)[2];
        JWTClaimsSet.Builder token = new JWTClaimsSet.Builder()
                .issuer(IDP.issuer(realm))
                .subject(subject)
                .audience(CLIENT_ID)
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("nonce", request.get("nonce"))
                .claim("name", "Ada Lovelace");
        String code = IDP.code(
                request.get("code_challenge"), IDP.sign(claims.apply(token).build(), forged));
        // The state is base64url and the code a UUID, so neither needs encoding; the server's address helper encodes
        // what it is given once more.
        String callback = "/api/login/oauth2/code/" + registration + "?code=" + code + "&state=" + request.get("state");
        var answered = web.get()
                .uri(root(URI.create(callback)))
                .cookies(cookies -> cookies.addAll(jar))
                .exchange()
                .expectStatus()
                .is3xxRedirection()
                .expectBody(Void.class)
                .returnResult();
        boolean session = answered.getResponseCookies().keySet().stream()
                .anyMatch(name -> name.endsWith("HEPHAESTUS_AT")
                        && answered.getResponseCookies().getFirst(name) != null
                        && !Objects.requireNonNull(answered.getResponseCookies().getFirst(name))
                                .getValue()
                                .isEmpty());
        return new SignIn(location(answered).toString(), session, subject);
    }

    private record SignIn(String location, boolean sessionCookie, String subject) {}

    private String sessionOf(Account account) {
        return jwtIssuer
                .issue(Objects.requireNonNull(account.getId()), TokenConstraints.session(null, Instant.now()), null)
                .value();
    }

    private record IdentityRow(long accountId, long providerId, String teamKey, String linkedVia) {}

    private IdentityRow onlyIdentity(String registration, String subject) {
        List<IdentityRow> found = identitiesOf(registration, subject);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private List<IdentityRow> identitiesOf(String registration, String subject) {
        return jdbc.query(
                """
                SELECT i.account_id,i.provider_id,i.team_key,i.linked_via FROM identity i
                JOIN identity_provider p ON p.id=i.provider_id
                WHERE p.type='OIDC' AND p.server_url=? AND i.provider_user_key=?
                """,
                (rs, row) -> new IdentityRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)),
                IDP.issuer(Objects.requireNonNull(realms.get(registration))),
                subject);
    }

    private Account.AppRole roleOf(IdentityRow identity) {
        return accounts.findById(identity.accountId()).orElseThrow().getAppRole();
    }

    /** The same path and query on this server. */
    private URI root(URI location) {
        return URI.create(ApiWebTestClient.root(
                context, location.getRawPath() + (location.getRawQuery() == null ? "" : "?" + location.getRawQuery())));
    }

    private static URI location(EntityExchangeResult<?> result) {
        return Objects.requireNonNull(result.getResponseHeaders().getLocation());
    }

    private static void keep(MultiValueMap<String, String> jar, EntityExchangeResult<?> result) {
        for (List<ResponseCookie> cookies : result.getResponseCookies().values()) {
            for (ResponseCookie cookie : cookies) {
                jar.remove(cookie.getName());
                if (!cookie.getMaxAge().isZero()) jar.add(cookie.getName(), cookie.getValue());
            }
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new HashMap<>();
        for (String pair : Objects.requireNonNull(uri.getRawQuery()).split("&", -1)) {
            int separator = pair.indexOf('=');
            values.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    /** A test OpenID provider: discovery, key set and token endpoint for any realm, over real HTTP on loopback. */
    static final class TestIssuer {
        final MockWebServer server = new MockWebServer();
        final RSAKey key = rsa("issuer-key");
        final RSAKey stranger = rsa("stranger-key");
        final Map<String, String[]> codes = new ConcurrentHashMap<>();
        final Set<String> liars = ConcurrentHashMap.newKeySet();

        static TestIssuer start() {
            var issuer = new TestIssuer();
            issuer.server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return issuer.answer(request);
                }
            });
            try {
                issuer.server.start(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return issuer;
        }

        String origin() {
            return "http://127.0.0.1:" + server.getPort();
        }

        String issuer(String realm) {
            return origin() + "/realms/" + realm;
        }

        String code(@Nullable String challenge, String idToken) {
            String code = UUID.randomUUID().toString();
            codes.put(code, new String[] {Objects.requireNonNull(challenge), idToken});
            return code;
        }

        String sign(JWTClaimsSet claims, boolean forged) {
            RSAKey signer = forged ? stranger : key;
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims);
            try {
                jwt.sign(new RSASSASigner(signer));
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
            return jwt.serialize();
        }

        private MockResponse answer(RecordedRequest request) {
            String path = request.getUrl().encodedPath();
            String[] parts = path.split("/", -1);
            if (parts.length < 3 || !"realms".equals(parts[1])) return json(404, Map.of());
            String realm = parts[2];
            String base = issuer(realm);
            if (path.endsWith("/.well-known/openid-configuration")) {
                return json(
                        200,
                        Map.of(
                                "issuer", liars.contains(realm) ? issuer("someone-else") : base,
                                "authorization_endpoint", base + "/protocol/openid-connect/auth",
                                "token_endpoint", base + "/protocol/openid-connect/token",
                                "jwks_uri", base + "/protocol/openid-connect/certs",
                                "response_types_supported", List.of("code")));
            }
            if (path.endsWith("/protocol/openid-connect/certs")) {
                return json(200, new JWKSet(key.toPublicJWK()).toJSONObject());
            }
            if (path.endsWith("/protocol/openid-connect/token") && "POST".equals(request.getMethod())) {
                return token(request);
            }
            return json(404, Map.of());
        }

        private MockResponse token(RecordedRequest request) {
            String expected = "Basic "
                    + Base64.getEncoder()
                            .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(request.getHeaders().get("Authorization")))
                return json(401, Map.of("error", "invalid_client"));
            var body = request.getBody();
            Map<String, String> form = new HashMap<>();
            for (String pair : (body == null ? "" : body.utf8()).split("&", -1)) {
                int separator = pair.indexOf('=');
                if (separator > 0)
                    form.put(
                            URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
            String[] pending = codes.remove(form.getOrDefault("code", ""));
            // RFC 7636 § 4.6: the verifier's S256 must be the challenge that the authorization request carried.
            if (pending == null || !pending[0].equals(s256(form.getOrDefault("code_verifier", "")))) {
                return json(400, Map.of("error", "invalid_grant"));
            }
            return json(
                    200,
                    Map.of(
                            "access_token", "opaque-access-token",
                            "token_type", "Bearer",
                            "expires_in", 300,
                            "scope", "openid profile email",
                            "id_token", pending[1]));
        }

        private static String s256(String verifier) {
            try {
                return Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256")
                                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private static MockResponse json(int status, Map<String, ?> body) {
            return new MockResponse.Builder()
                    .code(status)
                    .addHeader("Content-Type", "application/json")
                    .body(JSONObjectUtils.toJSONString(Map.copyOf(body)))
                    .build();
        }

        private static RSAKey rsa(String id) {
            try {
                return new RSAKeyGenerator(2048).keyID(id).generate();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

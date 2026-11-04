package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.OfflineNetwork;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.SpringConstraintValidatorFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

class ScmServerEndpointPolicyTest extends BaseUnitTest {

    @BeforeAll
    static void declarePublicHosts() {
        OfflineNetwork.answer("gitlab.com", OfflineNetwork.PUBLIC_ADDRESS);
        OfflineNetwork.answer("gitlab.corp.internal", "10.20.30.40");
    }

    private static final String PROPERTY = "hephaestus.e2e.scm-origin";
    private static final String ORIGIN = "http://127.0.0.1:38171";

    @Test
    void shouldAdmitOnlyTheListedPrivateHost() {
        var listed = new ScmServerEndpointPolicy(
                new MockEnvironment(),
                WebClient.builder(),
                new ScmEgressProperties(List.of("gitlab.corp.internal", "10.20.30.40")));

        assertThatCode(() -> listed.validate("https://gitlab.corp.internal")).doesNotThrowAnyException();
        assertThatCode(() -> listed.validate("https://10.20.30.40")).doesNotThrowAnyException();
        assertThatThrownBy(() -> listed.validate("https://other.corp.internal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listed.validate("https://10.20.30.41")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseEveryPrivateHostByDefault() {
        var unlisted = new ScmServerEndpointPolicy(
                new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of()));

        assertThatThrownBy(() -> unlisted.validate("https://gitlab.corp.internal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> unlisted.validate("https://10.20.30.40")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://169.254.169.254", "https://127.0.0.1", "https://localhost"})
    void shouldKeepLoopbackAndMetadataOutOfReachWhateverTheList(String url) {
        var listed = new ScmServerEndpointPolicy(
                new MockEnvironment(),
                WebClient.builder(),
                new ScmEgressProperties(List.of("gitlab.corp.internal", "10.20.0.0/16")));

        assertThatThrownBy(() -> listed.validate(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldStopStartupOnAListEntryThatIsNoPrivateRange() {
        assertThatThrownBy(() -> new ScmServerEndpointPolicy(
                        new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of("8.8.8.0/24"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "local", "prod", "e2e,prod"})
    void shouldRejectConfiguredSimulationOutsideIsolatedE2e(String profiles) {
        var environment = new MockEnvironment().withProperty(PROPERTY, ORIGIN);
        if (!profiles.isEmpty()) {
            environment.setActiveProfiles(profiles.split(","));
        }
        assertThatThrownBy(() -> new ScmServerEndpointPolicy(
                        environment, WebClient.builder(), new ScmEgressProperties(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://localhost:38171",
                "http://127.0.0.1:38171/",
                "http://127.0.0.1:38171/path",
                "http://127.0.0.1:38171?x=1",
                "http://127.0.0.1:38171#fragment",
                "http://user@127.0.0.1:38171",
                "http://10.0.0.1:38171",
                "http://127.0.0.1",
                "http://127.0.0.1:0",
                "http://127.0.0.1:65536"
            })
    void shouldRejectNonLiteralOrNonOriginSimulationConfiguration(String origin) {
        assertThatThrownBy(() -> policy(origin)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRetainStrictDefaultsEvenWithE2eProfile() {
        var endpoints = policy("");
        assertThatThrownBy(() -> endpoints.validate(ORIGIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> endpoints.validate("https://gitlab.com")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://127.0.0.1:38172", "https://127.0.0.1:38171", "http://localhost:38171",
                "http://127.0.0.1:38171/path", "http://127.0.0.1:38171?x=1", "https://10.0.0.1",
                "http://user@127.0.0.1:38171", "http://127.0.0.1:38171#fragment"
            })
    void shouldRejectEveryOtherPrivateEndpoint(String origin) {
        assertThatThrownBy(() -> policy(ORIGIN).validate(origin)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowOnlyExactConfiguredOriginAndOptionalRootSlash() {
        var endpoints = policy(ORIGIN);
        assertThatCode(() -> endpoints.validate(ORIGIN)).doesNotThrowAnyException();
        assertThatCode(() -> endpoints.validate(ORIGIN + "/")).doesNotThrowAnyException();
    }

    @Test
    void shouldReachSimulatorWithoutFollowingRedirectsOrAllowingOriginChanges() {
        var server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.get(
                                "/api/v4/user",
                                (request, response) -> response.sendString(Mono.just("simulated identity")))
                        .get(
                                "/redirect",
                                (request, response) -> response.status(302)
                                        .header("Location", "http://127.0.0.1:1/forbidden")
                                        .send()))
                .bindNow();
        try {
            String origin = "http://127.0.0.1:" + server.port();
            var client = policy(origin).clientFor(origin);
            assertThat(client.get()
                            .uri(origin + "/api/v4/user")
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(Duration.ofSeconds(5)))
                    .isEqualTo("simulated identity");
            assertThat(client.get()
                            .uri(origin + "/redirect")
                            .exchangeToMono(
                                    response -> Mono.just(response.statusCode().value()))
                            .block(Duration.ofSeconds(5)))
                    .isEqualTo(302);
            assertThatThrownBy(() -> client.get()
                            .uri("http://127.0.0.1:1/forbidden")
                            .retrieve()
                            .toBodilessEntity()
                            .block(Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("validated origin");
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void shouldApplyTheSamePolicyToWorkspaceBeanValidation() {
        try (var context = new StaticApplicationContext();
                var validator = new LocalValidatorFactoryBean()) {
            context.getBeanFactory().registerSingleton("endpoints", policy(ORIGIN));
            validator.setConstraintValidatorFactory(new SpringConstraintValidatorFactory(context.getBeanFactory()));
            validator.afterPropertiesSet();
            assertThat(validator.validateValue(CreateWorkspaceRequestDTO.class, "serverUrl", ORIGIN))
                    .isEmpty();
            assertThat(validator.validateValue(CreateWorkspaceRequestDTO.class, "serverUrl", "http://127.0.0.1:38172"))
                    .hasSize(1);
            assertThat(validator.validateValue(CreateWorkspaceRequestDTO.class, "serverUrl", ""))
                    .isEmpty();
        }
    }

    /** The URL passes validation, because a name proves nothing. The address is refused when the client connects. */
    @ParameterizedTest
    @CsvSource({
        "rebound-metadata.corp.example,169.254.169.254",
        "rebound-mapped.corp.example,::ffff:169.254.169.254",
        "rebound-loopback.corp.example,127.0.0.1",
        "rebound-loopback6.corp.example,::1",
        "rebound-private.corp.example,10.9.9.9",
    })
    void shouldRefuseAtConnectTimeWhatDnsAnswersAfterValidation(String host, String address) {
        OfflineNetwork.answer(host, address);
        var unlisted = new ScmServerEndpointPolicy(
                new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of()));
        String url = "https://" + host;

        assertThatCode(() -> unlisted.validate(url)).doesNotThrowAnyException();
        assertBlockedAtConnect(unlisted.clientFor(url), url);
        assertBlockedAtConnect(
                WebClient.builder().clientConnector(unlisted.connector()).build(), url);
    }

    @ParameterizedTest
    @CsvSource({
        "listed-metadata.corp.example,169.254.169.254",
        "listed-mapped.corp.example,::ffff:169.254.169.254",
        "listed-loopback.corp.example,127.0.0.1",
        "listed-cloud.corp.example,100.100.100.200",
    })
    void shouldRefuseAtConnectTimeWhatAListedNameRebindsToOutsideThePrivateRanges(String host, String address) {
        OfflineNetwork.answer(host, address);
        var listed = new ScmServerEndpointPolicy(
                new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of(host)));

        assertBlockedAtConnect(listed.clientFor("https://" + host), "https://" + host);
    }

    @Test
    void shouldRefuseAtConnectTimeThisProcessWhenAListedNameRebindsToIt() throws Exception {
        var own = PrivateHostAllowlist.ownAddresses().stream()
                .filter(address -> address.isSiteLocalAddress())
                .findFirst();
        Assumptions.assumeTrue(own.isPresent(), "this host has no private interface address");
        String host = "rebound-self.corp.example";
        OfflineNetwork.answer(host, own.orElseThrow().getHostAddress());
        var listed = new ScmServerEndpointPolicy(
                new MockEnvironment(), WebClient.builder(), new ScmEgressProperties(List.of(host)));

        assertBlockedAtConnect(listed.clientFor("https://" + host), "https://" + host);
    }

    @Test
    void shouldNotFollowARedirectToAnotherAddress() throws Exception {
        var secret = new AtomicInteger();
        var target =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        target.createContext("/", exchange -> {
            secret.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        var redirector =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        redirector.createContext("/", exchange -> {
            exchange.getResponseHeaders()
                    .add("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        target.start();
        redirector.start();
        try {
            String origin = "http://127.0.0.1:" + redirector.getAddress().getPort();
            var endpoints = policy(origin);
            for (var client : List.of(
                    endpoints.clientFor(origin),
                    WebClient.builder().clientConnector(endpoints.connector()).build())) {
                var status = client.get()
                        .uri(origin + "/api/v4/user")
                        .exchangeToMono(
                                response -> Mono.just(response.statusCode().value()))
                        .block(Duration.ofSeconds(10));
                assertThat(status).isEqualTo(302);
            }
            assertThat(secret).hasValue(0);
        } finally {
            redirector.stop(0);
            target.stop(0);
        }
    }

    private static void assertBlockedAtConnect(WebClient client, String url) {
        assertThatThrownBy(() -> client.get()
                        .uri(url + "/api/v4/user")
                        .retrieve()
                        .toBodilessEntity()
                        .block(Duration.ofSeconds(10)))
                .hasRootCauseInstanceOf(UnknownHostException.class)
                .rootCause()
                .hasMessageContaining("(SSRF guard)");
    }

    private static ScmServerEndpointPolicy policy(String origin) {
        var environment = new MockEnvironment().withProperty(PROPERTY, origin);
        environment.setActiveProfiles("e2e");
        return new ScmServerEndpointPolicy(environment, WebClient.builder(), new ScmEgressProperties(List.of()));
    }
}

package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.SpringConstraintValidatorFactory;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

class ScmServerEndpointPolicyTest extends BaseUnitTest {
    private static final String PROPERTY = "hephaestus.e2e.scm-origin";
    private static final String ORIGIN = "http://127.0.0.1:38171";

    @ParameterizedTest
    @ValueSource(strings = {"", "local", "prod", "e2e,prod"})
    void shouldRejectConfiguredSimulationOutsideIsolatedE2e(String profiles) {
        var environment = new MockEnvironment().withProperty(PROPERTY, ORIGIN);
        if (!profiles.isEmpty()) {
            environment.setActiveProfiles(profiles.split(","));
        }
        assertThatThrownBy(() -> new ScmServerEndpointPolicy(environment)).isInstanceOf(IllegalArgumentException.class);
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

    private static ScmServerEndpointPolicy policy(String origin) {
        var environment = new MockEnvironment().withProperty(PROPERTY, origin);
        environment.setActiveProfiles("e2e");
        return new ScmServerEndpointPolicy(environment);
    }
}

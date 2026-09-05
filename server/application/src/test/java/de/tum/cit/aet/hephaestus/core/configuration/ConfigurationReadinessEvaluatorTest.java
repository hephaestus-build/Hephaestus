package de.tum.cit.aet.hephaestus.core.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
@ExtendWith(OutputCaptureExtension.class)
class ConfigurationReadinessEvaluatorTest extends BaseUnitTest {

    private static final String SECRET_MARKER = "PLANTED-CREDENTIAL-MUST-NEVER-LEAK";

    @Test
    void shouldReportEveryIndependentDefectWithoutLeakingValues() {
        Map<String, Object> properties = validProperties();
        properties.put("spring.datasource.url", SECRET_MARKER);
        properties.put("spring.datasource.password", SECRET_MARKER);
        properties.put("hephaestus.security.encryption-key", SECRET_MARKER);
        properties.put("hephaestus.security.credential-encryption-key", SECRET_MARKER);
        properties.put("hephaestus.webhook.secret", "PLANTED");
        properties.put("hephaestus.auth.state-cookie-key", SECRET_MARKER);
        properties.put("hephaestus.auth.login-providers.github.client-secret", "");

        List<ConfigurationFactDTO> facts = evaluateReadiness(properties, false);

        assertThat(facts.stream()
                        .filter(f -> f.status() == ConfigurationStatus.ACTION_REQUIRED)
                        .map(ConfigurationFactDTO::id))
                .contains(
                        "database.url",
                        "security.credential-encryption",
                        "security.value-encryption",
                        "webhook.shared-secret",
                        "auth.state-cookie-key",
                        "auth.login-provider");
        assertThat(facts.toString()).doesNotContain(SECRET_MARKER);
    }

    @Test
    void shouldSatisfyAllChecksForCombinedRole() {
        assertThat(evaluateReadiness(validProperties(), true))
                .allMatch(fact -> Set.of(ConfigurationStatus.SATISFIED, ConfigurationStatus.NOT_CONFIGURED)
                        .contains(fact.status()));
    }

    @Test
    void shouldApplyServerRoleChecksAndExcludeWorkerChecks() {
        Map<String, Object> properties = role(false, false);
        List<ConfigurationFactDTO> facts = evaluateReadiness(properties, true);
        assertStatus(facts, "auth.login-provider", ConfigurationStatus.SATISFIED);
        assertStatus(facts, "llm.proxy-egress", ConfigurationStatus.NOT_APPLICABLE);
        assertStatus(facts, "agent.image-contract", ConfigurationStatus.NOT_APPLICABLE);
    }

    @Test
    void shouldApplyWorkerRoleChecksAndExcludeServerChecks() {
        Map<String, Object> properties = role(true, false);
        properties.put("hephaestus.sync.nats.enabled", false);
        List<ConfigurationFactDTO> facts = evaluateReadiness(properties, true);
        assertStatus(facts, "llm.proxy-egress", ConfigurationStatus.SATISFIED);
        assertStatus(facts, "auth.login-provider", ConfigurationStatus.NOT_APPLICABLE);
        assertStatus(facts, "nats.server", ConfigurationStatus.NOT_APPLICABLE);
    }

    @Test
    void shouldApplyWebhookRoleChecksAndExcludeLoginAndWorkerChecks() {
        Map<String, Object> properties = role(false, true);
        properties.put("hephaestus.runtime.server.enabled", false);
        List<ConfigurationFactDTO> facts = evaluateReadiness(properties, true);
        assertStatus(facts, "webhook.shared-secret", ConfigurationStatus.SATISFIED);
        assertStatus(facts, "auth.login-provider", ConfigurationStatus.NOT_APPLICABLE);
        assertStatus(facts, "agent.image-contract", ConfigurationStatus.NOT_APPLICABLE);
    }

    @Test
    void shouldReportTheDockerRuntimeSettingAndDetectWhenItIsMissing() {
        Map<String, Object> properties = role(true, false);
        assertThat(evaluateReadiness(properties, true))
                .filteredOn(fact -> fact.id().equals("sandbox.isolation-runtime"))
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.subject()).isEqualTo("hephaestus.sandbox.docker.container-runtime");
                    assertThat(fact.status()).isEqualTo(ConfigurationStatus.SATISFIED);
                });

        properties.remove("hephaestus.sandbox.docker.container-runtime");
        assertStatus(
                evaluateReadiness(properties, true), "sandbox.isolation-runtime", ConfigurationStatus.ACTION_REQUIRED);
        properties.put("hephaestus.runtime.worker.enabled", false);
        assertStatus(
                evaluateReadiness(properties, true), "sandbox.isolation-runtime", ConfigurationStatus.NOT_APPLICABLE);
    }

    @Test
    void shouldRejectRoleSpecificNegativeCases() {
        Map<String, Object> worker = role(true, false);
        worker.put("hephaestus.sync.nats.enabled", true);
        worker.put("hephaestus.llm.egress.allow-loopback", true);
        worker.put("hephaestus.agent.image.reference", "moving-tag");
        List<ConfigurationFactDTO> facts = evaluateReadiness(worker, true);
        assertStatus(facts, "nats.role-contract", ConfigurationStatus.ACTION_REQUIRED);
        assertStatus(facts, "llm.proxy-egress", ConfigurationStatus.ACTION_REQUIRED);
        assertStatus(facts, "agent.image-contract", ConfigurationStatus.ACTION_REQUIRED);

        worker.put("hephaestus.agent.image.reference", "ghcr.io/example/agent@sha256:" + "a".repeat(64));
        worker.put("hephaestus.agent.image.require-digest", false);
        assertStatus(evaluateReadiness(worker, true), "agent.image-contract", ConfigurationStatus.ACTION_REQUIRED);
    }

    @Test
    void shouldRejectMalformedSecurityBooleans() {
        Map<String, Object> properties = validProperties();
        properties.put("hephaestus.llm.egress.allow-loopback", "not-a-boolean");
        properties.put("hephaestus.sync.nats.enabled", "not-a-boolean");

        List<ConfigurationFactDTO> facts = evaluateReadiness(properties, true);

        assertStatus(facts, "llm.proxy-egress", ConfigurationStatus.ACTION_REQUIRED);
        assertStatus(facts, "nats.role-contract", ConfigurationStatus.ACTION_REQUIRED);
    }

    @Test
    void shouldAggregateProductionStartupFailuresWithoutLeakingValues(CapturedOutput output) {
        MockEnvironment environment = environment(validProperties());
        environment.setActiveProfiles("prod");
        environment.setProperty("spring.datasource.url", SECRET_MARKER);
        environment.setProperty("hephaestus.security.encryption-key", SECRET_MARKER);
        environment.setProperty("hephaestus.security.credential-encryption-key", SECRET_MARKER);
        environment.setProperty("hephaestus.webhook.secret", "short-secret");

        assertThatThrownBy(() -> new ProductionConfigurationEnvironmentPostProcessor()
                        .postProcessEnvironment(environment, new SpringApplication(Object.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database.url")
                .hasMessageContaining("security.credential-encryption")
                .hasMessageContaining("security.value-encryption")
                .hasMessageContaining("webhook.shared-secret")
                .hasMessageNotContaining(SECRET_MARKER);
        assertThat(output).doesNotContain(SECRET_MARKER).doesNotContain("short-secret");
    }

    @Test
    void shouldReportAnUnresolvablePlaceholderAsTheSettingItStandsFor() {
        MockEnvironment environment = environment(validProperties());
        environment.setActiveProfiles("prod");
        // Resolving a placeholder over an unset variable throws instead of returning null, so
        // reading it must not end the report. The second problem proves the report continues past
        // the one it could not read, rather than stopping at it.
        environment.setProperty("spring.datasource.url", "jdbc:${DATABASE_URL}");
        environment.setProperty("hephaestus.webhook.secret", "short-secret");

        assertThatThrownBy(() -> new ProductionConfigurationEnvironmentPostProcessor()
                        .postProcessEnvironment(environment, new SpringApplication(Object.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database.url")
                .hasMessageContaining("webhook.shared-secret");
    }

    @Test
    void shouldMarkAnUnreadableSettingActionRequiredRatherThanNotConfigured() {
        Map<String, Object> properties = validProperties();
        properties.put("spring.datasource.url", "jdbc:${DATABASE_URL}");

        List<ConfigurationFactDTO> facts = evaluateReadiness(properties, true);

        // Only ACTION_REQUIRED reaches the startup filter; NOT_CONFIGURED would boot the server.
        assertStatus(facts, "database.url", ConfigurationStatus.ACTION_REQUIRED);
    }

    @Test
    void shouldRejectSecurityBooleansItCannotRead() {
        Map<String, Object> properties = validProperties();
        properties.put("hephaestus.llm.egress.allow-loopback", "${MISSING_EGRESS_FLAG}");
        properties.put("hephaestus.sync.nats.enabled", "${MISSING_NATS_FLAG}");

        List<ConfigurationFactDTO> facts = evaluateReadiness(properties, true);

        // A value that cannot be read must not be answered with its permissive default.
        assertStatus(facts, "llm.proxy-egress", ConfigurationStatus.ACTION_REQUIRED);
        assertStatus(facts, "nats.role-contract", ConfigurationStatus.ACTION_REQUIRED);
    }

    @Test
    void shouldNotAllowAnotherProfileToBypassProductionValidation() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "test");

        assertThatThrownBy(() -> new ProductionConfigurationEnvironmentPostProcessor()
                        .postProcessEnvironment(environment, new SpringApplication(Object.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldLeaveNonProductionProfilesUsable() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertThatCode(() -> new ProductionConfigurationEnvironmentPostProcessor()
                        .postProcessEnvironment(environment, new SpringApplication(Object.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectWhitespaceAndControlCharacterSecrets() {
        Map<String, Object> properties = validProperties();
        properties.put("hephaestus.security.encryption-key", " ".repeat(32));
        properties.put("hephaestus.security.credential-encryption-key", " ".repeat(32));
        properties.put("hephaestus.webhook.secret", "a".repeat(31) + "\t");

        List<ConfigurationFactDTO> facts = evaluateReadiness(properties, true);

        assertStatus(facts, "security.credential-encryption", ConfigurationStatus.ACTION_REQUIRED);
        assertStatus(facts, "security.value-encryption", ConfigurationStatus.ACTION_REQUIRED);
        assertStatus(facts, "webhook.shared-secret", ConfigurationStatus.ACTION_REQUIRED);
    }

    private static Map<String, Object> role(boolean worker, boolean webhook) {
        Map<String, Object> properties = validProperties();
        properties.put("hephaestus.runtime.server.enabled", !worker && !webhook);
        properties.put("hephaestus.runtime.worker.enabled", worker);
        properties.put("hephaestus.runtime.webhook.enabled", webhook);
        return properties;
    }

    private static Map<String, Object> validProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("hephaestus.runtime.server.enabled", true);
        properties.put("hephaestus.runtime.worker.enabled", true);
        properties.put("hephaestus.runtime.webhook.enabled", true);
        properties.put("spring.datasource.url", "jdbc:postgresql://database/hephaestus");
        properties.put("spring.datasource.username", "hephaestus");
        properties.put("spring.datasource.password", "database-secret");
        properties.put("hephaestus.security.encryption-key", "0123456789abcdef0123456789abcdef");
        properties.put("hephaestus.security.credential-encryption-key", "0123456789abcdef0123456789abcdef");
        properties.put("hephaestus.host-url", "https://hephaestus.example.com");
        properties.put("hephaestus.webhook.secret", "0123456789abcdef0123456789abcdef");
        properties.put("hephaestus.sync.nats.enabled", true);
        properties.put("hephaestus.sync.nats.server", "nats://nats:4222");
        properties.put("hephaestus.auth.state-cookie-key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        properties.put("hephaestus.llm.egress.allow-loopback", false);
        properties.put("hephaestus.agent.image.require-digest", true);
        properties.put("hephaestus.agent.image.reference", "ghcr.io/example/agent@sha256:" + "a".repeat(64));
        properties.put("hephaestus.sandbox.docker.container-runtime", "runsc");
        return properties;
    }

    private static List<ConfigurationFactDTO> evaluateReadiness(Map<String, Object> properties, boolean hasProvider) {
        MockEnvironment environment = environment(properties);
        return new ConfigurationReadinessEvaluator(environment).evaluateReadiness(hasProvider);
    }

    private static MockEnvironment environment(Map<String, Object> properties) {
        MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::setProperty);
        return environment;
    }

    private static void assertStatus(List<ConfigurationFactDTO> facts, String id, ConfigurationStatus status) {
        assertThat(facts)
                .filteredOn(fact -> fact.id().equals(id))
                .extracting(ConfigurationFactDTO::status)
                .containsExactly(status);
    }
}

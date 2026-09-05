package de.tum.cit.aet.hephaestus.core.configuration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.PlaceholderResolutionException;

@Component
public final class ConfigurationReadinessEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationReadinessEvaluator.class);

    static final String DOC = "https://docs.hephaestus.build/admin/configuration-readiness";
    private static final Pattern DIGEST = Pattern.compile("^[a-z0-9][a-z0-9._/:\\-]*@sha256:[a-f0-9]{64}$");

    /** Spring property paths the Docker sandbox rename retired; none has a compatibility alias. */
    private static final List<String> LEGACY_SANDBOX_PROPERTIES = List.of(
            "hephaestus.sandbox.docker-host",
            "hephaestus.sandbox.tls-verify",
            "hephaestus.sandbox.cert-path",
            "hephaestus.sandbox.container-runtime",
            "hephaestus.sandbox.app-server-container-id",
            "hephaestus.mentor.docker-cli");

    /**
     * Environment variable names the rename retired. Unlike {@link #LEGACY_SANDBOX_PROPERTIES},
     * these do not share a prefix with their replacement, so Spring's relaxed binding cannot find
     * them under the new property path — they must be checked by their literal name.
     */
    private static final List<String> LEGACY_SANDBOX_ENVIRONMENT_VARIABLES =
            List.of("SANDBOX_TLS_VERIFY", "SANDBOX_CONTAINER_RUNTIME");

    private final Environment environment;

    public ConfigurationReadinessEvaluator(Environment environment) {
        this.environment = environment;
    }

    public List<ConfigurationFactDTO> evaluateDeployment() {
        BooleanSetting serverRole = booleanSetting("hephaestus.runtime.server.enabled", true);
        BooleanSetting workerRole = booleanSetting("hephaestus.runtime.worker.enabled", true);
        BooleanSetting webhookRole = booleanSetting("hephaestus.runtime.webhook.enabled", true);
        boolean server = serverRole.value();
        boolean worker = workerRole.value();
        boolean webhook = webhookRole.value();
        List<ConfigurationFactDTO> facts = new ArrayList<>();

        add(
                facts,
                "runtime.roles",
                "hephaestus.runtime.*",
                allRoles(),
                ConfigurationRequirement.REQUIRED,
                true,
                serverRole.valid() && workerRole.valid() && webhookRole.valid() && (server || worker || webhook),
                "At least one runtime role must be enabled.",
                "runtime-roles");
        add(
                facts,
                "database.url",
                "spring.datasource.url",
                allRoles(),
                ConfigurationRequirement.REQUIRED,
                true,
                this::validJdbcPostgres,
                "A PostgreSQL JDBC URL is required.",
                "database");
        add(
                facts,
                "database.username",
                "spring.datasource.username",
                allRoles(),
                ConfigurationRequirement.REQUIRED,
                true,
                ConfigurationReadinessEvaluator::notBlank,
                "A database username is required.",
                "database");
        add(
                facts,
                "database.password",
                "spring.datasource.password",
                allRoles(),
                ConfigurationRequirement.REQUIRED,
                true,
                ConfigurationReadinessEvaluator::notBlank,
                "A database password is required.",
                "database");
        add(
                facts,
                "security.credential-encryption",
                "hephaestus.security.credential-encryption-key",
                allRoles(),
                ConfigurationRequirement.REQUIRED,
                true,
                this::validEncryptionKey,
                "A 32-character printable ASCII key is required to encrypt stored integration credentials.",
                "credential-encryption");
        add(
                facts,
                "security.value-encryption",
                "hephaestus.security.encryption-key",
                allRoles(),
                ConfigurationRequirement.REQUIRED,
                true,
                this::validEncryptionKey,
                "A 32-character printable ASCII key is required to seal JWT signing keys and other encrypted values.",
                "credential-encryption");
        add(
                facts,
                "external.base-url",
                "hephaestus.host-url",
                roles(ConfigurationRole.SERVER),
                ConfigurationRequirement.REQUIRED,
                server,
                this::validHttpsOrigin,
                "A root HTTPS origin without credentials, query, or fragment is required.",
                "external-url");
        add(
                facts,
                "webhook.shared-secret",
                "hephaestus.webhook.secret",
                roles(ConfigurationRole.SERVER, ConfigurationRole.WEBHOOK),
                ConfigurationRequirement.REQUIRED,
                server || webhook,
                this::validWebhookSecret,
                "A webhook secret of at least 32 printable ASCII characters is required.",
                "webhooks");
        add(
                facts,
                "nats.role-contract",
                "hephaestus.sync.nats.enabled",
                allRoles(),
                ConfigurationRequirement.REQUIRED,
                true,
                validNatsRoleContract(server, webhook),
                "NATS must be enabled for server/webhook ingestion and disabled on a worker-only process.",
                "nats");
        add(
                facts,
                "nats.server",
                "hephaestus.sync.nats.server",
                roles(ConfigurationRole.SERVER, ConfigurationRole.WEBHOOK),
                ConfigurationRequirement.REQUIRED,
                server || webhook,
                this::validNatsUri,
                "An explicit nats:// or tls:// server URI is required.",
                "nats");
        add(
                facts,
                "auth.state-cookie-key",
                "hephaestus.auth.state-cookie-key",
                roles(ConfigurationRole.SERVER),
                ConfigurationRequirement.REQUIRED,
                server,
                this::validBase64Key,
                "A Base64-encoded 32-byte key is required to seal OAuth state.",
                "login");
        add(
                facts,
                "llm.proxy-egress",
                "hephaestus.llm.egress.allow-loopback",
                roles(ConfigurationRole.WORKER),
                ConfigurationRequirement.REQUIRED,
                worker,
                validFalse("hephaestus.llm.egress.allow-loopback", false),
                "Loopback LLM provider egress must be disabled in production.",
                "llm-proxy");
        add(
                facts,
                "agent.image-contract",
                "hephaestus.agent.image.reference",
                roles(ConfigurationRole.WORKER),
                ConfigurationRequirement.REQUIRED,
                worker,
                this::validAgentImage,
                "The worker image contract requires digest enforcement and a digest-pinned agent image.",
                "agent-image");
        add(
                facts,
                "sandbox.isolation-runtime",
                "hephaestus.sandbox.docker.container-runtime",
                roles(ConfigurationRole.WORKER),
                ConfigurationRequirement.RECOMMENDED,
                worker,
                "runsc".equals(property("hephaestus.sandbox.docker.container-runtime")),
                "gVisor (runsc) is recommended for stronger agent sandbox isolation.",
                "sandbox-isolation");
        String legacySandboxKey = firstConfiguredLegacySandboxKey();
        add(
                facts,
                "sandbox.docker-legacy-configuration",
                legacySandboxKey == null ? "hephaestus.sandbox.docker.*" : legacySandboxKey,
                roles(ConfigurationRole.WORKER),
                ConfigurationRequirement.REQUIRED,
                worker,
                legacySandboxKey == null,
                legacySandboxKey == null
                        ? "Docker sandbox settings live under hephaestus.sandbox.docker.*."
                        : "\"" + legacySandboxKey + "\" moved under hephaestus.sandbox.docker.* and is no longer read.",
                "docker-configuration");
        add(
                facts,
                "observability.sentry",
                "hephaestus.sentry.dsn",
                roles(ConfigurationRole.SERVER),
                ConfigurationRequirement.OPTIONAL,
                server,
                this::validOptionalHttpsUri,
                "Sentry is optional, but a configured DSN must be an HTTPS URI.",
                "optional-observability");

        verifyCatalogue(facts);
        return List.copyOf(facts);
    }

    public List<ConfigurationFactDTO> evaluateReadiness(boolean hasSignInProvider) {
        List<ConfigurationFactDTO> facts = new ArrayList<>(evaluateDeployment());
        boolean server =
                booleanSetting("hephaestus.runtime.server.enabled", true).value();
        add(
                facts,
                "auth.login-provider",
                "login-provider capability",
                roles(ConfigurationRole.SERVER),
                ConfigurationRequirement.REQUIRED,
                server,
                hasSignInProvider,
                "At least one enabled sign-in provider is required.",
                "login");
        verifyCatalogue(facts);
        return List.copyOf(facts);
    }

    private void add(
            List<ConfigurationFactDTO> facts,
            String id,
            String subject,
            List<ConfigurationRole> roles,
            ConfigurationRequirement requirement,
            boolean applicable,
            Predicate<String> predicate,
            String explanation,
            String anchor) {
        String value = property(subject);
        boolean configured = requirement != ConfigurationRequirement.OPTIONAL || notBlank(value);
        boolean satisfied = applicable && predicate.test(value);
        add(facts, id, subject, roles, requirement, applicable, configured, satisfied, explanation, anchor);
    }

    private static void add(
            List<ConfigurationFactDTO> facts,
            String id,
            String subject,
            List<ConfigurationRole> roles,
            ConfigurationRequirement requirement,
            boolean applicable,
            boolean satisfied,
            String explanation,
            String anchor) {
        add(facts, id, subject, roles, requirement, applicable, true, satisfied, explanation, anchor);
    }

    private static void add(
            List<ConfigurationFactDTO> facts,
            String id,
            String subject,
            List<ConfigurationRole> roles,
            ConfigurationRequirement requirement,
            boolean applicable,
            boolean configured,
            boolean satisfied,
            String explanation,
            String anchor) {
        ConfigurationStatus status = !applicable
                ? ConfigurationStatus.NOT_APPLICABLE
                : !configured
                        ? ConfigurationStatus.NOT_CONFIGURED
                        : satisfied ? ConfigurationStatus.SATISFIED : ConfigurationStatus.ACTION_REQUIRED;
        facts.add(new ConfigurationFactDTO(id, subject, roles, requirement, status, explanation, DOC + "#" + anchor));
    }

    /**
     * Reads a property the way an operator experiences it. A value like {@code jdbc:${DATABASE_URL}}
     * throws rather than resolving when the variable behind it is unset, which is exactly the
     * deployment this evaluator exists to describe — so an unresolvable placeholder is reported as
     * the missing setting it stands for instead of ending the report with a stack trace.
     */
    private @Nullable String property(String key) {
        try {
            return environment.getProperty(key);
        } catch (PlaceholderResolutionException unresolved) {
            unreadable(key, unresolved);
            return null;
        }
    }

    /**
     * Logs what the verdict cannot carry. Every caller reduces an unreadable value to "no value",
     * which is the right verdict but a poor diagnosis: a circular reference and an unset variable
     * look identical afterwards, and only this message separates them.
     */
    private void unreadable(String key, PlaceholderResolutionException unresolved) {
        log.warn("Could not read {} while checking configuration: {}", key, unresolved.getMessage());
    }

    private BooleanSetting booleanSetting(String key, boolean fallback) {
        String value;
        try {
            value = environment.getProperty(key);
        } catch (PlaceholderResolutionException unresolved) {
            // Absent means the operator accepted the default. Unreadable means nobody can say what
            // they chose, so falling back would answer a question like "is loopback egress off?"
            // with a guess — the same verdict a value that does not parse gets.
            unreadable(key, unresolved);
            return new BooleanSetting(false, false);
        }
        if (value == null) return new BooleanSetting(true, fallback);
        if ("true".equalsIgnoreCase(value)) return new BooleanSetting(true, true);
        if ("false".equalsIgnoreCase(value)) return new BooleanSetting(true, false);
        return new BooleanSetting(false, false);
    }

    private @Nullable String firstConfiguredLegacySandboxKey() {
        for (String key : LEGACY_SANDBOX_PROPERTIES) {
            if (environment.containsProperty(key)) return key;
        }
        for (String key : LEGACY_SANDBOX_ENVIRONMENT_VARIABLES) {
            if (environment.containsProperty(key)) return key;
        }
        return null;
    }

    private boolean validFalse(String key, boolean fallback) {
        BooleanSetting setting = booleanSetting(key, fallback);
        return setting.valid() && !setting.value();
    }

    private boolean validNatsRoleContract(boolean server, boolean webhook) {
        BooleanSetting nats = booleanSetting("hephaestus.sync.nats.enabled", false);
        return nats.valid() && nats.value() == (server || webhook);
    }

    private boolean validEncryptionKey(@Nullable String value) {
        return value != null && value.length() == 32 && value.chars().allMatch(c -> c >= 0x21 && c <= 0x7e);
    }

    private boolean validBase64Key(@Nullable String value) {
        if (!notBlank(value)) return false;
        try {
            return Base64.getDecoder().decode(value).length == 32;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean validHttpsOrigin(@Nullable String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && (uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean validJdbcPostgres(@Nullable String value) {
        return value != null && !value.isBlank() && value.startsWith("jdbc:postgresql://");
    }

    private boolean validWebhookSecret(@Nullable String value) {
        return value != null
                && value.length() >= 32
                && value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
    }

    private boolean validNatsUri(@Nullable String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value);
            int port = uri.getPort();
            return Set.of("nats", "tls").contains(uri.getScheme())
                    && uri.getHost() != null
                    && (port == -1 || port > 0 && port <= 65535)
                    && (uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean validAgentImage(@Nullable String value) {
        BooleanSetting requireDigest = booleanSetting("hephaestus.agent.image.require-digest", false);
        return requireDigest.valid()
                && requireDigest.value()
                && notBlank(value)
                && DIGEST.matcher(value).matches();
    }

    private boolean validOptionalHttpsUri(@Nullable String value) {
        if (!notBlank(value)) return true;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean notBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static List<ConfigurationRole> allRoles() {
        return List.of(ConfigurationRole.SERVER, ConfigurationRole.WORKER, ConfigurationRole.WEBHOOK);
    }

    private static List<ConfigurationRole> roles(ConfigurationRole... roles) {
        return List.of(roles);
    }

    private record BooleanSetting(boolean valid, boolean value) {}

    private static void verifyCatalogue(List<ConfigurationFactDTO> facts) {
        Set<String> ids = new HashSet<>();
        if (facts.isEmpty() || facts.stream().anyMatch(fact -> !ids.add(fact.id()))) {
            throw new IllegalStateException(
                    "Configuration readiness catalogue is empty or contains duplicate identifiers");
        }
    }
}

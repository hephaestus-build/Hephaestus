package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Blocklist of env-var names callers must not inject into a sandbox: library injection,
 * code-execution-via-tooling, proxy hijacking, credential exfiltration. Used by the practice review
 * sandbox. Matching folds ASCII case only.
 */
public final class SandboxEnvBlocklist {

    static final Set<String> BLOCKED_NAMES;

    /** Prefix matches catch new credentials in known families (e.g. {@code AWS_ROLE_ARN}). */
    static final List<String> BLOCKED_PREFIXES =
            List.of("AWS_", "GOOGLE_", "GCP_", "AZURE_", "DOCKER_", "ALIBABA_CLOUD_", "GIT_CONFIG_");

    /** Names re-allowed even when they match a blocked prefix (legitimate SDK config). */
    static final Set<String> ALLOWED_PREFIX_EXCEPTIONS;

    static {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(List.of(
                // Library / module injection.
                "LD_PRELOAD",
                "LD_LIBRARY_PATH",
                "PATH",
                "SHELL",
                "USER",
                "NODE_OPTIONS",
                "BASH_ENV",
                "ENV",
                "JAVA_TOOL_OPTIONS",
                "_JAVA_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "PYTHONPATH",
                "PYTHONSTARTUP",
                "PYTHONUSERBASE",
                "PERL5OPT",
                "PERL5LIB",
                "RUBYOPT",
                "RUBYLIB",
                "OPENSSL_CONF",
                "OPENSSL_ENGINES",
                "CURL_CA_BUNDLE",
                "SSL_CERT_FILE",
                "SSL_CERT_DIR",
                "REQUESTS_CA_BUNDLE",
                // Server-owned correlation context.
                "TRACE_ID",
                "TRACEPARENT",
                // Proxy hijacking.
                "http_proxy",
                "https_proxy",
                "HTTP_PROXY",
                "HTTPS_PROXY",
                "no_proxy",
                "NO_PROXY",
                // Git env-based command execution (overrides git-config independently).
                "GIT_SSH",
                "GIT_SSH_COMMAND",
                "GIT_ASKPASS",
                "GIT_EDITOR",
                "GIT_EXEC_PATH",
                "GIT_TEMPLATE_DIR",
                "GIT_EXTERNAL_DIFF",
                "GIT_PROXY_COMMAND",
                "GIT_SEQUENCE_EDITOR",
                "GIT_PAGER",
                "GIT_TERMINAL_PROMPT",
                "GIT_ATTR_NOSYSTEM"));
        BLOCKED_NAMES = Collections.unmodifiableSet(names);

        TreeSet<String> allowed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        allowed.addAll(
                List.of("AZURE_OPENAI_DEPLOYMENT_NAME_MAP", "AZURE_OPENAI_BASE_URL", "AZURE_OPENAI_API_VERSION"));
        ALLOWED_PREFIX_EXCEPTIONS = Collections.unmodifiableSet(allowed);
    }

    private SandboxEnvBlocklist() {}

    /** {@return true if the named env var is forbidden by exact or prefix match (case-insensitive)}. */
    public static boolean isBlocked(@Nullable String name) {
        if (name == null) {
            return false;
        }
        if (BLOCKED_NAMES.contains(name)) {
            return true;
        }
        if (ALLOWED_PREFIX_EXCEPTIONS.contains(name)) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        for (String prefix : BLOCKED_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

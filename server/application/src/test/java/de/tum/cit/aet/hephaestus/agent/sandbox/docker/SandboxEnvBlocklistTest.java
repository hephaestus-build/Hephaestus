package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SandboxEnvBlocklistTest extends BaseUnitTest {

    @ParameterizedTest(name = "blocks exact: {0}")
    @ValueSource(
            strings = {
                "LD_PRELOAD",
                "LD_LIBRARY_PATH",
                "PATH",
                "NODE_OPTIONS",
                "GIT_SSH_COMMAND",
                "GIT_PAGER",
                "HTTPS_PROXY",
                "http_proxy",
                "BASH_ENV",
                "JAVA_TOOL_OPTIONS",
                "_JAVA_OPTIONS",
                "PYTHONPATH",
                "PYTHONSTARTUP",
                "PERL5OPT",
                "RUBYOPT",
                "OPENSSL_CONF",
                "CURL_CA_BUNDLE",
                "SSL_CERT_FILE",
            })
    void blocksExactByName(String name) {
        assertThat(SandboxEnvBlocklist.isBlocked(name)).isTrue();
    }

    @Test
    @DisplayName("static blocklist sets are unmodifiable")
    void blocklistIsImmutable() {
        assertThatThrownBy(() -> SandboxEnvBlocklist.BLOCKED_NAMES.add("INJECT"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> SandboxEnvBlocklist.ALLOWED_PREFIX_EXCEPTIONS.add("BYPASS"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "blocks prefix: {0}")
    @ValueSource(
            strings = {"AWS_ROLE_ARN", "GOOGLE_CLOUD_PROJECT", "AZURE_CLIENT_SECRET", "DOCKER_HOST", "GIT_CONFIG_KEY_0"
            })
    void blocksByPrefix(String name) {
        assertThat(SandboxEnvBlocklist.isBlocked(name)).isTrue();
    }

    @ParameterizedTest(name = "permits prefix exception: {0}")
    @ValueSource(strings = {"AZURE_OPENAI_DEPLOYMENT_NAME_MAP", "AZURE_OPENAI_BASE_URL", "AZURE_OPENAI_API_VERSION"})
    void allowsPrefixExceptions(String name) {
        assertThat(SandboxEnvBlocklist.isBlocked(name)).isFalse();
    }

    @ParameterizedTest(name = "permits unrelated: {0}")
    @ValueSource(strings = {"MY_APP_KEY", "FOO", "PI_AGENT_BUDGET_MS", "HOME"})
    void allowsUnrelated(String name) {
        assertThat(SandboxEnvBlocklist.isBlocked(name)).isFalse();
    }

    @ParameterizedTest(name = "case-insensitive: {0}")
    @ValueSource(
            strings = {"ld_preload", "Node_Options", "aws_access_key_id", "Google_Cloud_Project", "git_config_key_0"})
    void caseInsensitive(String name) {
        assertThat(SandboxEnvBlocklist.isBlocked(name)).isTrue();
    }

    @Test
    @DisplayName("null returns false — caller validates")
    void nullIsNotBlocked() {
        assertThat(SandboxEnvBlocklist.isBlocked(null)).isFalse();
    }
}

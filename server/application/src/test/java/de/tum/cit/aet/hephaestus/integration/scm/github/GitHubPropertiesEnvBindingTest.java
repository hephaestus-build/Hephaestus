package de.tum.cit.aet.hephaestus.integration.scm.github;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.github.workspace.GitHubWorkspaceProviderAvailability;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Compose forwards {@code GH_APP_INSTALLATION_URL} unconditionally, so an install that configured
 * no URL still reaches the container with the variable set to the empty string. Availability has to
 * agree with the operator that they configured nothing, or the workspace wizard offers a GitHub
 * option whose link goes nowhere.
 *
 * <p>These bind through a real {@link SystemEnvironmentPropertySource} and the shipped
 * {@code application.yml}, because the defect only exists on that path: the variable reaches the
 * {@code hephaestus.integration.github} prefix through a placeholder with an empty default, which a
 * direct constructor call would never exercise.
 */
@Tag("unit")
class GitHubPropertiesEnvBindingTest {

    private static final String INSTALLATION_URL = "https://github.com/apps/hephaestus/installations/new";

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void shouldHideGitHubAppWhenInstallationUrlIsBlank(String url) throws IOException {
        var properties = bindWith(Map.of("GH_APP_ID", "123456", "GH_APP_INSTALLATION_URL", url));

        assertThat(properties.app().id()).isEqualTo(123456);
        assertThat(properties.app().installationUrl()).isNull();
        assertThat(new GitHubWorkspaceProviderAvailability(properties).hintUrl())
                .isEmpty();
    }

    @Test
    void shouldHideGitHubAppWhenInstallationUrlIsMissing() throws IOException {
        var properties = bindWith(Map.of("GH_APP_ID", "123456"));

        assertThat(properties.app().installationUrl()).isNull();
        assertThat(new GitHubWorkspaceProviderAvailability(properties).hintUrl())
                .isEmpty();
    }

    @Test
    void shouldHideGitHubAppWhenNothingIsConfigured() throws IOException {
        var properties = bindWith(Map.of());

        assertThat(properties.app().id()).isZero();
        assertThat(properties.app().installationUrl()).isNull();
        assertThat(new GitHubWorkspaceProviderAvailability(properties).hintUrl())
                .isEmpty();
    }

    @Test
    void shouldHideGitHubAppWhenAppIdIsMissing() throws IOException {
        var properties = bindWith(Map.of("GH_APP_INSTALLATION_URL", INSTALLATION_URL));

        assertThat(properties.app().id()).isZero();
        assertThat(properties.app().installationUrl()).isEqualTo(INSTALLATION_URL);
        assertThat(new GitHubWorkspaceProviderAvailability(properties).hintUrl())
                .isEmpty();
    }

    @Test
    void shouldHideGitHubAppWhenAppIdIsDisabled() throws IOException {
        var properties = bindWith(Map.of("GH_APP_ID", "0", "GH_APP_INSTALLATION_URL", INSTALLATION_URL));

        assertThat(new GitHubWorkspaceProviderAvailability(properties).hintUrl())
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {INSTALLATION_URL, "https://github.example.com/apps/hephaestus/installations/new?target_id=42"})
    void shouldExposeUnchangedInstallationUrlWhenGitHubAppIsConfigured(String url) throws IOException {
        var properties = bindWith(Map.of("GH_APP_ID", "123456", "GH_APP_INSTALLATION_URL", url));
        var availability = new GitHubWorkspaceProviderAvailability(properties);

        assertThat(properties.app().installationUrl()).isEqualTo(url);
        assertThat(availability.hintUrl()).contains(url);
    }

    @Test
    void shouldBindTheAppsClientCredentialsAndTreatBlankOnesAsAbsent() throws IOException {
        assertThat(bindWith(Map.of("GH_APP_CLIENT_ID", "Iv1.client", "GH_APP_CLIENT_SECRET", "secret"))
                        .app())
                .extracting(GitHubProperties.App::clientId, GitHubProperties.App::clientSecret)
                .containsExactly("Iv1.client", "secret");
        assertThat(bindWith(Map.of("GH_APP_CLIENT_ID", " ", "GH_APP_CLIENT_SECRET", ""))
                        .app())
                .extracting(GitHubProperties.App::clientId, GitHubProperties.App::clientSecret)
                .containsOnlyNulls();
    }

    private static GitHubProperties bindWith(Map<String, Object> environmentVariables) throws IOException {
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));
        // GH_APP_* reach the configuration prefix through application.yml placeholders. Only the
        // first document is profile-independent; the rest activate on a profile no test selects.
        var yaml = new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        environment.getPropertySources().addLast(yaml.getFirst());

        return Binder.get(environment).bindOrCreate("hephaestus.integration.github", GitHubProperties.class);
    }
}

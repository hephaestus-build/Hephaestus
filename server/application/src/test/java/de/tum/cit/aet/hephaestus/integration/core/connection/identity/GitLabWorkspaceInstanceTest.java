package de.tum.cit.aet.hephaestus.integration.core.connection.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceProviderAvailability;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class GitLabWorkspaceInstanceTest {

    @Test
    void shouldRefuseGitLabWhenNoGitLabProviderIsAvailable() {
        var instance = new GitLabWorkspaceInstance(List.of(provider(IntegrationKind.GITHUB, "https://github.com")));

        assertThatThrownBy(() -> instance.require(null))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void shouldResolveTheConfiguredInstanceWhenGitLabIsAvailable() {
        var instance =
                new GitLabWorkspaceInstance(List.of(provider(IntegrationKind.GITLAB, "https://gitlab.example.com")));

        assertThat(instance.require(null)).isEqualTo("https://gitlab.example.com");
    }

    private static WorkspaceProviderAvailability provider(IntegrationKind kind, String url) {
        return new WorkspaceProviderAvailability() {
            @Override
            public IntegrationKind kind() {
                return kind;
            }

            @Override
            public Optional<String> hintUrl() {
                return Optional.of(url);
            }
        };
    }
}

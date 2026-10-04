package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PersonSourceNamespaceTest extends BaseUnitTest {
    @Test
    void shouldUseOnlyKnownProviderDefaultsAndPreserveExplicitSourceSpelling() {
        assertThat(PersonSourceNamespace.from("GITHUB", null).orElseThrow().serverUrl())
                .isEqualTo("https://github.com");
        assertThat(PersonSourceNamespace.from("GITLAB", null).orElseThrow().serverUrl())
                .isEqualTo("https://gitlab.com");
        assertThat(PersonSourceNamespace.from("SLACK", null).orElseThrow().serverUrl())
                .isEqualTo("https://slack.com");
        assertThat(PersonSourceNamespace.from("OUTLINE", null)).isEmpty();
        assertThat(PersonSourceNamespace.from("OUTLINE", "HTTPS://DOCS.EXAMPLE:443/")
                        .orElseThrow()
                        .serverUrl())
                .isEqualTo("HTTPS://DOCS.EXAMPLE:443/");
        assertThatThrownBy(() -> PersonSourceNamespace.from("OUTLINE", "not-a-provider-instance"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowEquivalentSourceSpellingButRejectRebindingABoundConnection() {
        var connection = new Connection(
                new Workspace(),
                IntegrationKind.OUTLINE,
                "bound",
                new ConnectionConfig.OutlineConfig("https://docs.example", null, null, Set.of()));
        connection.setConfig(new ConnectionConfig.OutlineConfig("HTTPS://DOCS.EXAMPLE:443/", null, null, Set.of()));
        assertThatThrownBy(() -> connection.setConfig(
                        new ConnectionConfig.OutlineConfig("https://another.example", null, null, Set.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot change its provider instance");
    }
}

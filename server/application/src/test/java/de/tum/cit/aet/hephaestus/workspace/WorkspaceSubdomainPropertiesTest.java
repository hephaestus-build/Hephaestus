package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceSubdomainPropertiesTest extends BaseUnitTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "https://hephaestus.build",
                "hephaestus.build:443",
                "Hephaestus.build",
                "hephaestus.build/",
                "build",
                "bad-.build",
                "127.0.0.1",
                "127.1",
                "0x7f.0x1",
                "example.123"
            })
    void shouldRejectBaseDomainWhenItCannotNameTenantHosts(String domain) {
        assertThatThrownBy(() -> new WorkspaceSubdomainProperties(true, domain))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExposeWorkspaceAddressWhenSwitchChanges() {
        assertThat(new WorkspaceSubdomainProperties(false, "").address("ls1intum", "https://hephaestus.build/"))
                .isEqualTo("https://hephaestus.build/w/ls1intum");
        assertThat(new WorkspaceSubdomainProperties(true, "hephaestus.build")
                        .address("ls1intum", "https://hephaestus.build"))
                .isEqualTo("https://ls1intum.hephaestus.build");
    }

    @Test
    void shouldFailClosedWhenApexConfigurationDoesNotAgree() {
        var subdomains = new WorkspaceSubdomainProperties(true, "hephaestus.build");
        String webappUrl = "https://hephaestus.build";
        assertThatThrownBy(() ->
                        new WorkspaceOriginPolicy(subdomains, webappUrl, URI.create("https://tenant.hephaestus.build")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuer");
        assertThatThrownBy(() -> new WorkspaceOriginPolicy(
                        subdomains, "http://localhost:4200", URI.create("https://hephaestus.build")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("webapp URL");
    }
}

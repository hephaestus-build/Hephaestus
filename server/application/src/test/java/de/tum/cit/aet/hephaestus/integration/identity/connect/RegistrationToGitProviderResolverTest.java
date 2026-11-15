package de.tum.cit.aet.hephaestus.integration.identity.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmIdentityLookup;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class RegistrationToGitProviderResolverTest {

    private IdentityProviderLookup providers;
    private ScmIdentityLookup users;
    private RegistrationToGitProviderResolver resolver;
    private SourceProviderNamespaces namespaces;

    @BeforeEach
    void setup() {
        providers = mock(IdentityProviderLookup.class);
        users = mock(ScmIdentityLookup.class);
        namespaces = mock(SourceProviderNamespaces.class);
        resolver = new RegistrationToGitProviderResolver(providers, users, mock(ConnectionQueries.class), namespaces);
    }

    @Test
    void shouldReadOnlyExistingEquivalentProviderOriginsWithoutRegisteringOne() {
        when(providers.findByOrigin(IdentityProviderType.OUTLINE, "https://outline.example.test"))
                .thenReturn(Optional.of(stamped(IdentityProviderType.OUTLINE, "HTTPS://Outline.Example.test:443/", 4)));
        assertThat(resolver.findProviderId("OUTLINE", "https://outline.example.test/path"))
                .contains(4L);
        assertThat(resolver.findProviderId("OUTLINE", "https://another.example.test"))
                .isEmpty();
        assertThat(resolver.findProviderId("OUTLINE", "not a URL")).isEmpty();
        assertThat(resolver.findProviderId("GITLAB", "https://outline.example.test"))
                .isEmpty();
        verify(namespaces, never()).registerIdentityProvider(any(), any());
    }

    private void stubRegistration(IdentityProviderType type, String origin, long id) {
        when(namespaces.registerIdentityProvider(type, origin)).thenReturn(id);
    }

    @Test
    void upsertsGithubComOrigin() {
        stubRegistration(IdentityProviderType.GITHUB, "https://github.com", 7L);

        assertThat(resolver.resolveProviderId("GITHUB", "https://github.com")).isEqualTo(7L);
    }

    @Test
    void selfHostedGitlabOriginStripsPath() {
        stubRegistration(IdentityProviderType.GITLAB, "https://gitlab.example.test", 8L);

        assertThat(resolver.resolveProviderId("GITLAB", "https://gitlab.example.test/sub/path"))
                .isEqualTo(8L);
        assertThat(resolver.resolveProviderId("GITLAB", "https://gitlab.example.test/sub/path"))
                .isEqualTo(8L);
    }

    @Test
    void preservesExplicitPort() {
        stubRegistration(IdentityProviderType.GITLAB, "https://gitlab.example.test:8443", 9L);

        assertThat(resolver.resolveProviderId("GITLAB", "https://gitlab.example.test:8443"))
                .isEqualTo(9L);
    }

    @Test
    void reusesExistingProviderRow() {
        when(providers.findByOrigin(IdentityProviderType.GITLAB, "https://gitlab.lrz.de"))
                .thenReturn(Optional.of(stamped(IdentityProviderType.GITLAB, "https://gitlab.lrz.de", 3L)));

        assertThat(resolver.resolveProviderId("GITLAB", "https://gitlab.lrz.de"))
                .isEqualTo(3L);
    }

    @Test
    void shouldFindTheRowByTheCanonicalOrigin() {
        when(providers.findByOrigin(IdentityProviderType.GITLAB, "https://gitlab.example.test"))
                .thenReturn(Optional.of(stamped(IdentityProviderType.GITLAB, "https://gitlab.example.test", 10L)));

        assertThat(resolver.resolveProviderId("GITLAB", "HTTPS://GitLab.Example.test:443"))
                .isEqualTo(10L);
    }

    @Test
    void shouldReuseARowThatSpellsTheSameOriginDifferently() {
        when(providers.findByOrigin(IdentityProviderType.GITLAB, "https://gitlab.lrz.de"))
                .thenReturn(Optional.of(stamped(IdentityProviderType.GITLAB, "HTTPS://GitLab.lrz.de:443", 4L)));

        assertThat(resolver.resolveProviderId("GITLAB", "https://gitlab.lrz.de"))
                .isEqualTo(4L);
    }

    @Test
    void shouldReportTheServerUrlAsItsCanonicalOrigin() {
        when(providers.findById(4L))
                .thenReturn(Optional.of(stamped(IdentityProviderType.GITLAB, "HTTPS://GitLab.lrz.de:443", 4L)));

        assertThat(resolver.providerServerUrl(4L)).isEqualTo("https://gitlab.lrz.de");
    }

    @Test
    void providerTypeNameIsUnknownForNull() {
        assertThat(resolver.providerTypeName(null)).isEqualTo("UNKNOWN");
    }

    private static IdentityProviderLookup.Provider stamped(IdentityProviderType type, String serverUrl, long id) {
        return new IdentityProviderLookup.Provider(id, type, serverUrl);
    }
}

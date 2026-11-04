package de.tum.cit.aet.hephaestus.integration.identity.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class RegistrationToGitProviderResolverTest {

    private IdentityProviderRepository gitProviderRepository;
    private UserRepository userRepository;
    private RegistrationToGitProviderResolver resolver;

    @BeforeEach
    void setup() {
        gitProviderRepository = mock(IdentityProviderRepository.class);
        userRepository = mock(UserRepository.class);
        resolver = new RegistrationToGitProviderResolver(
                gitProviderRepository, userRepository, mock(ConnectionRepository.class));
    }

    @Test
    void shouldReadOnlyExistingEquivalentProviderOriginsWithoutRegisteringOne() {
        when(gitProviderRepository.findAllByType(IdentityProviderType.OUTLINE))
                .thenReturn(List.of(stamped(IdentityProviderType.OUTLINE, "HTTPS://Outline.Example.test:443/", 4)));
        assertThat(resolver.findProviderId("OUTLINE", "https://outline.example.test/path"))
                .contains(4L);
        assertThat(resolver.findProviderId("OUTLINE", "https://another.example.test"))
                .isEmpty();
        assertThat(resolver.findProviderId("OUTLINE", "not a URL")).isEmpty();
        assertThat(resolver.findProviderId("GITLAB", "https://outline.example.test"))
                .isEmpty();
        verify(gitProviderRepository, never()).save(any(IdentityProvider.class));
    }

    private void stubRegistration(IdentityProviderType type, String origin, long id) {
        when(gitProviderRepository.findByTypeAndServerUrl(type, origin))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stamped(type, origin, id)));
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
        when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.lrz.de"))
                .thenReturn(Optional.of(stamped(IdentityProviderType.GITLAB, "https://gitlab.lrz.de", 3L)));

        assertThat(resolver.resolveProviderId("GITLAB", "https://gitlab.lrz.de"))
                .isEqualTo(3L);
    }

    @Test
    void shouldFindTheRowByTheCanonicalOrigin() {
        when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.example.test"))
                .thenReturn(Optional.of(stamped(IdentityProviderType.GITLAB, "https://gitlab.example.test", 10L)));

        assertThat(resolver.resolveProviderId("GITLAB", "HTTPS://GitLab.Example.test:443"))
                .isEqualTo(10L);
    }

    @Test
    void shouldReuseARowThatSpellsTheSameOriginDifferently() {
        when(gitProviderRepository.findAllByType(IdentityProviderType.GITLAB))
                .thenReturn(List.of(stamped(IdentityProviderType.GITLAB, "HTTPS://GitLab.lrz.de:443", 4L)));

        assertThat(resolver.resolveProviderId("GITLAB", "https://gitlab.lrz.de"))
                .isEqualTo(4L);
    }

    @Test
    void shouldReportTheServerUrlAsItsCanonicalOrigin() {
        when(gitProviderRepository.findById(4L))
                .thenReturn(Optional.of(stamped(IdentityProviderType.GITLAB, "HTTPS://GitLab.lrz.de:443", 4L)));

        assertThat(resolver.providerServerUrl(4L)).isEqualTo("https://gitlab.lrz.de");
    }

    @Test
    void providerTypeNameIsUnknownForNull() {
        assertThat(resolver.providerTypeName(null)).isEqualTo("UNKNOWN");
    }

    private static IdentityProvider stamped(IdentityProviderType type, String serverUrl, long id) {
        IdentityProvider p = new IdentityProvider(type, serverUrl);
        setId(p, id);
        return p;
    }

    private static void setId(Object entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }
}

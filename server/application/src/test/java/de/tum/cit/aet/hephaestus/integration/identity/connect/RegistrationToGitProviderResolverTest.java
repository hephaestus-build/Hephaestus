package de.tum.cit.aet.hephaestus.integration.identity.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RegistrationToGitProviderResolver}, the {@code GitProviderRegistry} SPI impl:
 * given a login provider's {@code (type, baseUrl)}, it upserts the {@code git_provider} row keyed on
 * the canonical server-url origin (scheme + host + explicit non-default port).
 */
@Tag("unit")
class RegistrationToGitProviderResolverTest {

    private IdentityProviderRepository gitProviderRepository;
    private UserRepository userRepository;
    private RegistrationToGitProviderResolver resolver;

    @BeforeEach
    void setup() {
        gitProviderRepository = mock(IdentityProviderRepository.class);
        userRepository = mock(UserRepository.class);
        resolver = new RegistrationToGitProviderResolver(gitProviderRepository, userRepository);
    }

    @Test
    void shouldFindTheSyncedUserWhenTheSubjectIsTheProvidersNumericId() {
        User synced = new User();
        synced.setId(555L);
        when(userRepository.findByNativeIdAndProviderId(777L, 7L)).thenReturn(Optional.of(synced));

        assertThat(resolver.findActorId(7L, "777")).contains(555L);
    }

    @Test
    void shouldFindNobodyWhenTheSubjectIsNotNumeric() {
        assertThat(resolver.findActorId(7L, "U0777")).isEmpty();
        verifyNoInteractions(userRepository);
    }

    private void stubSaveStampsId(long id) {
        when(gitProviderRepository.save(any(IdentityProvider.class))).thenAnswer(inv -> {
            IdentityProvider p = inv.getArgument(0);
            setId(p, id);
            return p;
        });
    }

    @Test
    void upsertsGithubComOrigin() {
        stubSaveStampsId(7L);
        when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com"))
                .thenReturn(Optional.empty());

        assertThat(resolver.resolveProviderId("GITHUB", "https://github.com")).isEqualTo(7L);
    }

    @Test
    void selfHostedGitlabOriginStripsPath() {
        stubSaveStampsId(8L);
        when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.example.test"))
                .thenReturn(Optional.empty());

        resolver.resolveProviderId("GITLAB", "https://gitlab.example.test/sub/path");

        // Save is keyed on the bare origin, not the full base URL.
        when(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.example.test"))
                .thenReturn(Optional.of(stamped(IdentityProviderType.GITLAB, "https://gitlab.example.test", 8L)));
        assertThat(resolver.resolveProviderId("GITLAB", "https://gitlab.example.test/sub/path"))
                .isEqualTo(8L);
    }

    @Test
    void preservesExplicitPort() {
        stubSaveStampsId(9L);
        when(gitProviderRepository.findByTypeAndServerUrl(
                        IdentityProviderType.GITLAB, "https://gitlab.example.test:8443"))
                .thenReturn(Optional.empty());

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
    void providerTypeNameIsUnknownForNull() {
        assertThat(resolver.providerTypeName(null)).isEqualTo("UNKNOWN");
    }

    private static IdentityProvider stamped(IdentityProviderType type, String serverUrl, long id) {
        IdentityProvider p = new IdentityProvider(type, serverUrl);
        setId(p, id);
        return p;
    }

    private static void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not set id via reflection", e);
        }
    }
}

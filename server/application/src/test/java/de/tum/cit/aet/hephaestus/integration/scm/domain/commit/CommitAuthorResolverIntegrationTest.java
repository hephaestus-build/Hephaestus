package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** An email resolves only to what is stored for it on the provider, never to a guess. */
class CommitAuthorResolverIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CommitAuthorResolver resolver;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    private long github;
    private long gitlab;
    private String mark;

    @BeforeEach
    void providers() {
        github = provider(IdentityProviderType.GITHUB, "https://github.com");
        gitlab = provider(IdentityProviderType.GITLAB, "https://gitlab.com");
        mark = UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void shouldResolveAStoredEmailWhateverItsCase() {
        long user = user(gitlab, "login" + mark, "Some Name", mark + "@example.com");

        assertThat(resolver.resolveByEmail(mark.toUpperCase(Locale.ROOT) + "@example.com", gitlab))
                .isEqualTo(user);
    }

    @Test
    void shouldResolveNobodyWhenTheEmailOnlyMatchesAsAPattern() {
        user(gitlab, "login" + mark, "Some Name", mark + "@example.com");

        assertThat(resolver.resolveByEmail(mark.substring(0, 8) + "%@example.com", gitlab))
                .isNull();
    }

    @Test
    void shouldResolveNobodyWhenTwoUsersOfTheProviderHaveTheEmail() {
        String email = mark + "@example.com";
        user(gitlab, "first" + mark, "First", email);
        user(gitlab, "second" + mark, "Second", email);

        assertThat(resolver.resolveByEmail(email, gitlab)).isNull();
    }

    @Test
    void shouldResolveNobodyWhenTheEmailBelongsToAUserOfAnotherProvider() {
        user(github, "login" + mark, "Some Name", mark + "@example.com");

        assertThat(resolver.resolveByEmail(mark + "@example.com", gitlab)).isNull();
    }

    @Test
    void shouldResolveAGitHubNoreplyAddressOnGitHub() {
        long user = user(github, "login" + mark, "Some Name", null);

        assertThat(resolver.resolveByEmail("1+login" + mark + "@users.noreply.github.com", github))
                .isEqualTo(user);
    }

    @Test
    void shouldResolveNobodyWhenAGitHubNoreplyAddressIsOnGitLab() {
        user(gitlab, "login" + mark, "Some Name", null);

        assertThat(resolver.resolveByEmail("login" + mark + "@users.noreply.github.com", gitlab))
                .isNull();
    }

    @Test
    void shouldResolveNobodyWhenOnlyTheLocalPartEqualsALogin() {
        user(gitlab, "login" + mark, "Some Name", null);

        assertThat(resolver.resolveByEmail("login" + mark + "@elsewhere.example", gitlab))
                .isNull();
    }

    @Test
    void shouldResolveNobodyWhenOnlyTheLocalPartEqualsADisplayName() {
        user(gitlab, "login" + mark, "Ada" + mark + " Lovelace", null);

        assertThat(resolver.resolveByEmail("ada" + mark + ".lovelace@elsewhere.example", gitlab))
                .isNull();
    }

    private long provider(IdentityProviderType type, String serverUrl) {
        return Objects.requireNonNull(providers
                .findByTypeAndServerUrl(type, serverUrl)
                .orElseGet(() -> providers.save(new IdentityProvider(type, serverUrl)))
                .getId());
    }

    private long user(long providerId, String login, String name, @Nullable String email) {
        var user = new User();
        user.setNativeId(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE));
        user.setLogin(login);
        user.setName(name);
        if (email != null) user.setEmail(email);
        user.setAvatarUrl("https://example.com/" + login + ".png");
        user.setHtmlUrl("https://example.com/" + login);
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setProvider(providers.findById(providerId).orElseThrow());
        return Objects.requireNonNull(users.save(user).getId());
    }
}

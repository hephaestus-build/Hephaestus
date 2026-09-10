package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test")
public class TestUserConfig {

    @Bean
    public ApplicationRunner seedTestUsers(
            UserRepository userRepository,
            IdentityProviderRepository gitProviderRepository,
            AccountRepository accounts,
            IdentityLinkRepository identities) {
        return args -> {
            IdentityProvider provider = gitProviderRepository
                    .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                    .orElseGet(() -> gitProviderRepository.save(
                            new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
            seed(userRepository, accounts, identities, "testuser", 1, provider);
            seed(userRepository, accounts, identities, "mentor", 2, provider);
            seed(userRepository, accounts, identities, "admin", 3, provider);
        };
    }

    private void seed(
            UserRepository repo,
            AccountRepository accounts,
            IdentityLinkRepository identities,
            String login,
            long userId,
            IdentityProvider provider) {
        var actor = TestUserFactory.ensureUser(repo, login, userId, provider);
        TestUserFactory.ensureAccountForUser(accounts, identities, actor);
    }
}

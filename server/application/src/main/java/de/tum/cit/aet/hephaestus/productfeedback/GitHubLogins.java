package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The github.com login of an account that signs in with GitHub, for the opt-in mention on a public issue. */
@Component
@RequiredArgsConstructor
class GitHubLogins {
    private final AccountIdentityQuery identities;
    private final GitProviderRegistry providers;

    Optional<String> of(long accountId) {
        for (var link : identities.activeLinksForAccount(accountId)) {
            if (!"GITHUB".equals(providers.providerTypeName(link.providerId()).toUpperCase(Locale.ROOT))) continue;
            String server = providers.providerServerUrl(link.providerId());
            if (server == null || !server.toLowerCase(Locale.ROOT).contains("github.com")) continue;
            var key = new GitProviderRegistry.IdentityKey(link.providerId(), link.providerUserKey(), link.teamKey());
            var profile = providers.profiles(List.of(key)).get(key);
            if (profile != null && profile.login() != null) return Optional.of(profile.login());
        }
        return Optional.empty();
    }
}

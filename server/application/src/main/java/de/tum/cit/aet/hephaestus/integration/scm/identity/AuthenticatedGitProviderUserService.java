package de.tum.cit.aet.hephaestus.integration.scm.identity;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery.IdentityView;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthenticatedGitProviderUserService {

    private final UserRepository userRepository;
    private final IdentityProviderLookup identityProviders;
    private final AccountIdentityQuery accountIdentityQuery;

    public AuthenticatedGitProviderUserService(
            UserRepository userRepository,
            IdentityProviderLookup identityProviders,
            AccountIdentityQuery accountIdentityQuery) {
        this.userRepository = userRepository;
        this.identityProviders = identityProviders;
        this.accountIdentityQuery = accountIdentityQuery;
    }

    @Transactional
    public Optional<User> resolveCurrentUser() {
        for (IdentityView link : activeLinksForCurrentAccount()) {
            Optional<IdentityProviderLookup.Provider> provider = identityProviders.findById(link.providerId());
            if (provider.isPresent()
                    && (provider.get().type() == IdentityProviderType.GITHUB
                            || provider.get().type() == IdentityProviderType.GITLAB)) {
                return Optional.of(resolveUser(link));
            }
        }
        return Optional.empty();
    }

    private List<IdentityView> activeLinksForCurrentAccount() {
        return SecurityUtils.getCurrentAccountId()
                .map(accountIdentityQuery::activeLinksForAccount)
                .orElseGet(List::of);
    }

    private User resolveUser(IdentityView link) {
        IdentityProviderLookup.Provider provider = identityProviders
                .findById(link.providerId())
                .orElseThrow(
                        () -> new IllegalStateException(
                                "We could not find the provider for this connected account. Disconnect it in User settings, then connect it again."));
        long nativeId = parseSubject(link.providerUserKey(), provider.type());
        return userRepository
                .findByNativeIdAndProviderId(nativeId, link.providerId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Your provider profile is not available yet. Wait for synchronization, then try again."));
    }

    private static long parseSubject(String subject, IdentityProviderType type) {
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            // A mutable login cannot substitute for the provider's numeric actor id.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Hephaestus cannot use your connected "
                            + type
                            + " account because its ID is not a number. Disconnect it in User settings, under Connected accounts, then connect it again.",
                    e);
        }
    }
}

package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderService;
import de.tum.cit.aet.hephaestus.core.auth.spi.DirectoryIdentitySourceQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class DirectoryIdentitySourceQueryService implements DirectoryIdentitySourceQuery {
    private final LoginProviderService providers;
    private final GitProviderRegistry identities;

    @Override
    @Transactional(readOnly = true)
    public List<Source> approvedSources() {
        return providers.listEnabled().stream()
                .filter(this::approved)
                .map(this::source)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Source> approvedSource(String registrationId) {
        return providers.findEnabled(registrationId).filter(this::approved).map(this::source);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<Source> approvedSourceForUpdate(String registrationId) {
        return providers
                .findEnabledForUpdate(registrationId)
                .filter(this::approved)
                .map(this::source);
    }

    private boolean approved(LoginProvider provider) {
        return provider.getType() == LoginProvider.ProviderType.OIDC
                && !provider.getDirectoryGroupIds().isEmpty();
    }

    private Source source(LoginProvider provider) {
        return new Source(
                provider.getRegistrationId(),
                provider.getDisplayName(),
                provider.getBaseUrl(),
                identities
                        .findProviderId("OIDC", provider.getBaseUrl())
                        .orElseThrow(
                                () -> new IllegalStateException("Approved directory identity provider is missing")),
                Set.copyOf(provider.getDirectoryGroupIds()),
                provider.getUpdatedAt());
    }
}

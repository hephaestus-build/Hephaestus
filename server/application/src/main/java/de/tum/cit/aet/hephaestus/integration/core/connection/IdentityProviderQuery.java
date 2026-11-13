package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@WorkspaceAgnostic("Identity providers describe shared instances; connections bind each workspace")
public class IdentityProviderQuery implements IdentityProviderLookup {
    private final IdentityProviderRepository providers;

    @Override
    public Optional<Provider> findById(long providerId) {
        return providers.findById(providerId).map(IdentityProviderQuery::view);
    }

    @Override
    public Optional<Provider> findByOrigin(IdentityProviderType type, String origin) {
        // Retained provider namespaces may spell the same exact origin differently.
        return providers
                .findByTypeAndServerUrl(type, origin)
                .or(() -> providers.findAllByType(type).stream()
                        .filter(provider ->
                                ScmOrigin.of(provider.getServerUrl()).equals(Optional.of(origin)))
                        .findFirst())
                .map(IdentityProviderQuery::view);
    }

    private static Provider view(IdentityProvider provider) {
        return new Provider(Objects.requireNonNull(provider.getId()), provider.getType(), provider.getServerUrl());
    }

    @Override
    public Optional<Long> findId(IdentityProviderType type, String serverUrl) {
        return providers.findByTypeAndServerUrl(type, serverUrl).map(IdentityProvider::getId);
    }
}

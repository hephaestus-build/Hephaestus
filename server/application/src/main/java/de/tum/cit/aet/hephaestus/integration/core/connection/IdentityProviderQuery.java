package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
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
        return providers
                .findById(providerId)
                .map(provider -> new Provider(
                        Objects.requireNonNull(provider.getId()), provider.getType(), provider.getServerUrl()));
    }
}

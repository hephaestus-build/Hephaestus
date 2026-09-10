package de.tum.cit.aet.hephaestus.core.auth.provider;

import de.tum.cit.aet.hephaestus.core.auth.spi.LoginProviderQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class LoginProviderQueryAdapter implements LoginProviderQuery {
    private final LoginProviderService providers;

    @Override
    @Transactional(readOnly = true)
    public Optional<Provider> findEnabled(String registrationId) {
        return providers.listEnabled().stream()
                .filter(provider -> provider.getRegistrationId().equals(registrationId))
                .findFirst()
                .map(provider -> new Provider(
                        provider.getRegistrationId(),
                        provider.getDisplayName(),
                        provider.getType().name(),
                        provider.getBaseUrl()));
    }
}

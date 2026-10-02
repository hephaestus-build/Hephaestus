package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class LlmSettingsAccountErasureAdapter implements AccountErasureContributor {
    private final InstanceLlmSettingsRepository repository;

    @Override
    public void eraseAccount(long accountId) {
        repository.clearPersonAttribution(accountId);
    }
}

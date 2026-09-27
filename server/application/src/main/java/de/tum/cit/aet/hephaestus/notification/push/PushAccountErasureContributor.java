package de.tum.cit.aet.hephaestus.notification.push;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import org.springframework.stereotype.Component;

/** Account erasure removes the account's push devices; their queued notifications go with them. */
@Component
class PushAccountErasureContributor implements AccountErasureContributor {

    private final PushDeviceRepository deviceRepository;

    PushAccountErasureContributor(PushDeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Override
    public void eraseAccount(long accountId) {
        deviceRepository.deleteByAccountId(accountId);
    }
}

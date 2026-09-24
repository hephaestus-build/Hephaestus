package de.tum.cit.aet.hephaestus.notification.preferences;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class NotificationAccountErasureAdapter implements AccountErasureContributor {
    private final NotificationSubscriptionRepository subscriptions;

    @Override
    @Transactional
    public void eraseAccount(long accountId) {
        subscriptions.deleteAllByAccountId(accountId);
    }
}

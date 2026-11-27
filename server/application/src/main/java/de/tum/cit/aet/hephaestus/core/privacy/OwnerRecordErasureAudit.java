package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataErasureAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class OwnerRecordErasureAudit implements PersonDataErasureAudit {
    private final AuthEventLogger logger;

    @Override
    public void requestedByOwner(long accountId) {
        TransactionCallbacks.afterCommit(() -> logger.event(AuthEvent.EventType.PERSON_DATA_ERASURE_REQUESTED,
                        AuthEvent.Result.SUCCESS)
                .account(accountId)
                .actingAccount(accountId)
                .record());
    }
}

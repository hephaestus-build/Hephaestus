package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Records security outcomes and queues notices without credential material. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PasskeySecurityEvents {
    private final AuthEventLogger audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public void changed(Account account, String operation) {
        Long id = Objects.requireNonNull(account.getId());
        events.publishEvent(new PasskeySuccess(id, AuthEvent.EventType.PASSKEY_CHANGED, operation));
        events.publishEvent(
                new AccountSecurityChangedEvent(id, AccountSecurityChangedEvent.Kind.PASSKEY_CHANGED, clock.instant()));
    }

    public void verified(Long accountId) {
        events.publishEvent(new PasskeySuccess(accountId, AuthEvent.EventType.PASSKEY_VERIFIED, "verified"));
    }

    public void recoveryRejected(Long accountId) {
        events.publishEvent(
                new PasskeyFailure(accountId, AuthEvent.EventType.PASSKEY_CHANGED, "invalid_recovery_code"));
    }

    public void required(Long accountId) {
        events.publishEvent(new PasskeyFailure(accountId, AuthEvent.EventType.PASSKEY_VERIFIED, "passkey_required"));
    }

    record PasskeySuccess(Long accountId, AuthEvent.EventType type, String operation) {}

    record PasskeyFailure(Long accountId, AuthEvent.EventType type, String reason) {}

    // The account write lock must end before the audit writer opens its independent transaction.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void success(PasskeySuccess event) {
        audit.event(event.type(), AuthEvent.Result.SUCCESS)
                .account(event.accountId())
                .details("{\"passkey_operation\":\"" + event.operation() + "\"}")
                .record();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
    void failure(PasskeyFailure event) {
        audit.event(event.type(), AuthEvent.Result.FAILURE)
                .account(event.accountId())
                .failureReason(event.reason())
                .record();
    }
}

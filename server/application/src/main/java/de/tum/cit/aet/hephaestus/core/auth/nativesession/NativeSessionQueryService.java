package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.consent.ConsentService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.NativeSessionQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Native sessions and consent are account-scoped")
class NativeSessionQueryService implements NativeSessionQuery {

    private final NativeSessionRepository sessionRepository;
    private final IssuedJwtRepository issuedJwtRepository;
    private final AccountRepository accountRepository;
    private final ConsentService consentService;
    private final Clock clock;

    NativeSessionQueryService(
            NativeSessionRepository sessionRepository,
            IssuedJwtRepository issuedJwtRepository,
            AccountRepository accountRepository,
            ConsentService consentService,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.issuedJwtRepository = issuedJwtRepository;
        this.accountRepository = accountRepository;
        this.consentService = consentService;
        this.clock = clock;
    }

    /** The same verdict a refresh reaches, plus consent, without rotating anything. */
    @Override
    @Transactional(readOnly = true)
    public boolean isSignedIn(UUID nativeSessionId, long accountId) {
        Instant now = clock.instant();
        NativeSession session = sessionRepository.findById(nativeSessionId).orElse(null);
        if (session == null
                || session.getAccountId() != accountId
                || session.getRevokedAt() != null
                || !now.isBefore(session.getSessionExpiresAt())) {
            return false;
        }
        boolean backingTokenLive = issuedJwtRepository
                .findById(session.getCurrentJti())
                .map(token -> token.getRevokedAt() == null)
                .orElse(false);
        boolean active = accountRepository
                .findById(accountId)
                .map(account -> account.getStatus() == Account.Status.ACTIVE)
                .orElse(false);
        return backingTokenLive && active && consentService.hasCompletedCurrentNotice(accountId);
    }
}

package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.ControlledClock;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSessionRepository;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInHandoffRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.mcp.McpClientGrantRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSessionRevocationContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one account-wide revocation: sign out everywhere, an administrator's forced sign-out, account
 * deletion and role demotion all end here.
 *
 * <p>It write-locks the account row before touching anything else. Every token issuance share-locks
 * that row first, so an issuance either commits before the revocation's statements run — which then see
 * its new token and revoke it — or waits and sees the revocation. Pending sign-in handoffs are burned,
 * installed-client sessions are ended, and issued tokens are revoked in the same transaction. Each
 * {@link AccountSessionRevocationContributor} ends its own credentials there too, also when the caller keeps its own
 * session: a host's team grant is never the caller's session (ADR 0166).
 */
@ConditionalOnServerRole
@Service
public class SessionRevocation {

    private final PrivacyJournal journal;
    private final AccountRepository accountRepository;
    private final ClientSessionRepository clientSessionRepository;
    private final ClientSignInHandoffRepository handoffRepository;
    private final IssuedJwtRepository issuedJwtRepository;
    private final McpClientGrantRepository grantRepository;
    private final List<AccountSessionRevocationContributor> contributors;
    private final Clock clock;

    public SessionRevocation(
            AccountRepository accountRepository,
            ClientSessionRepository clientSessionRepository,
            ClientSignInHandoffRepository handoffRepository,
            IssuedJwtRepository issuedJwtRepository,
            McpClientGrantRepository grantRepository,
            List<AccountSessionRevocationContributor> contributors,
            Clock clock,
            PrivacyJournal journal) {
        this.journal = journal;
        this.accountRepository = accountRepository;
        this.clientSessionRepository = clientSessionRepository;
        this.handoffRepository = handoffRepository;
        this.issuedJwtRepository = issuedJwtRepository;
        this.grantRepository = grantRepository;
        this.contributors = contributors;
        this.clock = ControlledClock.operationalClock(clock);
    }

    /**
     * Write-locks the account row for the caller's transaction. A caller that changes the account's
     * standing (deletion, demotion) takes it before reading the account, so the change and the
     * revocation that follows are one step to every concurrent issuance.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAccount(Long accountId) {
        accountRepository.lockStatusForUpdate(accountId);
    }

    /**
     * Revokes every session of the account.
     *
     * @param exceptJti       a browser token to keep (the caller's own), or null
     * @param exceptSessionId an installed-client session to keep whole (the caller's own), or null
     * @return the number of issued tokens revoked
     */
    @Transactional
    public int revokeAccount(
            Long accountId, IssuedJwt.RevokedReason reason, @Nullable UUID exceptJti, @Nullable UUID exceptSessionId) {
        accountRepository.lockStatusForUpdate(accountId);
        Instant now = clock.instant();
        if (reason != IssuedJwt.RevokedReason.ACCOUNT_DELETED) {
            Map<String, Object> refs = new HashMap<>();
            refs.put("accountId", accountId);
            refs.put("through", now);
            refs.put("target", "ACCOUNT");
            if (exceptJti != null) refs.put("keepTokenId", exceptJti);
            if (exceptSessionId != null) refs.put("keepSessionId", exceptSessionId);
            journal.record(PrivacyJournalKind.SESSION_REVOKED, refs);
        }
        handoffRepository.consumeAllForAccount(accountId, now);
        for (AccountSessionRevocationContributor contributor : contributors) contributor.revokeAll(accountId, now);
        if (exceptSessionId != null) {
            grantRepository.revokeAllForAccountExceptSession(accountId, exceptSessionId, now);
            clientSessionRepository.endAllForAccountExcept(accountId, exceptSessionId, now, reason);
            return issuedJwtRepository.revokeAllForAccountExceptSession(accountId, exceptSessionId, now, reason);
        }
        grantRepository.revokeAllForAccount(accountId, now);
        clientSessionRepository.endAllForAccount(accountId, now, reason);
        if (exceptJti != null) {
            return issuedJwtRepository.revokeAllForAccountExcept(accountId, exceptJti, now, reason);
        }
        return issuedJwtRepository.revokeAllForAccount(accountId, now, reason);
    }
}

package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.auth.AuthSessionService;
import de.tum.cit.aet.hephaestus.core.auth.SessionRevocation;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates account locks, session revocation, and assurance-preserving token rotation. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PasskeySessionService {
    private final AccountRepository accounts;
    private final SessionRevocation revocation;
    private final IssuedJwtRepository tokens;
    private final HephaestusJwtIssuer issuer;
    private final AuthSessionService sessions;
    private final Clock clock;

    public Account lock(Long accountId) {
        if (CurrentAccount.sessionIdOrNull() != null) {
            throw new PasskeyRequiredException();
        }
        revocation.lockAccount(accountId);
        if (tokens.findActive(CurrentAccount.requireJti(), clock.instant()).isEmpty()) {
            throw new PasskeyRequiredException();
        }
        Account account = accounts.findById(accountId).orElseThrow(PasskeyRequiredException::new);
        if (account.getStatus() != Account.Status.ACTIVE) {
            throw new PasskeyRequiredException();
        }
        return account;
    }

    public void rotate(
            Long accountId,
            @Nullable Instant passkeyTime,
            HttpServletRequest request,
            HttpServletResponse response,
            boolean all) {
        Instant ceiling = CurrentAccount.sessionExpiresAt();
        if (ceiling == null || !ceiling.isAfter(clock.instant())) {
            throw new PasskeyRequiredException();
        }
        if (all) {
            revocation.revokeAccount(accountId, IssuedJwt.RevokedReason.SELF_REVOKE, null, null);
        } else if (tokens.revoke(CurrentAccount.requireJti(), clock.instant(), IssuedJwt.RevokedReason.ROTATE) != 1) {
            throw new PasskeyRequiredException();
        }
        sessions.setCookie(
                response,
                issuer.issue(
                        accountId,
                        new TokenConstraints(ceiling, CurrentAccount.authTime(), null, passkeyTime),
                        request));
    }

    public void revokeOthers(Long accountId) {
        revocation.revokeAccount(accountId, IssuedJwt.RevokedReason.SELF_REVOKE, CurrentAccount.requireJti(), null);
    }
}

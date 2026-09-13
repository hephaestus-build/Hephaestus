package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * One-time break-glass bootstrap of the very first instance super-admin ({@code APP_ADMIN}).
 *
 * <p>Gated by {@code hephaestus.auth.bootstrap-token}: when blank the path is disabled and
 * {@code POST /auth/bootstrap-admin} 404s (invisible). When set, an already-authenticated caller who
 * presents the matching token is promoted to {@code APP_ADMIN} — but ONLY while no active admin
 * exists, enforced atomically in Postgres by {@link AccountRepository#promoteToFirstAdminIfNoneExists}
 * so it self-disables the instant a real admin appears (no in-JVM count → no cross-pod TOCTOU). The
 * token is the proof-of-control (deployment access); it is compared in constant time and never logged.
 *
 * <p>This is the safety net for {@code hephaestus.auth.bootstrap-admins} (the preferred allowlist) —
 * it covers the case where the operator cannot predict an identity ahead of time, and guards against
 * zero-admin lockout.
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Instance-admin bootstrap is account-scoped; not workspace-scoped")
public class AccountBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AccountBootstrapService.class);

    private final AccountRepository accountRepository;
    private final AuthEventLogger authEventLogger;
    private final String configuredToken;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public AccountBootstrapService(
            AccountRepository accountRepository,
            AuthEventLogger authEventLogger,
            AuthProperties authProperties,
            Clock clock,
            ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.authEventLogger = authEventLogger;
        this.configuredToken = authProperties.bootstrapToken();
        if (enabled()) {
            log.warn("auth.bootstrap: break-glass token endpoint POST /auth/bootstrap-admin is ENABLED "
                    + "(hephaestus.auth.bootstrap-token set). It self-disables once an admin exists; "
                    + "unset it after bootstrapping.");
        }
    }

    private boolean enabled() {
        return configuredToken != null && !configuredToken.isBlank();
    }

    /**
     * Promote {@code accountId} to the first {@code APP_ADMIN} if {@code presentedToken} matches and
     * no active admin exists yet.
     *
     * @throws ResponseStatusException 404 if the token is not configured (endpoint disabled), 403 on
     *     token mismatch, 409 if an admin already exists (self-disabled) or the caller is already admin.
     */
    @Transactional
    public void bootstrapFirstAdmin(Long accountId, String presentedToken) {
        if (!enabled()) {
            // Invisible when disabled — do not reveal the endpoint exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (presentedToken == null || !constantTimeEquals(configuredToken, presentedToken)) {
            log.warn("auth.bootstrap: rejected bootstrap-admin attempt by accountId={} — bad token", accountId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid bootstrap token");
        }
        int promoted = accountRepository.promoteToFirstAdminIfNoneExists(accountId);
        if (promoted == 0) {
            // Either an APP_ADMIN already exists (self-disabled) or this account is already one.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "bootstrap unavailable: an administrator already exists");
        }
        authEventLogger
                .event(AuthEvent.EventType.APP_ROLE_CHANGED, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .actingAccount(accountId)
                .details("{\"from\":\"USER\",\"to\":\"APP_ADMIN\",\"via\":\"bootstrap-token\"}")
                .record();
        eventPublisher.publishEvent(new AccountSecurityChangedEvent(
                accountId, AccountSecurityChangedEvent.Kind.APP_ROLE_CHANGED, clock.instant()));
        log.warn("auth.bootstrap: accountId={} self-promoted to APP_ADMIN via break-glass token", accountId);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}

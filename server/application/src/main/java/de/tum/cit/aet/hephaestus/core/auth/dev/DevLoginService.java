package de.tum.cit.aet.hephaestus.core.auth.dev;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Passwordless dev/test sign-in. Resolves (or just-in-time creates) a local {@link Account} and mints
 * the <em>same</em> cookie-JWT the OAuth success path does ({@link JwtPrincipalFactory#forAccountId} →
 * {@link HephaestusJwtIssuer#issue}, so the token is {@code issued_jwt}-backed and revocable like any
 * real session), letting local dev and live E2E authenticate without an OAuth IdP. The account has no
 * SCM identity; workspace access for a dev {@code APP_ADMIN} comes from the super-admin elevation in
 * {@code WorkspaceContextFilter}.
 *
 * <h2>Fail-closed in production</h2>
 * Gated by {@code hephaestus.auth.dev-login-enabled} (default {@code false} → the endpoint 404s, invisible).
 * Enabling it under the {@code prod} profile is a <strong>startup failure</strong> — a passwordless login
 * must be impossible to switch on in production (mirrors {@code JwtSigningKeySealer}'s blank-key guard).
 */
@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Dev sign-in mints an account-scoped session; not workspace-scoped")
public class DevLoginService {

    private static final Logger log = LoggerFactory.getLogger(DevLoginService.class);

    /** Scopes dev accounts; keeps repeat logins idempotent. */
    private static final String DEV_EMAIL_DOMAIN = "@dev.invalid";

    private final boolean enabled;
    private final AccountRepository accountRepository;
    private final JwtPrincipalFactory principalFactory;
    private final HephaestusJwtIssuer jwtIssuer;
    private final Clock clock;
    private final Duration sessionMaxLifetime;

    public DevLoginService(
            AuthProperties authProperties,
            AccountRepository accountRepository,
            JwtPrincipalFactory principalFactory,
            HephaestusJwtIssuer jwtIssuer,
            Clock clock,
            Environment environment) {
        this.enabled = authProperties.devLoginEnabled();
        this.accountRepository = accountRepository;
        this.principalFactory = principalFactory;
        this.jwtIssuer = jwtIssuer;
        this.clock = clock;
        this.sessionMaxLifetime = authProperties.sessionMaxLifetime();

        // acceptsProfiles (not a raw spring.profiles.active string-split) so the guard also fires when
        // prod is activated via a deploy-role GROUP alias — webhook-server/worker-node expand to include
        // prod (application.yml). The same idiom guards JwtSigningKeyService / AuthSecurityConfig.
        if (enabled && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "hephaestus.auth.dev-login-enabled must NOT be true under the 'prod' profile — "
                            + "a passwordless sign-in is fail-closed in production.");
        }
        if (enabled) {
            log.warn("auth.dev-login: PASSWORDLESS dev sign-in POST /auth/dev-login is ENABLED "
                    + "(hephaestus.auth.dev-login-enabled=true). For local dev / E2E only — NEVER on an "
                    + "internet-exposed deployment.");
        }
    }

    /** Whether the dev sign-in is operational; drives both the endpoint guard and SPA discovery. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Resolve-or-create the dev account for {@code username} and mint a session token for it.
     *
     * @param username    stable key for the dev account (synthetic email {@code <username>@dev.invalid})
     * @param displayName optional human label; defaults to {@code username}
     * @param admin       when {@code true}, create/promote the account to {@code APP_ADMIN} (promote-only)
     * @param request     the inbound request, for issuer audit (IP / user-agent)
     * @return the minted cookie-JWT the caller sets via {@code AuthSessionService.setCookie}
     * @throws ResponseStatusException 404 when the dev sign-in is disabled (invisible)
     */
    @Transactional
    public HephaestusJwtIssuer.Token devLogin(
            String username, @Nullable String displayName, boolean admin, @Nullable HttpServletRequest request) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String email = username.trim().toLowerCase(Locale.ROOT) + DEV_EMAIL_DOMAIN;
        Account account = accountRepository.findByPrimaryEmail(email).orElseGet(() -> {
            Account fresh = new Account(displayName != null && !displayName.isBlank() ? displayName : username);
            fresh.setPrimaryEmail(email);
            fresh.setAppRole(admin ? Account.AppRole.APP_ADMIN : Account.AppRole.USER);
            return accountRepository.save(fresh);
        });
        if (admin && account.getAppRole() != Account.AppRole.APP_ADMIN) {
            account.setAppRole(Account.AppRole.APP_ADMIN);
            account = accountRepository.save(account);
        }
        log.info("auth.dev-login: signed in dev account id={} login={} admin={}", account.getId(), username, admin);
        // Parity with the OAuth success path: stamp the same absolute session ceiling so a dev session
        // can't be silently kept alive past sessionMaxLifetime by the rolling refresh (OWASP absolute
        // timeout). Reuses the identical issuer seam, so the token stays issued_jwt-backed and revocable.
        Instant now = clock.instant();
        return jwtIssuer.issue(
                principalFactory.forAccountId(Objects.requireNonNull(account.getId())),
                TokenConstraints.session(now.plus(sessionMaxLifetime), now),
                request);
    }
}

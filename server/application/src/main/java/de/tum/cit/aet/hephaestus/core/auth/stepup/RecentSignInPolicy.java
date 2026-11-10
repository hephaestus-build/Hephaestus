package de.tum.cit.aet.hephaestus.core.auth.stepup;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AllRequiredFactorsAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Decides whether a caller signed in recently enough for a high-risk instance-admin action, and records
 * the refusal when they did not.
 *
 * <p>The decision itself is Spring Security's: the session's {@code auth_time} rides on the
 * {@link org.springframework.security.core.authority.FactorGrantedAuthority} minted in
 * {@code SecurityConfig}, and {@link AllRequiredFactorsAuthorizationManager} compares it against the
 * configured window. That comparison grants anything not yet expired, so a session stamped by a pod
 * whose clock leads this one is still fresh — a local clock skew must never tell an administrator to
 * sign in again.
 *
 * <p>This is a re-authentication gate, not a second factor: the upstream IdP decides whether it
 * challenges for anything, and {@code docs/contributor/instance-admin.md} states what the gate does and
 * does not stop.
 */
@ConditionalOnServerRole
@Service
public class RecentSignInPolicy {
    private static final Logger log = LoggerFactory.getLogger(RecentSignInPolicy.class);

    /** The factor decision reads only the authentication, but the manager contract still wants a subject. */
    private static final Object NO_SUBJECT = new Object();

    private final AuthorizationManager<Object> recentSignIn;
    private final AuthEventLogger authEventLogger;
    private final AuthMetrics metrics;
    private final Duration maxAge;
    private final boolean assuranceEnforced;

    public RecentSignInPolicy(
            AuthProperties properties, AuthEventLogger authEventLogger, AuthMetrics metrics, Clock clock) {
        this.authEventLogger = authEventLogger;
        this.metrics = metrics;
        this.maxAge = properties.stepUpMaxAge();
        this.assuranceEnforced = properties.assuranceEnforced();
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalStateException("hephaestus.auth.step-up-max-age must be a positive duration");
        }
        if (maxAge.compareTo(properties.sessionMaxLifetime()) >= 0) {
            log.warn(
                    "auth.step-up: step-up-max-age ({}) >= session-max-lifetime ({}) — the gate is inert: no session"
                            + " can outlive its ceiling, so a sign-in can never be older than the window.",
                    maxAge,
                    properties.sessionMaxLifetime());
        }
        AllRequiredFactorsAuthorizationManager<Object> manager =
                AllRequiredFactorsAuthorizationManager.<Object>builder()
                        .requireFactor(
                                factor -> factor.authorizationCodeAuthority().validDuration(maxAge))
                        .build();
        manager.setClock(clock);
        AllRequiredFactorsAuthorizationManager<Object> passkey =
                AllRequiredFactorsAuthorizationManager.<Object>builder()
                        .requireFactor(factor -> factor.webauthnAuthority().validDuration(maxAge))
                        .build();
        passkey.setClock(clock);
        this.recentSignIn = AllRequiredFactorsAuthorizationManager.anyOf(manager, passkey);
    }

    /** Whether {@code authentication} carries a sign-in or passkey factor younger than the window. */
    public boolean isRecent(@Nullable Authentication authentication) {
        AuthorizationResult result = recentSignIn.authorize(() -> authentication, NO_SUBJECT);
        return result != null && result.isGranted();
    }

    /**
     * Let {@code authentication} through, or record the refusal and throw {@link StepUpRequiredException}.
     *
     * @param auditType       the attempted action's event type — the refusal is recorded on it as FAILURE.
     * @param actingAccountId the account whose session was refused.
     */
    public void require(
            @Nullable Authentication authentication, AuthEvent.EventType auditType, @Nullable Long actingAccountId) {
        require(authentication, auditType, actingAccountId, null);
    }

    /**
     * As {@link #require(Authentication, AuthEvent.EventType, Long)}, for an action that a workspace already
     * authorized: the refusal also names that workspace.
     */
    public void require(
            @Nullable Authentication authentication,
            AuthEvent.EventType auditType,
            @Nullable Long actingAccountId,
            @Nullable Long workspaceId) {
        if ((!assuranceEnforced
                        && authentication != null
                        && authentication.isAuthenticated()
                        && !(authentication instanceof AnonymousAuthenticationToken))
                || isRecent(authentication)) {
            return;
        }
        log.info("auth.step-up: refused {} for actingAccountId={} (max-age={})", auditType, actingAccountId, maxAge);
        // An interceptor refuses before the action's own arguments are validated, so its row names only the
        // session that was refused; claiming a target this request never reached would be a wrong claim. A
        // workspace that already authorized the action passes its own id.
        authEventLogger
                .event(auditType, AuthEvent.Result.FAILURE)
                .account(actingAccountId)
                .actingAccount(actingAccountId)
                .workspace(workspaceId)
                .failureReason(StepUpRequiredException.CODE)
                .record();
        metrics.recordStepUpDenied(auditType.name().toLowerCase(Locale.ROOT));
        throw new StepUpRequiredException(maxAge);
    }
}

package de.tum.cit.aet.hephaestus.core.auth.metrics;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Micrometer instruments for the auth module (ADR 0017 hardening — the A→A+ observability gap).
 *
 * <p>Naming follows the rest of the codebase ({@code mentor.turn.*}, {@code agent.job.*}): dot-case
 * Java identifiers that Micrometer's Prometheus renderer maps to {@code auth_login_total{result=…}}
 * etc. Tag values are a fixed enum so cardinality stays bounded.
 *
 * <h2>Where each instrument fires (and why <em>not</em> a global event listener)</h2>
 * Spring Security's resource-server {@code BearerTokenAuthenticationFilter} publishes an
 * {@code AuthenticationSuccessEvent} on <strong>every</strong> cookie-JWT-bearing API request — so a
 * listener on that event would count API traffic, not logins. The interactive oauth2Login filter
 * (an {@code AbstractAuthenticationProcessingFilter}) instead publishes
 * {@code InteractiveAuthenticationSuccessEvent} on success and an
 * {@code AbstractAuthenticationFailureEvent} on a failed IdP exchange, and the bearer filter does
 * <em>not</em>. {@code AuthLoginEventMetrics} listens for exactly those two — the precise, login-only
 * signal — and increments:
 * <ul>
 *   <li>{@code auth.login{result=success}} — interactive OAuth login completed.</li>
 *   <li>{@code auth.login{result=failure}} — the oauth2Login IdP exchange failed.</li>
 * </ul>
 * {@code auth.ratelimit.blocked{bucket=…}} is incremented on the 429 path of
 * {@code AuthRateLimitFilter}; the bucket namespace ({@code oauth-authz}, {@code refresh}, …) is a
 * bounded tag. {@code auth.token.refresh} times the token-rotation critical section in
 * {@code AuthSessionService.refresh}.
 */
@ConditionalOnServerRole
@Component
public class AuthMetrics {

    public enum LoginResult {
        SUCCESS("success"),
        FAILURE("failure");

        private final String tag;

        LoginResult(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /**
     * Terminal outcome tagged on {@code auth.token.refresh.result}. The {@code auth.token.refresh}
     * Timer alone wraps the whole method, so a {@code revoked==0} rotation race and a SUSPENDED /
     * DELETING / DELETED early-return are indistinguishable from a real re-mint. This split lets an
     * operator alert on an abnormal {@code noop} (token-reuse / replay) or {@code suspended} rate
     * without inferring it from latency. Fixed set → bounded cardinality.
     */
    public enum RefreshResult {
        SUCCESS("success"),
        NOOP("noop"),
        SUSPENDED("suspended"),
        ERROR("error");

        private final String tag;

        RefreshResult(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final MeterRegistry registry;
    private final Counter loginSuccess;
    private final Counter loginFailure;
    private final Timer tokenRefresh;
    private final Counter revocationCheckFailed;
    private final Counter rateLimitBackendError;
    private final Counter auditWriteFailed;

    public AuthMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.loginSuccess = login(registry, LoginResult.SUCCESS);
        this.loginFailure = login(registry, LoginResult.FAILURE);
        this.tokenRefresh = Timer.builder(CoreMetrics.AUTH_TOKEN_REFRESH)
                .description("Wall-clock latency of the access-token rotation in AuthSessionService.refresh.")
                .publishPercentileHistogram()
                .register(registry);
        this.revocationCheckFailed = Counter.builder(CoreMetrics.AUTH_REVOCATION_CHECK_FAILED)
                .description("Fail-closed revocation lookups in RevocationAwareJwtDecoder (DB unreachable → 401). "
                        + "A spike means a DB outage is mass-rejecting otherwise-valid cookie-JWTs.")
                .register(registry);
        this.rateLimitBackendError = Counter.builder(CoreMetrics.AUTH_RATELIMIT_BACKEND_ERROR)
                .description(
                        "Rate-limit bucket-store failures. Authentication fails open; costly operations fail closed.")
                .register(registry);
        this.auditWriteFailed = Counter.builder(CoreMetrics.AUTH_AUDIT_WRITE_FAILED)
                .description(
                        "Auth-audit persist failures in AuthEventWriter (the sequence value was already consumed → "
                                + "a permanent gap in auth_event.id). Swallowed so audit never breaks the request, but a "
                                + "nonzero rate means the tamper-evident trail is silently losing events.")
                .register(registry);
    }

    private static Counter login(MeterRegistry registry, LoginResult result) {
        return Counter.builder(CoreMetrics.AUTH_LOGIN)
                .description("Interactive OAuth logins, tagged by terminal result.")
                .tag("result", result.tag())
                .register(registry);
    }

    public void recordLogin(LoginResult result) {
        switch (result) {
            case SUCCESS -> loginSuccess.increment();
            case FAILURE -> loginFailure.increment();
        }
    }

    /**
     * Count one rate-limit 429. {@code bucket} is the endpoint namespace
     * ({@code oauth-authz}/{@code refresh}/{@code impersonate}/{@code delete-user}) — bounded, no PII.
     * The per-(bucket) Counter is registered lazily; the namespace set is tiny so this is effectively
     * a fixed handful of meters.
     */
    public void recordRateLimitBlocked(String bucket) {
        Counter.builder(CoreMetrics.AUTH_RATELIMIT_BLOCKED)
                .description("Requests rejected with HTTP 429 by the rate limiter, tagged by bucket namespace.")
                .tag("bucket", bucket)
                .register(registry)
                .increment();
    }

    public Timer.Sample startRefreshTimer() {
        return Timer.start(registry);
    }

    public void stopRefreshTimer(Timer.Sample sample) {
        sample.stop(tokenRefresh);
    }

    /**
     * Count one token-refresh by its terminal {@code result} ({@code success}/{@code noop}/
     * {@code suspended}). Lazily-registered per-result Counter; the result set is a fixed enum so
     * this is a fixed handful of meters.
     */
    public void recordRefreshResult(RefreshResult result) {
        Counter.builder(CoreMetrics.AUTH_TOKEN_REFRESH_RESULT)
                .description("Token-refresh attempts in AuthSessionService.refresh, tagged by terminal result.")
                .tag("result", result.tag())
                .register(registry)
                .increment();
    }

    /**
     * Count one fail-closed revocation lookup ({@code RevocationAwareJwtDecoder} could not reach the
     * DB and surfaced a 401). Makes a DB-outage mass-401 observable instead of only logged.
     */
    public void recordRevocationCheckFailed() {
        revocationCheckFailed.increment();
    }

    /**
     * Count one rate-limit backend failure ({@code AuthRateLimitFilter} could not reach the bucket
     * store). Makes a degraded limiter observable.
     */
    public void recordRateLimitBackendError() {
        rateLimitBackendError.increment();
    }

    /**
     * Count one swallowed auth-audit persist failure ({@code AuthEventWriter} could not save an
     * {@code AuthEvent} after the id sequence was already advanced). Makes the otherwise-silent gap in
     * the append-only trail alertable.
     */
    public void recordAuditWriteFailed() {
        auditWriteFailed.increment();
    }

    /**
     * Count one instance-admin action refused for want of a recent sign-in. {@code action} is the audit
     * event type, a fixed enum, so cardinality stays bounded. A steady low rate is the gate working as
     * designed — administrators are prompted; only a spike on one action is worth looking at.
     */
    public void recordStepUpDenied(String action) {
        Counter.builder(CoreMetrics.AUTH_STEP_UP_DENIED)
                .description("Instance-admin actions refused for want of a recent sign-in, tagged by action.")
                .tag("action", action)
                .register(registry)
                .increment();
    }
}

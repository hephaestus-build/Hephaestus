package de.tum.cit.aet.hephaestus.core.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Centralises the HTTP security-header set so every user-facing filter chain (the resource-server
 * chain in {@code SecurityConfig} and the oauth2Login chain in {@code AuthSecurityConfig}) emits an
 * identical, complete set. Single source of truth — change the policy here, not per chain.
 *
 * <p>Headers applied:
 * <ul>
 *   <li>{@code Strict-Transport-Security} — 30 days, includeSubDomains. See {@link #HSTS_MAX_AGE_SECONDS}.</li>
 *   <li>{@code X-Frame-Options: DENY} + {@code X-Content-Type-Options: nosniff} (existing, preserved).</li>
 *   <li>{@code Content-Security-Policy} — enforced (not report-only) and fully instance-agnostic: it
 *       contains NO hardcoded partner hosts. See {@link #CONTENT_SECURITY_POLICY}.</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin}.</li>
 *   <li>{@code Cross-Origin-Opener-Policy: same-origin}.</li>
 *   <li>{@code Cross-Origin-Embedder-Policy: credentialless} — written via a static writer because
 *       the Spring Security {@code CrossOriginEmbedderPolicy} enum on the classpath only models
 *       {@code unsafe-none} / {@code require-corp}, not {@code credentialless}.</li>
 * </ul>
 */
public final class SecurityHeaders {

    private SecurityHeaders() {}

    /**
     * 30 days: the policy that browsers already keep, because the chart's route set it on every response. This class is
     * the home of HSTS for server responses (ADR 0100). Until the server also serves the web app, the route sets the
     * same value on the web app's rules only. A longer policy cannot be withdrawn before it expires, so raising it is a
     * decision of its own.
     */
    private static final long HSTS_MAX_AGE_SECONDS = 2_592_000;

    /**
     * Enforced CSP for the server-rendered surface this header set covers (Swagger UI, {@code /auth/error},
     * RFC-7807 error pages). It is deliberately INSTANCE-AGNOSTIC — no partner hosts — because each
     * directive is keyed to what OUR origin may do, not to an integration allowlist:
     * <ul>
     *   <li>{@code script-src 'self'} (no {@code 'unsafe-inline'}) is the actual XSS control; Swagger's
     *       bundled JS loads from {@code 'self'}, so enforcement is safe. {@code style-src} keeps
     *       {@code 'unsafe-inline'} because Swagger injects inline <em>styles</em> only.</li>
     *   <li>{@code img-src 'self' data: https:} — images cannot execute, and SCM avatars render on the
     *       separate SPA origin, not here; allowing any HTTPS image avoids enumerating per-instance avatar
     *       hosts (the old {@code gitlab.lrz.de}/{@code *.gitlab.com} list) while still blocking
     *       plaintext-HTTP. </li>
     *   <li>{@code form-action 'self'} — this restricts HTML <em>form submission</em> targets only. OAuth
     *       login is a server {@code 302} redirect (a navigation), not a form post, so the IdP host is not
     *       needed here; including it was dead weight that also leaked the instance.</li>
     * </ul>
     * Because the policy is a compile-time constant with zero configurable hosts, it never reads the
     * {@code login_provider} table — satisfying the no-DB-at-context-refresh constraint by construction.
     */
    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; " + "script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: https:; "
            + "connect-src 'self'; "
            + "frame-ancestors 'none'; "
            + "base-uri 'self'; "
            + "form-action 'self'";

    /** Applies the full header set to the given {@link HttpSecurity}. */
    public static void apply(HttpSecurity http) throws Exception {
        http.headers(headers -> headers.frameOptions(frameOptions -> frameOptions.deny())
                .contentTypeOptions(contentType -> {})
                .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS))
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .crossOriginOpenerPolicy(coop -> coop.policy(CrossOriginOpenerPolicy.SAME_ORIGIN))
                // COEP credentialless: the builder enum lacks this value on the current Spring
                // Security version, so emit it directly.
                .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Embedder-Policy", "credentialless")));
    }
}

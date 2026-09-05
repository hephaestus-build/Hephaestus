package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Runtime configuration for the core.auth module.
 *
 * <p>All knobs that vary by environment live here. Defaults are tuned for production;
 * integration tests override via {@code @TestPropertySource}.
 *
 * @param issuer          Canonical issuer URI; populates the {@code iss} claim.
 * @param apiBasePath     Public path prefix the reverse proxy strips before requests reach this app
 *                        (e.g. {@code /api} when Traefik strips {@code /api}); empty when the app is
 *                        served at the origin root (local dev). Prepended when building the absolute,
 *                        browser-reachable OAuth URLs — the authorization-request {@code redirect_uri}
 *                        and the {@code /oauth2/authorization} init redirect — so the IdP and the
 *                        callback land back on the proxied API path, not the SPA. It cannot be inferred
 *                        from the request: prod runs {@code forward-headers-strategy: native} (Tomcat
 *                        {@code RemoteIpValve}, kept for the pre-auth IP rate-limit trust model — see
 *                        {@code ProxyTrustGuard}), which restores forwarded host/proto but NOT
 *                        {@code X-Forwarded-Prefix}. Normalized to leading-slash / no-trailing-slash.
 * @param audience        Default {@code aud} claim for SPA cookies.
 * @param accessTtl       Cookie-JWT lifetime.
 * @param cookieName      Access-token cookie name (the {@code __Host-} prefix is
 *                        browser-enforced — must be {@code Secure}, no {@code Domain},
 *                        {@code Path=/}).
 * @param stateCookieKey  Base64-encoded 32-byte AES key used to seal the OAuth state
 *                        cookie and the auth-intent cookie. Required in non-dev profiles.
 *                        Bean wiring will generate an ephemeral key (and log a warning)
 *                        if this is left blank — fine for local dev, fatal in prod.
 * @param deleteCooldown  GDPR Art. 17 soft-delete cooldown. A DELETING account is invisible but
 *                        recoverable for this window; once {@code deleted_at} is older than this,
 *                        {@code AccountHardDeleteSweeper} hard-deletes the account (cascading its
 *                        auth rows) and flips status to DELETED. Default 48h.
 * @param loginProviders  Instance login providers to seed on first boot, keyed by a stable
 *                        {@code registrationId} (the OAuth callback segment {@code /login/oauth2/code/
 *                        {id}} and the identity key — e.g. {@code github}, {@code gitlab},
 *                        {@code gitlab-lrz}). Each entry is blank-tolerant: a blank client id omits
 *                        that provider (no crash), so credential-less CI/specs/worker pods still boot.
 *                        Multiple providers per instance are supported (one per {@code (type, base-url)}
 *                        SCM instance); seeding is seed-if-absent, so an admin's later edits are never
 *                        clobbered on reboot. Additional providers can also be added at runtime in
 *                        Instance admin → Login providers.
 * @param bootstrapAdmins Instance super-admin (APP_ADMIN) allowlist of {@code <registrationId>:<who>}.
 *                        {@code who} is either {@code @username} (recommended, readable — e.g.
 *                        {@code gitlab:@m.mustermann}, matched against the git login) or the
 *                        stable numeric {@code subject} (e.g. {@code github:1234567}, reclaim-proof,
 *                        best on public github.com). A listed identity is promoted to APP_ADMIN on
 *                        login (idempotent, promote-only — never demotes). Matched on the authenticated
 *                        identity, NEVER email (so it is not nOAuth-vulnerable); the {@code @username}
 *                        form's only residual risk is username reclaim (negligible on an institutional
 *                        GitLab where usernames aren't recycled; prefer {@code subject} on github.com).
 *                        This is the operator-scoped source of truth for "who runs this instance",
 *                        deliberately separate from per-workspace roles (those derive from SCM team /
 *                        WorkspaceMembership). Empty by default.
 * @param bootstrapToken  Optional one-time break-glass token. When non-blank it enables
 *                        {@code POST /auth/bootstrap-admin}, which promotes the authenticated
 *                        caller to APP_ADMIN exactly while NO active admin exists (self-disables
 *                        afterwards). Proof-of-control is deployment access; deliver out-of-band,
 *                        never log it. Blank = endpoint disabled (404). Prefer {@code bootstrapAdmins};
 *                        keep this as the lockout safety net.
 * @param impersonationMaxLifetime Absolute ceiling on an impersonation session ({@code imp_exp}),
 *                        enforced on every {@code POST /auth/refresh} rotation (the silent-refresh
 *                        keep-alive cannot extend an impersonation past it). See
 *                        {@code docs/contributor/instance-admin.md}.
 * @param stepUpMaxAge    How recently the caller must have signed in for a high-risk instance-admin
 *                        action ({@code auth_time} vs now). A token minted without {@code auth_time}
 *                        never satisfies it. See {@code docs/contributor/instance-admin.md} for what
 *                        the gate does and does not stop.
 * @param sessionMaxLifetime Absolute ceiling on an ordinary session ({@code session_exp}), set at login
 *                        and carried unchanged through every silent refresh. The access token's
 *                        {@code exp} is capped at {@code min(now + accessTtl, session_exp)}, so the
 *                        rolling refresh CANNOT extend a session past this — once it passes, the token
 *                        lapses and the user must re-authenticate (OWASP absolute timeout). Default 12h
 *                        (a long workday); raise/lower per deployment risk.
 * @param devLoginEnabled  Enables the passwordless dev/test sign-in ({@code POST /auth/dev-login}),
 *                        which mints a real session for an arbitrary local {@code Account} without an
 *                        OAuth IdP — for local development and live (Playwright) E2E. Default
 *                        {@code false}: the endpoint 404s and is invisible. FATAL at startup under the
 *                        {@code prod} profile (fail-closed) — see {@code DevLoginService}. Never enable
 *                        on an internet-exposed deployment.
 * @param cookieSecure    Whether the auth + CSRF cookies carry the {@code Secure} attribute and the
 *                        {@code __Host-} name prefix. Default {@code true} (production). Set {@code false}
 *                        ONLY for local http E2E (Playwright over {@code http://localhost}, where the
 *                        browser rejects {@code __Host-}/Secure cookies): the cookies drop the prefix and
 *                        the Secure flag. FATAL at startup under the {@code prod} profile when {@code false}
 *                        (fail-closed) — insecure cookies must be impossible in production.
 */
@ConfigurationProperties(prefix = "hephaestus.auth")
public record AuthProperties(
        @DefaultValue("http://localhost:8080") URI issuer,
        @DefaultValue("") String apiBasePath,
        @DefaultValue("hephaestus-spa") String audience,
        @DefaultValue("15m") Duration accessTtl,
        @DefaultValue(DEFAULT_COOKIE_NAME) String cookieName,
        @DefaultValue("") String stateCookieKey,
        @DefaultValue("48h") Duration deleteCooldown,
        Map<String, LoginProviderSeed> loginProviders,
        @DefaultValue List<String> bootstrapAdmins,
        @DefaultValue("") String bootstrapToken,
        @DefaultValue("1h") Duration impersonationMaxLifetime,
        @DefaultValue("12h") Duration sessionMaxLifetime,
        @DefaultValue("5m") Duration stepUpMaxAge,
        @DefaultValue("false") boolean devLoginEnabled,
        @DefaultValue("true") boolean cookieSecure) {
    /**
     * Null-coalesce the optional provider map so a deployment with no {@code login-providers} block (and
     * direct test construction passing {@code null}) binds to an empty map rather than NPEing at seed time.
     */
    public AuthProperties {
        loginProviders = loginProviders == null ? Map.of() : loginProviders;
        apiBasePath = normalizeApiBasePath(apiBasePath);
    }

    /**
     * Coerce {@code apiBasePath} to the leading-slash / no-trailing-slash form the OAuth-URL builders
     * concatenate, so {@code api}, {@code /api} and {@code /api/} are equivalent and {@code /} or blank
     * mean root — a misconfigured value can't silently produce {@code hostapi/…} or a double slash.
     */
    private static String normalizeApiBasePath(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim().replaceAll("/+$", "");
        if (trimmed.isEmpty()) {
            return "";
        }
        // Collapse a leading slash run to a single slash so `//api` (and any deeper duplicate) cannot
        // survive into a protocol-relative-looking `{baseUrl}//api/…` redirect_uri; a missing leading
        // slash is then prepended.
        trimmed = trimmed.replaceAll("^/+", "/");
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /**
     * Access-token cookie name. The {@code __Host-} prefix forces Secure + host-only (no Domain),
     * so the browser drops it if a proxy injects a Domain attribute. Single source of truth — also
     * referenced by {@code SecurityConfig}'s CSRF cookie check so the two cannot drift.
     */
    public static final String DEFAULT_COOKIE_NAME = "__Host-HEPHAESTUS_AT";

    /**
     * One instance login provider to seed. {@code type} selects the OAuth wiring (GitHub → github.com
     * endpoints; GitLab → endpoints derived from {@code baseUrl}); {@code baseUrl} is the instance root
     * for a self-hosted GitLab (ignored for GitHub, which is github.com only). {@code displayName} is the
     * login-button label (defaults to the registration id when blank). A blank {@code clientId} <em>or</em>
     * {@code clientSecret} omits the provider so credential-less pods still boot.
     *
     * <p>Scopes are intentionally NOT settable here — they are derived from {@code type} in
     * {@code LoginProviderService} so the GitLab "must not request {@code openid}" invariant cannot be
     * misconfigured from env.
     */
    public record LoginProviderSeed(
            LoginProvider.ProviderType type,
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("") String displayName) {
        /**
         * A provider slot counts as configured only when it carries BOTH halves of the OAuth client
         * credential. A half-filled slot (client id set, secret blank — e.g. {@code OUTLINE_OAUTH_CLIENT_ID}
         * exported but {@code OUTLINE_OAUTH_CLIENT_SECRET} forgotten) would otherwise seed an ENABLED
         * provider whose every link/sign-in attempt dies at the token exchange with an opaque IdP error.
         * Treat it as unconfigured — {@code LoginProviderService} reports the missing half at ERROR — so a
         * credential-less pod still boots but a broken button is never offered.
         */
        public boolean configured() {
            return !isBlank(clientId) && !isBlank(clientSecret);
        }

        /**
         * True when the slot is half-filled: one of the two credential halves is present, the other blank.
         * An entirely blank slot ("this deployment has no wiki") is silence; a half-filled one is an
         * operator mistake that must be reported loudly.
         */
        public boolean partiallyConfigured() {
            return !configured() && (!isBlank(clientId) || !isBlank(clientSecret));
        }

        /** The blank credential half, for an actionable "set this env var" diagnostic; empty when both are set. */
        public String missingCredentialField() {
            if (isBlank(clientId)) {
                return "client-id";
            }
            if (isBlank(clientSecret)) {
                return "client-secret";
            }
            return "";
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}

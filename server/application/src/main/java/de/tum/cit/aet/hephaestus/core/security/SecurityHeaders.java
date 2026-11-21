package de.tum.cit.aet.hephaestus.core.security;

import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.core.webapp.WebAppRequests;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.stereotype.Component;

/**
 * The one header policy of every response, the web app's included (ADR 0100). Every user-facing filter chain
 * applies it, so a change here reaches all of them. The Gateway adds no header.
 *
 * <ul>
 *   <li>{@code Strict-Transport-Security}: 30 days, includeSubDomains. See {@link #HSTS_MAX_AGE_SECONDS}.</li>
 *   <li>{@code X-Frame-Options: DENY} and {@code X-Content-Type-Options: nosniff}.</li>
 *   <li>{@code Content-Security-Policy}, enforced. See {@link #contentSecurityPolicy}.</li>
 *   <li>{@code Referrer-Policy}: {@code no-referrer} on the web app's page and files, as its {@code index.html}
 *       also says; {@code strict-origin-when-cross-origin} on every other response.</li>
 *   <li>{@code Cross-Origin-Opener-Policy: same-origin}.</li>
 *   <li>{@code Cross-Origin-Embedder-Policy: credentialless}: Spring Security's enum models only
 *       {@code unsafe-none} and {@code require-corp}, so a static writer sends it.</li>
 *   <li>{@code Permissions-Policy}: no geolocation, camera, microphone or payment.</li>
 * </ul>
 */
@Component
public final class SecurityHeaders {

    /**
     * 30 days: the policy that browsers already keep. A longer policy cannot be withdrawn before it expires, so
     * raising it is a decision of its own.
     */
    private static final long HSTS_MAX_AGE_SECONDS = 2_592_000;

    private final String contentSecurityPolicy;

    public SecurityHeaders(
            @Value("${hephaestus.auth.issuer}") URI issuer, WorkspaceSubdomainProperties workspaceSubdomains) {
        this.contentSecurityPolicy = contentSecurityPolicy(
                "https".equals(issuer.getScheme()),
                workspaceSubdomains.enabled() ? "https://" + workspaceSubdomains.baseDomain() : null);
    }

    /**
     * The directives of every deployment, one per line. The web app's code editor check
     * ({@code webapp/check-code-editor-runtime.ts}) serves the editor under the same file, so the policy has one home.
     */
    private static final String POLICY_DIRECTIVES = "/security/content-security-policy.txt";

    /**
     * The policy names no partner host, because each directive says what this origin may do:
     *
     * <ul>
     *   <li>{@code script-src 'self'}, without {@code 'unsafe-inline'}, is the XSS control. The web app's config
     *       record is JSON, not a script, so it needs no exception.</li>
     *   <li>{@code style-src} keeps {@code 'unsafe-inline'}: Swagger UI and the web app's components set inline
     *       styles.</li>
     *   <li>{@code img-src https:} shows avatars from any provider host without listing them, and still blocks
     *       plain HTTP.</li>
     *   <li>{@code connect-src} adds the apex when workspaces have their own hosts: a page on a workspace host calls
     *       the API on the apex.</li>
     *   <li>{@code upgrade-insecure-requests} only on an HTTPS deployment, so a local HTTP run keeps its requests.</li>
     * </ul>
     */
    static String contentSecurityPolicy(boolean https, @Nullable String apexOrigin) {
        var directives = new ArrayList<String>();
        for (String directive : policyDirectives()) {
            directives.add(
                    apexOrigin != null && directive.startsWith("connect-src ")
                            ? directive + " " + apexOrigin
                            : directive);
        }
        if (https) {
            directives.add("upgrade-insecure-requests");
        }
        return String.join("; ", directives);
    }

    private static List<String> policyDirectives() {
        try (InputStream in = SecurityHeaders.class.getResourceAsStream(POLICY_DIRECTIVES)) {
            if (in == null) {
                throw new IllegalStateException("The content security policy " + POLICY_DIRECTIVES + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Applies the full header set to the given {@link HttpSecurity}. */
    public void apply(HttpSecurity http) throws Exception {
        http.headers(headers -> headers.frameOptions(frameOptions -> frameOptions.deny())
                .contentTypeOptions(contentType -> {})
                .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS))
                .contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy))
                // The web app sends no referrer at all, so a file such as a stylesheet or worker does not send the
                // origin to another site either; its page also says so in a meta tag.
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        WebAppRequests.MATCHER, new ReferrerPolicyHeaderWriter(ReferrerPolicy.NO_REFERRER)))
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        new NegatedRequestMatcher(WebAppRequests.MATCHER),
                        new ReferrerPolicyHeaderWriter(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .crossOriginOpenerPolicy(coop -> coop.policy(CrossOriginOpenerPolicy.SAME_ORIGIN))
                .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Embedder-Policy", "credentialless"))
                .addHeaderWriter(new StaticHeadersWriter(
                        "Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=()")));
    }
}

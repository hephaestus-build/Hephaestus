package de.tum.cit.aet.hephaestus.core.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fail-closed guard for the Tomcat {@code RemoteIpValve} proxy-trust configuration in prod.
 *
 * <p>Prod runs {@code server.forward-headers-strategy: native}, which activates the valve. If
 * {@code server.tomcat.remoteip.internal-proxies} is left at Boot's default it trusts the entire
 * RFC-1918 space, so a client behind in-cluster ingress can spoof {@code X-Forwarded-For} and forge
 * {@code getRemoteAddr()} — defeating the pre-auth IP rate-limit buckets
 * ({@link de.tum.cit.aet.hephaestus.core.auth.ratelimit.AuthRateLimitFilter}). We require the
 * operator to pin the ingress address via {@code HEPHAESTUS_TRUSTED_PROXIES} and abort the boot if the
 * wildcard default is still in effect.
 *
 * <p>Mirrors {@link de.tum.cit.aet.hephaestus.core.auth.jwt.JwtSigningKeyService#assertProdKeysSealed()}:
 * prod-only, throws {@link IllegalStateException} from a non-swallowed {@code @PostConstruct}.
 */
@Component
public class ProxyTrustGuard {

    private final Environment environment;
    private final String forwardHeadersStrategy;
    private final String internalProxies;

    public ProxyTrustGuard(
            Environment environment,
            @Value("${server.forward-headers-strategy:none}") String forwardHeadersStrategy,
            // Empty when the property is unset — Boot still applies its wildcard RFC-1918 default at the
            // valve, but an unset property is itself a misconfiguration in prod, so blank trips the guard.
            @Value("${server.tomcat.remoteip.internal-proxies:}") String internalProxies) {
        this.environment = environment;
        this.forwardHeadersStrategy = forwardHeadersStrategy;
        this.internalProxies = internalProxies;
    }

    @PostConstruct
    void assertProxyTrustPinnedInProd() {
        if (!isProd() || !"native".equalsIgnoreCase(forwardHeadersStrategy)) {
            return; // valve not active → no XFF trust to lock down
        }
        // Require an explicit pin. We deliberately do NOT try to detect "too broad" — robustly
        // recognizing every dangerous regex (Tomcat's 4-token default, .*, a /0 CIDR, link-local) is
        // not reliably possible, and a sniff that's wrong in either direction is worse than none. The
        // contract is simple and honest: in prod+native, internal-proxies MUST be set to a non-blank
        // operator-chosen value (the yaml binds it from HEPHAESTUS_TRUSTED_PROXIES, empty when unset).
        if (internalProxies == null || internalProxies.isBlank()) {
            throw new IllegalStateException(
                    "server.tomcat.remoteip.internal-proxies is unset while forward-headers-strategy=native in "
                            + "prod. The Boot default trusts all RFC-1918 addresses. A client behind an in-cluster "
                            + "ingress can then spoof X-Forwarded-For and forge getRemoteAddr(). This defeats the "
                            + "pre-auth IP rate limit. Pin the ingress address with HEPHAESTUS_TRUSTED_PROXIES "
                            + "(a Tomcat internal-proxies regex).");
        }
    }

    private boolean isProd() {
        return environment.acceptsProfiles(Profiles.of("prod"));
    }
}

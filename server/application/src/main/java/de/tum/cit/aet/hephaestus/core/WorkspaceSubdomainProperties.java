package de.tum.cit.aet.hephaestus.core;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Instance-wide presentation origins. Operator procedure: {@code docs/admin/workspace-subdomains.mdx}.
 *
 * @param enabled Gives each workspace its own subdomain address on the base domain. Authentication and the API stay
 *     on the apex domain. Prepare DNS, TLS and OAuth callbacks before you turn it on.
 * @param baseDomain Domain under which each workspace gets its subdomain, such as hephaestus.build. Lowercase ASCII
 *     without a scheme, port or path, at most 189 characters. Required when subdomains are enabled.
 */
@ConfigurationProperties("hephaestus.workspace.subdomains")
public record WorkspaceSubdomainProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String baseDomain) {
    private static final Pattern DOMAIN = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\\.[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");

    public WorkspaceSubdomainProperties {
        if (enabled && (baseDomain.length() > 189 || !DOMAIN.matcher(baseDomain).matches())) {
            throw new IllegalArgumentException(
                    "Workspace subdomains need a lowercase ASCII DNS base domain without a scheme, port, or path.");
        }
    }

    public String address(String slug, String webappUrl) {
        return enabled ? "https://" + slug + "." + baseDomain : webappUrl.replaceAll("/+$", "") + "/w/" + slug;
    }
}

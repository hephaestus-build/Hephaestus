package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.validation.WorkspaceSlugValidator;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Tenant origins grant browser access, never workspace membership. */
@Component
@ConditionalOnServerRole
public class WorkspaceOriginPolicy {
    private final WorkspaceSubdomainProperties properties;
    private final Pattern originPattern;

    public WorkspaceOriginPolicy(
            WorkspaceSubdomainProperties properties,
            @Value("${hephaestus.webapp.url}") String webappUrl,
            @Value("${hephaestus.auth.issuer}") URI issuer) {
        this.properties = properties;
        this.originPattern = Pattern.compile("https://([a-z0-9-]+)\\." + Pattern.quote(properties.baseDomain()));
        if (properties.enabled()
                && !("https".equals(issuer.getScheme())
                        && properties.baseDomain().equals(issuer.getHost())
                        && (issuer.getPort() == -1 || issuer.getPort() == 443))) {
            throw new IllegalArgumentException(
                    "The auth issuer must use the HTTPS apex when workspace subdomains are on.");
        }
        if (properties.enabled() && !webappUrl.equals("https://" + properties.baseDomain())) {
            throw new IllegalArgumentException(
                    "The webapp URL must be the HTTPS apex when workspace subdomains are on.");
        }
    }

    /** Explicit origins cannot bypass tenant checks, including the reserved docs host. */
    public boolean isTenantOrigin(String origin) {
        if (!properties.enabled()) {
            return false;
        }
        try {
            String host = URI.create(origin).getHost();
            return host != null && host.toLowerCase(Locale.ROOT).endsWith("." + properties.baseDomain());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public boolean allows(String origin) {
        if (!properties.enabled()) {
            return false;
        }
        var matcher = originPattern.matcher(origin);
        if (!matcher.matches()) {
            return false;
        }
        String slug = matcher.group(1);
        return WorkspaceSlugValidator.isAssignable(slug);
    }
}

package de.tum.cit.aet.hephaestus.integration.slack;

import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
public class SlackHephaestusUiLinks {

    private final String webappUrl;
    private final WorkspaceSubdomainProperties subdomains;

    public SlackHephaestusUiLinks(
            @Value("${hephaestus.webapp.url:}") String webappUrl, WorkspaceSubdomainProperties subdomains) {
        this.webappUrl = normalize(webappUrl);
        this.subdomains = subdomains;
    }

    public String userSettingsUrl() {
        if (webappUrl.isBlank()) {
            return "";
        }
        return webappUrl + "/settings";
    }

    /** A message in the developer's Heph conversation; empty when the instance has no webapp address. */
    public String mentorEntryUrl(String workspaceSlug, UUID entryId) {
        if (webappUrl.isBlank()) {
            return "";
        }
        return subdomains.address(workspaceSlug, webappUrl) + "/conversation?entry=" + entryId;
    }

    private static String normalize(String url) {
        if (url == null) {
            return "";
        }
        return url.trim().replaceAll("/+$", "");
    }
}

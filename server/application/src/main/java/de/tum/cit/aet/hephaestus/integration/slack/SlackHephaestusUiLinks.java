package de.tum.cit.aet.hephaestus.integration.slack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
public class SlackHephaestusUiLinks {

    private final String webappUrl;

    public SlackHephaestusUiLinks(@Value("${hephaestus.webapp.url:}") String webappUrl) {
        this.webappUrl = normalize(webappUrl);
    }

    public String userSettingsUrl() {
        if (webappUrl.isBlank()) {
            return "";
        }
        return webappUrl + "/settings";
    }

    private static String normalize(String url) {
        if (url == null) {
            return "";
        }
        return url.trim().replaceAll("/+$", "");
    }
}

package de.tum.cit.aet.hephaestus.integration.access.github;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Separate App credentials: the normal repository integration never gains membership writes. */
@ConfigurationProperties("hephaestus.github-access")
public record GitHubAccessProperties(
        @DefaultValue("0") long appId,
        @DefaultValue("") String privateKey,
        @DefaultValue("") String appSlug) {
    @Override
    public String toString() {
        return "GitHubAccessProperties[appId=" + appId + ", privateKey=***, appSlug=" + appSlug + "]";
    }
}

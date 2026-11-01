package de.tum.cit.aet.hephaestus.contributors;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for fetching Hephaestus contributors from GitHub.
 *
 * <p>The contributor list is meta-information about the application itself (who built
 * Hephaestus), not tenant-managed data. It lives under its own property prefix so the
 * SCM-integration code is no longer the carrier of an unrelated PAT.
 *
 * <p>Prefix: {@code hephaestus.contributors.github}
 *
 * @param authToken Use a personal access token or fine-grained token to read this application's contributor list from GitHub.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.contributors.github")
public record ContributorProperties(@Nullable String authToken) {
    public ContributorProperties {
        // record is shape-only; null means "no token configured, endpoint will short-circuit"
    }
}

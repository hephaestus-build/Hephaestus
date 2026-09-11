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
 * <p>Backwards compatibility: the legacy key {@code hephaestus.integration.github.meta.auth-token}
 * is still bound by {@code GitHubProperties.meta().authToken()} but is no longer read by
 * {@link ContributorService}. Deployments should migrate to
 * {@code hephaestus.contributors.github.auth-token}; the legacy key will be removed once
 * all environments have been updated.
 *
 * @param authToken personal access token or fine-grained token used for unauthenticated-ish
 *                  reads of the {@code hephaestus-build/Hephaestus} contributor graph
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.contributors.github")
public record ContributorProperties(@Nullable String authToken) {
    public ContributorProperties {
        // record is shape-only; null means "no token configured, endpoint will short-circuit"
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.github;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for GitHub integration.
 *
 * <p>This record consolidates all GitHub-related configuration under the
 * {@code hephaestus.integration.github} prefix. It supports GitHub App authentication
 * and metadata API access.
 *
 * <p>Example configuration:
 * <pre>{@code
 * hephaestus:
 *   integration:
 *     github:
 *       app:
 *         id: 123456
 *         private-key-location: classpath:github-app-private-key.pem
 *         # or inline:
 *         private-key: |
 *           -----BEGIN RSA PRIVATE KEY-----
 *           ...
 *           -----END RSA PRIVATE KEY-----
 *       meta:
 *         auth-token: ghp_xxxxxxxxxxxx
 * }</pre>
 *
 * @param app  GitHub App configuration for installation-based authentication
 * @param meta metadata API configuration (e.g., for fetching contributor avatars)
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.integration.github")
public record GitHubProperties(@Valid App app, @Valid Meta meta) {
    /**
     * Compact constructor ensuring nested records are never null.
     *
     * @param app  GitHub App configuration (defaults to empty App if null)
     * @param meta metadata API configuration (defaults to empty Meta if null)
     */
    public GitHubProperties {
        if (app == null) {
            app = new App(0, null, null, null, null, null);
        }
        if (meta == null) {
            meta = new Meta(null);
        }
    }

    /**
     * GitHub App configuration.
     *
     * <p>Supports two methods of providing the private key:
     * <ul>
     *   <li>{@code privateKeyLocation} - a Spring {@link Resource} supporting
     *       {@code classpath:} and {@code file:} URLs</li>
     *   <li>{@code privateKey} - the PEM-encoded private key as a string</li>
     * </ul>
     *
     * <p>If both are provided, {@code privateKeyLocation} takes precedence.
     *
     * @param id                 the GitHub App ID (0 means disabled, must be non-negative)
     * @param privateKeyLocation resource location of the private key file
     * @param privateKey         PEM-encoded private key as inline string
     * @param installationUrl    URL where users install the GitHub App (e.g. https://github.com/apps/my-app/installations/new)
     * @param clientId           the App's client ID, which exchanges the code GitHub returns when an installation
     *                           requests user authorization
     * @param clientSecret       the App's client secret, paired with {@code clientId}
     */
    public record App(
            @Min(value = 0, message = "GitHub App ID must be non-negative (0 means disabled)") @DefaultValue("0")
            long id,

            @Nullable Resource privateKeyLocation,
            @Nullable String privateKey,
            @Nullable String installationUrl,
            @Nullable String clientId,
            @Nullable String clientSecret) {
        // application.yml binds `${GH_APP_INSTALLATION_URL:}` and its siblings, so an install that configured
        // nothing arrives as "" rather than null — treat blank as absent, or GitHubWorkspaceProviderAvailability
        // offers a wizard option whose link is the empty string.
        public App {
            installationUrl = blankToNull(installationUrl);
            clientId = blankToNull(clientId);
            clientSecret = blankToNull(clientSecret);
        }

        private static @Nullable String blankToNull(@Nullable String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    /**
     * GitHub metadata API configuration.
     *
     * <p>Used for authenticated requests to GitHub's API that don't require
     * App installation context (e.g., fetching public user profiles).
     *
     * @param authToken personal access token or fine-grained token for API access
     */
    public record Meta(@Nullable String authToken) {}
}

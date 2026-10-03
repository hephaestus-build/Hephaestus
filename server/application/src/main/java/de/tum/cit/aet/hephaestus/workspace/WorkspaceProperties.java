package de.tum.cit.aet.hephaestus.workspace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for workspace initialization and management.
 *
 * <p>This record consolidates all workspace-related configuration under the
 * {@code hephaestus.workspace} prefix. It controls whether a default workspace
 * is automatically initialized at application startup.
 *
 * <h2>Local Development vs Production</h2>
 * <p>This configuration is primarily intended for <b>local development</b> environments
 * where setting up a full GitHub App with webhooks is impractical. In production,
 * workspaces are automatically created via GitHub App installations.
 *
 * <h3>Local Development — GitHub (PAT Mode)</h3>
 * <pre>{@code
 * hephaestus:
 *   workspace:
 *     init-default: true          # Enable GitHub PAT workspace bootstrap
 *     default:
 *       login: my-github-org      # GitHub org/user to sync
 *       token: ghp_xxxxxxxxxxxx   # Personal Access Token
 *       repositories-to-monitor:
 *         - my-github-org/repo1
 *         - my-github-org/repo2
 * }</pre>
 *
 * <h3>Local Development — GitLab (PAT Mode)</h3>
 * <pre>{@code
 * hephaestus:
 *   workspace:
 *     init-gitlab-default: true       # Enable GitLab PAT workspace bootstrap
 *     gitlab-default:
 *       login: my-group/subgroup      # GitLab group full path
 *       token: glpat-xxxxxxxxxxxx     # Group or Personal Access Token
 *       server-url: https://gitlab.example.com  # Optional, for self-hosted
 * }</pre>
 *
 * <h3>Production (GitHub App Mode)</h3>
 * <pre>{@code
 * hephaestus:
 *   workspace:
 *     init-default: false         # Disable PAT bootstrap
 *   github:
 *     app:
 *       id: 12345                 # GitHub App ID
 *       private-key: ...          # GitHub App private key
 * }</pre>
 *
 * <p><strong>Cross-field validation:</strong> When {@code initDefault} is {@code true},
 * the {@code default.login} and {@code default.token} fields must be provided; otherwise,
 * configuration validation will fail at application startup.
 *
 * @param initDefault whether to initialize a default GitHub PAT workspace at startup (default: {@code false})
 * @param defaultProperties configuration for the default GitHub workspace; required when {@code initDefault} is {@code true}
 * @param initGitlabDefault whether to initialize a default GitLab PAT workspace at startup (default: {@code false})
 * @param gitlabDefault configuration for the default GitLab workspace; required when {@code initGitlabDefault} is {@code true}
 * @see DefaultProperties
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.workspace")
public record WorkspaceProperties(
        @DefaultValue("false") boolean initDefault,
        @Name("default") @Valid DefaultProperties defaultProperties,
        @DefaultValue("false") boolean initGitlabDefault,
        @Valid GitLabDefaultProperties gitlabDefault,
        @DefaultValue("ADMIN_ONLY") CreationPolicy creationPolicy) {
    /**
     * Who may create workspaces via {@code POST /workspaces}. Defaults to {@code ADMIN_ONLY} (safe for
     * a shared instance); set {@code SELF_SERVICE} to let any authenticated user create one. This is
     * the actor gate; per-provider availability (e.g. the GitLab feature flag) is orthogonal.
     */
    public enum CreationPolicy {
        ADMIN_ONLY,
        SELF_SERVICE,
    }

    public WorkspaceProperties(
            boolean initDefault,
            @Nullable DefaultProperties defaultProperties,
            boolean initGitlabDefault,
            @Nullable GitLabDefaultProperties gitlabDefault,
            @Nullable CreationPolicy creationPolicy) {
        DefaultProperties normalizedDefault =
                defaultProperties == null ? new DefaultProperties(null, null, List.of()) : defaultProperties;
        GitLabDefaultProperties normalizedGitlab =
                gitlabDefault == null ? new GitLabDefaultProperties(null, null, null) : gitlabDefault;
        if (initDefault) {
            if (normalizedDefault.login() == null || normalizedDefault.login().isBlank()) {
                throw new IllegalStateException(
                        "hephaestus.workspace.default.login must not be blank when init-default is true");
            }
            if (normalizedDefault.token() == null || normalizedDefault.token().isBlank()) {
                throw new IllegalStateException(
                        "hephaestus.workspace.default.token must not be blank when init-default is true");
            }
        }
        if (initGitlabDefault) {
            if (normalizedGitlab.login() == null || normalizedGitlab.login().isBlank()) {
                throw new IllegalStateException(
                        "hephaestus.workspace.gitlab-default.login must not be blank when init-gitlab-default is true");
            }
            if (normalizedGitlab.token() == null || normalizedGitlab.token().isBlank()) {
                throw new IllegalStateException(
                        "hephaestus.workspace.gitlab-default.token must not be blank when init-gitlab-default is true");
            }
        }
        this.initDefault = initDefault;
        this.defaultProperties = normalizedDefault;
        this.initGitlabDefault = initGitlabDefault;
        this.gitlabDefault = normalizedGitlab;
        this.creationPolicy = creationPolicy == null ? CreationPolicy.ADMIN_ONLY : creationPolicy;
    }

    @AssertTrue(message = "When init-default is true, default.login and default.token must not be blank")
    private boolean isCredentialsValidWhenInitDefaultEnabled() {
        if (!initDefault) {
            return true;
        }
        return (defaultProperties != null
                && defaultProperties.login() != null
                && !defaultProperties.login().isBlank()
                && defaultProperties.token() != null
                && !defaultProperties.token().isBlank());
    }

    @AssertTrue(
            message =
                    "When init-gitlab-default is true, gitlab-default.login and gitlab-default.token must not be blank")
    private boolean isGitLabCredentialsValidWhenInitGitlabDefaultEnabled() {
        if (!initGitlabDefault) {
            return true;
        }
        return (gitlabDefault != null
                && gitlabDefault.login() != null
                && !gitlabDefault.login().isBlank()
                && gitlabDefault.token() != null
                && !gitlabDefault.token().isBlank());
    }

    /**
     * Configuration for the default workspace.
     *
     * <p>Contains the credentials and repository list used when initializing
     * a default workspace. The {@code login} and {@code token} are used to
     * authenticate with the Git provider (e.g., GitHub).
     *
     * @param login the username or organization login for the Git provider;
     *              required when workspace initialization is enabled
     * @param token the authentication token (e.g., GitHub PAT) for API access;
     *              required when workspace initialization is enabled
     * @param repositoriesToMonitor list of repository identifiers (in {@code owner/repo} format)
     *                              to monitor in the default workspace (default: empty list)
     */
    public record DefaultProperties(
            @Nullable String login, @Nullable String token, List<String> repositoriesToMonitor) {
        /**
         * Compact constructor ensuring the repository list is never null.
         *
         * @param login the Git provider login/username
         * @param token the authentication token
         * @param repositoriesToMonitor the list of repositories to monitor
         */
        public DefaultProperties {
            if (repositoriesToMonitor == null) {
                repositoriesToMonitor = List.of();
            }
        }
    }

    /**
     * Configuration for the default GitLab workspace.
     *
     * @param login     the GitLab group full path (e.g., {@code my-org/my-team})
     * @param token     the GitLab Group or Personal Access Token
     * @param serverUrl optional custom server URL for self-hosted GitLab instances
     */
    public record GitLabDefaultProperties(
            @Nullable String login,
            @Nullable String token,
            @Nullable String serverUrl) {}
}

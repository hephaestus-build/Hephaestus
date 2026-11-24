package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * Authentication mode for SCM API access.
 * <p>
 * This enum represents the two supported authentication mechanisms across SCM
 * vendors (GitHub today; GitLab / Bitbucket follow the same shape):
 * <ul>
 *   <li>{@link #INSTALLATION_APP} - Uses installation-based App authentication
 *       (GitHub App installation tokens; analogous flow on other vendors)</li>
 *   <li>{@link #PERSONAL_ACCESS_TOKEN} - Uses a Personal Access Token (PAT)</li>
 * </ul>
 * <p>
 * This is a shared enum used across the SPI layer to ensure consistent authentication
 * mode representation throughout the codebase. The name {@code INSTALLATION_APP} is
 * deliberately vendor-neutral — see {@link ApiCredentialProvider.InstallationCredential}
 * for the matching credential bundle.
 */
@SourceControlContract
public enum AuthMode {
    /**
     * Installation-based App authentication (e.g. GitHub App installation tokens).
     * <p>
     * Uses short-lived tokens minted from an App installation. This is the recommended
     * authentication mode as it provides:
     * <ul>
     *   <li>Fine-grained permissions</li>
     *   <li>Automatic token rotation</li>
     *   <li>Higher rate limits</li>
     *   <li>Organization-level access</li>
     * </ul>
     */
    INSTALLATION_APP,

    /**
     * Personal Access Token authentication for local development.
     * <p>
     * Uses a user-provided PAT stored in the workspace configuration.
     * This mode is intended for <b>local development</b> where setting up
     * a full App with webhooks is impractical.
     * <p>
     * Configure via:
     * <ul>
     *   <li>{@code hephaestus.workspace.init-default: true}</li>
     *   <li>{@code hephaestus.workspace.default.token: <your-PAT>}</li>
     * </ul>
     * <p>
     * <b>Not recommended for production.</b> Use {@link #INSTALLATION_APP} instead.
     *
     * @see de.tum.cit.aet.hephaestus.workspace.WorkspaceProperties
     */
    PERSONAL_ACCESS_TOKEN,
}

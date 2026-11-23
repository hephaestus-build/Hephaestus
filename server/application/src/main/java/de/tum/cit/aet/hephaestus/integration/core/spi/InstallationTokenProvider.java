package de.tum.cit.aet.hephaestus.integration.core.spi;

import org.jspecify.annotations.Nullable;

/**
 * Provides a scope's (workspace's) access to its source-control provider. Provider-specific token work (GitHub App
 * tokens, GitLab validation) stays in the token services that consume this SPI.
 */
@SourceControlContract
public interface InstallationTokenProvider {

    /** The scope's connection settings, from one read of its active GitHub or GitLab connection. Reads no credential. */
    ScmConnection connection(Long scopeId);

    /**
     * The scope's access, from one read of the workspace and one of its active GitHub or GitLab connection. The stored
     * token is read only for an active scope.
     */
    ScmAccess access(Long scopeId);

    /**
     * @param authMode       how the scope authenticates; {@code INSTALLATION_APP} when it has no SCM connection
     * @param installationId the GitHub App installation, in App mode
     * @param serverUrl      the connection's self-hosted server URL; null for the provider's default
     */
    record ScmConnection(
            AuthMode authMode,
            @Nullable Long installationId,
            @Nullable String serverUrl) {
        public static final ScmConnection NONE = new ScmConnection(AuthMode.INSTALLATION_APP, null, null);
    }

    /**
     * @param active              false for a suspended, purged or unknown scope, which must not call the provider
     * @param personalAccessToken the stored token of a GitHub PAT or GitLab connection of an active scope; never an
     *                            App's stored identity
     */
    record ScmAccess(
            boolean active,
            ScmConnection connection,
            @Nullable String personalAccessToken) {
        /** Keeps the token out of logs and test failure output. */
        @Override
        public String toString() {
            return "ScmAccess[active=" + active + ", connection=" + connection + ", personalAccessToken="
                    + (personalAccessToken == null ? "null" : "***") + "]";
        }
    }
}

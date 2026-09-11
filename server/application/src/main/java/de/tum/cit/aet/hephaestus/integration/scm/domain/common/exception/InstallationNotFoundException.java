package de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception;

import java.io.Serial;

/**
 * Exception thrown when a GitHub App installation is no longer accessible.
 * This typically occurs when:
 * <ul>
 *   <li>The installation was uninstalled by the user</li>
 *   <li>The installation was revoked</li>
 *   <li>The installation ID is stale/invalid</li>
 * </ul>
 *
 * <p>This is a non-retryable error - the installation will not become available
 * by retrying. The caller should abort the operation and clean up any cached state.
 */
public class InstallationNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long installationId;

    public InstallationNotFoundException(long installationId, Throwable cause) {
        super("GitHub installation " + installationId + " not found (deleted or inaccessible)", cause);
        this.installationId = installationId;
    }

    public InstallationNotFoundException(long installationId) {
        super("GitHub installation " + installationId + " not found (deleted or inaccessible)");
        this.installationId = installationId;
    }

    public long getInstallationId() {
        return installationId;
    }
}

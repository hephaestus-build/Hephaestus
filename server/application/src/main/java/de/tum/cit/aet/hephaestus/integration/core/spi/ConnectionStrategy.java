package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Per-kind connection lifecycle strategy.
 *
 * <p>Drives the user-visible "Connect X" + "Disconnect X" flows. Vendor specifics
 * (GitHub App install click vs GitLab PAT paste vs Slack OAuth)
 * live in per-kind implementations; the orchestrator stays vendor-neutral.
 */
public interface ConnectionStrategy {
    IntegrationKind kind();

    /**
     * Begin connecting. Returns either a redirect to the vendor (OAuth / App install)
     * or an inline-credentials prompt (paste a PAT).
     */
    ConnectInitiation initiate(InitiateRequest request);

    /**
     * Complete a redirect-style flow: the vendor sent the user back via
     * callback with code/state/etc.
     */
    ConnectFinalization finalizeConnect(IntegrationRef ref, Map<String, String> callbackParams);

    /**
     * Erases the data this integration mirrored into the workspace. Joins the caller's transaction and
     * throws on failure, so a disconnect that cannot erase leaves the connection as it was. Idempotent.
     */
    void eraseLocalData(IntegrationRef ref);

    /**
     * Removes what this connection installed at the provider — app installation, token, webhook — and
     * throws when that cannot be confirmed. {@code ref} carries the connection id. The caller holds the
     * connection row locked, credentials still stored, until its transition commits, so this reads the
     * row but never writes it, and calls no provider inside a database transaction.
     */
    void revokeProvider(IntegrationRef ref);

    record InitiateRequest(
            long workspaceId,
            IntegrationKind kind,
            Map<String, String> userInput, // pasted PAT, configured server URL, etc.
            @Nullable String actorRef // initiating admin, woven into the OAuth state so the audit row attributes it
            ) {}

    sealed interface ConnectInitiation permits ConnectInitiation.RedirectToVendor, ConnectInitiation.AcceptInline {
        record RedirectToVendor(URI vendorUrl, String oauthState) implements ConnectInitiation {}

        record AcceptInline(
                CredentialBundle credentials, @Nullable String instanceKey) implements ConnectInitiation {}
    }

    sealed interface ConnectFinalization permits ConnectFinalization.Completed, ConnectFinalization.Failed {
        /**
         * Successful finalization. Nullable {@code config} lets strategies stamp vendor
         * metadata onto the Connection row; null keeps the placeholder set by
         * {@code findOrCreatePendingConnection}.
         */
        record Completed(
                String instanceKey,
                @Nullable CredentialBundle credentials,
                @Nullable String displayName,
                @Nullable ConnectionConfig config)
                implements ConnectFinalization {
            /** 3-arg overload for strategies that don't need to upgrade the config blob. */
            public Completed(String instanceKey, @Nullable CredentialBundle credentials, @Nullable String displayName) {
                this(instanceKey, credentials, displayName, null);
            }
        }

        record Failed(String reason) implements ConnectFinalization {}
    }
}

package de.tum.cit.aet.hephaestus.integration.access.github;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Access setup is owner-approved; generic connection flows cannot bypass the organization-owner handoff. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessConnection implements ApiCredentialProvider, ConnectionStrategy, IntegrationLifecycleListener {
    private final ConnectionService connections;
    private final CredentialReader credentials;

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB_ACCESS;
    }

    @Override
    public Optional<CredentialBundle> resolve(IntegrationRef ref) {
        return connections
                .findReferenced(ref)
                .filter(connection -> connection.getState() == IntegrationState.ACTIVE)
                .flatMap(credentials::credentialsOf)
                .filter(InstallationCredential.class::isInstance);
    }

    @Override
    public ConnectInitiation initiate(InitiateRequest request) {
        throw new IllegalArgumentException("Configure GitHub access through workspace access administration");
    }

    @Override
    public ConnectFinalization finalizeConnect(IntegrationRef ref, Map<String, String> callbackParams) {
        throw new IllegalArgumentException(
                "GitHub access requires an expiring organization-owner handoff, not a generic callback");
    }

    @Override
    public void revoke(@Nullable IntegrationRef ref) {
        throw new IllegalArgumentException(
                "End GitHub access management and confirm pending removals before disconnecting");
    }

    @Override
    public void revokeProvider(IntegrationRef ref) {
        // Workspace purge is guarded by the managed-access ledger before deleting its rows.
        // The GitHub organization owns this installation and may share it with another target;
        // ending workspace management must never uninstall that shared App.
    }
}

package de.tum.cit.aet.hephaestus.integration.directory;

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

/** Directory setup is owner-approved; generic connection flows cannot bypass that approval. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class KeycloakDirectoryConnection
        implements ApiCredentialProvider, ConnectionStrategy, IntegrationLifecycleListener {
    private final ConnectionService connections;
    private final CredentialReader credentials;

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.KEYCLOAK_DIRECTORY;
    }

    @Override
    public Optional<CredentialBundle> resolve(IntegrationRef ref) {
        return connections
                .findReferenced(ref)
                .filter(connection -> connection.getState() == IntegrationState.ACTIVE)
                .flatMap(credentials::credentialsOf)
                .filter(ClientCredentials.class::isInstance);
    }

    @Override
    public ConnectInitiation initiate(InitiateRequest request) {
        throw new IllegalArgumentException("Configure the directory through workspace member administration");
    }

    @Override
    public ConnectFinalization finalizeConnect(IntegrationRef ref, Map<String, String> callbackParams) {
        throw new IllegalArgumentException("Directory credentials do not use an interactive callback");
    }

    @Override
    public void revoke(@Nullable IntegrationRef ref) {
        // Credentials are read-only. Teardown removes local managed access, never directory users or groups.
    }

    @Override
    public void revokeProvider(IntegrationRef ref) {
        // There is no provider-side installation or credential ownership to revoke.
    }
}

package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceActorSelector {

    private final ConnectionService connectionService;
    private final IdentityProviderLookup identityProviders;
    private final GitProviderRegistry providers;

    public WorkspaceActorSelector(
            ConnectionService connectionService,
            IdentityProviderLookup identityProviders,
            GitProviderRegistry providers) {
        this.connectionService = connectionService;
        this.identityProviders = identityProviders;
        this.providers = providers;
    }

    /**
     * @param memberIdsInLinkOrder the account's actors that are members of the workspace, in
     *     linking order
     */
    public Optional<Long> select(long workspaceId, List<Long> memberIdsInLinkOrder) {
        if (memberIdsInLinkOrder.size() <= 1) {
            return memberIdsInLinkOrder.stream().findFirst();
        }
        Set<Long> onConnectedInstance = connectedInstance(workspaceId)
                .map(instance -> memberIdsInLinkOrder.stream()
                        .filter(id -> providers
                                .actor(id)
                                .map(actor -> actor.providerType()
                                                .equals(instance.type().name())
                                        && actor.serverUrl().equals(instance.serverUrl()))
                                .orElse(false))
                        .collect(Collectors.toSet()))
                .orElseGet(Set::of);
        return memberIdsInLinkOrder.stream()
                .filter(onConnectedInstance::contains)
                .findFirst()
                .or(() -> memberIdsInLinkOrder.stream().findFirst());
    }

    /**
     * The identity provider of the instance the workspace's active SCM connection reads from; empty
     * without a connection or before anything from that instance was recorded.
     */
    public Optional<Long> connectedProviderId(long workspaceId) {
        return connectedInstance(workspaceId)
                .flatMap(instance -> identityProviders.findId(instance.type(), instance.serverUrl()));
    }

    private Optional<ConnectedInstance> connectedInstance(long workspaceId) {
        var kind = connectionService.findActiveProviderKind(workspaceId).orElse(null);
        if (kind == IntegrationKind.GITHUB) {
            var server = connectionService
                    .findActiveGitHubAppConfig(workspaceId)
                    .map(config -> Objects.requireNonNullElse(config.serverUrl(), "https://github.com"))
                    .or(() -> connectionService
                            .findActiveGitHubPatConfig(workspaceId)
                            .map(config -> Objects.requireNonNullElse(config.serverUrl(), "https://github.com")));
            return server.map(url -> new ConnectedInstance(IdentityProviderType.GITHUB, url));
        }
        if (kind == IntegrationKind.GITLAB)
            return connectionService
                    .findActiveGitLabConfig(workspaceId)
                    .map(config -> new ConnectedInstance(
                            IdentityProviderType.GITLAB,
                            Objects.requireNonNullElse(config.serverUrl(), "https://gitlab.com")));
        return Optional.empty();
    }

    private record ConnectedInstance(IdentityProviderType type, String serverUrl) {}
}

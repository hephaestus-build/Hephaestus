package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmTokenSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Chooses the actor an account speaks through in one workspace, as {@code workspace-context.mdx} describes. */
@Component
public class WorkspaceActorSelector {

    private final ConnectionService connectionService;
    private final List<ScmTokenSource> scmSources;
    private final WorkspaceMembershipRepository membershipRepository;
    private final IdentityProviderRepository identityProviderRepository;

    public WorkspaceActorSelector(
            ConnectionService connectionService,
            List<ScmTokenSource> scmSources,
            WorkspaceMembershipRepository membershipRepository,
            IdentityProviderRepository identityProviderRepository) {
        this.connectionService = connectionService;
        this.scmSources = scmSources;
        this.membershipRepository = membershipRepository;
        this.identityProviderRepository = identityProviderRepository;
    }

    /** @param memberIdsInLinkOrder the account's actors that are members of the workspace, in linking order */
    public Optional<Long> select(long workspaceId, List<Long> memberIdsInLinkOrder) {
        if (memberIdsInLinkOrder.size() <= 1) {
            return memberIdsInLinkOrder.stream().findFirst();
        }
        Set<Long> onConnectedInstance = connectedInstance(workspaceId)
                .map(instance -> membershipRepository.findMemberUserIdsByWorkspaceIdAndProvider(
                        workspaceId, memberIdsInLinkOrder, instance.type(), instance.serverUrl()))
                .orElseGet(Set::of);
        return memberIdsInLinkOrder.stream()
                .filter(onConnectedInstance::contains)
                .findFirst()
                .or(() -> memberIdsInLinkOrder.stream().findFirst());
    }

    /**
     * The identity provider of the instance the workspace's active SCM connection reads from; empty without a
     * connection or before anything from that instance was recorded.
     */
    public Optional<Long> connectedProviderId(long workspaceId) {
        return connectedInstance(workspaceId)
                .flatMap(instance ->
                        identityProviderRepository.findByTypeAndServerUrl(instance.type(), instance.serverUrl()))
                .map(IdentityProvider::getId);
    }

    private Optional<ConnectedInstance> connectedInstance(long workspaceId) {
        return connectionService
                .findActiveProviderKind(workspaceId)
                .flatMap(kind -> scmSources.stream()
                        .filter(source -> source.kind() == kind)
                        .findFirst()
                        .flatMap(source -> source.serverUrl(workspaceId))
                        .map(serverUrl -> new ConnectedInstance(IdentityProviderType.from(kind), serverUrl)));
    }

    private record ConnectedInstance(IdentityProviderType type, String serverUrl) {}
}

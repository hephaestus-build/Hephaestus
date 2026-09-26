package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
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

    public WorkspaceActorSelector(
            ConnectionService connectionService,
            List<ScmTokenSource> scmSources,
            WorkspaceMembershipRepository membershipRepository) {
        this.connectionService = connectionService;
        this.scmSources = scmSources;
        this.membershipRepository = membershipRepository;
    }

    /** @param memberIdsInLinkOrder the account's actors that are members of the workspace, in linking order */
    public Optional<Long> select(long workspaceId, List<Long> memberIdsInLinkOrder) {
        if (memberIdsInLinkOrder.size() <= 1) {
            return memberIdsInLinkOrder.stream().findFirst();
        }
        Set<Long> onConnectedInstance = connectionService
                .findActiveProviderKind(workspaceId)
                .flatMap(kind -> scmSources.stream()
                        .filter(source -> source.kind() == kind)
                        .findFirst()
                        .flatMap(source -> source.serverUrl(workspaceId))
                        .map(serverUrl -> membershipRepository.findMemberUserIdsByWorkspaceIdAndProvider(
                                workspaceId, memberIdsInLinkOrder, IdentityProviderType.from(kind), serverUrl)))
                .orElseGet(Set::of);
        return memberIdsInLinkOrder.stream()
                .filter(onConnectedInstance::contains)
                .findFirst()
                .or(() -> memberIdsInLinkOrder.stream().findFirst());
    }
}

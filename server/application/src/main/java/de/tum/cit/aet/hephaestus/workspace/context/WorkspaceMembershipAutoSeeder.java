package de.tum.cit.aet.hephaestus.workspace.context;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import java.util.Collection;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@ConditionalOnServerRole
@Component
public class WorkspaceMembershipAutoSeeder {

    private final WorkspaceMembershipRepository membershipRepository;
    private final WorkspaceMembershipService membershipService;
    private final ConnectionService connectionService;
    private final boolean enabled;

    public WorkspaceMembershipAutoSeeder(
            WorkspaceMembershipRepository membershipRepository,
            WorkspaceMembershipService membershipService,
            ConnectionService connectionService,
            @Value("${hephaestus.workspace.auto-seed-membership:false}") boolean enabled) {
        this.membershipRepository = membershipRepository;
        this.membershipService = membershipService;
        this.connectionService = connectionService;
        this.enabled = enabled;
    }

    public Optional<WorkspaceMembership> seedFirstUserWhenEmpty(Workspace workspace, Collection<User> users) {
        if (!enabled) {
            return Optional.empty();
        }
        // The visitor's actor on the workspace's own provider, never one of their other links.
        Optional<IntegrationKind> provider = connectionService.findActiveProviderKind(workspace.getId());
        Optional<User> firstUser = users.stream()
                .filter(user -> user != null && user.getId() != null)
                .filter(user -> provider.map(
                                kind -> kind == user.getProvider().getType().kind())
                        .orElse(true))
                .findFirst();
        if (firstUser.isEmpty() || membershipRepository.countByWorkspace_Id(workspace.getId()) != 0) {
            return Optional.empty();
        }
        return firstUser.map(user -> membershipService.createMembership(workspace, user.getId(), WorkspaceRole.ADMIN));
    }
}

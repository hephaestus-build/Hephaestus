package de.tum.cit.aet.hephaestus.workspace.context;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry.ActorProfile;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Collection;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Local development only: makes the first signed-in visitor of a workspace with no members its ADMIN. It is off unless
 * {@code hephaestus.workspace.auto-seed-membership} is set, and the server refuses to start with it on under the
 * {@code prod} profile, because an unaudited ADMIN grant must not depend on a profile list being right.
 */
@ConditionalOnServerRole
@Component
public class MembershipAutoSeeder {

    private final MembershipRepository membershipRepository;
    private final MembershipService membershipService;
    private final ConnectionService connectionService;
    private final boolean enabled;

    public MembershipAutoSeeder(
            MembershipRepository membershipRepository,
            MembershipService membershipService,
            ConnectionService connectionService,
            @Value("${hephaestus.workspace.auto-seed-membership:false}") boolean enabled,
            Environment environment) {
        if (enabled && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "hephaestus.workspace.auto-seed-membership must not be true under the 'prod' profile");
        }
        this.membershipRepository = membershipRepository;
        this.membershipService = membershipService;
        this.connectionService = connectionService;
        this.enabled = enabled;
    }

    public Optional<Membership> seedFirstUserWhenEmpty(Workspace workspace, Collection<ActorProfile> users) {
        if (!enabled) {
            return Optional.empty();
        }
        // The visitor's actor on the workspace's own provider, never one of their other links.
        Optional<IntegrationKind> provider = connectionService.findActiveProviderKind(workspace.getId());
        Optional<ActorProfile> firstUser = users.stream()
                .filter(user -> user.human())
                .filter(user -> provider.map(kind -> kind.name().equals(user.providerType()))
                        .orElse(true))
                .findFirst();
        if (firstUser.isEmpty() || membershipRepository.countByWorkspace_Id(workspace.getId()) != 0) {
            return Optional.empty();
        }
        return firstUser.map(user -> membershipService.createMembership(
                workspace, membershipService.accountIdForUser(user.actorId()), WorkspaceRole.ADMIN));
    }
}

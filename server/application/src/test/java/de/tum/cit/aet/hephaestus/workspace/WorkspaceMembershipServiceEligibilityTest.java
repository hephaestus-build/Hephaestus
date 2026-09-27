package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.authorization.WorkspaceAccessService;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** Who practice review can take as its subject: the one predicate coverage counts, selects and admits with. */
class WorkspaceMembershipServiceEligibilityTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;
    private static final IdentityProvider GITLAB = TestEntities.gitProvider(10L, IdentityProviderType.GITLAB);
    private static final IdentityProvider GITHUB = TestEntities.gitProvider(20L, IdentityProviderType.GITHUB);

    @Mock
    private WorkspaceMembershipRepository memberships;

    @Mock
    private WorkspaceRepository workspaces;

    @Mock
    private WorkspaceActorSelector actorSelector;

    @Mock
    private OrganizationMembershipRepository roster;

    @Mock
    private TeamMembershipRepository teams;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ConfigAuditPort configAudit;

    @Mock
    private WorkspaceAccessService accessService;

    @Mock
    private HiddenFormerMemberRepository hiddenFormerMembers;

    private WorkspaceMembershipService service;
    private Workspace workspace;
    private Organization group;

    @BeforeEach
    void setUp() {
        service = new WorkspaceMembershipService(
                memberships,
                workspaces,
                entityManager,
                configAudit,
                accessService,
                hiddenFormerMembers,
                actorSelector,
                roster,
                teams);
        group = new Organization();
        group.setId(5L);
        group.setLogin("course/intro");
        group.setProvider(GITLAB);
        workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setOrganization(group);
        when(workspaces.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
    }

    @Test
    void shouldReviewOnlyMembersTheGroupOrOneOfItsTeamsStillGrants() {
        when(actorSelector.connectedProviderId(WORKSPACE_ID)).thenReturn(Optional.of(10L));
        WorkspaceMembership student = membership(100L, User.Type.USER, GITLAB);
        WorkspaceMembership tutor = membership(101L, User.Type.USER, GITLAB);
        WorkspaceMembership retainedOwner = membership(102L, User.Type.USER, GITLAB);
        WorkspaceMembership bot = membership(103L, User.Type.BOT, GITLAB);
        when(memberships.findAllWithUserByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(student, tutor, retainedOwner, bot));
        when(roster.findUserIdsByOrganizationId(5L)).thenReturn(List.of(100L, 103L));
        when(teams.findDistinctUserIdsOfSubteams("course/intro", 10L)).thenReturn(Set.of(101L));

        assertThat(service.practiceReviewEligibleUserIds(WORKSPACE_ID))
                .as("an owner kept only for administration is not a review subject, and a bot never is")
                .containsExactlyInAnyOrder(100L, 101L);
    }

    @Test
    void shouldNotReviewAnEntitledIdOnAnotherProviderUnderALinkedGroup() {
        when(actorSelector.connectedProviderId(WORKSPACE_ID)).thenReturn(Optional.of(10L));
        WorkspaceMembership student = membership(100L, User.Type.USER, GITLAB);
        WorkspaceMembership githubIdentity = membership(200L, User.Type.USER, GITHUB);
        when(memberships.findAllWithUserByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(student, githubIdentity));
        when(roster.findUserIdsByOrganizationId(5L)).thenReturn(List.of(100L, 200L));
        when(teams.findDistinctUserIdsOfSubteams("course/intro", 10L)).thenReturn(Set.of());

        assertThat(service.practiceReviewEligibleUserIds(WORKSPACE_ID)).containsExactly(100L);
    }

    @Test
    void shouldReviewNobodyWithoutAnActiveConnectionOrWhenTheGroupIsOnAnotherProvider() {
        WorkspaceMembership student = membership(100L, User.Type.USER, GITLAB);
        when(memberships.findAllWithUserByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(student));
        when(actorSelector.connectedProviderId(WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThat(service.practiceReviewEligibleUserIds(WORKSPACE_ID)).isEmpty();

        when(actorSelector.connectedProviderId(WORKSPACE_ID)).thenReturn(Optional.of(20L));

        assertThat(service.practiceReviewEligibleUserIds(WORKSPACE_ID)).isEmpty();
    }

    @Test
    void shouldReviewOnlyIdentitiesOnTheConnectedProviderBeforeAGroupIsLinked() {
        workspace.setOrganization(null);
        when(actorSelector.connectedProviderId(WORKSPACE_ID)).thenReturn(Optional.of(10L));
        WorkspaceMembership onGitLab = membership(100L, User.Type.USER, GITLAB);
        WorkspaceMembership creatorOnGitHub = membership(200L, User.Type.USER, GITHUB);
        when(memberships.findAllWithUserByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(onGitLab, creatorOnGitHub));

        assertThat(service.practiceReviewEligibleUserIds(WORKSPACE_ID)).containsExactly(100L);
    }

    private static WorkspaceMembership membership(long userId, User.Type type, IdentityProvider provider) {
        User user = new User();
        user.setId(userId);
        user.setType(type);
        user.setProvider(provider);
        WorkspaceMembership membership = new WorkspaceMembership();
        membership.setUser(user);
        return membership;
    }
}

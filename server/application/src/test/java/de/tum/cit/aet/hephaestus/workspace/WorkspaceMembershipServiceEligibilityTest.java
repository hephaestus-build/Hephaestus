package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.authorization.WorkspaceAccessService;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
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
    private EntityManager entityManager;

    @Mock
    private ConfigAuditPort configAudit;

    @Mock
    private WorkspaceAccessService accessService;

    @Mock
    private HiddenFormerMemberRepository hiddenFormerMembers;

    private WorkspaceMembershipService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceMembershipService(
                memberships, workspaces, entityManager, configAudit, accessService, hiddenFormerMembers, actorSelector);
    }

    @Test
    void shouldCountOnlyHumanMembersOnTheConnectedInstance() {
        when(actorSelector.connectedProviderId(WORKSPACE_ID)).thenReturn(Optional.of(10L));
        WorkspaceMembership student = membership(100L, User.Type.USER, GITLAB);
        WorkspaceMembership creatorOnGitHub = membership(200L, User.Type.USER, GITHUB);
        WorkspaceMembership bot = membership(103L, User.Type.BOT, GITLAB);
        when(memberships.findAllWithUserByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(student, creatorOnGitHub, bot));

        assertThat(service.practiceReviewEligibleUserIds(WORKSPACE_ID))
                .as("the owner's linked GitHub profile never authors GitLab work, and a bot is never a subject")
                .containsExactly(100L);
    }

    @Test
    void shouldAdmitOneMemberOnlyOnTheConnectedInstance() {
        when(actorSelector.connectedProviderId(WORKSPACE_ID)).thenReturn(Optional.of(10L));
        when(memberships.findByWorkspace_IdAndUser_Id(WORKSPACE_ID, 100L))
                .thenReturn(Optional.of(membership(100L, User.Type.USER, GITLAB)));
        when(memberships.findByWorkspace_IdAndUser_Id(WORKSPACE_ID, 200L))
                .thenReturn(Optional.of(membership(200L, User.Type.USER, GITHUB)));

        assertThat(service.isPracticeReviewEligible(WORKSPACE_ID, 100L)).isTrue();
        assertThat(service.isPracticeReviewEligible(WORKSPACE_ID, 200L)).isFalse();
        assertThat(service.isPracticeReviewEligible(WORKSPACE_ID, 300L)).isFalse();
    }

    @Test
    void shouldCountEveryHumanMemberWhenNoConnectionNamesTheInstance() {
        when(actorSelector.connectedProviderId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(memberships.findAllWithUserByWorkspaceId(WORKSPACE_ID))
                .thenReturn(List.of(
                        membership(100L, User.Type.USER, GITLAB),
                        membership(200L, User.Type.USER, GITHUB),
                        membership(103L, User.Type.BOT, GITLAB)));

        assertThat(service.practiceReviewEligibleUserIds(WORKSPACE_ID)).containsExactlyInAnyOrder(100L, 200L);
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

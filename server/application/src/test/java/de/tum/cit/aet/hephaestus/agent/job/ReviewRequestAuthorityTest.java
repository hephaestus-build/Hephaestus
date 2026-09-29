package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;

/**
 * The rule that decides who may spend the workspace's budget to have coaching delivered to somebody else.
 * Every case below is written from the attacker's side rather than the feature's.
 */
@Tag("unit")
@DisplayName("Who may ask for a review")
class ReviewRequestAuthorityTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long AUTHOR_ID = 1L;
    private static final long ASSIGNEE_ID = 2L;
    private static final long BYSTANDER_ID = 3L;

    @Mock
    private WorkspaceMembershipRepository memberships;

    private ReviewRequestAuthority authority;

    @BeforeEach
    void setUp() {
        authority = new ReviewRequestAuthority(memberships);
        lenient()
                .when(memberships.findByWorkspace_IdAndUser_Id(anyLong(), anyLong()))
                .thenReturn(Optional.empty());
    }

    @Test
    void theAuthorOfTheWorkMayAskAboutIt() {
        assertThat(authority.mayRequest(WORKSPACE_ID, artifact(), user(AUTHOR_ID)))
                .isTrue();
    }

    @Test
    void anAssigneeMayAskAboutIt() {
        assertThat(authority.mayRequest(WORKSPACE_ID, artifact(), user(ASSIGNEE_ID)))
                .isTrue();
    }

    /**
     * A bystander can comment on the merge request — that is what being on the team means — but the
     * feedback the review produces is delivered to the author, so letting the bystander occasion it hands
     * them a way to aim coaching at a colleague.
     */
    @Test
    void aWorkspaceMemberWhoIsNeitherAuthorNorAssigneeMayNot() {
        givenMembership(BYSTANDER_ID, WorkspaceRole.MEMBER);

        assertThat(authority.mayRequest(WORKSPACE_ID, artifact(), user(BYSTANDER_ID)))
                .isFalse();
    }

    @ParameterizedTest(name = "a workspace {0} may ask about anyone''s work")
    @EnumSource(
            value = WorkspaceRole.class,
            names = {"ADMIN", "OWNER"})
    void anAdminMayAskAboutAnyonesWork(WorkspaceRole role) {
        givenMembership(BYSTANDER_ID, role);

        assertThat(authority.mayRequest(WORKSPACE_ID, artifact(), user(BYSTANDER_ID)))
                .isTrue();
    }

    /** Standing is per workspace: admin somewhere else is not admin here. */
    @Test
    void adminOfADifferentWorkspaceMayNot() {
        lenient()
                .when(memberships.findByWorkspace_IdAndUser_Id(99L, BYSTANDER_ID))
                .thenReturn(Optional.of(membership(WorkspaceRole.OWNER)));

        assertThat(authority.mayRequest(WORKSPACE_ID, artifact(), user(BYSTANDER_ID)))
                .isFalse();
    }

    /** An ask nobody can be named for cannot be shown to be an authorized one. */
    @Test
    void anUnattributableRequestIsRefused() {
        assertThat(authority.mayRequest(WORKSPACE_ID, artifact(), null)).isFalse();
    }

    /** A requester the mirror has synced but never persisted has no id to compare or look up. */
    @Test
    void aRequesterWithNoIdIsRefused() {
        assertThat(authority.mayRequest(WORKSPACE_ID, artifact(), new User())).isFalse();
    }

    /** An artifact with neither author nor assignees leaves only the admin route open. */
    @Test
    void anUnattributedArtifactIsNotOpenToEveryone() {
        PullRequest orphan = new PullRequest();
        orphan.setId(500L);
        orphan.setAssignees(Set.of());

        assertThat(authority.mayRequest(WORKSPACE_ID, orphan, user(BYSTANDER_ID)))
                .isFalse();
    }

    /**
     * The multi-identity case, and the reason the collection overload exists. A Hephaestus account can
     * link a GitLab and a GitHub login; workspace membership is keyed on the SCM user. Asking the
     * question of one identity at a time refuses an admin for having signed in through the other one.
     */
    @Test
    void anAdminUnderOneOfTheirLinkedIdentitiesMayAsk() {
        givenMembership(BYSTANDER_ID, WorkspaceRole.ADMIN);

        Optional<User> standing =
                authority.standingOf(WORKSPACE_ID, artifact(), List.of(user(999L), user(BYSTANDER_ID)));

        assertThat(standing).map(User::getId).contains(BYSTANDER_ID);
    }

    /**
     * Returns the identity that actually qualified, not merely "yes". The ledger row is filed under it,
     * and a row naming somebody the rule did not accept would misattribute the request that spent the
     * budget — and misdirect the per-person allowance that counts those rows.
     */
    @Test
    void theIdentityHandedBackIsTheOneThatQualified() {
        Optional<User> standing =
                authority.standingOf(WORKSPACE_ID, artifact(), List.of(user(BYSTANDER_ID), user(AUTHOR_ID)));

        assertThat(standing).map(User::getId).contains(AUTHOR_ID);
    }

    @Test
    void anEmptyIdentitySetHasNobodyWithStanding() {
        assertThat(authority.standingOf(WORKSPACE_ID, artifact(), List.of())).isEmpty();
    }

    /** Admin standing belongs to the account, whichever of its identities holds the membership. */
    @Test
    void anAdminUnderAnyOfTheirIdentitiesHoldsAdminStanding() {
        givenMembership(BYSTANDER_ID, WorkspaceRole.ADMIN);

        assertThat(authority.anyAdmin(WORKSPACE_ID, List.of(user(AUTHOR_ID), user(BYSTANDER_ID))))
                .isTrue();
        assertThat(authority.anyAdmin(WORKSPACE_ID, List.of(user(AUTHOR_ID)))).isFalse();
    }

    @Test
    void anyOfTheRequestersIdentitiesMayBeTheOneOnTheWork() {
        assertThat(authority.isActorOn(artifact(), List.of(user(BYSTANDER_ID), user(ASSIGNEE_ID))))
                .isTrue();
        assertThat(authority.isActorOn(artifact(), List.of(user(BYSTANDER_ID)))).isFalse();
    }

    // Fixtures

    private Issue artifact() {
        PullRequest pr = new PullRequest();
        pr.setId(500L);
        pr.setAuthor(user(AUTHOR_ID));
        pr.setAssignees(Set.of(user(ASSIGNEE_ID)));
        return pr;
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        user.setLogin("user-" + id);
        return user;
    }

    private void givenMembership(long userId, WorkspaceRole role) {
        when(memberships.findByWorkspace_IdAndUser_Id(WORKSPACE_ID, userId)).thenReturn(Optional.of(membership(role)));
    }

    private WorkspaceMembership membership(WorkspaceRole role) {
        WorkspaceMembership membership = new WorkspaceMembership();
        membership.setRole(role);
        return membership;
    }
}

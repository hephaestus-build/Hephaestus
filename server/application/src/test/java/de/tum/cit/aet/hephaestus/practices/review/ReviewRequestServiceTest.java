package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.WorkspaceAdminAssurance;
import de.tum.cit.aet.hephaestus.core.auth.webauthn.PasskeyRequiredException;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.RoleHolder;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.authorization.WorkspaceAccessService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewRequestServiceTest extends BaseUnitTest {
    final AccountIdentityQuery identities = mock(AccountIdentityQuery.class);
    final WorkspaceAccessService access = mock(WorkspaceAccessService.class);
    final ProductItemQuery items = mock(ProductItemQuery.class);
    final ReviewService reviews = mock(ReviewService.class);
    final ReviewRequestPolicy policy = mock(ReviewRequestPolicy.class);
    final MembershipRepository memberships = mock(MembershipRepository.class);
    final WorkspaceAdminAssurance assurance = mock(WorkspaceAdminAssurance.class);
    final ReviewRequestService service =
            new ReviewRequestService(items, identities, access, reviews, policy, memberships, assurance);

    @Test
    void authorAndAssigneeCanRequestTheirOwnItem() {
        when(identities.resolveActiveAccountId(1L, "native-user", null)).thenReturn(Optional.of(7L));
        for (var role : List.of(AboutRole.AUTHOR, AboutRole.ASSIGNEE))
            assertThat(service.mayRequest(1, snapshot(1, role, true), 7)).isTrue();
    }

    @Test
    void reviewerAndMergerCannotRequestAsAnAuthor() {
        for (var role : List.of(AboutRole.REVIEWER, AboutRole.MERGER))
            assertThat(service.mayRequest(1, snapshot(1, role, true), 7)).isFalse();
        verifyNoInteractions(identities);
    }

    @Test
    void unresolvedIdentityDoesNotBecomeADeveloperClaim() {
        when(identities.resolveActiveAccountId(1L, "native-user", null)).thenReturn(Optional.empty());
        assertThat(service.mayRequest(1, snapshot(1, AboutRole.AUTHOR, true), 7))
                .isFalse();
    }

    @Test
    void botsCannotRequestAsPeople() {
        assertThat(service.mayRequest(1, snapshot(1, AboutRole.AUTHOR, false), 7))
                .isFalse();
        verifyNoInteractions(identities);
    }

    @Test
    void workspaceAdminCanRequestEvenWithoutAPersonRole() {
        when(access.isAdmin()).thenReturn(true);
        assertThat(service.mayRequest(1, snapshot(1, AboutRole.REVIEWER, true), 7))
                .isTrue();
    }

    @Test
    void foreignWorkspaceIsRejectedEvenForAnAdmin() {
        assertThat(service.mayRequest(1, snapshot(2, AboutRole.AUTHOR, true), 7))
                .isFalse();
        verifyNoInteractions(access, identities);
    }

    @Test
    void shouldRefuseAdminFallbackWhenAdminPasskeyProofIsMissing() {
        requesting(snapshot(1, AboutRole.REVIEWER, true));
        when(access.isAdmin()).thenReturn(true);
        doThrow(new PasskeyRequiredException()).when(assurance).require(true);

        assertThatThrownBy(() -> service.request(1, 3, 7)).isInstanceOf(PasskeyRequiredException.class);
        verify(reviews, never()).request(any());
    }

    @Test
    void shouldSubmitAdminFallbackWhenAdminPasskeyProofIsPresent() {
        requesting(snapshot(1, AboutRole.REVIEWER, true));
        when(access.isAdmin()).thenReturn(true);

        assertThat(service.request(1, 3, 7).status()).isEqualTo(ReviewRequestStatus.SUBMITTED);
        verify(assurance).require(true);
        verify(reviews).request(any());
    }

    @Test
    void shouldNotAskForAdminProofWhenTheAuthorRequestsTheirOwnWork() {
        requesting(snapshot(1, AboutRole.AUTHOR, true));
        when(identities.resolveActiveAccountId(1L, "native-user", null)).thenReturn(Optional.of(7L));
        when(access.isAdmin()).thenReturn(true);

        assertThat(service.request(1, 3, 7).status()).isEqualTo(ReviewRequestStatus.SUBMITTED);
        verifyNoInteractions(assurance);
    }

    @Test
    void shouldRefuseANonAdminWhenTheWorkBelongsToSomeoneElse() {
        requesting(snapshot(1, AboutRole.REVIEWER, true));

        assertThatThrownBy(() -> service.request(1, 3, 7)).isInstanceOf(AccessForbiddenException.class);
        verifyNoInteractions(assurance);
        verify(reviews, never()).request(any());
    }

    @Test
    void shouldKeepMembershipAuthorityWhenAProviderCommentRequestsTheReview() {
        requesting(snapshot(1, AboutRole.REVIEWER, true));
        when(identities.resolveActiveAccountId(1L, "native-user", null)).thenReturn(Optional.of(7L));
        var admin = new Membership();
        admin.setRole(Membership.WorkspaceRole.ADMIN);
        when(memberships.findByWorkspace_IdAndId_AccountId(1L, 7L)).thenReturn(Optional.of(admin));

        var outcome =
                service.requestByComment(1, 3, new PersonIdentity(1, "native-user", null), UUID.randomUUID(), null);

        assertThat(outcome.status()).isEqualTo(ReviewRequestStatus.SUBMITTED);
        verifyNoInteractions(assurance, access);
    }

    @Test
    void shouldDescribeAdminCapabilityWithoutAskingForProofWhenPreviewing() {
        when(access.isAdmin()).thenReturn(true);

        assertThat(service.mayRequest(1, snapshot(1, AboutRole.REVIEWER, true), 7))
                .isTrue();
        verifyNoInteractions(assurance);
    }

    void requesting(ItemSnapshot snapshot) {
        when(items.require(1, 3)).thenReturn(snapshot.item());
        when(items.pin(snapshot.item(), null)).thenReturn(snapshot);
        when(policy.refusal(1, 3, 7)).thenReturn(Optional.empty());
    }

    ItemSnapshot snapshot(long ws, AboutRole role, boolean human) {
        var person = new PersonIdentity(1, "native-user", null);
        return ItemFixtures.snapshot(new ItemRef(ws, 3, new ItemKind("scm.pull_request"), 3L, null, null), Map.of())
                .integration(new IntegrationRef(IntegrationKind.GITHUB, 1, "Test", null))
                .roles(List.of(new RoleHolder(role, person, human)))
                .label(ItemFixtures.label()
                        .itemId(3)
                        .title("Item")
                        .noun("Pull request")
                        .number(1)
                        .container("Repository")
                        .integration(IntegrationKind.GITHUB)
                        .build())
                .build();
    }
}

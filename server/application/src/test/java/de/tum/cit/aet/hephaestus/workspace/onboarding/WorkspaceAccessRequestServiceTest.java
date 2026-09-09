package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class WorkspaceAccessRequestServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final WorkspaceAccessPolicyRepository policies = mock(WorkspaceAccessPolicyRepository.class);
    private final WorkspaceAccessRequestRepository requests = mock(WorkspaceAccessRequestRepository.class);
    private final WorkspaceAccountMembershipRepository memberships = mock(WorkspaceAccountMembershipRepository.class);
    private final AccountIdentityQuery identities = mock(AccountIdentityQuery.class);
    private final WorkspaceAccessCatalog catalog = mock(WorkspaceAccessCatalog.class);
    private final WorkspaceAccessNotifications notifications = mock(WorkspaceAccessNotifications.class);
    private final ConfigAuditPort audit = mock(ConfigAuditPort.class);
    private final WorkspaceAccessRequestService service = new WorkspaceAccessRequestService(
            workspaces,
            policies,
            requests,
            memberships,
            identities,
            catalog,
            notifications,
            audit,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private Workspace workspace;
    private WorkspaceAccessPolicy policy;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(10L);
        workspace.setWorkspaceSlug("team");
        workspace.setDisplayName("Team");
        policy = new WorkspaceAccessPolicy();
        policy.setWorkspace(workspace);
        policy.setEnabled(true);
        policy.setVersion(3);
        policy.setSettings(settings());
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        when(policies.findByWorkspace_Id(10L)).thenReturn(Optional.of(policy));
        when(identities.accountForUpdate(any()))
                .thenAnswer(
                        call -> Optional.of(new AccountIdentityQuery.AccountView(call.getArgument(0), "Person", true)));
        when(catalog.requiredIdentityLinks(eq(workspace), any(), any())).thenReturn(List.of(11L, 12L));
        when(catalog.maintainers(eq(workspace), any()))
                .thenReturn(List.of(new WorkspaceAccessCatalog.WorkspaceAccessMaintainerOptionDTO(1L, "Owner")));
        when(requests.saveAndFlush(any())).thenAnswer(call -> {
            WorkspaceAccessRequest request = call.getArgument(0);
            request.setId(20L);
            return request;
        });
        var owner = membership(1L, WorkspaceAccountMembership.Source.MANUAL);
        owner.setRole(WorkspaceRole.OWNER);
        when(memberships.findByWorkspace_IdAndAccountId(10L, 1L)).thenReturn(Optional.of(owner));
        signIn(2L);
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldCaptureAcknowledgedPolicyAndIdentityLinksWhenSubmitting() {
        var result = service.submit(10L, submission(3));
        var saved = ArgumentCaptor.forClass(WorkspaceAccessRequest.class);
        verify(requests).saveAndFlush(saved.capture());
        assertThat(result.status()).isEqualTo(WorkspaceAccessRequest.Status.SUBMITTED);
        assertThat(saved.getValue().getPolicySnapshot()).isEqualTo(settings());
        assertThat(saved.getValue().getSubmission().identityLinkIds()).containsExactly(11L, 12L);
        assertThat(saved.getValue().getSubmission().acknowledgedNoticeKeys()).containsExactly("ai-policy");
        assertThat(saved.getValue().getAccountId()).isEqualTo(2L);
        verify(memberships, never()).save(any());
    }

    @Test
    void shouldRejectSubmissionWhenTheAcknowledgedPolicyIsStale() {
        assertThatThrownBy(() -> service.submit(10L, submission(2)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("policy changed");
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void shouldRejectMissingAcknowledgementsWithoutMakingResearchConsentAnAccessCondition() {
        var input =
                new WorkspaceAccessRequestService.SubmitWorkspaceAccessRequestDTO(3, true, List.of(), details(), null);
        assertThatThrownBy(() -> service.submit(10L, input)).hasMessageContaining("Acknowledge");
        // The access contract names only workspace notices; no research decision is accepted or required here.
        assertThat(settings().notices())
                .extracting(WorkspaceAccessPolicySettings.PolicyNoticeDTO::key)
                .containsExactly("ai-policy");
    }

    @Test
    void shouldRejectDuplicatePendingRequestsWhenSubmittingAgain() {
        when(requests.existsByWorkspace_IdAndAccountIdAndStatusIn(eq(10L), eq(2L), any()))
                .thenReturn(true);
        assertThatThrownBy(() -> service.submit(10L, submission(3))).hasMessageContaining("already awaiting review");
        verify(requests, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = WorkspaceAccountMembership.Source.class,
            names = {"MANUAL", "SCM", "DIRECTORY", "MIGRATED"})
    void shouldNotTakeOverSeparatelyManagedMembershipsWhenSubmitting(WorkspaceAccountMembership.Source source) {
        when(memberships.findByWorkspace_IdAndAccountId(10L, 2L)).thenReturn(Optional.of(membership(2L, source)));
        assertThatThrownBy(() -> service.submit(10L, submission(3))).hasMessageContaining("managed separately");
    }

    @Test
    void shouldNotRestoreSuspendedAccessWhenApproving() {
        var request = request();
        var membership = membership(2L, WorkspaceAccountMembership.Source.REQUEST);
        membership.setSuspended(true);
        when(memberships.findByWorkspace_IdAndAccountId(10L, 2L)).thenReturn(Optional.of(membership));
        signIn(1L);
        assertThatThrownBy(() -> service.decide(10L, 20L, approval())).hasMessageContaining("suspended");
        assertThat(request.getStatus()).isEqualTo(WorkspaceAccessRequest.Status.SUBMITTED);
        assertThat(membership.getExpiresAt()).isNull();
    }

    @Test
    void shouldGrantOnlyTimeBoundedMemberAccessWhenApproving() {
        var request = request();
        signIn(1L);
        var result = service.decide(10L, 20L, approval());
        var saved = ArgumentCaptor.forClass(WorkspaceAccountMembership.class);
        verify(memberships).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo(WorkspaceAccountMembership.Source.REQUEST);
        assertThat(saved.getValue().getRole()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(details().expiresAt());
        assertThat(saved.getValue().getAccessRequestId()).isEqualTo(20L);
        assertThat(request.getDecidedByAccountId()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(WorkspaceAccessRequest.Status.APPROVED);
    }

    @Test
    void shouldPreserveTheDeadlineWhenARenewalIsOnlyPending() {
        var membership = membership(2L, WorkspaceAccountMembership.Source.REQUEST);
        membership.setExpiresAt(NOW.plusSeconds(60));
        membership.setAccessRequestId(19L);
        when(memberships.findByWorkspace_IdAndAccountId(10L, 2L)).thenReturn(Optional.of(membership));
        service.submit(10L, submission(3));
        assertThat(membership.getExpiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(membership.isActiveAt(NOW.plusSeconds(60))).isFalse();
        assertThat(membership.getAccessRequestId()).isEqualTo(19L);
    }

    @Test
    void shouldRefuseSelfApprovalEvenForAWorkspaceOwner() {
        var request = request();
        request.setAccountId(1L);
        signIn(1L);
        assertThatThrownBy(() -> service.decide(10L, 20L, approval())).hasMessageContaining("own access request");
        verify(memberships, never()).save(any());
    }

    @Test
    void shouldRequireFreshSubmissionWhenIdentityLinksChange() {
        request();
        signIn(1L);
        when(catalog.requiredIdentityLinks(eq(workspace), any(), any())).thenReturn(List.of(11L, 99L));
        assertThatThrownBy(() -> service.decide(10L, 20L, approval())).hasMessageContaining("identity links changed");
        verify(memberships, never()).save(any());
    }

    @Test
    void shouldRefuseAnApprovalWhenThePolicyChangedAfterSubmission() {
        request();
        policy.setVersion(4);
        signIn(1L);
        assertThatThrownBy(() -> service.decide(10L, 20L, approval())).hasMessageContaining("policy changed");
        verify(memberships, never()).save(any());
    }

    @Test
    void shouldRejectForeignTeamSelectionsWhenAnAdministratorEditsAnApproval() {
        request();
        signIn(1L);
        var edited = new WorkspaceAccessDetails(1L, List.of(999L), details().expiresAt());
        assertThatThrownBy(() -> service.decide(
                        10L,
                        20L,
                        new WorkspaceAccessRequestService.ReviewWorkspaceAccessRequestDTO(
                                0, WorkspaceAccessRequestService.Decision.APPROVE, edited, null)))
                .hasMessageContaining("requestable teams");
        verify(memberships, never()).save(any());
    }

    @Test
    void shouldNotRevealSomeoneElsesRequestWhenWithdrawing() {
        request();
        signIn(3L);
        assertThatThrownBy(() -> service.cancel(10L, 20L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error)
                                .getStatusCode()
                                .value())
                        .isEqualTo(404));
    }

    @Test
    void shouldRecordRequiredChangesWithoutGrantingAccess() {
        var request = request();
        signIn(1L);
        var decision = new WorkspaceAccessRequestService.ReviewWorkspaceAccessRequestDTO(
                0, WorkspaceAccessRequestService.Decision.REQUEST_CHANGES, null, "Choose the team for your project.");
        service.decide(10L, 20L, decision);
        assertThat(request.getStatus()).isEqualTo(WorkspaceAccessRequest.Status.CHANGES_REQUESTED);
        assertThat(request.getDecisionComment()).isEqualTo("Choose the team for your project.");
        assertThat(request.getApprovedDetails()).isNull();
        verify(memberships, never()).save(any());
    }

    private WorkspaceAccessRequest request() {
        var request = new WorkspaceAccessRequest();
        request.setId(20L);
        request.setWorkspace(workspace);
        request.setAccountId(2L);
        request.setPolicyVersion(3);
        request.setPolicySnapshot(settings());
        request.setSubmittedAt(NOW);
        request.setSubmission(
                new WorkspaceAccessRequest.Submission(details(), List.of(11L, 12L), List.of("ai-policy"), null));
        when(requests.findByIdAndWorkspace_Id(20L, 10L)).thenReturn(Optional.of(request));
        return request;
    }

    private WorkspaceAccountMembership membership(Long accountId, WorkspaceAccountMembership.Source source) {
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(accountId);
        membership.setSource(source);
        return membership;
    }

    private static WorkspaceAccessPolicySettings settings() {
        return new WorkspaceAccessPolicySettings(
                "github",
                "# Code of conduct",
                "I have read and agree",
                List.of(),
                List.of(new WorkspaceAccessPolicySettings.PolicyNoticeDTO(
                        "ai-policy", "AI policy", "Read the policy.")),
                7L,
                List.of(8L),
                90,
                14,
                "admins@example.com",
                null);
    }

    private static WorkspaceAccessDetails details() {
        return new WorkspaceAccessDetails(1L, List.of(8L), NOW.plus(Duration.ofDays(10)));
    }

    private static WorkspaceAccessRequestService.SubmitWorkspaceAccessRequestDTO submission(long version) {
        return new WorkspaceAccessRequestService.SubmitWorkspaceAccessRequestDTO(
                version, true, List.of("ai-policy"), details(), null);
    }

    private static WorkspaceAccessRequestService.ReviewWorkspaceAccessRequestDTO approval() {
        return new WorkspaceAccessRequestService.ReviewWorkspaceAccessRequestDTO(
                0, WorkspaceAccessRequestService.Decision.APPROVE, details(), null);
    }

    private static void signIn(Long accountId) {
        var jwt = Jwt.withTokenValue("unit")
                .header("alg", "test")
                .subject(accountId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}

package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceApprovedAccessQuery;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorkspaceApprovedAccessQueryIntegrationTest extends RealAuthIntegrationTest {
    @Autowired
    private WorkspaceApprovedAccessQuery approvals;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceAccountMembershipRepository memberships;

    @Autowired
    private WorkspaceAccessRequestRepository requests;

    @Test
    void shouldExposeOnlyTheCurrentApprovedGrantWithinItsWorkspaceAndAbsoluteExpiry() {
        var workspace = new Workspace();
        workspace.setWorkspaceSlug("approved-query");
        workspace.setDisplayName("Approved query");
        workspace.setAccountLogin("approved-query");
        workspace.setAccountType(AccountType.ORG);
        workspace = workspaces.saveAndFlush(workspace);
        var account = accounts.saveAndFlush(new Account("Applicant"));
        long accountId = Objects.requireNonNull(account.getId());
        var now = Instant.parse("2026-09-10T00:00:00Z");
        var request = new WorkspaceAccessRequest();
        request.setWorkspace(workspace);
        request.setAccountId(accountId);
        request.setSubmittedAt(now.minusSeconds(3600));
        request.setPolicySnapshot(new WorkspaceAccessPolicySettings(
                "github",
                "Introduction",
                "Acknowledgement",
                List.of(),
                List.of(),
                1L,
                List.of(),
                90,
                14,
                "admin@example.test",
                null));
        var details = new WorkspaceAccessDetails(1L, List.of(11L, 12L), now.plusSeconds(3600));
        request.setSubmission(new WorkspaceAccessRequest.Submission(details, List.of(21L, 22L), List.of(), null));
        request.setApprovedDetails(details);
        request.setStatus(WorkspaceAccessRequest.Status.SUBMITTED);
        request = requests.saveAndFlush(request);
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(accountId);
        membership.setRole(WorkspaceRole.MEMBER);
        membership.setSource(WorkspaceAccountMembership.Source.REQUEST);
        membership.setAccessRequestId(request.getId());
        membership.setExpiresAt(now.plusSeconds(1800));
        membership = memberships.saveAndFlush(membership);

        assertThat(approvals.currentApprovals(workspace.getId(), now)).isEmpty();
        request.setStatus(WorkspaceAccessRequest.Status.APPROVED);
        requests.saveAndFlush(request);
        assertThat(approvals.currentApprovals(workspace.getId(), now))
                .containsExactly(new WorkspaceApprovedAccessQuery.Approval(
                        request.getId(), accountId, now.plusSeconds(1800), List.of(11L, 12L), List.of(21L, 22L)));
        assertThat(approvals.currentApprovals(Long.MAX_VALUE, now)).isEmpty();
        assertThat(approvals.currentApprovals(workspace.getId(), now.plusSeconds(1800)))
                .isEmpty();

        membership.setSuspended(true);
        memberships.saveAndFlush(membership);
        assertThat(approvals.currentApprovals(workspace.getId(), now)).isEmpty();
        membership.setSuspended(false);
        membership.setSource(WorkspaceAccountMembership.Source.MANUAL);
        membership.setAccessRequestId(null);
        memberships.saveAndFlush(membership);
        assertThat(approvals.currentApprovals(workspace.getId(), now)).isEmpty();
    }
}

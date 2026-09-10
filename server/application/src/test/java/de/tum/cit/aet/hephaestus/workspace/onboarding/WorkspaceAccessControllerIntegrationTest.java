package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Real sessions prove that the applicant exception does not open the private workspace around it. */
class WorkspaceAccessControllerIntegrationTest extends RealAuthIntegrationTest {
    @Autowired
    private WebTestClient client;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceAccountMembershipRepository memberships;

    @Autowired
    private WorkspaceAccessRequestRepository requests;

    @Autowired
    private HephaestusJwtIssuer issuer;

    @Autowired
    private JwtPrincipalFactory principals;

    @Test
    void nonmemberCanReadAndWithdrawOnlyTheirOwnRequestWithoutEnteringThePrivateWorkspace() {
        var workspace = workspace("private-application");
        var applicant = accounts.saveAndFlush(new Account("Applicant"));
        var other = accounts.saveAndFlush(new Account("Other applicant"));
        var own = request(workspace, applicant);
        var foreign = request(workspace, other);
        var session = token(applicant);
        client.get()
                .uri(path(workspace, "/access-requests/me"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri(path(workspace, "/access-requests/me"))
                .headers(headers -> headers.setBearerAuth(session))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].id")
                .isEqualTo(own.getId());
        client.get()
                .uri(path(workspace, "/members/me"))
                .headers(headers -> headers.setBearerAuth(session))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri(path(workspace, "/access-requests"))
                .headers(headers -> headers.setBearerAuth(session))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.delete()
                .uri(path(workspace, "/access-requests/me/" + foreign.getId()))
                .headers(headers -> headers.setBearerAuth(session))
                .exchange()
                .expectStatus()
                .isNotFound();
        client.delete()
                .uri(path(workspace, "/access-requests/me/" + own.getId()))
                .headers(headers -> headers.setBearerAuth(session))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("CANCELLED");
        assertThat(requests.findById(foreign.getId()))
                .get()
                .satisfies(value -> assertThat(value.getStatus()).isEqualTo(WorkspaceAccessRequest.Status.SUBMITTED));
    }

    @Test
    void expiryStopsAuthorizationBeforeTheSchedulerRunsButKeepsTheRenewalHistoryAvailable() {
        var workspace = workspace("expired-application");
        var applicant = accounts.saveAndFlush(new Account("Expired applicant"));
        var approved = request(workspace, applicant);
        approved.setStatus(WorkspaceAccessRequest.Status.APPROVED);
        requests.saveAndFlush(approved);
        var member = membership(workspace, applicant, WorkspaceRole.MEMBER);
        member.setSource(WorkspaceAccountMembership.Source.REQUEST);
        member.setAccessRequestId(approved.getId());
        member.setExpiresAt(Instant.EPOCH);
        memberships.saveAndFlush(member);
        var pending = request(workspace, applicant);
        var session = token(applicant);
        client.get()
                .uri(path(workspace, "/members/me"))
                .headers(headers -> headers.setBearerAuth(session))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri(path(workspace, "/access-requests/me"))
                .headers(headers -> headers.setBearerAuth(session))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2);
        assertThat(memberships.findActiveByAccountId(Objects.requireNonNull(applicant.getId())))
                .isEmpty();
        assertThat(requests.findById(pending.getId()))
                .get()
                .satisfies(value -> assertThat(value.getStatus()).isEqualTo(WorkspaceAccessRequest.Status.SUBMITTED));
    }

    @Test
    void evenAnOwnerCannotReviewTheirOwnRequestOrARequestInAnotherWorkspace() {
        var workspace = workspace("review-application");
        var otherWorkspace = workspace("foreign-application");
        var owner = accounts.saveAndFlush(new Account("Owner"));
        var applicant = accounts.saveAndFlush(new Account("Applicant"));
        membership(workspace, owner, WorkspaceRole.OWNER);
        var own = request(workspace, owner);
        var foreign = request(otherWorkspace, applicant);
        var review = new WorkspaceAccessRequestService.ReviewWorkspaceAccessRequestDTO(
                0, WorkspaceAccessRequestService.Decision.REJECT, null, "Not eligible");
        var session = token(owner);
        client.patch()
                .uri(path(workspace, "/access-requests/" + own.getId()))
                .headers(headers -> headers.setBearerAuth(session))
                .bodyValue(review)
                .exchange()
                .expectStatus()
                .isForbidden();
        client.patch()
                .uri(path(workspace, "/access-requests/" + foreign.getId()))
                .headers(headers -> headers.setBearerAuth(session))
                .bodyValue(review)
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void administratorCanReadReviewOptionsWithoutLinkingTheApplicantProviderButNonmembersCannot() {
        var workspace = workspace("review-options");
        var administrator = accounts.saveAndFlush(new Account("Administrator"));
        membership(workspace, administrator, WorkspaceRole.ADMIN);
        var applicant = accounts.saveAndFlush(new Account("Applicant"));
        client.get()
                .uri(path(workspace, "/access-requests/options"))
                .headers(headers -> headers.setBearerAuth(token(administrator)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.maintainers.length()")
                .isEqualTo(0);
        client.get()
                .uri(path(workspace, "/access-requests/options"))
                .headers(headers -> headers.setBearerAuth(token(applicant)))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void onlyOwnerCanReplaceRequestManagedExpiryWithPermanentManualAccess() {
        var workspace = workspace("request-adoption");
        var owner = accounts.saveAndFlush(new Account("Owner"));
        var admin = accounts.saveAndFlush(new Account("Administrator"));
        var applicant = accounts.saveAndFlush(new Account("Applicant"));
        membership(workspace, owner, WorkspaceRole.OWNER);
        membership(workspace, admin, WorkspaceRole.ADMIN);
        var request = request(workspace, applicant);
        var grant = membership(workspace, applicant, WorkspaceRole.MEMBER);
        grant.setSource(WorkspaceAccountMembership.Source.REQUEST);
        grant.setAccessRequestId(request.getId());
        grant.setExpiresAt(Instant.parse("2099-01-01T00:00:00Z"));
        memberships.saveAndFlush(grant);
        var assignment = new de.tum.cit.aet.hephaestus.workspace.dto.AssignRoleRequestDTO(
                Objects.requireNonNull(applicant.getId()), WorkspaceRole.MEMBER);
        client.get()
                .uri(path(workspace, "/members/" + applicant.getId()))
                .headers(headers -> headers.setBearerAuth(token(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.expiresAt")
                .isNotEmpty();
        client.post()
                .uri(path(workspace, "/members/assign"))
                .headers(headers -> headers.setBearerAuth(token(admin)))
                .bodyValue(assignment)
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri(path(workspace, "/members/assign"))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(assignment)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.source")
                .isEqualTo("MANUAL");
        assertThat(memberships.findByWorkspace_IdAndAccountId(workspace.getId(), applicant.getId()))
                .get()
                .satisfies(member -> {
                    assertThat(member.getExpiresAt()).isNull();
                    assertThat(member.getAccessRequestId()).isNull();
                });
    }

    private Workspace workspace(String slug) {
        var workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(slug);
        workspace.setAccountLogin(slug);
        workspace.setAccountType(AccountType.ORG);
        workspace.setIsPubliclyViewable(false);
        return workspaces.saveAndFlush(workspace);
    }

    private WorkspaceAccountMembership membership(Workspace workspace, Account account, WorkspaceRole role) {
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(Objects.requireNonNull(account.getId()));
        membership.setRole(role);
        return memberships.saveAndFlush(membership);
    }

    private WorkspaceAccessRequest request(Workspace workspace, Account account) {
        var request = new WorkspaceAccessRequest();
        request.setWorkspace(workspace);
        request.setAccountId(Objects.requireNonNull(account.getId()));
        request.setSubmittedAt(Instant.now());
        request.setPolicySnapshot(new WorkspaceAccessPolicySettings(
                "github",
                "Code of conduct",
                "I agree",
                List.of(),
                List.of(),
                1L,
                List.of(),
                90,
                14,
                "admin@example.test",
                null));
        request.setSubmission(new WorkspaceAccessRequest.Submission(
                new WorkspaceAccessDetails(1L, List.of(), Instant.now().plusSeconds(86400)),
                List.of(),
                List.of(),
                null));
        return requests.saveAndFlush(request);
    }

    private String token(Account account) {
        return issuer.issue(principals.forAccount(account), TokenConstraints.session(null, Instant.now()), null)
                .value();
    }

    private String path(Workspace workspace, String suffix) {
        return "/workspaces/" + workspace.getWorkspaceSlug() + suffix;
    }
}

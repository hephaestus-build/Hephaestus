package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.dto.AssignRoleRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceAccountMembershipDTO;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Real signed sessions exercise SQL-backed account authorization without any SCM identity fixtures. */
class WorkspaceMembershipControllerIntegrationTest extends RealAuthIntegrationTest {
    @Autowired
    private WebTestClient client;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceAccountMembershipRepository memberships;

    @Autowired
    private HephaestusJwtIssuer issuer;

    @Autowired
    private JwtPrincipalFactory principals;

    @Test
    void shouldReturnMembershipWhenAccountHasNoScmIdentity() {
        var owner = account("Organization owner");
        var workspace = workspace("account-membership", owner);
        client.get()
                .uri(path(workspace, "/me"))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accountId")
                .isEqualTo(id(owner))
                .jsonPath("$.displayName")
                .isEqualTo("Organization owner")
                .jsonPath("$.role")
                .isEqualTo("OWNER")
                .jsonPath("$.userId")
                .doesNotExist();
    }

    @Test
    void shouldListAccountMembersIncludingSuspensionsForAdministrators() {
        var owner = account("Owner");
        var workspace = workspace("account-roster", owner);
        var member = account("Member");
        var membership = membership(workspace, member, WorkspaceRole.MEMBER);
        membership.setSuspended(true);
        memberships.saveAndFlush(membership);
        var result = client.get()
                .uri(path(workspace, ""))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(WorkspaceAccountMembershipDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(result).isNotNull();
        assertThat(result)
                .extracting(WorkspaceAccountMembershipDTO::accountId)
                .containsExactlyInAnyOrder(id(owner), id(member));
        assertThat(result)
                .filteredOn(row -> row.accountId().equals(id(member)))
                .singleElement()
                .extracting(WorkspaceAccountMembershipDTO::suspended)
                .isEqualTo(true);
    }

    @Test
    void shouldDenyPrivateWorkspaceAccessToAnUnrelatedAccount() {
        var owner = account("Owner");
        var workspace = workspace("private-account-space", owner);
        var stranger = account("Stranger");
        client.get()
                .uri(path(workspace, "/me"))
                .headers(headers -> headers.setBearerAuth(token(stranger)))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get().uri(path(workspace, "")).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void shouldNotExposeOtherAccountsInAPublicWorkspace() {
        var owner = account("Owner");
        var workspace = workspace("public-account-space", owner);
        workspace.setIsPubliclyViewable(true);
        workspaces.saveAndFlush(workspace);
        var stranger = account("Stranger");
        client.get()
                .uri(path(workspace, ""))
                .headers(headers -> headers.setBearerAuth(token(stranger)))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri("/workspaces/{slug}/contributors", workspace.getWorkspaceSlug())
                .exchange()
                .expectStatus()
                .isOk();
    }

    @ParameterizedTest
    @EnumSource(
            value = WorkspaceRole.class,
            names = {"OWNER", "ADMIN", "MEMBER"})
    void shouldRejectMemberRoleMutationsRegardlessOfRequestedRole(WorkspaceRole role) {
        var owner = account("Owner");
        var workspace = workspace("member-cannot-admin", owner);
        var member = account("Member");
        membership(workspace, member, WorkspaceRole.MEMBER);
        assign(workspace, token(member), member, role).expectStatus().isForbidden();
    }

    @Test
    void shouldPreventAdministratorsFromGrantingOrRemovingOwnership() {
        var owner = account("Owner");
        var workspace = workspace("owner-boundary", owner);
        var admin = account("Admin");
        membership(workspace, admin, WorkspaceRole.ADMIN);
        var session = token(admin);
        assign(workspace, session, admin, WorkspaceRole.OWNER).expectStatus().isForbidden();
        assign(workspace, session, owner, WorkspaceRole.MEMBER).expectStatus().isForbidden();
        client.delete()
                .uri(path(workspace, "/" + id(owner)))
                .headers(headers -> headers.setBearerAuth(session))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void shouldRequireAnExplicitTransferBeforeDemotingTheLastOwner() {
        var owner = account("Owner");
        var workspace = workspace("owner-transfer", owner);
        var successor = account("Successor");
        var session = token(owner);
        assign(workspace, session, owner, WorkspaceRole.ADMIN).expectStatus().isEqualTo(409);
        assign(workspace, session, successor, WorkspaceRole.OWNER)
                .expectStatus()
                .isOk();
        assign(workspace, session, owner, WorkspaceRole.ADMIN).expectStatus().isOk();
        assertThat(memberships.countByWorkspace_IdAndRoleAndSuspendedFalse(workspace.getId(), WorkspaceRole.OWNER))
                .isEqualTo(1);
        assertThat(memberships.findByWorkspace_IdAndAccountId(workspace.getId(), id(successor)))
                .get()
                .extracting(WorkspaceAccountMembership::getRole)
                .isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    void shouldRevokeAccessImmediatelyAndRestoreOnlyAfterExplicitAssignment() {
        var owner = account("Owner");
        var workspace = workspace("suspend-membership", owner);
        var member = account("Member");
        membership(workspace, member, WorkspaceRole.MEMBER);
        var memberSession = token(member);
        var ownerSession = token(owner);
        client.delete()
                .uri(path(workspace, "/" + id(member)))
                .headers(headers -> headers.setBearerAuth(ownerSession))
                .exchange()
                .expectStatus()
                .isNoContent();
        client.get()
                .uri(path(workspace, "/me"))
                .headers(headers -> headers.setBearerAuth(memberSession))
                .exchange()
                .expectStatus()
                .isForbidden();
        assertThat(memberships.findByWorkspace_IdAndAccountId(workspace.getId(), id(member)))
                .get()
                .extracting(WorkspaceAccountMembership::isSuspended)
                .isEqualTo(true);
        assign(workspace, ownerSession, member, WorkspaceRole.MEMBER)
                .expectStatus()
                .isOk();
        client.get()
                .uri(path(workspace, "/me"))
                .headers(headers -> headers.setBearerAuth(memberSession))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void shouldScopeAccountLookupsToTheRequestedWorkspace() {
        var owner = account("Owner");
        var first = workspace("membership-first", owner);
        var second = workspace("membership-second", owner);
        var member = account("Member");
        membership(first, member, WorkspaceRole.MEMBER);
        client.get()
                .uri(path(second, "/" + id(member)))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void shouldSerializeConcurrentDemotionsOfTheFinalTwoOwners() throws Exception {
        var first = account("First owner");
        var second = account("Second owner");
        var workspace = workspace("concurrent-owners", first);
        membership(workspace, second, WorkspaceRole.OWNER);
        String firstToken = token(first);
        String secondToken = token(second);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = executor.submit(() -> {
                start.await();
                return assign(workspace, firstToken, first, WorkspaceRole.ADMIN)
                        .returnResult(Void.class)
                        .getStatus()
                        .value();
            });
            var secondResult = executor.submit(() -> {
                start.await();
                return assign(workspace, secondToken, second, WorkspaceRole.ADMIN)
                        .returnResult(Void.class)
                        .getStatus()
                        .value();
            });
            start.countDown();
            assertThat(List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(memberships.countByWorkspace_IdAndRoleAndSuspendedFalse(workspace.getId(), WorkspaceRole.OWNER))
                .isEqualTo(1);
    }

    @Test
    void shouldElevateInstanceAdminsWithoutInventingOwnership() {
        var owner = account("Owner");
        var workspace = workspace("instance-elevation", owner);
        var admin = account("Instance admin");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        accounts.saveAndFlush(admin);
        var session = token(admin);
        client.get()
                .uri(path(workspace, "/me"))
                .headers(headers -> headers.setBearerAuth(session))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.role")
                .isEqualTo("ADMIN");
        assign(workspace, session, admin, WorkspaceRole.OWNER).expectStatus().isForbidden();
        assertThat(memberships.findByWorkspace_IdAndAccountId(workspace.getId(), id(admin)))
                .isEmpty();
    }

    @Test
    void shouldRequireOwnershipTransferBeforeDeletingAnAccount() {
        var owner = account("Owner");
        var workspace = workspace("owner-deletion", owner);
        client.delete()
                .uri("/user")
                .header("X-Confirm-Delete", id(owner).toString())
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        assertThat(accounts.findById(Objects.requireNonNull(id(owner))))
                .get()
                .extracting(Account::getStatus)
                .isEqualTo(Account.Status.ACTIVE);
        assertThat(memberships.countByWorkspace_IdAndRoleAndSuspendedFalse(workspace.getId(), WorkspaceRole.OWNER))
                .isEqualTo(1);
        var successor = account("Successor");
        var session = token(owner);
        assign(workspace, session, successor, WorkspaceRole.OWNER)
                .expectStatus()
                .isOk();
        client.delete()
                .uri("/user")
                .header("X-Confirm-Delete", id(owner).toString())
                .headers(headers -> headers.setBearerAuth(session))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(accounts.findById(id(owner)))
                .get()
                .extracting(Account::getStatus)
                .isEqualTo(Account.Status.DELETING);
        assertThat(memberships.findByWorkspace_IdAndAccountId(workspace.getId(), id(owner)))
                .get()
                .extracting(WorkspaceAccountMembership::isSuspended)
                .isEqualTo(true);
        client.get()
                .uri(path(workspace, "/me"))
                .headers(headers -> headers.setBearerAuth(token(successor)))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void shouldAllowOnlyInstanceAdminToAppointFirstOwnerWhenInstallationHasNoAccountOwner() {
        var operator = account("Installation operator");
        operator.setAppRole(Account.AppRole.APP_ADMIN);
        accounts.saveAndFlush(operator);
        var candidate = account("Initial owner");
        var workspace = workspace("unclaimed-installation", candidate);
        memberships.deleteAll(memberships.findByWorkspace_Id(workspace.getId()));
        var admin = account("Workspace administrator");
        membership(workspace, admin, WorkspaceRole.ADMIN);
        assign(workspace, token(admin), admin, WorkspaceRole.OWNER)
                .expectStatus()
                .isForbidden();
        assign(workspace, token(operator), candidate, WorkspaceRole.OWNER)
                .expectStatus()
                .isOk();
        assertThat(memberships.findByWorkspace_IdAndAccountId(workspace.getId(), id(candidate)))
                .get()
                .extracting(WorkspaceAccountMembership::getRole)
                .isEqualTo(WorkspaceRole.OWNER);
        assign(workspace, token(operator), operator, WorkspaceRole.OWNER)
                .expectStatus()
                .isForbidden();
    }

    private static Long id(Account account) {
        return Objects.requireNonNull(account.getId());
    }

    private Account account(String name) {
        return accounts.saveAndFlush(new Account(name));
    }

    private Workspace workspace(String slug, Account owner) {
        var workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(slug);
        workspace.setAccountLogin(slug);
        workspace.setAccountType(AccountType.ORG);
        workspace.setIsPubliclyViewable(false);
        workspace = workspaces.saveAndFlush(workspace);
        membership(workspace, owner, WorkspaceRole.OWNER);
        return workspace;
    }

    private WorkspaceAccountMembership membership(Workspace workspace, Account account, WorkspaceRole role) {
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(Objects.requireNonNull(account.getId()));
        membership.setRole(role);
        return memberships.saveAndFlush(membership);
    }

    private String token(Account account) {
        return issuer.issue(principals.forAccount(account), TokenConstraints.session(null, Instant.now()), null)
                .value();
    }

    private String path(Workspace workspace, String suffix) {
        return "/workspaces/" + workspace.getWorkspaceSlug() + "/members" + suffix;
    }

    private WebTestClient.ResponseSpec assign(
            Workspace workspace, String session, Account account, WorkspaceRole role) {
        return client.post()
                .uri(path(workspace, "/assign"))
                .headers(headers -> headers.setBearerAuth(session))
                .bodyValue(new AssignRoleRequestDTO(Objects.requireNonNull(account.getId()), role))
                .exchange();
    }
}

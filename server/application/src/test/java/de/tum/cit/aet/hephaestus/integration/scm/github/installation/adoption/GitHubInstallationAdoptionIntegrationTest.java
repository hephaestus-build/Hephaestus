package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService.AppInstallation;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppUserAuthorizationClient;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppUserAuthorizationClient.UserInstallation;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Under the default {@code ADMIN_ONLY} policy, a GitHub App installation gets a workspace only when an instance admin
 * adopts it. Every case asserts on the rows that it created.
 */
@TestPropertySource(properties = "hephaestus.workspace.creation-policy=ADMIN_ONLY")
class GitHubInstallationAdoptionIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @MockitoBean
    private GitHubAppTokenService appTokens;

    @Autowired
    private GitHubAppUserAuthorizationClient userAuthorization;

    @Autowired
    private ProvisioningListener provisioning;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private GitHubInstallationRequestRepository requests;

    @Autowired
    private GitHubInstallationRequestPersonDataCatalog privacy;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private AuthEventRepository authEvents;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private WebTestClient client;

    private long installationId;
    private long accountId;
    private String login;

    @BeforeEach
    void setUp() {
        ensureGitHubProvider();
        installationId = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
        accountId = ThreadLocalRandom.current().nextLong(1_000_000, 1_000_000_000);
        login = "install-" + accountId;
    }

    @AfterEach
    void tearDown() {
        reset(userAuthorization);
    }

    @Test
    void shouldRecordANewInstallationWithoutCreatingAWorkspace() {
        installationCreated(ProvisioningListener.AccountType.USER);
        installationCreated(ProvisioningListener.AccountType.USER);

        assertThat(workspaces.findByInstallationId(installationId)).isEmpty();
        assertThat(workspaces.findByAccountLoginIgnoreCase(login)).isEmpty();
        GitHubInstallationRequest request = request();
        assertThat(request.getStatus()).isEqualTo(GitHubInstallationRequestStatus.PENDING);
        assertThat(request.getAccountId()).isEqualTo(accountId);
        assertThat(request.getAccountLogin()).isEqualTo(login);
        assertThat(request.getAccountKind()).isEqualTo(GitHubInstallationAccountKind.USER);
        assertThat(request.getDecidedByAccountId()).isNull();
    }

    @Test
    void shouldKeepAnInstallationThatAWorkspaceAlreadyHas() {
        Workspace existing = createWorkspace(login, login, login, AccountType.ORG, persistUser("holder-" + accountId));
        connections.save(new Connection(
                existing,
                IntegrationKind.GITHUB,
                Long.toString(installationId),
                new ConnectionConfig.GitHubAppConfig(installationId, login, null, Set.of())));

        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);

        assertThat(workspaces.findByInstallationId(installationId))
                .map(Workspace::getId)
                .contains(existing.getId());
        assertThat(requests.findById(installationId)).isEmpty();
    }

    @Test
    void shouldJoinOnlyTheWorkspaceOfTheOrganizationWhenWorkspacesShareItsLogin() {
        Workspace unrelated = createWorkspace(
                "shared-a-" + accountId, "Shared A", login, AccountType.ORG, persistUser("a-" + accountId));
        Workspace organizations = createWorkspace(
                "shared-b-" + accountId, "Shared B", login, AccountType.ORG, persistUser("b-" + accountId));
        organizations.setOrganizationId(organizationService
                .upsertIdentity(accountId, login, githubProviderId())
                .getId());
        workspaces.saveAndFlush(organizations);

        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);

        assertThat(workspaces.findByInstallationId(installationId))
                .map(Workspace::getId)
                .contains(organizations.getId());
        assertThat(connections.findActive(unrelated.getId(), IntegrationKind.GITHUB))
                .isEmpty();
        assertThat(requests.findById(installationId)).isEmpty();
    }

    @Test
    void shouldRecordAnInstallationWhoseLoginSeveralUnboundWorkspacesShare() {
        createWorkspace("shared-c-" + accountId, "Shared C", login, AccountType.ORG, persistUser("c-" + accountId));
        createWorkspace("shared-d-" + accountId, "Shared D", login, AccountType.ORG, persistUser("d-" + accountId));

        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);

        assertThat(workspaces.findByInstallationId(installationId)).isEmpty();
        assertThat(request().getStatus()).isEqualTo(GitHubInstallationRequestStatus.PENDING);
    }

    @Test
    void shouldRefuseToAdoptAnInstallationThatGitHubNoLongerHas() {
        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);
        Account admin = persistInstanceAdmin("Adopting admin");
        when(appTokens.installation(installationId)).thenThrow(new InstallationNotFoundException(installationId));

        adopt(admin, HttpStatus.CONFLICT);

        assertUnchangedAndWithoutWorkspace();
    }

    @Test
    void shouldRefuseToAdoptASuspendedInstallation() {
        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);
        Account admin = persistInstanceAdmin("Adopting admin");
        when(appTokens.installation(installationId))
                .thenReturn(new AppInstallation(
                        installationId,
                        "Organization",
                        new AppInstallation.Account(accountId, login, null),
                        "selected",
                        Instant.now()));

        adopt(admin, HttpStatus.CONFLICT);

        assertUnchangedAndWithoutWorkspace();
    }

    @Test
    void shouldRefuseToAdoptAnInstallationThatMovedToAnotherAccount() {
        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);
        Account admin = persistInstanceAdmin("Adopting admin");
        when(appTokens.installation(installationId))
                .thenReturn(new AppInstallation(
                        installationId,
                        "Organization",
                        new AppInstallation.Account(accountId + 1, login, null),
                        "selected",
                        null));

        adopt(admin, HttpStatus.CONFLICT);

        assertUnchangedAndWithoutWorkspace();
    }

    @Test
    void shouldCreateTheWorkspaceWithTheAdoptingAdminAsOwnerAndAuditIt() {
        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);
        Account admin = persistInstanceAdmin("Adopting admin");
        givenGitHubConfirms("Organization");

        adopt(admin, HttpStatus.OK);

        Workspace workspace = workspaces.findByInstallationId(installationId).orElseThrow();
        assertThat(workspace.getAccountLogin()).isEqualTo(login);
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(workspace.getId(), id(admin)))
                .map(Membership::getRole)
                .contains(Membership.WorkspaceRole.OWNER);
        GitHubInstallationRequest request = request();
        assertThat(request.getStatus()).isEqualTo(GitHubInstallationRequestStatus.ADOPTED);
        assertThat(request.getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(request.getDecidedByAccountId()).isEqualTo(admin.getId());
        assertThat(request.getDecidedAt()).isNotNull();
        assertThat(decisions(admin, AuthEvent.EventType.GITHUB_INSTALLATION_ADOPTED))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getWorkspaceId()).isEqualTo(workspace.getId());
                    assertThat(event.getDetails()).doesNotContain(login);
                });
    }

    @Test
    void shouldNeverLetAReplayedInstallationEventReplaceAnAdoptedWorkspace() {
        installationCreated(ProvisioningListener.AccountType.USER);
        Account admin = persistInstanceAdmin("Adopting admin");
        givenGitHubConfirms("User");
        adopt(admin, HttpStatus.OK);
        Workspace adopted = workspaces.findByInstallationId(installationId).orElseThrow();

        installationCreated(ProvisioningListener.AccountType.USER);

        assertThat(workspaces.findByInstallationId(installationId))
                .map(Workspace::getId)
                .contains(adopted.getId());
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(adopted.getId(), id(admin)))
                .map(Membership::getRole)
                .contains(Membership.WorkspaceRole.OWNER);

        lifecycle.purgeWorkspace(adopted.getWorkspaceSlug());
        assertThat(request().getWorkspaceId()).isNull();
        // The record still stops a replay, and a personal account's login and id leave with the workspace.
        assertThat(request().getAccountLogin()).isNull();
        assertThat(request().getAccountId()).isNull();

        installationCreated(ProvisioningListener.AccountType.USER);

        assertThat(workspaces
                        .findByInstallationId(installationId)
                        .filter(w -> w.getStatus() != Workspace.WorkspaceStatus.PURGED))
                .isEmpty();
        assertThat(request().getStatus()).isEqualTo(GitHubInstallationRequestStatus.ADOPTED);
    }

    @Test
    void shouldFindNoInstallationToAdoptAfterItsUninstall() {
        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);
        Account admin = persistInstanceAdmin("Late admin");
        givenGitHubConfirms("Organization");

        installationDeleted();
        adopt(admin, HttpStatus.NOT_FOUND);

        assertThat(requests.findById(installationId)).isEmpty();
        assertThat(workspaces.findByInstallationId(installationId)).isEmpty();
    }

    @Test
    void shouldPurgeTheAdoptedWorkspaceWhenItsInstallationIsUninstalled() {
        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);
        Account admin = persistInstanceAdmin("Adopting admin");
        givenGitHubConfirms("Organization");
        adopt(admin, HttpStatus.OK);
        Workspace adopted = workspaces.findByInstallationId(installationId).orElseThrow();

        installationDeleted();

        assertThat(workspaces.findById(adopted.getId()))
                .map(Workspace::getStatus)
                .contains(Workspace.WorkspaceStatus.PURGED);
        assertThat(request().getStatus()).isEqualTo(GitHubInstallationRequestStatus.ADOPTED);
    }

    @Test
    void shouldTellTheInstallerOfADeclinedInstallationThatItWasDeclined() {
        installationCreated(ProvisioningListener.AccountType.USER);
        Account admin = persistInstanceAdmin("Deciding admin");
        client.post()
                .uri("/admin/github-installations/{id}/decline", installationId)
                .headers(headers -> headers.setBearerAuth("mock-jwt-admin-" + admin.getId()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        Account installer = linkedToGitHub("Installer", 4242L);
        givenGitHubConfirms("User");
        givenTheCodeProves(4242L, true);

        assertThat(claim(installer, HttpStatus.OK).outcome()).isEqualTo(GitHubInstallationClaimDTO.Outcome.DECLINED);
        assertThat(request().getStatus()).isEqualTo(GitHubInstallationRequestStatus.DECLINED);
    }

    @Test
    void shouldNameTheWorkspaceOnlyToItsMembers() {
        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);
        Account admin = persistInstanceAdmin("Adopting admin");
        givenGitHubConfirms("Organization");
        adopt(admin, HttpStatus.OK);
        Workspace adopted = workspaces.findByInstallationId(installationId).orElseThrow();
        Account outsider = linkedToGitHub("Organization member", 4242L);
        givenTheCodeProves(4242L, true);

        GitHubInstallationClaimDTO notMember = claim(outsider, HttpStatus.OK);
        assertThat(notMember.outcome()).isEqualTo(GitHubInstallationClaimDTO.Outcome.READY);
        assertThat(notMember.workspaceSlug()).isNull();

        Membership membership = new Membership();
        membership.setWorkspace(adopted);
        membership.setId(new Membership.Id(adopted.getId(), id(outsider)));
        membership.setRole(Membership.WorkspaceRole.MEMBER);
        workspaceMembershipRepository.save(membership);

        assertThat(claim(outsider, HttpStatus.OK).workspaceSlug()).isEqualTo(adopted.getWorkspaceSlug());
    }

    @Test
    void shouldAuditADeclineAndStillAllowAnAdoptionLater() {
        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);
        Account admin = persistInstanceAdmin("Deciding admin");

        client.post()
                .uri("/admin/github-installations/{id}/decline", installationId)
                .headers(headers -> headers.setBearerAuth("mock-jwt-admin-" + admin.getId()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        assertThat(request().getStatus()).isEqualTo(GitHubInstallationRequestStatus.DECLINED);
        assertThat(decisions(admin, AuthEvent.EventType.GITHUB_INSTALLATION_DECLINED))
                .hasSize(1);
        assertThat(workspaces.findByInstallationId(installationId)).isEmpty();

        givenGitHubConfirms("Organization");
        adopt(admin, HttpStatus.OK);

        assertThat(request().getStatus()).isEqualTo(GitHubInstallationRequestStatus.ADOPTED);
        assertThat(workspaces.findByInstallationId(installationId)).isPresent();
    }

    @Test
    void shouldRefuseTheAdminEndpointsToAnyoneButAnInstanceAdmin() {
        installationCreated(ProvisioningListener.AccountType.ORGANIZATION);
        Account member = persistAccount("Member");

        for (String action : List.of("adopt", "decline")) {
            client.post()
                    .uri("/admin/github-installations/{id}/" + action, installationId)
                    .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + member.getId()))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }
        client.get()
                .uri("/admin/github-installations")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + member.getId()))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        assertUnchangedAndWithoutWorkspace();
    }

    @Test
    void shouldRecordTheInstallationOfAVerifiedInstallerAndCreateNoWorkspace() {
        Account installer = linkedToGitHub("Installer", 4242L);
        givenGitHubConfirms("User");
        givenTheCodeProves(4242L, true);

        GitHubInstallationClaimDTO claim = claim(installer, HttpStatus.OK);

        assertThat(claim.outcome()).isEqualTo(GitHubInstallationClaimDTO.Outcome.AWAITING_APPROVAL);
        assertThat(workspaces.findByInstallationId(installationId)).isEmpty();
        assertThat(request().getStatus()).isEqualTo(GitHubInstallationRequestStatus.PENDING);
    }

    @Test
    void shouldRefuseAClaimWhoseGitHubUserCannotAccessTheInstallation() {
        Account installer = linkedToGitHub("Outsider", 4242L);
        givenTheCodeProves(4242L, false);

        claim(installer, HttpStatus.FORBIDDEN);

        assertThat(requests.findById(installationId)).isEmpty();
    }

    @Test
    void shouldRefuseAClaimFromAnAccountLinkedToAnotherGitHubUser() {
        Account installer = linkedToGitHub("Someone else", 4343L);
        givenGitHubConfirms("User");
        givenTheCodeProves(4242L, true);

        claim(installer, HttpStatus.FORBIDDEN);

        assertThat(requests.findById(installationId)).isEmpty();
    }

    @Test
    void shouldEraseAPersonsUnadoptedInstallationAndKeepTheDecidersDecision() {
        installationCreated(ProvisioningListener.AccountType.USER);
        Account admin = persistInstanceAdmin("Deciding admin");
        client.post()
                .uri("/admin/github-installations/{id}/decline", installationId)
                .headers(headers -> headers.setBearerAuth("mock-jwt-admin-" + admin.getId()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        long providerId = githubProviderId();
        PersonScope installer = new PersonScope(
                null, List.of(new PersonIdentity(providerId, Long.toString(accountId), null)), List.of());
        PersonScope decider = new PersonScope(admin.getId(), List.of(), List.of());

        erase("github_installation_request_decider", decider);
        assertThat(request().getDecidedByAccountId()).isNull();
        assertThat(request().getStatus()).isEqualTo(GitHubInstallationRequestStatus.DECLINED);

        erase("github_installation_request", installer);
        assertThat(requests.findById(installationId)).isEmpty();
    }

    private long githubProviderId() {
        return Objects.requireNonNull(ensureGitHubProvider().getId());
    }

    private static long id(Account account) {
        return Objects.requireNonNull(account.getId());
    }

    private void installationCreated(ProvisioningListener.AccountType type) {
        transactions.executeWithoutResult(status -> provisioning.onInstallationCreated(
                new ProvisioningListener.InstallationData(installationId, accountId, login, type, null, List.of())));
    }

    private void installationDeleted() {
        transactions.executeWithoutResult(status -> provisioning.onInstallationDeleted(installationId));
    }

    private GitHubInstallationRequest request() {
        return requests.findById(installationId).orElseThrow();
    }

    private void assertUnchangedAndWithoutWorkspace() {
        GitHubInstallationRequest request = request();
        assertThat(request.getStatus()).isEqualTo(GitHubInstallationRequestStatus.PENDING);
        assertThat(request.getDecidedByAccountId()).isNull();
        assertThat(request.getWorkspaceId()).isNull();
        assertThat(workspaces.findByInstallationId(installationId)).isEmpty();
        assertThat(workspaces.findByAccountLoginIgnoreCase(login)).isEmpty();
    }

    private void givenGitHubConfirms(String targetType) {
        when(appTokens.installation(installationId))
                .thenReturn(new AppInstallation(
                        installationId,
                        targetType,
                        new AppInstallation.Account(accountId, login, null),
                        "selected",
                        null));
        when(appTokens.isInstallationMarkedSuspended(anyLong())).thenReturn(false);
    }

    private void givenTheCodeProves(long githubUserId, boolean accessible) {
        when(userAuthorization.isConfigured()).thenReturn(true);
        when(userAuthorization.exchangeCode("code-1")).thenReturn("ghu_user");
        when(userAuthorization.findAccessibleInstallation("ghu_user", installationId))
                .thenReturn(
                        accessible
                                ? Optional.of(new UserInstallation(
                                        installationId, "User", new UserInstallation.Account(accountId, login)))
                                : Optional.empty());
        when(userAuthorization.userId("ghu_user")).thenReturn(githubUserId);
    }

    private Account linkedToGitHub(String name, long githubUserId) {
        Account account = persistAccount(name);
        Identity link = new Identity();
        link.setAccount(account);
        link.setProviderId(githubProviderId());
        link.setProviderUserKey(Long.toString(githubUserId));
        identities.saveAndFlush(link);
        return account;
    }

    private void adopt(Account admin, HttpStatus status) {
        client.post()
                .uri("/admin/github-installations/{id}/adopt", installationId)
                .headers(headers -> headers.setBearerAuth("mock-jwt-admin-" + admin.getId()))
                .exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectBody(Void.class);
    }

    private GitHubInstallationClaimDTO claim(Account caller, HttpStatus status) {
        if (status != HttpStatus.OK) {
            client.post()
                    .uri("/github-installations/{id}/claim", installationId)
                    .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + caller.getId()))
                    .bodyValue(Map.of("code", "code-1"))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(status)
                    .expectBody(Void.class);
            return new GitHubInstallationClaimDTO(GitHubInstallationClaimDTO.Outcome.AWAITING_APPROVAL, null);
        }
        GitHubInstallationClaimDTO body = client.post()
                .uri("/github-installations/{id}/claim", installationId)
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + caller.getId()))
                .bodyValue(Map.of("code", "code-1"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(GitHubInstallationClaimDTO.class)
                .returnResult()
                .getResponseBody();
        return Objects.requireNonNull(body);
    }

    private List<AuthEvent> decisions(Account admin, AuthEvent.EventType type) {
        return authEvents.findByAccountSince(id(admin), Instant.EPOCH).stream()
                .filter(event -> event.getEventType() == type
                        && ("{\"installationId\":" + installationId + "}").equals(event.getDetails()))
                .toList();
    }

    private void erase(String store, PersonScope scope) {
        PersonDataContributor contributor = privacy.contributors().stream()
                .filter(c -> c.store().equals(store))
                .findFirst()
                .orElseThrow();
        transactions.executeWithoutResult(status -> contributor.erase(contributor.select(scope), scope));
    }
}

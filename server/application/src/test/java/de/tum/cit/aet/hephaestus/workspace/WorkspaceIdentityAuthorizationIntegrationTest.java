package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.ActivityTargetType;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceIdentityAuthorizationIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private WebTestClient client;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private AccountIdentityQuery identityQuery;

    @Autowired
    private PeopleRegistry people;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ActivityEventRepository activityEvents;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitoredRepositories;

    @Test
    void shouldRejectAnUnlinkedCreatorInsteadOfUsingTheirNamesake() {
        var account = accounts.saveAndFlush(new Account("Unlinked creator"));
        var accountId = Objects.requireNonNull(account.getId());
        persistUser("account-" + accountId);
        var request = new CreateWorkspaceRequestDTO(
                "unlinked-creation",
                "Unlinked",
                "unlinked",
                AccountType.ORG,
                IntegrationKind.GITHUB,
                "test-token",
                null,
                null,
                null);

        client.post()
                .uri("/workspaces")
                .headers(headers -> headers.setBearerAuth("mock-jwt-sub-" + accountId))
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        assertThat(workspaces.findByWorkspaceSlug("unlinked-creation")).isEmpty();
    }

    @Test
    void shouldOwnAGitHubWorkspaceThroughTheGitHubActorWhenTheAccountLinkedGitLabFirst() {
        var account = accounts.saveAndFlush(new Account("Linked twice"));
        var gitLabActor =
                userRepository.saveAndFlush(TestUserFactory.createUser(990L, "linked-twice", ensureGitLabProvider()));
        var gitHubActor = persistUser("linked-twice");
        for (var actor : List.of(gitLabActor, gitHubActor)) {
            var link = new Identity();
            link.setAccount(account);
            link.setProviderId(Objects.requireNonNull(actor.getProvider().getId()));
            link.setProviderUserKey(actor.getNativeId().toString());
            identities.saveAndFlush(link);
        }
        var request = new CreateWorkspaceRequestDTO(
                "github-owner",
                "GitHub owner",
                "github-owner",
                AccountType.ORG,
                IntegrationKind.GITHUB,
                "t",
                null,
                null,
                null);

        client.post()
                .uri("/workspaces")
                .headers(headers -> headers.setBearerAuth("mock-jwt-sub-" + account.getId()))
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(Void.class);

        var workspace = workspaces.findByWorkspaceSlug("github-owner").orElseThrow();
        assertThat(workspaceMembershipRepository.findByWorkspace_Id(workspace.getId()))
                .singleElement()
                .satisfies(membership ->
                        assertThat(membership.getId().getAccountId()).isEqualTo(accountId(gitHubActor)));
    }

    @Test
    void shouldKeepTheAccountMembershipWhenLoginsCollideOrItsProviderLinkIsDisabled() {
        persistUser("shared-login");
        var provider = ensureGitLabProvider();
        var providerId = provider.getId();
        assertNotNull(providerId);
        var owner = userRepository.saveAndFlush(TestUserFactory.createUser(987L, "shared-login", provider));
        var workspace = createWorkspace("identity-http", "Identity", "identity", AccountType.ORG, owner);
        workspace.setPublicActivityEnabled(false);
        workspaces.saveAndFlush(workspace);
        connectActivitySource(workspace, provider);
        people.claim(new PersonIdentity(providerId, owner.getNativeId().toString(), null));
        var account = accounts.findById(accountId(owner)).orElseThrow();
        var accountId = account.getId();
        assertNotNull(accountId);
        var link = identities
                .findActiveByProviderSubject(providerId, owner.getNativeId().toString(), null)
                .orElseThrow();
        String token = "mock-jwt-user-sub-" + accountId;
        String path = "/workspaces/identity-http/members/me";

        client.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.account.id")
                .isEqualTo(Long.toString(accountId));

        owner.setLogin("renamed-login");
        userRepository.saveAndFlush(owner);
        userRepository.saveAndFlush(TestUserFactory.createUser(988L, "shared-login", provider));
        client.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.account.id")
                .isEqualTo(Long.toString(accountId))
                .jsonPath("$.account.identities[0].login")
                .isEqualTo("renamed-login");

        link.setDisabledAt(Instant.now());
        identities.saveAndFlush(link);
        client.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.account.id")
                .isEqualTo(Long.toString(accountId))
                .jsonPath("$.account.identities.length()")
                .isEqualTo(0);
        assertThat(identities.findActiveByAccountId(accountId)).isEmpty();
        assertThat(accounts.findById(accountId)).isPresent();
    }

    @Test
    void shouldReadTheSignedInActorsOwnActivityWhenAnEarlierMemberSharesItsLogin() {
        var namesake = persistUser("shared-profile");
        var provider = ensureGitLabProvider();
        var actor = userRepository.saveAndFlush(TestUserFactory.createUser(989L, "shared-profile", provider));
        var workspace = createWorkspace("identity-profile", "Profile", "profile", AccountType.ORG, namesake);
        ensureMembership(workspace, actor, Membership.WorkspaceRole.MEMBER);
        people.claim(new PersonIdentity(
                Objects.requireNonNull(provider.getId()), actor.getNativeId().toString(), null));
        var account = accounts.findById(accountId(actor)).orElseThrow();
        connectActivitySource(workspace, provider);
        recordIssueOpened(workspace, namesake);
        recordIssueOpened(workspace, actor);

        client.get()
                .uri("/workspaces/identity-profile/activity/work?login=shared-profile")
                .headers(headers -> headers.setBearerAuth("mock-jwt-user-sub-" + account.getId()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].people[0].id")
                .isEqualTo(actor.getId());
    }

    @Test
    void shouldUseOneAccountMembershipWithoutChangingTheFirstLinkedActor() {
        var olderActor = persistUser("older-http-actor");
        var firstActor = userRepository.saveAndFlush(
                TestUserFactory.createUser(991L, "first-http-actor", ensureGitLabProvider()));
        var account = accounts.saveAndFlush(new Account("Multi-identity member"));
        var accountId = account.getId();
        assertNotNull(accountId);
        for (var actor : List.of(firstActor, olderActor)) {
            var link = new Identity();
            link.setAccount(account);
            link.setProviderId(Objects.requireNonNull(actor.getProvider().getId()));
            link.setProviderUserKey(actor.getNativeId().toString());
            identities.saveAndFlush(link);
        }
        var workspace = createWorkspace("identity-http-roles", "Roles", "roles", AccountType.ORG, olderActor);
        connectActivitySource(workspace, firstActor.getProvider());
        client.get()
                .uri("/workspaces/identity-http-roles/members/me")
                .headers(headers -> headers.setBearerAuth("mock-jwt-user-sub-" + accountId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.account.id")
                .isEqualTo(Long.toString(accountId))
                .jsonPath("$.account.identities[0].login")
                .isEqualTo(firstActor.getLogin())
                .jsonPath("$.role")
                .isEqualTo("OWNER");
    }

    @ParameterizedTest
    @EnumSource(Account.Status.class)
    void shouldAdmitNewInteractionsOnlyWhenTheLinkedAccountIsActive(Account.Status status) {
        var providerId = ensureGitLabProvider().getId();
        assertNotNull(providerId);
        var account = new Account("Lifecycle identity");
        account.setStatus(status);
        account = accounts.saveAndFlush(account);
        var accountId = account.getId();
        assertNotNull(accountId);
        var link = new Identity();
        link.setAccount(account);
        link.setProviderId(providerId);
        link.setProviderUserKey("789");
        identities.saveAndFlush(link);

        // Account lifecycle gates new interactions, not attribution of existing work or account
        // exports.
        assertThat(identityQuery.resolveAccountId(providerId, "789", null)).contains(accountId);
        assertThat(identityQuery.resolveActiveAccountId(providerId, "789", null).isPresent())
                .isEqualTo(status == Account.Status.ACTIVE);
    }

    private void recordIssueOpened(Workspace workspace, User actor) {
        UUID id = UUID.randomUUID();
        Repository repository = new Repository();
        repository.setProvider(actor.getProvider());
        repository.setNativeId(id.getMostSignificantBits());
        repository.setName("issues-" + id);
        repository.setNameWithOwner("profile/" + repository.getName());
        repository.setHtmlUrl("https://example.com/" + repository.getNameWithOwner());
        repository.setDefaultBranch("main");
        repository = repositories.save(repository);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        monitoredRepositories.save(monitor);
        activityEvents.insertIfAbsent(
                id,
                "issue-" + id,
                ActivityEventType.ISSUE_CREATED.name(),
                Instant.now().minusSeconds(60),
                actor.getId(),
                workspace.getId(),
                repository.getId(),
                ActivityTargetType.ISSUE.getValue(),
                id.getMostSignificantBits());
    }
}

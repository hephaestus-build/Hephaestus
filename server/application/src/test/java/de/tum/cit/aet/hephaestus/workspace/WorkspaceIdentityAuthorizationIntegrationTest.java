package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
    private IdentityLinkRepository identities;

    @Autowired
    private AccountIdentityQuery identityQuery;

    @Autowired
    private WorkspaceRepository workspaces;

    @Test
    void shouldKeepAccountOwnershipButRemoveScmAttributionWhenItsLinkIsDisabled() {
        var namesake = persistUser("shared-login");
        var provider = ensureGitLabProvider();
        var providerId = provider.getId();
        assertNotNull(providerId);
        var owner = userRepository.saveAndFlush(TestUserFactory.createUser(987L, "shared-login", provider));
        var account = accounts.saveAndFlush(new Account("Verified owner"));
        var accountId = account.getId();
        assertNotNull(accountId);
        var link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(providerId);
        link.setSubject(owner.getNativeId().toString());
        link.setUsernameAtSignup("shared-login");
        link.setExternalActorId(namesake.getId());
        link = identities.saveAndFlush(link);
        var workspace = createWorkspace("identity-http", "Identity", "identity", AccountType.ORG, owner);
        workspace.setIsPubliclyViewable(false);
        workspaces.saveAndFlush(workspace);

        String token = "mock-jwt-user-sub-" + accountId;
        String path = "/workspaces/identity-http/members/me";

        client.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accountId")
                .isEqualTo(accountId);

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
                .jsonPath("$.accountId")
                .isEqualTo(accountId)
                .jsonPath("$.scmUserLogin")
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
                .jsonPath("$.accountId")
                .isEqualTo(accountId)
                .jsonPath("$.scmUserLogin")
                .doesNotExist();
        assertThat(identities.findActiveByAccountId(accountId)).isEmpty();
        assertThat(accounts.findById(accountId)).isPresent();
    }

    @Test
    void shouldNotPromoteAnAccountFromALinkedContributorsProviderRole() {
        var provider = ensureGitLabProvider();
        var olderActor = userRepository.saveAndFlush(TestUserFactory.createUser(543L, "older-http-actor", provider));
        var firstActor = userRepository.saveAndFlush(TestUserFactory.createUser(544L, "first-http-actor", provider));
        var workspace = createWorkspace(
                "identity-http-roles", "Roles", "roles", AccountType.ORG, persistUser("workspace-owner"));
        var account = accounts.saveAndFlush(new Account("Multi-identity member"));
        var accountId = account.getId();
        assertNotNull(accountId);
        for (var actor : List.of(firstActor, olderActor)) {
            var link = new IdentityLink();
            link.setAccount(account);
            link.setProviderId(Objects.requireNonNull(actor.getProvider().getId()));
            link.setSubject(actor.getNativeId().toString());
            identities.saveAndFlush(link);
        }
        ensureWorkspaceMembership(workspace, firstActor, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, olderActor, WorkspaceMembership.WorkspaceRole.OWNER);
        client.get()
                .uri("/workspaces/identity-http-roles/members/me")
                .headers(headers -> headers.setBearerAuth("mock-jwt-user-sub-" + accountId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.scmUserLogin")
                .isEqualTo(firstActor.getLogin())
                .jsonPath("$.role")
                .isEqualTo("MEMBER");
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
        var link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(providerId);
        link.setSubject("789");
        identities.saveAndFlush(link);

        // Account lifecycle gates new interactions, not attribution of existing work or account exports.
        assertThat(identityQuery.resolveAccountId(providerId, "789", null)).contains(accountId);
        assertThat(identityQuery.resolveActiveAccountId(providerId, "789", null).isPresent())
                .isEqualTo(status == Account.Status.ACTIVE);
    }
}

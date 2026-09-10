package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
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
    private IdentityLinkRepository identities;

    @Autowired
    private AccountIdentityQuery identityQuery;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ChatThreadRepository threads;

    @Test
    void shouldNotExposeNamesakeConversationsWhenAnInstanceAdminHasNoWorkspaceActor() {
        var account = accounts.saveAndFlush(new Account("Elevated administrator"));
        var accountId = Objects.requireNonNull(account.getId());
        var namesake = persistUser("account-" + accountId);
        var workspace =
                createWorkspace("namesake-threads", "Private conversations", "namesake", AccountType.ORG, namesake);
        var thread = new ChatThread();
        var threadId = UUID.randomUUID();
        thread.setId(threadId);
        thread.setWorkspace(workspace);
        thread.setUser(namesake);
        thread.setTitle("Private conversation");
        threads.saveAndFlush(thread);
        String token = "mock-jwt-sub-" + accountId;
        String path = "/workspaces/namesake-threads/mentor/threads";

        client.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        client.get()
                .uri(path + "/" + threadId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        client.delete()
                .uri(path + "/" + threadId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        assertThat(threads.findById(threadId)).isPresent();
    }

    @Test
    void shouldRejectAnUnlinkedCreatorInsteadOfUsingTheirNamesakeOrTheSubmittedOwner() {
        var account = accounts.saveAndFlush(new Account("Unlinked creator"));
        var accountId = Objects.requireNonNull(account.getId());
        var namesake = persistUser("account-" + accountId);
        var request = new CreateWorkspaceRequestDTO(
                "unlinked-creation",
                "Unlinked",
                "unlinked",
                AccountType.ORG,
                namesake.getId(),
                IntegrationKind.GITHUB,
                "test-token",
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
    void shouldKeepTheVerifiedOwnerWhenLoginsCollideAndDenyAccessWhenItsLinkIsDisabled() {
        var namesake = persistUser("shared-login");
        var provider = ensureGitLabProvider();
        var providerId = provider.getId();
        assertNotNull(providerId);
        var owner = userRepository.saveAndFlush(TestUserFactory.createUser(987L, "shared-login", provider));
        var workspace = createWorkspace("identity-http", "Identity", "identity", AccountType.ORG, owner);
        workspace.setIsPubliclyViewable(false);
        workspaces.saveAndFlush(workspace);
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
        String token = "mock-jwt-user-sub-" + accountId;
        String path = "/workspaces/identity-http/members/me";

        client.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.userId")
                .isEqualTo(owner.getId());

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
                .jsonPath("$.userId")
                .isEqualTo(owner.getId())
                .jsonPath("$.userLogin")
                .isEqualTo("renamed-login");

        link.setDisabledAt(Instant.now());
        identities.saveAndFlush(link);
        client.get()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        assertThat(identities.findActiveByAccountId(accountId)).isEmpty();
        assertThat(accounts.findById(accountId)).isPresent();
    }

    @Test
    void shouldReportUnionedRoleWithoutChangingTheFirstLinkedActor() {
        var olderActor = persistUser("older-http-actor");
        var firstActor = persistUser("first-http-actor");
        var workspace = createWorkspace("identity-http-roles", "Roles", "roles", AccountType.ORG, olderActor);
        ensureWorkspaceMembership(workspace, firstActor, WorkspaceMembership.WorkspaceRole.MEMBER);
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
        client.get()
                .uri("/workspaces/identity-http-roles/members/me")
                .headers(headers -> headers.setBearerAuth("mock-jwt-user-sub-" + accountId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.userId")
                .isEqualTo(firstActor.getId())
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

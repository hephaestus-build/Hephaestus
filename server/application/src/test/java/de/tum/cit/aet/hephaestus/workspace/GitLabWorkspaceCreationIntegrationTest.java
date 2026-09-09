package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceListItemDTO;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.reactive.server.WebTestClient;

@DisplayName("GitLab workspace creation integration")
class GitLabWorkspaceCreationIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMembershipRepository workspaceMembershipRepository;

    @Autowired
    private WorkspaceLifecycleService workspaceLifecycleService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdentityLinkRepository identityLinkRepository;

    /**
     * Creating a GitLab workspace now requires the CALLER to have an active GitLab identity (the
     * native-auth migration's {@code AuthenticatedGitProviderUserService.ensureCurrentGitLabUserExists}).
     * Seed that precondition — a real {@link Account} + an active GitLab {@link IdentityLink} — and
     * return headers authenticating as that account (sub = the account id, via the {@code mock-jwt-sub-}
     * dynamic test token).
     */
    private Consumer<HttpHeaders> gitLabCaller(String login) {
        Account account = accountRepository.save(new Account(login));
        IdentityProvider gitlab = ensureGitLabProvider();
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(persistedId(gitlab.getId()));
        link.setSubject(String.valueOf(70_000 + persistedId(account.getId())));
        link.setUsernameAtSignup(login);
        identityLinkRepository.save(link);
        String token = "mock-jwt-sub-" + persistedId(account.getId());
        return headers -> headers.setBearerAuth(token);
    }

    /** A seeded GitLab caller plus its resolved SCM {@link User} mirror (for owner/membership setup). */
    private record GitLabCaller(Consumer<HttpHeaders> headers, User scmUser) {}

    /**
     * Like {@link #gitLabCaller}, but also provisions the SCM {@link User} mirror the auth layer would
     * lazily create (same {@code (nativeId, provider)}) and wires it as the identity's external actor, so
     * the caller resolves to a stable User that can own workspaces and appear in their listings.
     */
    private GitLabCaller gitLabCallerWithMirror(String login) {
        Account account = accountRepository.save(new Account(login));
        IdentityProvider gitlab = ensureGitLabProvider();
        long nativeId = 70_000 + persistedId(account.getId());
        // The SCM mirror's login MUST equal the token's preferred_username, because the current user is
        // resolved by login (SecurityUtils.getCurrentUserLogin → UserRepository.findByLogin). The
        // mock-jwt-sub-<id> token sets preferred_username = "account-<id>".
        String scmLogin = "account-" + persistedId(account.getId());
        User scmUser = TestUserFactory.ensureUser(userRepository, scmLogin, nativeId, gitlab);
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(persistedId(gitlab.getId()));
        link.setSubject(String.valueOf(nativeId));
        link.setUsernameAtSignup(scmLogin);
        link.setExternalActorId(persistedId(scmUser.getId()));
        identityLinkRepository.save(link);
        return new GitLabCaller(
                headers -> headers.setBearerAuth("mock-jwt-sub-" + persistedId(account.getId())), scmUser);
    }

    @Test
    void createGitLabWorkspacePersistsCorrectProviderModeAndServerUrl() {
        User owner = persistUser("mentor");
        Consumer<HttpHeaders> auth = gitLabCaller("mentor");

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-space",
                "My GitLab Workspace",
                "my-group/my-project",
                AccountType.ORG,
                persistedId(owner.getId()),
                IntegrationKind.GITLAB,
                "glpat-test-token-12345",
                "https://gitlab.example.com");

        WorkspaceDTO created = webTestClient
                .post()
                .uri("/workspaces")
                .headers(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        WorkspaceDTO workspace = Objects.requireNonNull(created);
        assertThat(workspace.workspaceSlug()).isEqualTo("gitlab-space");
        // provider classification is derived from the active Connection
        // and surfaced via `kind` (the renamed field). The legacy gitProviderMode field
        // is gone.
        assertThat(workspace.kind()).isEqualTo("GITLAB");
        assertThat(workspace.providerType()).isEqualTo(IdentityProviderType.GITLAB);
        assertThat(workspace.serverUrl()).isEqualTo("https://gitlab.example.com");
        assertThat(workspace.status()).isEqualTo("ACTIVE");
        assertThat(workspace.hasPersonalAccessToken()).isTrue();

        // Verify persisted entity exists; provider mode / PAT / server URL now live on
        // the workspace's GitLab Connection row rather than legacy Workspace columns,
        // which the WorkspaceDTO assertions above already cover end-to-end.
        Workspace persisted = workspaceRepository.findById(workspace.id()).orElseThrow();
        assertThat(persisted.getAccountLogin()).isEqualTo("my-group/my-project");
    }

    @Test
    void createGitLabWorkspaceWithDefaultServerUrlOmitsServerUrl() {
        User owner = persistUser("mentor");
        Consumer<HttpHeaders> auth = gitLabCaller("mentor");

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-default",
                "Default GitLab",
                "my-group",
                AccountType.ORG,
                persistedId(owner.getId()),
                IntegrationKind.GITLAB,
                "glpat-test-token-67890",
                null);

        WorkspaceDTO created = webTestClient
                .post()
                .uri("/workspaces")
                .headers(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        WorkspaceDTO workspace = Objects.requireNonNull(created);
        assertThat(workspace.providerType()).isEqualTo(IdentityProviderType.GITLAB);
        assertThat(workspace.serverUrl()).isNull();
    }

    @Test
    void createGitLabWorkspaceWithoutTokenReturnsValidationError() {
        User owner = persistUser("admin");
        Consumer<HttpHeaders> auth = gitLabCaller("admin");

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-notoken",
                "No Token",
                "my-group",
                AccountType.ORG,
                persistedId(owner.getId()),
                IntegrationKind.GITLAB,
                null, // missing token
                null);

        ProblemDetail problem = webTestClient
                .post()
                .uri("/workspaces")
                .headers(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Validation failed");
        assertNotNull(problem.getProperties());
        assertThat(problem.getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsKey("tokenProvided");

        assertThat(workspaceRepository.findByWorkspaceSlug("gitlab-notoken")).isEmpty();
    }

    @Test
    void createGitLabWorkspaceWithHttpServerUrlReturnsValidationError() {
        User owner = persistUser("admin");
        Consumer<HttpHeaders> auth = gitLabCaller("admin");

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-http",
                "HTTP GitLab",
                "my-group",
                AccountType.ORG,
                persistedId(owner.getId()),
                IntegrationKind.GITLAB,
                "glpat-test-token",
                "http://insecure.example.com" // not HTTPS
                );

        ProblemDetail problem = webTestClient
                .post()
                .uri("/workspaces")
                .headers(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Validation failed");
        assertNotNull(problem.getProperties());
        assertThat(problem.getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsKey("serverUrl");

        assertThat(workspaceRepository.findByWorkspaceSlug("gitlab-http")).isEmpty();
    }

    @Test
    void createGitLabWorkspaceAssignsOwnerMembership() {
        User owner = persistUser("mentor");
        Consumer<HttpHeaders> auth = gitLabCaller("mentor");

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-ownership",
                "Owner Test",
                "owner-group",
                AccountType.ORG,
                persistedId(owner.getId()),
                IntegrationKind.GITLAB,
                "glpat-owner-token",
                null);

        WorkspaceDTO created = webTestClient
                .post()
                .uri("/workspaces")
                .headers(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody();

        WorkspaceDTO workspace = Objects.requireNonNull(created);

        var membership = workspaceMembershipRepository
                .findByWorkspace_IdAndUser_Id(workspace.id(), persistedId(owner.getId()))
                .orElseThrow(() -> new AssertionError("Owner membership not created"));
        assertThat(membership.getRole()).isEqualTo(WorkspaceMembership.WorkspaceRole.OWNER);
    }

    @Test
    void createGitLabWorkspaceResponseNeverContainsRawToken() {
        User owner = persistUser("mentor");
        Consumer<HttpHeaders> auth = gitLabCaller("mentor");
        String secretToken = "test-token-placeholder";

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-secret",
                "Secret Test",
                "secret-group",
                AccountType.ORG,
                persistedId(owner.getId()),
                IntegrationKind.GITLAB,
                secretToken,
                null);

        String responseBody = webTestClient
                .post()
                .uri("/workspaces")
                .headers(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(responseBody).isNotNull();
        assertThat(responseBody).doesNotContain(secretToken);
        assertThat(responseBody).contains("\"hasPersonalAccessToken\":true");
    }

    @Test
    @WithMentorUser
    void gitLabWorkspaceAppearsInListWithCorrectProviderType() {
        // The caller owns the workspace (listing is by the current user's memberships, resolved via the
        // identity's external-actor SCM mirror), so the GitLab workspace surfaces in their list.
        GitLabCaller caller = gitLabCallerWithMirror("mentor");

        var gitlabRequest = new CreateWorkspaceRequestDTO(
                "gitlab-ws",
                "GitLab WS",
                "gitlab-group",
                AccountType.ORG,
                caller.scmUser().getId(),
                IntegrationKind.GITLAB,
                "glpat-list-token",
                null);

        webTestClient
                .post()
                .uri("/workspaces")
                .headers(caller.headers())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(gitlabRequest)
                .exchange()
                .expectStatus()
                .isCreated();

        List<WorkspaceListItemDTO> workspaces = webTestClient
                .get()
                .uri("/workspaces")
                .headers(caller.headers())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(WorkspaceListItemDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(workspaces).isNotNull();
        // providerType is derived from the active Connection: the GitLab workspace created via the REST
        // API path provisions a GitLab Connection inline and surfaces as GITLAB.
        assertThat(workspaces).extracting(WorkspaceListItemDTO::providerType).contains(IdentityProviderType.GITLAB);
    }

    @Test
    @WithAdminUser
    void gitLabWorkspaceLifecycleSuspendAndPurgeWorkCorrectly() {
        User owner = persistUser("lifecycle-owner");
        Workspace workspace = workspaceService.createWorkspace(new CreateWorkspaceRequestDTO(
                "gitlab-lifecycle",
                "Lifecycle Test",
                "lifecycle-group",
                AccountType.ORG,
                persistedId(owner.getId()),
                IntegrationKind.GITLAB,
                "glpat-lifecycle-token",
                null));
        ensureOwnerMembership(workspace);

        // Verify it's ACTIVE
        assertThat(workspace.getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);

        // Suspend
        workspaceLifecycleService.suspendWorkspace(workspace.getWorkspaceSlug());
        Workspace suspended =
                workspaceRepository.findById(persistedId(workspace.getId())).orElseThrow();
        assertThat(suspended.getStatus()).isEqualTo(Workspace.WorkspaceStatus.SUSPENDED);

        // Resume
        workspaceLifecycleService.resumeWorkspace(workspace.getWorkspaceSlug());
        Workspace resumed =
                workspaceRepository.findById(persistedId(workspace.getId())).orElseThrow();
        assertThat(resumed.getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);

        // Purge
        workspaceLifecycleService.purgeWorkspace(workspace.getWorkspaceSlug());
        Workspace purged =
                workspaceRepository.findById(persistedId(workspace.getId())).orElseThrow();
        assertThat(purged.getStatus()).isEqualTo(Workspace.WorkspaceStatus.PURGED);
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}

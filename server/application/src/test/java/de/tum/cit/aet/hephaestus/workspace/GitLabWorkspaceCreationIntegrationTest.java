package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabPreflightRequestDTO;
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
    private IdentityLinkRepository identityLinkRepository;

    @Autowired
    private LoginProviderRepository loginProviderRepository;

    @Autowired
    private GitProviderRegistry gitProviderRegistry;

    /** {@code hephaestus.integration.gitlab.default-server-url} in the test profile. */
    private static final String DEFAULT_INSTANCE = "https://gitlab.lrz.de";

    /**
     * A caller whose {@link Account} has an active GitLab {@link IdentityLink} on the default instance,
     * authenticated through the {@code mock-jwt-sub-} test token (sub = the account id).
     */
    private Consumer<HttpHeaders> gitLabCaller(String login) {
        return gitLabCaller(login, gitLabInstance(DEFAULT_INSTANCE));
    }

    /** A caller linked once on each of {@code instances}, with a distinct subject per link. */
    private Consumer<HttpHeaders> gitLabCaller(String login, IdentityProvider... instances) {
        Account account = accountRepository.save(new Account(login));
        for (IdentityProvider gitlab : instances) {
            IdentityLink link = new IdentityLink();
            link.setAccount(account);
            link.setProviderId(persistedId(gitlab.getId()));
            link.setSubject(String.valueOf(subject(account, gitlab)));
            link.setUsernameAtSignup(login);
            identityLinkRepository.save(link);
        }
        String token = "mock-jwt-sub-" + persistedId(account.getId());
        return headers -> headers.setBearerAuth(token);
    }

    private static long subject(Account account, IdentityProvider gitlab) {
        return 70_000 + persistedId(account.getId()) * 100 + persistedId(gitlab.getId());
    }

    /** A seeded GitLab caller plus its resolved SCM {@link User} mirror (for owner/membership setup). */
    private record GitLabCaller(Consumer<HttpHeaders> headers, User scmUser) {}

    /** Adds the SCM mirror belonging to the caller's verified provider subject. */
    private GitLabCaller gitLabCallerWithMirror(String login) {
        Account account = accountRepository.save(new Account(login));
        IdentityProvider gitlab = gitLabInstance(DEFAULT_INSTANCE);
        long nativeId = 70_000 + persistedId(account.getId());
        User scmUser = TestUserFactory.ensureUser(userRepository, login, nativeId, gitlab);
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(persistedId(gitlab.getId()));
        link.setSubject(String.valueOf(nativeId));
        link.setUsernameAtSignup(login);
        link.setExternalActorId(persistedId(scmUser.getId()));
        identityLinkRepository.save(link);
        return new GitLabCaller(
                headers -> headers.setBearerAuth("mock-jwt-sub-" + persistedId(account.getId())), scmUser);
    }

    private IdentityProvider gitLabInstance(String serverUrl) {
        return gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, serverUrl)
                .orElseGet(
                        () -> gitProviderRepository.save(new IdentityProvider(IdentityProviderType.GITLAB, serverUrl)));
    }

    /** An enabled GitLab login provider: what makes a self-hosted instance eligible for workspaces. */
    private void configureGitLabLogin(String registrationId, String baseUrl) {
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(LoginProvider.ProviderType.GITLAB);
        provider.setDisplayName("GitLab Example");
        provider.setBaseUrl(baseUrl);
        provider.setClientId("test-client-id");
        provider.setClientSecret("test-client-secret");
        provider.setScopes("read_user");
        loginProviderRepository.save(provider);
    }

    private WebTestClient.ResponseSpec postGitLabWorkspace(
            Consumer<HttpHeaders> auth, String slug, @Nullable String serverUrl) {
        return webTestClient
                .post()
                .uri("/workspaces")
                .headers(auth)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWorkspaceRequestDTO(
                        slug, slug, "my-group", AccountType.ORG, null, IntegrationKind.GITLAB, "glpat-test", serverUrl))
                .exchange();
    }

    @Test
    void shouldRefuseUnconfiguredInstanceBeforeCreatingTheWorkspace() {
        postGitLabWorkspace(gitLabCaller("mentor"), "gitlab-unconfigured", "https://gitlab.attacker.example")
                .expectStatus()
                .isEqualTo(422)
                .expectBody(Void.class);

        assertThat(workspaceRepository.findByWorkspaceSlug("gitlab-unconfigured"))
                .isEmpty();
    }

    @Test
    void shouldRejectPreflightServerUrlWithPathOrQueryBeforeResolvingTheInstance() {
        ProblemDetail problem = webTestClient
                .post()
                .uri("/workspaces/gitlab/preflight")
                .headers(gitLabCaller("mentor"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new GitLabPreflightRequestDTO("glpat-test", DEFAULT_INSTANCE + "/api?redirect=1", null))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(problem);
        assertNotNull(problem.getProperties());
        assertThat(problem.getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsKey("serverUrl");
    }

    @Test
    void shouldRefuseWhenCallerIsLinkedOnlyOnAnotherGitLabInstance() {
        Consumer<HttpHeaders> linkedElsewhere = gitLabCaller("elsewhere", gitLabInstance("https://gitlab.com"));

        postGitLabWorkspace(linkedElsewhere, "gitlab-other-instance", DEFAULT_INSTANCE)
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);

        assertThat(workspaceRepository.findByWorkspaceSlug("gitlab-other-instance"))
                .isEmpty();
    }

    @Test
    void shouldBindTheWorkspaceToTheProviderRowItsOwnerSignedInThroughWhenSpelledDifferently() {
        String configured = "HTTPS://GitLab.example.com:443";
        configureGitLabLogin("gitlab-example", configured);
        long signedInThrough = gitProviderRegistry.resolveProviderId("GITLAB", configured);
        IdentityProvider provider =
                gitProviderRepository.findById(signedInThrough).orElseThrow();

        WorkspaceDTO workspace = Objects.requireNonNull(
                postGitLabWorkspace(gitLabCaller("mentor", provider), "gitlab-respelled", "https://gitlab.example.com")
                        .expectStatus()
                        .isCreated()
                        .expectBody(WorkspaceDTO.class)
                        .returnResult()
                        .getResponseBody());

        // Initial project discovery resolves the stored instance to its provider row by exact URL.
        String storedInstance = Objects.requireNonNull(workspace.serverUrl());
        assertThat(gitProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITLAB, storedInstance))
                .map(IdentityProvider::getId)
                .contains(signedInThrough);
    }

    @Test
    void shouldRefuseCreationWhenTwoEnabledLoginProvidersSignInToTheSameInstance() {
        configureGitLabLogin("gitlab-example", "https://gitlab.example.com");
        configureGitLabLogin("gitlab-example-again", "HTTPS://GitLab.example.com:443");
        Consumer<HttpHeaders> linkedTwice = gitLabCaller(
                "twice",
                gitLabInstance("https://gitlab.example.com"),
                gitLabInstance("HTTPS://GitLab.example.com:443"));

        postGitLabWorkspace(linkedTwice, "gitlab-ambiguous", "https://gitlab.example.com")
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);

        assertThat(workspaceRepository.findByWorkspaceSlug("gitlab-ambiguous")).isEmpty();
    }

    @Test
    void createGitLabWorkspacePersistsCorrectProviderModeAndServerUrl() {
        User owner = persistUser("mentor");
        configureGitLabLogin("gitlab-example", "https://gitlab.example.com");
        IdentityProvider example = gitLabInstance("https://gitlab.example.com");
        Consumer<HttpHeaders> auth = gitLabCaller("mentor", example, gitLabInstance(DEFAULT_INSTANCE));

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-space",
                "My GitLab Workspace",
                "my-group/my-project",
                AccountType.ORG,
                persistedId(owner.getId()),
                IntegrationKind.GITLAB,
                "glpat-test-token-12345",
                "https://gitlab.example.com/");

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
        assertThat(workspace.kind()).isEqualTo("GITLAB");
        assertThat(workspace.providerType()).isEqualTo(IdentityProviderType.GITLAB);
        assertThat(workspace.serverUrl()).isEqualTo("https://gitlab.example.com");
        assertThat(workspace.status()).isEqualTo("ACTIVE");
        assertThat(workspace.hasPersonalAccessToken()).isTrue();

        Workspace persisted = workspaceRepository.findById(workspace.id()).orElseThrow();
        assertThat(persisted.getAccountLogin()).isEqualTo("my-group/my-project");
        User exampleActor = userRepository
                .findByLoginAndProviderId("mentor", persistedId(example.getId()))
                .orElseThrow();
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.id(), exampleActor.getId()))
                .hasValueSatisfying(membership ->
                        assertThat(membership.getRole()).isEqualTo(WorkspaceMembership.WorkspaceRole.OWNER));
    }

    @Test
    void createGitLabWorkspaceWithoutServerUrlUsesTheDefaultInstance() {
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
        assertThat(workspace.serverUrl()).isEqualTo(DEFAULT_INSTANCE);
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
    void shouldAssignOwnershipToTheVerifiedGitLabCallerInsteadOfTheSubmittedNamesake() {
        User namesake = persistUser("mentor");
        GitLabCaller caller = gitLabCallerWithMirror("mentor");

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-ownership",
                "Owner Test",
                "owner-group",
                AccountType.ORG,
                persistedId(namesake.getId()),
                IntegrationKind.GITLAB,
                "glpat-owner-token",
                null);

        WorkspaceDTO created = webTestClient
                .post()
                .uri("/workspaces")
                .headers(caller.headers())
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
                .findByWorkspace_IdAndUser_Id(
                        workspace.id(), persistedId(caller.scmUser().getId()))
                .orElseThrow(() -> new AssertionError("Owner membership not created"));
        assertThat(membership.getRole()).isEqualTo(WorkspaceMembership.WorkspaceRole.OWNER);
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.id(), namesake.getId()))
                .isEmpty();
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
                .isCreated()
                .expectBody(Void.class);

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

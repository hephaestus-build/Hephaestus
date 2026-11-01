package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.api.InitiateConnectionRequestDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.OfflineNetwork;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabPreflightRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceListItemDTO;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("GitLab workspace creation integration")
class GitLabWorkspaceCreationIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @BeforeAll
    static void declarePublicHosts() {
        OfflineNetwork.answer("gitlab.lrz.de", OfflineNetwork.PUBLIC_ADDRESS);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceLifecycleService workspaceLifecycleService;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private LoginProviderRepository loginProviderRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    /** {@code hephaestus.integration.gitlab.default-server-url} in the test profile. */
    private static final String DEFAULT_INSTANCE = "https://gitlab.lrz.de";

    private Consumer<HttpHeaders> gitLabCaller(String login) {
        return gitLabCaller(login, gitLabInstance(DEFAULT_INSTANCE));
    }

    /** A caller linked once on each of {@code instances}, with a distinct subject per link. */
    private Consumer<HttpHeaders> gitLabCaller(String login, IdentityProvider... instances) {
        Account account = accountRepository.save(new Account(login));
        for (IdentityProvider gitlab : instances) {
            Identity link = new Identity();
            link.setAccount(account);
            link.setProviderId(persistedId(gitlab.getId()));
            long nativeId = subject(account, gitlab);
            TestUserFactory.ensureUser(userRepository, login, nativeId, gitlab);
            link.setProviderUserKey(String.valueOf(nativeId));
            identityRepository.save(link);
        }
        String token = "mock-jwt-sub-" + persistedId(account.getId());
        return headers -> headers.setBearerAuth(token);
    }

    private static long subject(Account account, IdentityProvider gitlab) {
        return 70_000 + persistedId(account.getId()) * 100 + persistedId(gitlab.getId());
    }

    private record GitLabCaller(Consumer<HttpHeaders> headers, User scmUser) {}

    /** Adds the SCM mirror belonging to the caller's verified provider subject. */
    private GitLabCaller gitLabCallerWithMirror(String login) {
        Account account = accountRepository.save(new Account(login));
        IdentityProvider gitlab = gitLabInstance(DEFAULT_INSTANCE);
        long nativeId = 70_000 + persistedId(account.getId());
        User scmUser = TestUserFactory.ensureUser(userRepository, login, nativeId, gitlab);
        Identity link = new Identity();
        link.setAccount(account);
        link.setProviderId(persistedId(gitlab.getId()));
        link.setProviderUserKey(String.valueOf(nativeId));
        identityRepository.save(link);
        return new GitLabCaller(
                headers -> headers.setBearerAuth("mock-jwt-sub-" + persistedId(account.getId())), scmUser);
    }

    private IdentityProvider gitLabInstance(String serverUrl) {
        return gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, serverUrl)
                .orElseGet(
                        () -> gitProviderRepository.save(new IdentityProvider(IdentityProviderType.GITLAB, serverUrl)));
    }

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
                        slug,
                        slug,
                        "my-group",
                        AccountType.ORG,
                        IntegrationKind.GITLAB,
                        "glpat-test",
                        serverUrl,
                        null,
                        null))
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
    void shouldRefuseAnotherInstanceEvenWhenTheCallerCanSignInThere() {
        configureGitLabLogin("gitlab-example", "https://gitlab.example.com");
        Consumer<HttpHeaders> linkedThere =
                gitLabCaller("example", gitLabInstance("https://gitlab.example.com"), gitLabInstance(DEFAULT_INSTANCE));

        postGitLabWorkspace(linkedThere, "gitlab-example", "https://gitlab.example.com")
                .expectStatus()
                .isEqualTo(422)
                .expectBody(Void.class);

        assertThat(workspaceRepository.findByWorkspaceSlug("gitlab-example")).isEmpty();
    }

    @Test
    void shouldRefuseWhenTheCallerHasTwoIdentitiesOnTheDefaultInstance() {
        Consumer<HttpHeaders> linkedTwice =
                gitLabCaller("twice", gitLabInstance(DEFAULT_INSTANCE), gitLabInstance("HTTPS://GitLab.LRZ.de:443"));

        postGitLabWorkspace(linkedTwice, "gitlab-ambiguous", DEFAULT_INSTANCE)
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);

        assertThat(workspaceRepository.findByWorkspaceSlug("gitlab-ambiguous")).isEmpty();
    }

    @Test
    void shouldRefuseToConnectGitLabOutsideWorkspaceCreation() {
        Consumer<HttpHeaders> owner = gitLabCaller("connector");
        WorkspaceDTO workspace = Objects.requireNonNull(postGitLabWorkspace(owner, "gitlab-connect", null)
                .expectStatus()
                .isCreated()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody());

        webTestClient
                .post()
                .uri("/workspaces/{slug}/connections", workspace.workspaceSlug())
                .headers(owner)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InitiateConnectionRequestDTO(
                        IntegrationKind.GITLAB,
                        Map.of("pat", "glpat-test", "group_id", "7", "server_url", "https://gitlab.attacker.example")))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);

        assertThat(connectionRepository.findByWorkspaceId(workspace.id()))
                .singleElement()
                .extracting(Connection::getConfig)
                .isInstanceOfSatisfying(
                        ConnectionConfig.GitLabConfig.class,
                        config -> assertThat(config.serverUrl()).isEqualTo(DEFAULT_INSTANCE));
    }

    @Test
    void createGitLabWorkspacePersistsCorrectProviderModeAndServerUrl() {
        persistUser("mentor");
        IdentityProvider instance = gitLabInstance(DEFAULT_INSTANCE);
        Consumer<HttpHeaders> auth = gitLabCaller("mentor", gitLabInstance("https://gitlab.com"), instance);

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-space",
                "My GitLab Workspace",
                "my-group/my-project",
                AccountType.ORG,
                IntegrationKind.GITLAB,
                "glpat-test-token-12345",
                "HTTPS://GitLab.LRZ.de:443",
                null,
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
        assertThat(workspace.workspaceSlug()).isEqualTo("gitlab-space");
        assertThat(workspace.kind()).isEqualTo("GITLAB");
        assertThat(workspace.providerType()).isEqualTo(IdentityProviderType.GITLAB);
        assertThat(workspace.serverUrl()).isEqualTo(DEFAULT_INSTANCE);
        assertThat(workspace.status()).isEqualTo("ACTIVE");
        assertThat(workspace.hasPersonalAccessToken()).isTrue();

        Workspace persisted = workspaceRepository.findById(workspace.id()).orElseThrow();
        assertThat(persisted.getAccountLogin()).isEqualTo("my-group/my-project");
        // A request that names no selection follows every project of the group.
        assertThat(persisted.getRepositorySelection()).isEqualTo(RepositorySelection.ALL);
        User instanceActor = userRepository
                .findByLoginAndProviderId("mentor", persistedId(instance.getId()))
                .orElseThrow();
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(
                        workspace.id(), accountId(instanceActor)))
                .hasValueSatisfying(
                        membership -> assertThat(membership.getRole()).isEqualTo(Membership.WorkspaceRole.OWNER));
    }

    @Test
    void createGitLabWorkspaceWithoutServerUrlUsesTheDefaultInstance() {
        persistUser("mentor");
        Consumer<HttpHeaders> auth = gitLabCaller("mentor");

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-default",
                "Default GitLab",
                "my-group",
                AccountType.ORG,
                IntegrationKind.GITLAB,
                "glpat-test-token-67890",
                null,
                null,
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
        persistUser("admin");
        Consumer<HttpHeaders> auth = gitLabCaller("admin");

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-notoken",
                "No Token",
                "my-group",
                AccountType.ORG,
                IntegrationKind.GITLAB,
                null, // missing token
                null,
                null,
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
        persistUser("admin");
        Consumer<HttpHeaders> auth = gitLabCaller("admin");

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-http",
                "HTTP GitLab",
                "my-group",
                AccountType.ORG,
                IntegrationKind.GITLAB,
                "glpat-test-token",
                "http://insecure.example.com", // not HTTPS
                null,
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
                IntegrationKind.GITLAB,
                "glpat-owner-token",
                null,
                null,
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
                .findByWorkspace_IdAndId_AccountId(workspace.id(), accountId(caller.scmUser()))
                .orElseThrow(() -> new AssertionError("Owner membership not created"));
        assertThat(membership.getRole()).isEqualTo(Membership.WorkspaceRole.OWNER);
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(workspace.id(), accountId(namesake)))
                .isEmpty();
    }

    @Test
    void createGitLabWorkspaceResponseNeverContainsRawToken() {
        persistUser("mentor");
        Consumer<HttpHeaders> auth = gitLabCaller("mentor");
        String secretToken = "test-token-placeholder";

        var request = new CreateWorkspaceRequestDTO(
                "gitlab-secret",
                "Secret Test",
                "secret-group",
                AccountType.ORG,
                IntegrationKind.GITLAB,
                secretToken,
                null,
                null,
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
                IntegrationKind.GITLAB,
                "glpat-list-token",
                null,
                null,
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
        // providerType is derived from the active Connection: the GitLab workspace created via the
        // REST
        // API path provisions a GitLab Connection inline and surfaces as GITLAB.
        assertThat(workspaces).extracting(WorkspaceListItemDTO::providerType).contains(IdentityProviderType.GITLAB);
    }

    @Test
    @WithAdminUser
    void gitLabWorkspaceLifecycleSuspendAndPurgeWorkCorrectly() {
        User owner = persistUser("lifecycle-owner");
        Workspace workspace = workspaceService.createWorkspace(
                new CreateWorkspaceRequestDTO(
                        "gitlab-lifecycle",
                        "Lifecycle Test",
                        "lifecycle-group",
                        AccountType.ORG,
                        IntegrationKind.GITLAB,
                        "glpat-lifecycle-token",
                        null,
                        null,
                        null),
                persistedId(owner.getId()));
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

    private CreateWorkspaceRequestDTO selecting(
            String slug,
            IntegrationKind kind,
            @Nullable RepositorySelection selection,
            @Nullable List<Long> projectIds) {
        return new CreateWorkspaceRequestDTO(
                slug, slug, "my-group", AccountType.ORG, kind, "glpat-test", null, selection, projectIds);
    }

    @Test
    void shouldRefuseAContradictoryProjectSelectionBeforeAskingGitLab() {
        Consumer<HttpHeaders> caller = gitLabCaller("mentor");
        List<CreateWorkspaceRequestDTO> contradictory = List.of(
                selecting("selection-all-ids", IntegrationKind.GITLAB, RepositorySelection.ALL, List.of(5L)),
                selecting("selection-ids-only", IntegrationKind.GITLAB, null, List.of(5L)),
                selecting("selection-missing", IntegrationKind.GITLAB, RepositorySelection.SELECTED, null),
                selecting("selection-empty", IntegrationKind.GITLAB, RepositorySelection.SELECTED, List.of()),
                selecting("selection-zero", IntegrationKind.GITLAB, RepositorySelection.SELECTED, List.of(0L)),
                selecting("selection-negative", IntegrationKind.GITLAB, RepositorySelection.SELECTED, List.of(-3L)),
                selecting("selection-github", IntegrationKind.GITHUB, RepositorySelection.SELECTED, List.of(5L)));

        for (CreateWorkspaceRequestDTO request : contradictory) {
            webTestClient
                    .post()
                    .uri("/workspaces")
                    .headers(caller)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);

            assertThat(workspaceRepository.findByWorkspaceSlug(Objects.requireNonNull(request.workspaceSlug())))
                    .isEmpty();
        }
    }

    @Test
    void shouldApplyTheSelectionRuleToInProcessCallersToo() {
        persistUser("in-process-owner");
        var request = new CreateWorkspaceRequestDTO(
                "selection-in-process",
                "In process",
                "my-group",
                AccountType.ORG,
                IntegrationKind.GITLAB,
                "glpat-test",
                null,
                RepositorySelection.ALL,
                List.of(5L));

        assertThatThrownBy(() -> workspaceService.createWorkspace(request))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(workspaceRepository.findByWorkspaceSlug("selection-in-process"))
                .isEmpty();
    }

    @Test
    void shouldRefuseToListProjectsWithoutAGroupOrOnAPageBeforeTheFirst() {
        Consumer<HttpHeaders> caller = gitLabCaller("mentor");

        webTestClient
                .post()
                .uri("/workspaces/gitlab/projects")
                .headers(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new GitLabPreflightRequestDTO("glpat-test", null, null))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        webTestClient
                .post()
                .uri("/workspaces/gitlab/projects?page=0")
                .headers(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new GitLabPreflightRequestDTO("glpat-test", null, "my-group"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        webTestClient
                .post()
                .uri("/workspaces/gitlab/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new GitLabPreflightRequestDTO("glpat-test", null, "my-group"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}

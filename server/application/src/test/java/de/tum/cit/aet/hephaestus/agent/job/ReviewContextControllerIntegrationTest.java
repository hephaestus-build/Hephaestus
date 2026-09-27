package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Resolving a provider page's address to this workspace's mirrored work.
 *
 * <p>The fixture is built around the cases a name-only lookup gets wrong: one GitLab project path that
 * exists on two servers of which the workspace is connected to one, an issue and a merge request sharing
 * a project IID, and an issue whose confidential tombstone still carries its old public title. The
 * GitLab connection stores no access token, so a resolution that read one would fail the request.
 */
class ReviewContextControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String CONTEXT = "/workspaces/{slug}/practices/review-context?url={url}";
    private static final String CONNECTED = "https://gitlab.alpha.test";
    private static final String UNCONNECTED = "https://gitlab.beta.test";
    private static final String PROJECT = "top/sub/project";
    private static final String OTHER_PROJECT = "top/other";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityLinkRepository identityLinks;

    private final AtomicLong nativeIds = new AtomicLong(880_000);

    private Workspace workspace;
    private IdentityProvider connectedProvider;
    private IdentityProvider unconnectedProvider;

    private long issueFive;
    private long mergeRequestFive;
    private long otherProjectIssueFive;
    private long assignedIssue;

    /** Signs in through a GitHub identity; the merge request's author is their linked GitLab identity. */
    private Account author;

    private Account bystander;
    private User bystanderOnGitLab;
    private Account workspaceAdmin;
    private Account instanceAdmin;

    @BeforeEach
    void setUp() {
        connectedProvider = gitLabProvider(CONNECTED);
        unconnectedProvider = gitLabProvider(UNCONNECTED);

        User owner = persistUser("context-owner");
        workspace = createWorkspace("context-ws", "Context WS", "context-org", AccountType.ORG, owner);
        connectGitLab(workspace, CONNECTED);

        User authorOnGitLab = gitLabUser(connectedProvider, "gl-author");
        User authorOnGitHub = persistUser("gh-author");
        ensureWorkspaceMembership(workspace, authorOnGitHub, WorkspaceRole.MEMBER);
        author = accountLinkedTo(authorOnGitHub, authorOnGitLab);

        bystanderOnGitLab = gitLabUser(connectedProvider, "gl-bystander");
        ensureWorkspaceMembership(workspace, bystanderOnGitLab, WorkspaceRole.MEMBER);
        bystander = accountLinkedTo(bystanderOnGitLab);

        User adminOnGitLab = gitLabUser(connectedProvider, "gl-admin");
        ensureWorkspaceMembership(workspace, adminOnGitLab, WorkspaceRole.ADMIN);
        workspaceAdmin = accountLinkedTo(adminOnGitLab);

        instanceAdmin = persistInstanceAdmin("Instance admin");

        Repository project = repository(connectedProvider, PROJECT);
        monitor(workspace, PROJECT);
        issueFive = issue(project, 5, "Issue five", authorOnGitLab, null).getId();
        mergeRequestFive = mergeRequest(project, 5, "Merge request five", authorOnGitLab, null)
                .getId();
        assignedIssue = issue(project, 10, "Somebody else's issue", bystanderOnGitLab, authorOnGitLab)
                .getId();
        issue(project, 6, "Public title before it turned confidential", authorOnGitLab, null, Instant.now());
        mergeRequest(project, 7, "Deleted upstream", authorOnGitLab, Instant.now());

        Repository otherProject = repository(connectedProvider, OTHER_PROJECT);
        monitor(workspace, OTHER_PROJECT);
        otherProjectIssueFive = issue(otherProject, 5, "Another project's five", authorOnGitLab, null)
                .getId();

        // The same path on a server this workspace is not connected to, with work the connected one lacks.
        Repository lookalike = repository(unconnectedProvider, PROJECT);
        mergeRequest(lookalike, 5, "Lookalike five", authorOnGitLab, null);
        mergeRequest(lookalike, 8, "Only on the other server", authorOnGitLab, null);
    }

    @Nested
    @DisplayName("Which work")
    class WhichWork {

        @Test
        void shouldResolveTheMergeRequestWhenTheIssueSharesItsNumber() {
            get(bystander, CONNECTED + "/top/sub/project/-/merge_requests/5")
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.work.id")
                    .isEqualTo(Long.toString(mergeRequestFive))
                    .jsonPath("$.work.kind")
                    .isEqualTo("scm.pull_request")
                    .jsonPath("$.work.provider")
                    .isEqualTo("GITLAB")
                    .jsonPath("$.work.label")
                    .isEqualTo("!5")
                    .jsonPath("$.work.title")
                    .isEqualTo("Merge request five")
                    .jsonPath("$.work.url")
                    .isEqualTo(CONNECTED + "/top/sub/project/-/merge_requests/5")
                    .jsonPath("$.work.repositoryName")
                    .isEqualTo(PROJECT);
        }

        @Test
        void shouldResolveTheIssueWhenAMergeRequestSharesItsNumber() {
            get(bystander, CONNECTED + "/top/sub/project/-/issues/5")
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.work.id")
                    .isEqualTo(Long.toString(issueFive))
                    .jsonPath("$.work.kind")
                    .isEqualTo("scm.issue")
                    .jsonPath("$.work.label")
                    .isEqualTo("#5");
        }

        @Test
        void shouldResolveTheSameIssueWhenAddressedAsAProjectWorkItem() {
            expectWork(bystander, CONNECTED + "/top/sub/project/-/work_items/5", issueFive);
        }

        @Test
        void shouldResolveEachProjectsOwnIssueWhenTwoProjectsShareANumber() {
            expectWork(bystander, CONNECTED + "/top/other/-/issues/5", otherProjectIssueFive);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://GITLAB.alpha.test:443/top/sub/project/-/merge_requests/5/diffs",
                    "https://gitlab.alpha.test/top/sub/project/-/merge_requests/5/reports",
                    "https://gitlab.alpha.test/top/sub/project/-/merge_requests/5/?tab=commits#note_42",
                    "https://gitlab.alpha.test/Top/Sub/Project/-/merge_requests/5",
                })
        void shouldResolveTheSameMergeRequestWhenTheAddressDiffersOnlyInTabQueryOrCase(String url) {
            expectWork(bystander, url, mergeRequestFive);
        }

        /** Work that is mirrored but was never reviewed still resolves; the trace answers for its history. */
        @Test
        void shouldResolveUnreviewedWorkWhileItsTraceStillReportsNothingRecorded() {
            expectWork(bystander, CONNECTED + "/top/sub/project/-/issues/5", issueFive);

            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/practices/trace/scm.issue/{id}", workspace.getWorkspaceSlug(), issueFive)
                    .headers(h -> h.setBearerAuth(member(bystander)))
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("GitHub")
    class GitHub {

        /** A GitHub Enterprise Server connection names its own origin; github.com is then another server. */
        @Test
        void shouldResolveAPullRequestOnTheConfiguredEnterpriseServerAndNotOnGitHubCom() {
            Workspace enterprise =
                    createWorkspace("context-ghe", "Context GHE", "octo", AccountType.ORG, persistUser("ghe-owner"));
            ensureAdminMembership(enterprise);
            connectGitHub(enterprise, "https://ghe.example.test");
            IdentityProvider ghe = gitHubProvider("https://ghe.example.test");
            User admin = userRepository
                    .findByLoginAndProviderId(
                            "admin",
                            Objects.requireNonNull(ensureGitHubProvider().getId()))
                    .orElseThrow();
            Repository repository = repository(ghe, "octo/repo");
            monitor(enterprise, "octo/repo");
            long pullRequest = pullRequest(repository, 3, admin).getId();
            // The same repository name and number mirrored from github.com must not answer for GHES.
            pullRequest(repository(ensureGitHubProvider(), "octo/repo"), 3, admin);

            adminGet(enterprise, "https://ghe.example.test/octo/repo/pull/3/files")
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.work.id")
                    .isEqualTo(Long.toString(pullRequest))
                    .jsonPath("$.work.provider")
                    .isEqualTo("GITHUB")
                    .jsonPath("$.work.label")
                    .isEqualTo("#3")
                    .jsonPath("$.canRequestReview")
                    .isEqualTo(true)
                    .jsonPath("$.canInspectReviewDetails")
                    .isEqualTo(true);
            adminGet(enterprise, "https://github.com/octo/repo/pull/3")
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        void shouldResolveAnIssueOnGitHubComWhenTheConnectionNamesNoServer() {
            Workspace dotCom =
                    createWorkspace("context-gh", "Context GH", "octo", AccountType.ORG, persistUser("gh-owner"));
            ensureAdminMembership(dotCom);
            connectGitHub(dotCom, null);
            User admin = userRepository
                    .findByLoginAndProviderId(
                            "admin",
                            Objects.requireNonNull(ensureGitHubProvider().getId()))
                    .orElseThrow();
            Repository repository = repository(ensureGitHubProvider(), "octo/hello");
            monitor(dotCom, "octo/hello");
            long issue =
                    issue(repository, 4, "An issue on github.com", admin, null).getId();

            adminGet(dotCom, "https://github.com/octo/hello/issues/4")
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.work.id")
                    .isEqualTo(Long.toString(issue));
            adminGet(dotCom, "https://github.com/octo/hello/pull/4")
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        private WebTestClient.ResponseSpec adminGet(Workspace target, String url) {
            return webTestClient
                    .get()
                    .uri(CONTEXT, target.getWorkspaceSlug(), url)
                    .headers(h -> h.setBearerAuth("mock-jwt-token-for-admin-user"))
                    .exchange();
        }

        private PullRequest pullRequest(Repository repository, int number, User prAuthor) {
            PullRequest pullRequest = new PullRequest();
            fill(pullRequest, repository, number, "A pull request", prAuthor, null, "/pull/");
            return pullRequestRepository.save(pullRequest);
        }
    }

    @Nested
    @DisplayName("Work that is not there")
    class NotThere {

        /**
         * Every unavailable case answers with one body. A confidential issue's tombstone keeps the title it
         * had while public, so it must look exactly like an issue that was never mirrored at all.
         */
        @ParameterizedTest
        @ValueSource(
                strings = {
                    CONNECTED + "/top/sub/project/-/issues/6",
                    CONNECTED + "/top/sub/project/-/issues/404",
                    CONNECTED + "/top/sub/project/-/merge_requests/7",
                    CONNECTED + "/top/sub/project/-/merge_requests/8",
                    UNCONNECTED + "/top/sub/project/-/merge_requests/5",
                    CONNECTED + "/top/unmonitored/-/issues/5",
                    "https://github.com/top/project/pull/5",
                    "https://gitlab.alpha.test:8443/top/sub/project/-/merge_requests/5",
                    "https://evil-gitlab.alpha.test/top/sub/project/-/merge_requests/5",
                })
        void shouldAnswerTheSameNotFoundWhenTheWorkIsUnavailable(String url) {
            String body = get(bystander, url)
                    .expectStatus()
                    .isNotFound()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(body)
                    .contains("This workspace has no reviewed work at this address.")
                    .doesNotContain("Public title", "Deleted upstream", "Lookalike", "Only on the other server")
                    .doesNotContain("top/", "gitlab.");
        }

        @Test
        void shouldAnswerNotFoundWhenAnotherWorkspaceOnTheSameServerMonitorsTheProjectInstead() {
            Workspace other = createWorkspace(
                    "context-other", "Context Other", "context-other-org", AccountType.ORG, persistUser("other-owner"));
            connectGitLab(other, CONNECTED);
            ensureWorkspaceMembership(other, bystanderOnGitLab, WorkspaceRole.MEMBER);

            webTestClient
                    .get()
                    .uri(CONTEXT, other.getWorkspaceSlug(), CONNECTED + "/top/sub/project/-/merge_requests/5")
                    .headers(h -> h.setBearerAuth(member(bystander)))
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        void shouldAnswerNotFoundWhenTheWorkspaceHasNoActiveScmConnection() {
            Workspace unconnected = createWorkspace(
                    "context-bare", "Context Bare", "context-bare-org", AccountType.ORG, persistUser("bare-owner"));
            ensureAdminMembership(unconnected);

            webTestClient
                    .get()
                    .uri(CONTEXT, unconnected.getWorkspaceSlug(), CONNECTED + "/top/sub/project/-/merge_requests/5")
                    .headers(h -> h.setBearerAuth("mock-jwt-token-for-admin-user"))
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("Addresses that are not a work page")
    class BadAddresses {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "not a url secret-token",
                    "http://gitlab.alpha.test/top/sub/project/-/merge_requests/5",
                    "https://user:secret-token@gitlab.alpha.test/top/sub/project/-/merge_requests/5",
                    "/top/sub/project/-/merge_requests/5",
                    "https://gitlab.alpha.test/top/sub/%2e%2e/project/-/merge_requests/5",
                    "https://gitlab.alpha.test/top/sub%2Fproject/-/merge_requests/5",
                })
        void shouldRefuseAMalformedAddressWithoutEchoingItWhenItIsNotACleanHttpsAddress(String url) {
            expectBadRequestWithoutEcho(url);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://gitlab.alpha.test/groups/top/-/work_items/5?secret-token=1",
                    "https://gitlab.alpha.test/top/sub/project/-/merge_requests/5/secret-token",
                    "https://gitlab.alpha.test/top/sub/project/-/epics/5",
                    "https://gitlab.alpha.test/top/sub/project",
                })
        void shouldRefuseAnUnsupportedRouteWithoutEchoingItWhenItIsNotAWorkPage(String url) {
            expectBadRequestWithoutEcho(url);
        }

        @Test
        void shouldRefuseAnAddressLongerThanABrowserTabShouldCarry() {
            String url = CONNECTED + "/top/sub/project/-/issues/5?q=" + "x".repeat(2048);

            get(bystander, url).expectStatus().isBadRequest().expectBody(Void.class);
        }

        @Test
        void shouldRefuseTheRequestAsBadWhenTheAddressIsMissing() {
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/practices/review-context", workspace.getWorkspaceSlug())
                    .headers(h -> h.setBearerAuth(member(bystander)))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectHeader()
                    .contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody(Void.class);
        }

        private void expectBadRequestWithoutEcho(String url) {
            String body = get(bystander, url)
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody();

            assertThat(body).doesNotContain("secret-token", "top/", "project");
        }
    }

    @Nested
    @DisplayName("Who may look, and what they may do")
    class Capabilities {

        @Test
        void shouldRefuseAnAnonymousCallerWhenTheWorkspaceIsPubliclyViewable() {
            Workspace stored = workspaceRepository.findById(workspace.getId()).orElseThrow();
            stored.setIsPubliclyViewable(true);
            workspaceRepository.save(stored);

            webTestClient
                    .get()
                    .uri(CONTEXT, workspace.getWorkspaceSlug(), CONNECTED + "/top/sub/project/-/merge_requests/5")
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(String.class)
                    .value(body -> assertThat(body).doesNotContain("Merge request five"));
        }

        @Test
        void shouldRefuseASignedInNonMemberWhenTheyAreNotAnInstanceAdmin() {
            Account outsider = accountLinkedTo(gitLabUser(connectedProvider, "gl-outsider"));

            get(outsider, CONNECTED + "/top/sub/project/-/merge_requests/5")
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        /** Standing through a linked identity other than the one the account signed in with. */
        @Test
        void shouldLetTheAuthorRequestAReviewWhenTheirLinkedIdentityAuthoredTheWork() {
            expectCapabilities(author, CONNECTED + "/top/sub/project/-/merge_requests/5", true, false);
        }

        @Test
        void shouldLetAnAssigneeRequestAReviewWhenTheirLinkedIdentityIsAssigned() {
            get(author, CONNECTED + "/top/sub/project/-/issues/10")
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.work.id")
                    .isEqualTo(Long.toString(assignedIssue))
                    .jsonPath("$.canRequestReview")
                    .isEqualTo(true);
        }

        @Test
        void shouldNotLetAnUnrelatedMemberRequestOrInspectWhenTheyOnlyBelongToTheWorkspace() {
            expectCapabilities(bystander, CONNECTED + "/top/sub/project/-/merge_requests/5", false, false);
        }

        @Test
        void shouldLetAWorkspaceAdminRequestAndInspectWhenTheWorkIsSomebodyElses() {
            expectCapabilities(workspaceAdmin, CONNECTED + "/top/sub/project/-/merge_requests/5", true, true);
        }

        /**
         * Entering a workspace as an instance admin is not stored membership: it opens the details, but
         * the request endpoint would refuse the ask, so the capability says so.
         */
        @Test
        void shouldLetANonMemberInstanceAdminInspectButNotRequestWhenTheyHaveNoStandingOfTheirOwn() {
            webTestClient
                    .get()
                    .uri(CONTEXT, workspace.getWorkspaceSlug(), CONNECTED + "/top/sub/project/-/merge_requests/5")
                    .headers(h -> h.setBearerAuth("mock-jwt-admin-" + instanceAdmin.getId()))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.work.id")
                    .isEqualTo(Long.toString(mergeRequestFive))
                    .jsonPath("$.canRequestReview")
                    .isEqualTo(false)
                    .jsonPath("$.canInspectReviewDetails")
                    .isEqualTo(true);
        }

        private void expectCapabilities(Account caller, String url, boolean canRequest, boolean canInspect) {
            get(caller, url)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.canRequestReview")
                    .isEqualTo(canRequest)
                    .jsonPath("$.canInspectReviewDetails")
                    .isEqualTo(canInspect);
        }
    }

    // Requests

    private WebTestClient.ResponseSpec get(Account caller, String url) {
        return webTestClient
                .get()
                .uri(CONTEXT, workspace.getWorkspaceSlug(), url)
                .headers(h -> h.setBearerAuth(member(caller)))
                .exchange();
    }

    private void expectWork(Account caller, String url, long workId) {
        get(caller, url)
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.work.id")
                .isEqualTo(Long.toString(workId));
    }

    private static String member(Account account) {
        return "mock-jwt-member-" + account.getId();
    }

    // Fixtures

    private IdentityProvider gitLabProvider(String serverUrl) {
        return gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, serverUrl)
                .orElseGet(
                        () -> gitProviderRepository.save(new IdentityProvider(IdentityProviderType.GITLAB, serverUrl)));
    }

    /** An active GitLab connection to {@code serverUrl}; deliberately without a stored token. */
    private void connectGitLab(Workspace target, String serverUrl) {
        var config = new ConnectionConfig.GitLabConfig(
                serverUrl, null, null, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of());
        Connection connection = new Connection(target, IntegrationKind.GITLAB, serverUrl, config);
        connection.setDisplayName(target.getAccountLogin());
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connectionRepository.save(connection);
    }

    private IdentityProvider gitHubProvider(String serverUrl) {
        return gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, serverUrl)
                .orElseGet(
                        () -> gitProviderRepository.save(new IdentityProvider(IdentityProviderType.GITHUB, serverUrl)));
    }

    /** An active GitHub PAT connection; a null server is github.com. */
    private void connectGitHub(Workspace target, @Nullable String serverUrl) {
        var config = new ConnectionConfig.GitHubPatConfig(target.getAccountLogin(), serverUrl, Set.of());
        Connection connection = new Connection(target, IntegrationKind.GITHUB, target.getAccountLogin(), config);
        connection.setDisplayName(target.getAccountLogin());
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connectionRepository.save(connection);
    }

    private User gitLabUser(IdentityProvider provider, String login) {
        User user = new User();
        user.setNativeId(nativeIds.incrementAndGet());
        user.setProvider(provider);
        user.setLogin(login);
        user.setName(login);
        user.setAvatarUrl("https://example.com/" + login + ".png");
        user.setHtmlUrl(provider.getServerUrl() + "/" + login);
        user.setType(User.Type.USER);
        return userRepository.save(user);
    }

    private Account accountLinkedTo(User... identities) {
        Account account = persistAccount("Account of " + identities[0].getLogin());
        for (User identity : identities) {
            IdentityLink link = new IdentityLink();
            link.setAccount(account);
            link.setProviderId(Objects.requireNonNull(identity.getProvider().getId()));
            link.setSubject(identity.getNativeId().toString());
            link.setExternalActorId(identity.getId());
            identityLinks.save(link);
        }
        return account;
    }

    private Repository repository(IdentityProvider provider, String nameWithOwner) {
        Repository repository = new Repository();
        repository.setNativeId(nativeIds.incrementAndGet());
        repository.setProvider(provider);
        repository.setName(nameWithOwner.substring(nameWithOwner.lastIndexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl(provider.getServerUrl() + "/" + nameWithOwner);
        repository.setDefaultBranch("main");
        return repositoryRepository.save(repository);
    }

    private void monitor(Workspace target, String nameWithOwner) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(target);
        monitor.setNameWithOwner(nameWithOwner);
        monitorRepository.save(monitor);
    }

    private Issue issue(Repository repository, int number, String title, User issueAuthor, @Nullable User assignee) {
        return issue(repository, number, title, issueAuthor, assignee, null);
    }

    private Issue issue(
            Repository repository,
            int number,
            String title,
            User issueAuthor,
            @Nullable User assignee,
            @Nullable Instant deletedAt) {
        Issue issue = new Issue();
        fill(issue, repository, number, title, issueAuthor, deletedAt, "/-/issues/");
        if (assignee != null) {
            issue.getAssignees().add(assignee);
        }
        return issueRepository.save(issue);
    }

    private PullRequest mergeRequest(
            Repository repository, int number, String title, User mrAuthor, @Nullable Instant deletedAt) {
        PullRequest mergeRequest = new PullRequest();
        fill(mergeRequest, repository, number, title, mrAuthor, deletedAt, "/-/merge_requests/");
        return pullRequestRepository.save(mergeRequest);
    }

    private void fill(
            Issue work,
            Repository repository,
            int number,
            String title,
            User workAuthor,
            @Nullable Instant deletedAt,
            String route) {
        work.setNativeId(nativeIds.incrementAndGet());
        work.setProvider(repository.getProvider());
        work.setRepository(repository);
        work.setNumber(number);
        work.setTitle(title);
        work.setState(Issue.State.OPEN);
        work.setAuthor(workAuthor);
        work.setHtmlUrl(repository.getHtmlUrl() + route + number);
        work.setCreatedAt(Instant.now());
        work.setUpdatedAt(Instant.now());
        work.setDeletedAt(deletedAt);
    }
}

package de.tum.cit.aet.hephaestus.workspace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlFragments;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.RepositoryOriginNotSupportedException;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.practices.groupdetail.ProductItemDirectoryQuery;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * A manual Add grants a workspace a repository only on its own provider authority. A canonical mirror that another
 * workspace on the same provider origin stored is shared data, not proof that this workspace's credential may read
 * the repository, so its work never reaches this workspace's items or item directory through Add alone.
 */
class ManualRepositoryAuthorityIntegrationTest extends AbstractWorkspaceIntegrationTest {
    /** Distinctive synthetic words; no workspace that lacks the repository may read them anywhere. */
    private static final String MARKER = "SYNTHETIC-PRIVATE-7781";

    private static final String ISSUE_TITLE = MARKER + " issue only the authorized workspace reads";
    private static final String PULL_TITLE = MARKER + " change only the authorized workspace reads";

    @Autowired
    private WorkspaceRepositoryMonitorService monitorService;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private PullRequestRepository pulls;

    @Autowired
    private ItemRepository items;

    @Autowired
    private ProductItemDirectoryQuery directory;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private CredentialBundleConverter credentialConverter;

    @MockitoBean
    private GitLabGraphQlClientProvider gitLabClients;

    @MockitoBean
    private GitHubGraphQlClientProvider gitHubClients;

    @MockitoBean
    private GitLabSyncServiceHolder syncServices;

    @Test
    void shouldRefuseAGitLabAddWhoseTokenCannotReadTheSharedMirrorAndKeepTheAuthorizedWorkspace() {
        String origin = "https://manual-authority.gitlab.example.test";
        String path = "shared-org/private-project";
        long nativeId = 7101L;
        IdentityProvider source =
                gitProviderRepository.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, origin));
        Workspace authorized = gitLabWorkspace("manual-gitlab-a", persistUser("manual-gitlab-a-owner"), source);
        Workspace refused = gitLabWorkspace("manual-gitlab-b", persistUser("manual-gitlab-b-owner"), source);
        Repository shared = repository(source, nativeId, path);
        Issue issue = issue(shared);
        PullRequest pull = pullRequest(shared);
        // Each workspace's own credential: GitLab confirms the project to A and names no project to B.
        when(gitLabClients.forScope(authorized.getId(), tokenOf(authorized), origin))
                .thenReturn(answering(origin, "graphql/gitlab/", project(origin, nativeId, path)));
        when(gitLabClients.forScope(refused.getId(), tokenOf(refused), origin))
                .thenReturn(answering(origin, "graphql/gitlab/", "{\"data\":{\"project\":null}}"));

        // The inverse: A's own authority reuses the shared mirror at its native identity and lists its work.
        monitorService.addRepositoryToMonitor(authorized.getWorkspaceSlug(), path);
        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(authorized.getId(), path))
                .hasValueSatisfying(monitor -> assertThat(monitor.getNativeId()).isEqualTo(nativeId));
        assertThat(repositories.findAllByWorkspaceMonitors(authorized.getId()))
                .extracting(Repository::getId)
                .containsExactly(shared.getId());
        assertThat(directoryText(authorized)).contains(ISSUE_TITLE, PULL_TITLE);

        Throwable refusal =
                catchThrowable(() -> monitorService.addRepositoryToMonitor(refused.getWorkspaceSlug(), path));

        assertNothingLent(refusal, refused, authorized, shared, issue, pull);
        assertThat(workspaces.findById(refused.getId()).orElseThrow().getRepositorySelection())
                .as("a refused Add keeps the workspace following every project of its group")
                .isEqualTo(RepositorySelection.ALL);
    }

    @Test
    void shouldRefuseAGitHubTokenAddWhoseTokenCannotReadTheSharedMirrorAndKeepTheAuthorizedWorkspace() {
        String path = "shared-org/private-repository";
        IdentityProvider source = ensureGitHubProvider();
        Workspace authorized = gitHubTokenWorkspace("manual-github-a");
        Workspace refused = gitHubTokenWorkspace("manual-github-b");
        Repository shared = repository(source, 9101L, path);
        Issue issue = issue(shared);
        PullRequest pull = pullRequest(shared);
        // A holds the repository on its own authority: the monitor and the event every admitted Add publishes.
        RepositoryToMonitor held = new RepositoryToMonitor();
        held.setWorkspace(authorized);
        held.setNameWithOwner(path);
        held.setNativeId(shared.getNativeId());
        monitors.saveAndFlush(held);
        transactions.executeWithoutResult(status -> publisher.publishEvent(
                new RepositoryMonitoringChangedEvent(authorized.getId(), List.of(shared.getId()))));
        assertThat(directoryText(authorized)).contains(ISSUE_TITLE, PULL_TITLE);
        // B's own token: GitHub resolves no such repository for it, whatever it is asked about this path.
        when(gitHubClients.withToken(tokenOf(refused)))
                .thenReturn(answering("https://api.github.com", "graphql/github/", """
                {"data":null,"errors":[{"type":"NOT_FOUND","path":["repository"],
                  "message":"Could not resolve to a Repository with the name '%s'."}]}
                """.formatted(path)));

        Throwable refusal =
                catchThrowable(() -> monitorService.addRepositoryToMonitor(refused.getWorkspaceSlug(), path));

        assertNothingLent(refusal, refused, authorized, shared, issue, pull);
    }

    @Test
    void shouldAddAGitHubRepositoryItsOwnTokenConfirmsOnTheSharedMirror() {
        String path = "shared-org/confirmed-repository";
        IdentityProvider source = ensureGitHubProvider();
        Workspace holder = gitHubTokenWorkspace("manual-github-holder");
        Workspace confirmed = gitHubTokenWorkspace("manual-github-confirmed");
        Repository shared = repository(source, 9201L, path);
        issue(shared);
        pullRequest(shared);
        RepositoryToMonitor held = new RepositoryToMonitor();
        held.setWorkspace(holder);
        held.setNameWithOwner(path);
        held.setNativeId(shared.getNativeId());
        monitors.saveAndFlush(held);
        // GitHub names the repository, at the mirror's id, to this workspace's own token.
        when(gitHubClients.withToken(tokenOf(confirmed)))
                .thenReturn(answering("https://api.github.com", "graphql/github/", gitHubRepository(9201L, path)));

        monitorService.addRepositoryToMonitor(confirmed.getWorkspaceSlug(), path);

        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(confirmed.getId(), path))
                .hasValueSatisfying(monitor -> assertThat(monitor.getNativeId()).isEqualTo(9201L));
        assertThat(repositories.findAllByWorkspaceMonitors(confirmed.getId()))
                .as("the shared mirror is reused, not copied")
                .extracting(Repository::getId)
                .containsExactly(shared.getId());
        assertThat(directoryText(confirmed)).contains(ISSUE_TITLE, PULL_TITLE);
        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(holder.getId(), path))
                .isPresent();
    }

    @Test
    void shouldRefuseAGitHubTokenOfAnotherServerWithoutAskingGitHubDotCom() {
        String server = "https://github.enterprise.example.test";
        String path = "shared-org/enterprise-repository";
        IdentityProvider source =
                gitProviderRepository.saveAndFlush(new IdentityProvider(IdentityProviderType.GITHUB, server));
        Workspace onServer = gitHubTokenWorkspace("manual-github-enterprise", server);
        Repository mirrored = repository(source, 9401L, path);
        issue(mirrored);

        Throwable refusal =
                catchThrowable(() -> monitorService.addRepositoryToMonitor(onServer.getWorkspaceSlug(), path));

        assertThat(refusal).isInstanceOf(RepositoryOriginNotSupportedException.class);
        // Nothing reached GitHub's client: neither the scoped client nor one built from the stored token.
        verify(gitHubClients, never()).forScope(anyLong());
        verify(gitHubClients, never()).withToken(anyString());
        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(onServer.getId(), path))
                .isEmpty();
        assertThat(items.findSource(onServer.getId(), "scm.repository", mirrored.getId()))
                .isEmpty();
        assertThat(directoryText(onServer)).doesNotContain(MARKER);
    }

    /**
     * The provider is asked outside any transaction, with the token and server read together from one stored
     * connection. When that connection is reconnected on its row, or moved to another server, while the answer is on
     * the way, the answer speaks for the earlier connection, so the Add writes nothing; otherwise it adds. Either way
     * the token only ever travelled with the server it was stored with.
     */
    @ParameterizedTest(name = "{0}, while asked: {1}")
    @CsvSource({
        "GITLAB, NONE",
        "GITLAB, RECONNECT",
        "GITLAB, REPLACE_TOKEN",
        "GITHUB, NONE",
        "GITHUB, RECONNECT",
        "GITHUB, MOVE_SERVER"
    })
    void shouldAddOnlyOnAProviderAnswerForTheConnectionStillActive(IntegrationKind kind, String change)
            throws Exception {
        boolean changed = !change.equals("NONE");
        boolean gitLab = kind == IntegrationKind.GITLAB;
        String path = "shared-org/held-repository";
        long nativeId = gitLab ? 7301L : 9301L;
        IdentityProvider source = gitLab
                ? gitProviderRepository.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://held-authority.gitlab.example.test"))
                : ensureGitHubProvider();
        Workspace own = gitLab
                ? gitLabWorkspace("manual-held-gitlab", persistUser("manual-held-owner"), source)
                : gitHubTokenWorkspace("manual-held-github");
        Repository shared = repository(source, nativeId, path);
        issue(shared);
        pullRequest(shared);
        Connection before = connections.findActive(own.getId(), kind).orElseThrow();
        long connectionId = Objects.requireNonNull(before.getId());
        String confirmation =
                gitLab ? project(source.getServerUrl(), nativeId, path) : gitHubRepository(nativeId, path);
        var asked = new CountDownLatch(1);
        var answer = new CompletableFuture<String>();
        HttpGraphQlClient heldClient = client(
                gitLab ? source.getServerUrl() : "https://api.github.com",
                gitLab ? "graphql/gitlab/" : "graphql/github/",
                request -> {
                    asked.countDown();
                    return Mono.fromFuture(answer).map(ManualRepositoryAuthorityIntegrationTest::ok);
                });
        // Only the client built from this connection's stored token, for its stored server, reaches the transport.
        if (gitLab)
            when(gitLabClients.forScope(own.getId(), tokenOf(own), source.getServerUrl()))
                    .thenReturn(heldClient);
        else when(gitHubClients.withToken(tokenOf(own))).thenReturn(heldClient);

        try (var threads = Executors.newSingleThreadExecutor()) {
            Future<?> add = threads.submit(() -> monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), path));
            try {
                assertThat(asked.await(20, SECONDS))
                        .as("the Add's lookup reached the provider transport")
                        .isTrue();
                if (change.equals("RECONNECT")) reconnect(connectionId);
                if (change.equals("MOVE_SERVER")) moveGitHubServer(own.getId());
                if (change.equals("REPLACE_TOKEN")) {
                    var replacement = new BearerToken(tokenOf(own) + "-replaced", null);
                    assertThat(connectionService.rotateBearerToken(own.getId(), kind, replacement))
                            .isPresent();
                    assertThat(connectionService.findActiveBearerToken(own.getId(), kind))
                            .contains(replacement);
                }
                answer.complete(confirmation);
                Throwable outcome = catchThrowable(() -> add.get(20, SECONDS));
                Connection after = connections.findById(connectionId).orElseThrow();
                String listed = directoryText(own);
                if (changed) {
                    assertSoftly(softly -> {
                        softly.assertThat(outcome).isInstanceOf(ExecutionException.class);
                        if (change.equals("RECONNECT"))
                            softly.assertThat(outcome).hasMessageContaining("changed while we checked");
                        softly.assertThat(after.getId()).isEqualTo(connectionId);
                        softly.assertThat(after.getState()).isEqualTo(IntegrationState.ACTIVE);
                        softly.assertThat(after.getVersion())
                                .as("the change kept the row and moved its version")
                                .isNotEqualTo(before.getVersion());
                        softly.assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), path))
                                .isEmpty();
                        softly.assertThat(items.findSource(own.getId(), "scm.repository", shared.getId()))
                                .isEmpty();
                        softly.assertThat(listed).doesNotContain(MARKER);
                    });
                    // One client, built once from the captured token for the captured server; the scope was not
                    // resolved again, and the moved server never received the token.
                    if (gitLab) {
                        verify(gitLabClients, times(1)).forScope(anyLong(), anyString(), anyString());
                        verify(gitLabClients, never()).forScope(anyLong());
                    } else {
                        verify(gitHubClients, times(1)).withToken(anyString());
                        verify(gitHubClients, never()).forScope(anyLong());
                    }
                } else {
                    assertSoftly(softly -> {
                        softly.assertThat(outcome).isNull();
                        softly.assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), path))
                                .hasValueSatisfying(monitor ->
                                        assertThat(monitor.getNativeId()).isEqualTo(nativeId));
                        softly.assertThat(repositories.findAllByWorkspaceMonitors(own.getId()))
                                .extracting(Repository::getId)
                                .containsExactly(shared.getId());
                        softly.assertThat(listed).contains(ISSUE_TITLE, PULL_TITLE);
                    });
                }
            } finally {
                answer.complete(confirmation);
                add.cancel(true);
            }
        }
    }

    /** A token-managed GitHub connection can change its server while keeping its row. */
    private void moveGitHubServer(long workspaceId) {
        assertThat(connectionService.updateConfig(workspaceId, IntegrationKind.GITHUB, config -> switch (config) {
                    case ConnectionConfig.GitHubPatConfig c ->
                        new ConnectionConfig.GitHubPatConfig(
                                c.orgLogin(), "https://github.enterprise.example.test", c.enabledStreams());
                    default -> config;
                }))
                .isPresent();
    }

    /** The synthetic token stored with the workspace's connection; no real credential is read anywhere here. */
    private static String tokenOf(Workspace workspace) {
        return "synthetic-token-" + workspace.getWorkspaceSlug();
    }

    /** Suspends and resumes the connection on its own row, as a reconnect does. */
    private void reconnect(long connectionId) {
        for (IntegrationState state : List.of(IntegrationState.SUSPENDED, IntegrationState.ACTIVE)) {
            connectionService.transition(
                    connections.findById(connectionId).orElseThrow(),
                    new ConnectionService.TransitionRequest(
                            state,
                            "TEST_RECONNECTED",
                            "SYSTEM",
                            "manual-authority-test",
                            UUID.randomUUID().toString(),
                            "Reconnected while a lookup was answered"));
        }
    }

    private static String gitHubRepository(long nativeId, String path) {
        return """
                {"data":{"repository":{"id":"R_synthetic","databaseId":%s,"name":"%s","nameWithOwner":"%s",
                  "isPrivate":true}}}
                """.formatted(nativeId, path.substring(path.lastIndexOf('/') + 1), path);
    }

    /**
     * The refused workspace gained nothing from the shared mirror: no monitor, no repository item, no work item, and
     * no title, label or URL of that work in its item directory. The authorized workspace and the mirror keep theirs.
     */
    private void assertNothingLent(
            @Nullable Throwable refusal,
            Workspace refused,
            Workspace authorized,
            Repository shared,
            Issue issue,
            PullRequest pull) {
        String path = shared.getNameWithOwner();
        String disclosed = directoryText(refused);
        String retained = directoryText(authorized);
        assertSoftly(softly -> {
            softly.assertThat(refusal)
                    .as("the provider read nothing for this workspace's credential, so its Add is refused")
                    .isNotNull();
            softly.assertThat(monitors.findByWorkspaceIdAndNameWithOwner(refused.getId(), path))
                    .as("no monitor of the refused workspace")
                    .isEmpty();
            softly.assertThat(repositories.findAllByWorkspaceMonitors(refused.getId()))
                    .as("no source of the refused workspace")
                    .isEmpty();
            softly.assertThat(items.findSource(refused.getId(), "scm.repository", shared.getId()))
                    .as("no repository item of the refused workspace")
                    .isEmpty();
            softly.assertThat(items.findSource(refused.getId(), "scm.issue", issue.getId()))
                    .as("no issue item of the refused workspace")
                    .isEmpty();
            softly.assertThat(items.findSource(refused.getId(), "scm.pull_request", pull.getId()))
                    .as("no change item of the refused workspace")
                    .isEmpty();
            softly.assertThat(disclosed)
                    .as("the refused workspace's item directory names none of the shared work")
                    .doesNotContain(MARKER, Objects.requireNonNull(shared.getHtmlUrl()));
            softly.assertThat(monitors.findByWorkspaceIdAndNameWithOwner(authorized.getId(), path))
                    .as("the authorized workspace keeps its monitor")
                    .isPresent();
            softly.assertThat(repositories.findById(shared.getId()))
                    .as("the shared mirror stays")
                    .isPresent();
            softly.assertThat(issues.findById(issue.getId())).isPresent();
            softly.assertThat(pulls.findById(pull.getId())).isPresent();
            softly.assertThat(retained)
                    .as("the authorized workspace still lists its work")
                    .contains(ISSUE_TITLE, PULL_TITLE);
        });
    }

    /** Every title, label and URL the workspace's admin item directory shows. */
    private String directoryText(Workspace workspace) {
        return directory.list(workspace.getId(), null, PageRequest.of(0, 100)).getContent().stream()
                .map(ManualRepositoryAuthorityIntegrationTest::text)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static String text(ItemRefDTO item) {
        return item.label() + " | " + item.title() + " | " + item.url();
    }

    private Workspace gitLabWorkspace(String slug, User owner, IdentityProvider source) {
        Workspace workspace = createWorkspace(slug, slug, "shared-org", AccountType.ORG, owner);
        workspace.setRepositorySelection(RepositorySelection.ALL);
        workspace = workspaces.saveAndFlush(workspace);
        Connection unsaved = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                source.getServerUrl(),
                new ConnectionConfig.GitLabConfig(source.getServerUrl(), null, null, Set.of(), null));
        unsaved.setCredentials(new BearerToken(tokenOf(workspace), null), credentialConverter);
        Connection connection = connections.saveAndFlush(unsaved);
        connectionService.transition(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "TEST_CONNECTED",
                        "SYSTEM",
                        "manual-authority-test",
                        UUID.randomUUID().toString(),
                        "Source connected"));
        return workspace;
    }

    private Workspace gitHubTokenWorkspace(String slug) {
        return gitHubTokenWorkspace(slug, null);
    }

    /** A workspace whose token reads {@code serverUrl}, or github.com when it names none. */
    private Workspace gitHubTokenWorkspace(String slug, @Nullable String serverUrl) {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace(slug));
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                null,
                new ConnectionConfig.GitHubPatConfig(workspace.getAccountLogin(), serverUrl, Set.of()));
        connection.setCredentials(new BearerToken(tokenOf(workspace), null), credentialConverter);
        connection.setDisplayName(workspace.getAccountLogin());
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        return workspace;
    }

    private Repository repository(IdentityProvider provider, long nativeId, String path) {
        Repository repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(nativeId);
        repository.setNameWithOwner(path);
        repository.setName(path.substring(path.lastIndexOf('/') + 1));
        repository.setHtmlUrl(provider.getServerUrl() + "/" + path);
        repository.setPrivate(true);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        return repositories.saveAndFlush(repository);
    }

    private Issue issue(Repository repository) {
        Issue issue = new Issue();
        issue.setProvider(repository.getProvider());
        issue.setRepository(repository);
        issue.setNativeId(repository.getNativeId() + 1);
        issue.setNumber(1);
        issue.setTitle(ISSUE_TITLE);
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/issues/1");
        return issues.saveAndFlush(issue);
    }

    private PullRequest pullRequest(Repository repository) {
        PullRequest pull = new PullRequest();
        pull.setProvider(repository.getProvider());
        pull.setRepository(repository);
        pull.setNativeId(repository.getNativeId() + 2);
        pull.setNumber(2);
        pull.setTitle(PULL_TITLE);
        pull.setBody(MARKER + " description");
        pull.setState(Issue.State.OPEN);
        pull.setHtmlUrl(repository.getHtmlUrl() + "/pull/2");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pull.setBaseRefName("main");
        pull.setHeadRefName("topic");
        pull.setBaseRefOid("a".repeat(40));
        pull.setHeadRefOid("b".repeat(40));
        return pulls.saveAndFlush(pull);
    }

    private static String project(String origin, long nativeId, String path) {
        return """
                {"data":{"project":{"id":"gid://gitlab/Project/%s","fullPath":"%s","name":"%s",
                  "webUrl":"%s/%s","visibility":"private","group":null}}}
                """.formatted(nativeId, path, path.substring(path.lastIndexOf('/') + 1), origin, path);
    }

    /** A provider client bound to one workspace's credential that answers every request with {@code body}. */
    private static HttpGraphQlClient answering(String origin, String documents, String body) {
        return client(origin, documents, request -> Mono.just(ok(body)));
    }

    private static ClientResponse ok(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    /** A provider client bound to one workspace's credential whose requests {@code exchange} answers. */
    private static HttpGraphQlClient client(String origin, String documents, ExchangeFunction exchange) {
        List<Resource> fragments = documents.equals("graphql/github/")
                ? List.of(
                        new ClassPathResource(GitHubGraphQlFragments.PROJECT_FRAGMENTS_RESOURCE),
                        new ClassPathResource(GitHubGraphQlFragments.COMMIT_ENRICHMENT_FIELDS_RESOURCE))
                : List.of(new ClassPathResource("graphql/gitlab/fragments/GitLabUserFields.graphql"));
        return HttpGraphQlClient.builder(WebClient.builder().exchangeFunction(exchange))
                .url(origin + "/api/graphql")
                .documentSource(new FragmentMergingDocumentSource(
                        List.of(
                                new ClassPathResource(documents + "operations/"),
                                new ClassPathResource(documents + "fragments/")),
                        List.of(".graphql", ".gql"),
                        fragments))
                .build();
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.graphql.client.ClientGraphQlRequest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClientInterceptor;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

class GitHubIssueDependencyScopeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private SyncTargetProvider syncTargets;

    @Autowired
    private GitHubIssueProcessor processor;

    @Autowired
    private GitHubIssueDependencySyncService dependencyService;

    @Autowired
    private GitHubSyncProperties syncProperties;

    @Autowired
    private SyncSchedulerProperties schedulerProperties;

    @Autowired
    private GitHubExceptionClassifier classifier;

    @Autowired
    private GitHubGraphQlSyncCoordinator coordinator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    @Qualifier("gitHubGraphQlClient")
    private HttpGraphQlClient baseClient;

    private IdentityProvider github;
    private IdentityProvider gitlab;
    private Workspace workspace;
    private String owner;
    private long nativeId;

    @BeforeEach
    void setUp() {
        nativeId = 100_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()) % 800_000_000L;
        owner = "d-" + UUID.randomUUID();
        github = IdentityProviderFixtures.gitHub(providers);
        gitlab = providers.saveAndFlush(new IdentityProvider(
                IdentityProviderType.GITLAB, "https://" + UUID.randomUUID() + ".gitlab.example.test"));
        for (long offset = 10; offset <= 12; offset++) {
            assertThat(issues.findById(nativeId + offset)).isEmpty();
        }
        workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces, connections, WorkspaceTestFixtures.installationWorkspace(nativeId, owner), nativeId);
    }

    @Test
    void shouldKeepScopeStubsOnGitHubWhenAnotherProviderHasTheMonitoredPath() {
        String target = owner + "/target";
        String siblingPath = owner + "/sibling";
        monitor(target, nativeId);
        monitor(siblingPath, nativeId + 1);
        var foreign = repository(gitlab, nativeId, target);
        var sibling = repository(github, nativeId + 1, siblingPath);
        var service = service(Map.of(
                "target",
                page(node(target, nativeId + 10, 7, List.of())),
                "sibling",
                page(node(siblingPath, nativeId + 11, 7, List.of()))));

        service.syncDependenciesForScope(workspace.getId());
        boolean foreignWritten = stored(foreign, 7).isPresent();
        boolean siblingWritten = stored(sibling, 7).isPresent();
        assertThat(service.syncDependenciesForScope(workspace.getId())).isEqualTo(-1);
        var correct = repository(github, nativeId, target);
        resetTimestamp();

        @Nullable Throwable collision = catchThrowable(() -> service.syncDependenciesForScope(workspace.getId()));

        assertSoftly(softly -> {
            softly.assertThat(foreignWritten)
                    .as("foreign-only scope row must not receive GitHub stubs")
                    .isFalse();
            softly.assertThat(siblingWritten)
                    .as("allowed GitHub sibling is processed")
                    .isTrue();
            softly.assertThat(collision)
                    .as("same path on two providers selects GitHub")
                    .isNull();
            softly.assertThat(stored(correct, 7))
                    .as("correct GitHub repository receives the scope stub")
                    .hasValueSatisfying(issue -> assertThat(issue.getNativeId()).isEqualTo(nativeId + 10));
            softly.assertThat(stored(sibling, 7))
                    .as("GitHub sibling remains available")
                    .isPresent();
        });
    }

    @Test
    void shouldSkipForeignBlockerRepositoryAndRetainKnownGitHubBlocker() {
        String sourcePath = owner + "/source";
        String target = owner + "/foreign-blocker";
        String siblingPath = owner + "/known-blocker";
        monitor(sourcePath, nativeId);
        var source = repository(github, nativeId, sourcePath);
        var foreign = repository(gitlab, nativeId + 1, target);
        var sibling = repository(github, nativeId + 2, siblingPath);
        var service = service(Map.of(
                "source",
                page(node(
                        sourcePath,
                        nativeId + 10,
                        7,
                        List.of(
                                blocker(target, nativeId + 1, nativeId + 11, 8),
                                blocker(siblingPath, nativeId + 2, nativeId + 12, 9))))));

        service.syncDependenciesForScope(workspace.getId());
        var linkedRepositories = blockerRepositoryIds(source, 7);
        boolean foreignWritten = stored(foreign, 8).isPresent();
        boolean siblingWritten = stored(sibling, 9).isPresent();

        assertSoftly(softly -> {
            softly.assertThat(foreignWritten)
                    .as("cross-repository GitHub blocker must not become a GitLab stub")
                    .isFalse();
            softly.assertThat(siblingWritten)
                    .as("known GitHub blocker is created")
                    .isTrue();
            softly.assertThat(stored(source, 7))
                    .as("blocked GitHub issue is created")
                    .isPresent();
            softly.assertThat(linkedRepositories)
                    .as("only the known GitHub blocker is linked")
                    .containsExactly(sibling.getId());
        });
    }

    @Test
    void shouldSelectGitHubBlockerWhenAnotherProviderHasTheSamePath() {
        String sourcePath = owner + "/source";
        String target = owner + "/blocker";
        monitor(sourcePath, nativeId);
        var source = repository(github, nativeId, sourcePath);
        var foreign = repository(gitlab, nativeId + 1, target);
        var correct = repository(github, nativeId + 1, target);
        var service = service(Map.of(
                "source",
                page(node(sourcePath, nativeId + 10, 7, List.of(blocker(target, nativeId + 1, nativeId + 11, 8))))));

        @Nullable Throwable failure = catchThrowable(() -> service.syncDependenciesForScope(workspace.getId()));
        boolean correctWritten = stored(correct, 8).isPresent();

        assertSoftly(softly -> {
            softly.assertThat(failure).as("public dependency sync completes").isNull();
            softly.assertThat(stored(foreign, 8))
                    .as("foreign same-path repository receives no stub")
                    .isEmpty();
            softly.assertThat(correctWritten)
                    .as("GitHub blocker stub is committed")
                    .isTrue();
            softly.assertThat(blockerRepositoryIds(source, 7))
                    .as("dependency points to the GitHub repository")
                    .containsExactly(correct.getId());
        });
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITLAB", "GITHUB"})
    void shouldReconcileOnlyTheGitHubIssueWhenItsNativeIdMatchesForeignIssuePk(IdentityProviderType collisionProvider) {
        String targetPath = owner + "/target";
        String siblingPath = owner + "/sibling";
        monitor(targetPath, nativeId + 1);
        monitor(siblingPath, nativeId + 2);
        var foreignRepository = repository(
                collisionProvider == IdentityProviderType.GITHUB ? github : gitlab, nativeId, owner + "/foreign");
        var targetRepository = repository(github, nativeId + 1, targetPath);
        var siblingRepository = repository(github, nativeId + 2, siblingPath);
        var foreign = issue(foreignRepository, nativeId + 20, 1);
        long incomingNativeId = foreign.getId();
        var correct = issue(targetRepository, incomingNativeId, 7);
        var foreignMarker = issue(foreignRepository, nativeId + 21, 2);
        var correctMarker = issue(targetRepository, nativeId + 22, 8);
        var sibling = issue(siblingRepository, nativeId + 23, 7);
        var siblingMarker = issue(siblingRepository, nativeId + 24, 8);
        dependencyService.processIssueDependencyEvent(foreign.getId(), foreignMarker.getId(), true);
        dependencyService.processIssueDependencyEvent(correct.getId(), correctMarker.getId(), true);
        dependencyService.processIssueDependencyEvent(sibling.getId(), siblingMarker.getId(), true);
        assertThat(blockerIssueIds(foreign)).containsExactly(foreignMarker.getId());
        assertThat(blockerIssueIds(correct)).containsExactly(correctMarker.getId());
        assertThat(blockerIssueIds(sibling)).containsExactly(siblingMarker.getId());
        assertThat(issues.findById(sibling.getNativeId())).isEmpty();
        var service = service(Map.of(
                "target", page(node(targetPath, incomingNativeId, 7, List.of())),
                "sibling", page(node(siblingPath, sibling.getNativeId(), 7, List.of()))));

        service.syncDependenciesForScope(workspace.getId());
        var foreignBlockers = blockerIssueIds(foreign);
        var correctBlockers = blockerIssueIds(correct);
        var siblingBlockers = blockerIssueIds(sibling);

        assertSoftly(softly -> {
            softly.assertThat(foreignBlockers)
                    .as("GitHub native ID collision must preserve unrelated foreign edges")
                    .containsExactly(foreignMarker.getId());
            softly.assertThat(correctBlockers)
                    .as("GitHub page clears the correct stored issue's blockers")
                    .isEmpty();
            softly.assertThat(siblingBlockers)
                    .as("ordinary GitHub sibling is reconciled")
                    .isEmpty();
        });
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITLAB", "GITHUB"})
    void shouldLinkTheGitHubBlockerWhenItsNativeIdMatchesForeignIssuePk(IdentityProviderType collisionProvider) {
        String sourcePath = owner + "/source";
        String blockerPath = owner + "/blocker";
        String siblingPath = owner + "/sibling-blocker";
        monitor(sourcePath, nativeId);
        var sourceRepository = repository(github, nativeId, sourcePath);
        var foreignRepository = repository(
                collisionProvider == IdentityProviderType.GITHUB ? github : gitlab, nativeId + 1, owner + "/foreign");
        var blockerRepository = repository(github, nativeId + 2, blockerPath);
        var siblingRepository = repository(github, nativeId + 3, siblingPath);
        var foreign = issue(foreignRepository, nativeId + 20, 1);
        long incomingNativeId = foreign.getId();
        var correct = issue(blockerRepository, incomingNativeId, 8);
        var source = issue(sourceRepository, nativeId + 10, 7);
        var sibling = issue(siblingRepository, nativeId + 12, 9);
        assertThat(issues.findById(source.getNativeId())).isEmpty();
        assertThat(issues.findById(sibling.getNativeId())).isEmpty();
        var service = service(Map.of(
                "source",
                page(node(
                        sourcePath,
                        source.getNativeId(),
                        7,
                        List.of(
                                blocker(blockerPath, blockerRepository.getNativeId(), incomingNativeId, 8),
                                blocker(siblingPath, siblingRepository.getNativeId(), sibling.getNativeId(), 9))))));

        service.syncDependenciesForScope(workspace.getId());
        var linked = blockerIssueIds(source);

        assertSoftly(softly -> {
            softly.assertThat(linked)
                    .as("native blocker IDs select their GitHub rows")
                    .containsExactlyElementsOf(List.of(correct.getId(), sibling.getId()).stream()
                            .sorted()
                            .toList());
            softly.assertThat(linked)
                    .as("foreign issue is not a GitHub blocker")
                    .doesNotContain(foreign.getId());
            softly.assertThat(linked)
                    .as("ordinary GitHub sibling blocker remains linked")
                    .contains(sibling.getId());
        });
    }

    private Issue issue(Repository repository, long providerNativeId, int number) {
        var issue = new Issue();
        issue.setProvider(repository.getProvider());
        issue.setNativeId(providerNativeId);
        issue.setRepository(repository);
        issue.setNumber(number);
        issue.setTitle("Stored dependency issue");
        issue.setState(Issue.State.OPEN);
        assertThat(issue.getId()).isNull();
        var stored = issues.saveAndFlush(issue);
        assertThat(stored.getId()).isPositive().isNotEqualTo(providerNativeId);
        return stored;
    }

    private List<Long> blockerIssueIds(Issue issue) {
        return Objects.requireNonNull(transactions.execute(
                status -> issues.findByIdWithBlockedBy(issue.getId()).orElseThrow().getBlockedBy().stream()
                        .map(Issue::getId)
                        .sorted()
                        .toList()));
    }

    private void monitor(String path, long repositoryNativeId) {
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(workspace, path, repositoryNativeId));
    }

    private Repository repository(IdentityProvider provider, long id, String path) {
        var repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(id);
        repository.setName(path.substring(path.lastIndexOf('/') + 1));
        repository.setNameWithOwner(path);
        repository.setHtmlUrl(provider.getServerUrl() + "/" + path);
        assertThat(repository.getId()).isNull();
        var stored = repositories.saveAndFlush(repository);
        assertThat(stored.getId()).isPositive().isNotEqualTo(id);
        return stored;
    }

    private Optional<Issue> stored(Repository repository, int number) {
        return Objects.requireNonNull(
                transactions.execute(status -> issues.findByRepositoryIdAndNumber(repository.getId(), number)));
    }

    private List<Long> blockerRepositoryIds(Repository repository, int number) {
        return Objects.requireNonNull(transactions.execute(status -> issues.findByRepositoryIdAndNumber(
                        repository.getId(), number)
                .map(issue -> issues.findByIdWithBlockedBy(issue.getId()).orElseThrow().getBlockedBy().stream()
                        .map(blocker ->
                                Objects.requireNonNull(blocker.getRepository()).getId())
                        .sorted()
                        .toList())
                .orElseGet(List::of)));
    }

    private void resetTimestamp() {
        transactions.executeWithoutResult(status -> {
            var stored = workspaces.findById(workspace.getId()).orElseThrow();
            stored.setIssueDependenciesSyncedAt(Instant.EPOCH);
            workspaces.save(stored);
        });
    }

    private GitHubIssueDependencySyncService service(Map<String, String> pages) {
        var response = new AtomicReference<>("");
        GraphQlClientInterceptor route = new GraphQlClientInterceptor() {
            @Override
            public Mono<ClientGraphQlResponse> intercept(ClientGraphQlRequest request, Chain chain) {
                response.set(
                        Objects.requireNonNull(pages.get(request.getVariables().get("name"))));
                return chain.next(request);
            }
        };
        var client = baseClient
                .mutate()
                .interceptor(route)
                .webClient(builder -> builder.exchangeFunction(request -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(response.get())
                            .build());
                }))
                .build();
        var boundary = mock(GitHubGraphQlClientProvider.class);
        when(boundary.forScope(workspace.getId())).thenReturn(client);
        return new GitHubIssueDependencySyncService(
                issues,
                repositories,
                providers,
                syncTargets,
                boundary,
                syncProperties,
                classifier,
                schedulerProperties,
                processor,
                transactionManager,
                coordinator);
    }

    private static String page(String node) {
        return "{\"data\":{\"repository\":{\"issues\":{\"totalCount\":1,\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null},\"nodes\":["
                + node + "]}}}}";
    }

    private static String node(String path, long nativeId, int number, List<String> blockers) {
        return issueFields(path, nativeId, number) + ",\"blockedBy\":{\"totalCount\":" + blockers.size()
                + ",\"nodes\":[" + String.join(",", blockers) + "]}}";
    }

    private static String blocker(String path, long repositoryNativeId, long nativeId, int number) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return issueFields(path, nativeId, number) + ",\"repository\":{\"id\":\"R_" + repositoryNativeId
                + "\",\"databaseId\":" + repositoryNativeId + ",\"name\":\"" + name + "\",\"nameWithOwner\":\"" + path
                + "\",\"url\":\"https://github.com/" + path + "\"}}";
    }

    private static String issueFields(String path, long nativeId, int number) {
        return """
                {"id":"I_dependency_%d","fullDatabaseId":%d,"number":%d,
                "title":"Remote GitHub issue","body":"Remote work","state":"OPEN","stateReason":null,
                "url":"https://github.com/%s/issues/%d","locked":false,
                "createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z","closedAt":null
                """.formatted(nativeId, nativeId, number, path, number).strip();
    }
}

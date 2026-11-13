package de.tum.cit.aet.hephaestus.integration.scm.github.subissue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemSourceObserved;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.ItemEventRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.graphql.client.ClientGraphQlRequest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClientInterceptor;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

/** Real scope enumeration and persisted writes with GitHub stood in for at its transport boundary. */
@RecordApplicationEvents
class GitHubSubIssueScopeIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private IdentityProviderLookup providerLookup;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private ApplicationEvents publications;

    @Autowired
    private ItemIndexService itemIndex;

    @Autowired
    private ItemEventRepository occasionRows;

    @Autowired
    private ScmItemService itemSource;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private OrganizationRepository organizations;

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

    @Test
    void shouldKeepScopeSummariesOnGitHubWhenAnotherProviderHasTheMonitoredPath() {
        long nativeId =
                1_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()) * 10;
        String owner = "s-" + UUID.randomUUID();
        var github = IdentityProviderFixtures.gitHub(providers);
        var gitlab = providers.save(new IdentityProvider(
                IdentityProviderType.GITLAB, "https://" + UUID.randomUUID() + ".gitlab.example.test"));
        var workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces, connections, WorkspaceTestFixtures.installationWorkspace(nativeId, owner), nativeId);
        String targetPath = owner + "/target";
        String siblingPath = owner + "/sibling";
        monitors.save(WorkspaceTestFixtures.repositoryMonitor(workspace, targetPath, nativeId + 1));
        monitors.save(WorkspaceTestFixtures.repositoryMonitor(workspace, siblingPath, nativeId + 3));
        var foreign = repository(gitlab, nativeId + 1, targetPath);
        var foreignIssue = issue(foreign, nativeId + 2, 7);
        var sibling = repository(github, nativeId + 3, siblingPath);
        var siblingIssue = issue(sibling, nativeId + 4, 7);
        assertThat(repositories.findByNameWithOwnerAndProviderId(targetPath, Objects.requireNonNull(github.getId())))
                .isEmpty();

        var client = client(Map.of(
                "target", page(targetPath, nativeId + 5, 42),
                "sibling", page(siblingPath, nativeId + 4, 99)));
        var boundary = mock(GitHubGraphQlClientProvider.class);
        when(boundary.forScope(workspace.getId())).thenReturn(client);
        var service = new GitHubSubIssueSyncService(
                issues,
                repositories,
                providerLookup,
                syncTargets,
                boundary,
                syncProperties,
                classifier,
                schedulerProperties,
                processor,
                transactionManager,
                coordinator,
                mock(ApplicationEventPublisher.class));

        service.syncSubIssuesForScope(workspace.getId());
        Integer foreignSummary = summary(foreignIssue);
        Integer siblingSummary = summary(siblingIssue);
        assertThat(service.syncSubIssuesForScope(workspace.getId())).isEqualTo(-1);

        var correct = repository(github, nativeId + 1, targetPath);
        var organization = new Organization();
        organization.setProvider(github);
        organization.setNativeId(nativeId + 6);
        organization.setLogin(owner);
        organization.setName(owner);
        organization.setHtmlUrl("https://github.com/" + owner);
        correct.setOrganization(organizations.save(organization));
        repositories.save(correct);
        transactions.executeWithoutResult(status -> {
            var stored = workspaces.findById(workspace.getId()).orElseThrow();
            stored.setSubIssuesSyncedAt(Instant.EPOCH);
            workspaces.save(stored);
        });

        @Nullable Throwable collisionFailure = catchThrowable(() -> service.syncSubIssuesForScope(workspace.getId()));
        Optional<Issue> recovered = Objects.requireNonNull(
                transactions.execute(status -> issues.findByRepositoryIdAndNumber(correct.getId(), 7)));
        var detachedCorrect = Objects.requireNonNull(transactions.execute(status -> repositories
                .findByNameWithOwnerAndProviderId(targetPath, Objects.requireNonNull(github.getId()))
                .orElseThrow()));
        assertThat(detachedCorrect.getProvider().getServerUrl()).isEqualTo("https://github.com");
        assertThat(Objects.requireNonNull(detachedCorrect.getOrganization()).getLogin())
                .isEqualTo(owner);
        var detachedSibling = Objects.requireNonNull(transactions.execute(status -> repositories
                .findByNameWithOwnerAndProviderId(siblingPath, Objects.requireNonNull(github.getId()))
                .orElseThrow()));
        assertThat(detachedSibling.getProvider().getServerUrl()).isEqualTo("https://github.com");
        assertThat(detachedSibling.getOrganization()).isNull();

        assertSoftly(softly -> {
            softly.assertThat(foreignSummary)
                    .as("foreign-only row after a valid GitHub page")
                    .isEqualTo(7);
            softly.assertThat(siblingSummary)
                    .as("allowed sibling in the foreign-only pass")
                    .isEqualTo(99);
            softly.assertThat(collisionFailure)
                    .as("correct provider arrives while foreign path remains")
                    .isNull();
            softly.assertThat(recovered)
                    .as("GitHub stub created from the scope page")
                    .hasValueSatisfying(stored -> {
                        assertThat(stored.getProvider().getId()).isEqualTo(github.getId());
                        assertThat(stored.getNativeId()).isEqualTo(nativeId + 5);
                        assertThat(stored.getSubIssuesTotal()).isEqualTo(42);
                    });
            softly.assertThat(summary(foreignIssue))
                    .as("collision must not write the foreign row")
                    .isEqualTo(foreignSummary);
            softly.assertThat(summary(siblingIssue))
                    .as("allowed sibling remains stable")
                    .isEqualTo(99);
        });
    }

    @Test
    void shouldRecordChangedSyncProgressWithoutPublishingForUnchangedOrPartialPages() {
        long nativeId =
                1_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()) * 10;
        String owner = "progress-" + UUID.randomUUID();
        String path = owner + "/target";
        var github = IdentityProviderFixtures.gitHub(providers);
        var workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces, connections, WorkspaceTestFixtures.installationWorkspace(nativeId, owner), nativeId);
        var repository = repository(github, nativeId + 1, path);
        var monitor = WorkspaceTestFixtures.repositoryMonitor(workspace, path, repository.getNativeId());
        monitors.saveAndFlush(monitor);
        var parent = issue(repository, nativeId + 2, 7);
        var holder = itemIndex.upsert(
                new ItemRegistration(workspace.getId(), ScmItemEvents.REPOSITORY, repository.getId(), null, null));
        var item = itemIndex.upsert(
                new ItemRegistration(workspace.getId(), ScmItemEvents.ISSUE, parent.getId(), holder.itemId(), null));
        var original = itemSource.pin(item, null).version();
        Map<String, String> pages = new HashMap<>();
        pages.put("target", page(path, nativeId + 2, 2));
        var boundary = mock(GitHubGraphQlClientProvider.class);
        when(boundary.forScope(workspace.getId())).thenReturn(client(pages));
        var service = new GitHubSubIssueSyncService(
                issues,
                repositories,
                providerLookup,
                syncTargets,
                boundary,
                syncProperties,
                classifier,
                schedulerProperties,
                processor,
                transactionManager,
                coordinator,
                publisher);

        service.syncSubIssuesForScope(workspace.getId());

        var canonical = itemSource.pin(item, null).version();
        assertThat(canonical).isNotEqualTo(original);
        var occasion = occasionRows
                .findByWorkspaceIdAndItemIdAndNameAndItemVersion(
                        item.workspaceId(), item.itemId(), ScmItemEvents.ISSUE_UPDATED.value(), canonical.value())
                .orElseThrow();
        assertThat(occasion.getFoundBy()).isEqualTo(FoundBy.SYNC);
        resetSync(workspace);
        service.syncSubIssuesForScope(workspace.getId());
        String complete = page(path, nativeId + 2, 99).strip();
        pages.put(
                "target",
                complete.substring(0, complete.length() - 1)
                        + ",\"errors\":[{\"message\":\"Parent unavailable\",\"path\":[\"repository\",\"issues\",\"nodes\",0,\"parent\"]}]}");
        resetSync(workspace);
        service.syncSubIssuesForScope(workspace.getId());

        assertThat(summary(parent)).isEqualTo(2);
        assertThat(itemSource.pin(item, null).version()).isEqualTo(canonical);
        assertThat(publications.stream(ScmDomainEvent.IssueUpdated.class)
                        .filter(event -> event.issue().id().equals(parent.getId()))
                        .count())
                .isEqualTo(1);
        assertThat(publications.stream(ItemSourceObserved.class)
                        .filter(event -> event.workspaceId() == item.workspaceId() && event.itemId() == item.itemId())
                        .count())
                .isZero();
        assertThat(occasionRows
                        .findByWorkspaceIdAndItemIdAndNameAndItemVersion(
                                item.workspaceId(),
                                item.itemId(),
                                ScmItemEvents.ISSUE_UPDATED.value(),
                                canonical.value())
                        .orElseThrow()
                        .getId())
                .isEqualTo(occasion.getId());
    }

    private void resetSync(Workspace workspace) {
        transactions.executeWithoutResult(ignored -> {
            var stored = workspaces.findById(workspace.getId()).orElseThrow();
            stored.setSubIssuesSyncedAt(Instant.EPOCH);
            workspaces.save(stored);
        });
    }

    private HttpGraphQlClient client(Map<String, String> pages) {
        var response = new AtomicReference<>("");
        GraphQlClientInterceptor route = new GraphQlClientInterceptor() {
            @Override
            public Mono<ClientGraphQlResponse> intercept(ClientGraphQlRequest request, Chain chain) {
                response.set(
                        Objects.requireNonNull(pages.get(request.getVariables().get("name"))));
                return chain.next(request);
            }
        };
        return baseClient
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
    }

    private Integer summary(Issue issue) {
        return Objects.requireNonNull(transactions.execute(status -> Objects.requireNonNull(
                issues.findById(issue.getId()).orElseThrow().getSubIssuesTotal())));
    }

    private Repository repository(IdentityProvider provider, long nativeId, String path) {
        var repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(nativeId);
        repository.setName(path.substring(path.lastIndexOf('/') + 1));
        repository.setNameWithOwner(path);
        repository.setHtmlUrl(provider.getServerUrl() + "/" + path);
        return repositories.save(repository);
    }

    private Issue issue(Repository repository, long nativeId, int number) {
        var issue = new Issue();
        issue.setProvider(repository.getProvider());
        issue.setNativeId(nativeId);
        issue.setRepository(repository);
        issue.setNumber(number);
        issue.setTitle("Retained issue");
        issue.setState(Issue.State.OPEN);
        issue.setSubIssuesTotal(7);
        return issues.save(issue);
    }

    private static String page(String path, long nativeId, int total) {
        return """
                {"data":{"repository":{"issues":{"totalCount":1,
                "pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[{
                "id":"I_scope_%d","fullDatabaseId":%d,"number":7,
                "title":"Remote GitHub issue","body":"Remote work","state":"OPEN",
                "stateReason":null,"url":"https://github.com/%s/issues/7","locked":false,
                "createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z",
                "closedAt":null,"parent":null,
                "subIssuesSummary":{"total":%d,"completed":0,"percentCompleted":0}}]}}}}
                """.formatted(nativeId, nativeId, path, total);
    }
}

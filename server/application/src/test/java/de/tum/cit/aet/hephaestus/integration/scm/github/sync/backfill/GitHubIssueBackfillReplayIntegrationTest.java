package de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubClosingIssueClient;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.GitHubPullRequestReviewCommentSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

class GitHubIssueBackfillReplayIntegrationTest extends BaseIntegrationTest {
    private static final Instant VERSION = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ApplicationContext application;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private IssueRepository issues;

    @Autowired
    @Qualifier("gitHubGraphQlClient")
    private HttpGraphQlClient baseClient;

    private final GitHubGraphQlClientProvider clients = mock(GitHubGraphQlClientProvider.class);
    private final GitHubBackfillRepair repair = mock(GitHubBackfillRepair.class);

    private GitHubHistoricalBackfillService service() {
        return new GitHubHistoricalBackfillService(
                mock(SyncTargetProvider.class),
                mock(BackfillStateProvider.class),
                application.getBean(SyncSchedulerProperties.class),
                application.getBean(GitHubSyncProperties.class),
                clients,
                application.getBean(GitHubIssueProcessor.class),
                mock(GitHubIssueCommentProcessor.class),
                mock(GitHubPullRequestProcessor.class),
                mock(GitHubClosingIssueClient.class),
                mock(GitHubPullRequestReviewProcessor.class),
                mock(GitHubPullRequestReviewSyncService.class),
                mock(GitHubPullRequestReviewCommentSyncService.class),
                mock(GitHubIssueCommentSyncService.class),
                mock(PullRequestRepository.class),
                repositories,
                application.getBean(TransactionTemplate.class),
                Runnable::run,
                mock(ActivityLedgerRepair.class),
                repair);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepATiedWebhookWriteThatCommittedDuringTheBackfillRequest(boolean writeDuringRequest) {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("backfill-" + UUID.randomUUID()));
        var provider = IdentityProviderFixtures.gitHub(providers);
        var repository = new Repository();
        repository.setNativeId(Integer.toUnsignedLong(UUID.randomUUID().hashCode()));
        repository.setProvider(provider);
        repository.setName("project");
        repository.setNameWithOwner(workspace.getAccountLogin() + "/project");
        repository.setHtmlUrl("https://github.com/" + repository.getNameWithOwner());
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setCreatedAt(VERSION);
        repository.setUpdatedAt(VERSION);
        repository.setPushedAt(VERSION);
        var savedRepository = repositories.saveAndFlush(repository);
        var issue = new Issue();
        issue.setNativeId(repository.getNativeId());
        issue.setProvider(provider);
        issue.setRepository(savedRepository);
        issue.setNumber(1);
        issue.setState(Issue.State.OPEN);
        issue.setTitle("Stored title");
        issue.setCreatedAt(VERSION);
        issue.setUpdatedAt(VERSION);
        issue.setLastSyncAt(Instant.now().minusSeconds(60));
        var stored = issues.saveAndFlush(issue);
        var target = SyncTargetTestBuilder.syncTarget()
                .id(savedRepository.getId())
                .scopeId(workspace.getId())
                .repositoryNameWithOwner(savedRepository.getNameWithOwner())
                .build();
        when(repair.findRepository(target)).thenReturn(Optional.of(savedRepository));
        var requests = new AtomicInteger();
        var client = baseClient
                .mutate()
                .webClient(builder -> builder.exchangeFunction(request -> Mono.fromSupplier(() -> {
                    boolean issuePage = requests.getAndIncrement() == 0;
                    if (issuePage && writeDuringRequest) {
                        stored.setTitle("Webhook title");
                        stored.setLastSyncAt(Instant.now());
                        issues.saveAndFlush(stored);
                    }
                    String body = issuePage ? """
                            {"data":{"repository":{"issues":{"totalCount":1,
                              "nodes":[{"id":"I_backfill","fullDatabaseId":"%s","number":1,
                                "title":"Provider title","body":"Provider body","state":"OPEN",
                                "url":"https://github.com/example/project/issues/1",
                                "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}],
                              "pageInfo":{"hasNextPage":false}}}}}
                            """.formatted(stored.getNativeId()) : """
                            {"data":{"repository":{"pullRequests":{"totalCount":0,"nodes":[],
                              "pageInfo":{"hasNextPage":false}}}}}
                            """;
                    return ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .build();
                })))
                .build();
        when(clients.forScope(workspace.getId())).thenReturn(client);

        assertThat(service().backfillRepository(target, 1, BackfillPageObserver.NOOP))
                .isTrue();

        var actual = issues.findById(stored.getId()).orElseThrow();
        assertThat(actual.getTitle()).isEqualTo(writeDuringRequest ? "Webhook title" : "Provider title");
        assertThat(actual.getUpdatedAt()).isEqualTo(VERSION);
    }
}

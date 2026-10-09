package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmReviewRangeSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ReviewRepositoryPreparerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private PlatformTransactionManager transactions;

    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private PullRequest pullRequest;
    private AgentJob job;
    private ScmReviewRangeSource rangeSource;
    private ReviewRepositoryPreparer preparer;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        var provider = providers.save(new IdentityProvider(IdentityProviderType.GITLAB, "https://scm.example"));
        var repository = new Repository();
        repository.setNativeId(200L);
        repository.setProvider(provider);
        repository.setName("repo");
        repository.setNameWithOwner("owner/repo");
        repository.setHtmlUrl("https://scm.example/owner/repo");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository = repositories.save(repository);
        var pr = new PullRequest();
        pr.setNativeId(300L);
        pr.setProvider(provider);
        pr.setRepository(repository);
        pr.setNumber(42);
        pr.setTitle("Reviewed change");
        pr.setHtmlUrl("https://scm.example/owner/repo/-/merge_requests/42");
        pr.setState(PullRequest.State.OPEN);
        pr.setCreatedAt(Instant.now());
        pr.setUpdatedAt(Instant.parse("2026-10-01T12:00:00.123Z"));
        pr.setHeadRefOid(HEAD);
        pullRequest = pullRequests.save(pr);
        var workspace = new Workspace();
        workspace.setId(1L);
        job = new AgentJob();
        job.setWorkspace(workspace);
        job.setMetadata(JsonMapper.builder()
                .build()
                .createObjectNode()
                .put("pull_request_id", pullRequest.getId())
                .put("repository_id", repository.getId())
                .put("commit_sha", HEAD)
                .put("signal", "scm.pull_request.opened"));
        var connections = mock(ConnectionService.class);
        when(connections.findActiveProviderKind(1L)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        var connection = mock(Connection.class);
        when(connection.getId()).thenReturn(10L);
        when(connections.findActive(1L, IntegrationKind.GITLAB)).thenReturn(Optional.of(connection));
        var monitors = mock(RepositoryToMonitorRepository.class);
        when(monitors.existsByWorkspaceIdAndNameWithOwner(1L, "owner/repo")).thenReturn(true);
        var tokens = mock(ScmTokenSource.class);
        when(tokens.kind()).thenReturn(IntegrationKind.GITLAB);
        when(tokens.serverUrl(1L)).thenReturn(Optional.of("https://scm.example"));
        when(tokens.recordsReviewDiffBase()).thenReturn(true);
        when(tokens.accessToken(1L)).thenReturn(Optional.of("private-token"));
        var git = mock(GitRepositoryManager.class);
        when(git.commitExists(any(), anyString())).thenReturn(true);
        rangeSource = mock(ScmReviewRangeSource.class);
        when(rangeSource.kind()).thenReturn(IntegrationKind.GITLAB);
        preparer = new ReviewRepositoryPreparer(
                git,
                pullRequests,
                monitors,
                connections,
                List.of(tokens),
                repositories,
                List.of(rangeSource),
                transactions);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldPersistTheAuthoritativePairOnTheSameJobAfterGitLabFinishesPreparingIt(boolean captureTransaction) {
        var original = Objects.requireNonNull(job.getMetadata()).deepCopy();
        when(rangeSource.read(1L, "owner/repo", 42)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> preparer.prepare(job)).isInstanceOf(ReviewSourceNotReadyException.class);
        assertThat(pullRequests.findById(pullRequest.getId()).orElseThrow().getBaseRefOid())
                .isNull();
        when(rangeSource.read(1L, "owner/repo", 42)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return Optional.of(new ScmReviewRangeSource.ReviewRange(200L, 300L, HEAD, BASE));
        });

        var capture = new TransactionTemplate(transactions);
        capture.setReadOnly(true);
        capture.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        if (captureTransaction) {
            capture.executeWithoutResult(status -> {
                // Establish the same read-only snapshot the workspace capture keeps while sources run.
                assertThat(pullRequests
                                .findById(pullRequest.getId())
                                .orElseThrow()
                                .getBaseRefOid())
                        .isNull();
                assertThat(preparer.prepare(job).target()).isEqualTo(BASE);
            });
        } else {
            assertThat(preparer.prepare(job).target()).isEqualTo(BASE);
        }
        var stored = pullRequests.findById(pullRequest.getId()).orElseThrow();
        assertThat(stored.getHeadRefOid()).isEqualTo(HEAD);
        assertThat(stored.getBaseRefOid()).isEqualTo(BASE);
        assertThat(stored.getUpdatedAt()).isEqualTo(pullRequest.getUpdatedAt());
        assertThat(job.getMetadata()).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldNotWriteABaseIfTheProviderReadRacesAStoredHeadOrVersionChange(boolean changedHead) {
        when(rangeSource.read(1L, "owner/repo", 42)).thenAnswer(invocation -> {
            var current = pullRequests.findById(pullRequest.getId()).orElseThrow();
            if (changedHead) current.setHeadRefOid("c".repeat(40));
            else current.setUpdatedAt(Instant.parse("2026-10-01T12:00:01Z"));
            pullRequests.save(current);
            return Optional.of(new ScmReviewRangeSource.ReviewRange(200L, 300L, HEAD, BASE));
        });

        assertThatThrownBy(() -> preparer.prepare(job))
                .isInstanceOf(changedHead ? JobPreparationException.class : ReviewSourceNotReadyException.class);
        assertThat(pullRequests.findById(pullRequest.getId()).orElseThrow().getBaseRefOid())
                .isNull();
    }
}

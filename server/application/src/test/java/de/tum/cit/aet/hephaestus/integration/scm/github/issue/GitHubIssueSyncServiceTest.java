package de.tum.cit.aet.hephaestus.integration.scm.github.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncCursorKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

class GitHubIssueSyncServiceTest extends BaseUnitTest {

    @Test
    void shouldAbortAtThePageLimitAndKeepTheCheckpointForTheNextRun() {
        var repositories = mock(RepositoryRepository.class);
        var repository = new Repository();
        repository.setId(21L);
        repository.setNameWithOwner("example/repository");
        when(repositories.findById(21L)).thenReturn(Optional.of(repository));
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var transactions = new TransactionTemplate(manager);
        var checkpoints = mock(BackfillStateProvider.class);
        var properties = mock(GitHubSyncProperties.class);
        when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(1));
        var clients = mock(GitHubGraphQlClientProvider.class);
        var client = mock(HttpGraphQlClient.class);
        var request = mock(RequestSpec.class);
        when(clients.forScope(42L)).thenReturn(client);
        when(client.documentName("GetRepositoryIssues")).thenReturn(request);
        when(request.variable(anyString(), any())).thenReturn(request);
        var connection = new GHIssueConnection();
        connection.setNodes(List.of(new GHIssue()));
        connection.setTotalCount(1001);
        var pageInfo = new GHPageInfo();
        pageInfo.setHasNextPage(true);
        pageInfo.setEndCursor("next");
        connection.setPageInfo(pageInfo);
        var response = mock(ClientGraphQlResponse.class);
        when(response.isValid()).thenReturn(true);
        var field = mock(ClientResponseField.class);
        when(response.field("repository.issues")).thenReturn(field);
        when(field.toEntity(GHIssueConnection.class)).thenReturn(connection);
        when(request.execute()).thenReturn(Mono.just(response));
        var service = new GitHubIssueSyncService(
                repositories,
                clients,
                mock(GitHubIssueProcessor.class),
                mock(GitHubIssueCommentProcessor.class),
                mock(GitHubIssueCommentSyncService.class),
                checkpoints,
                transactions,
                properties,
                new SyncSchedulerProperties(true, 7, "0 0 3 * * *", 15, null, null),
                mock(GitHubExceptionClassifier.class),
                mock(GitHubGraphQlSyncCoordinator.class));

        SyncResult result = service.syncForRepository(42L, 21L, 52L, null, null);

        assertThat(result.status()).isEqualTo(SyncResult.Status.ABORTED_ERROR);
        assertThat(result.isCompleted()).isFalse();
        verify(checkpoints, atLeastOnce()).updateSyncCursor(52L, SyncCursorKind.ISSUE, "next");
        verify(checkpoints, never()).updateSyncCursor(52L, SyncCursorKind.ISSUE, null);
    }
}

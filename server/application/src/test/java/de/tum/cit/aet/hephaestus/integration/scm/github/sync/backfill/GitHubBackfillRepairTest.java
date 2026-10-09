package de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillRestartProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitHubBackfillRepairTest extends BaseUnitTest {
    @Mock
    private RepositoryRepository repositories;

    @Mock
    private PullRequestRepository pullRequests;

    @Mock
    private GitHubGraphQlClientProvider clients;

    @Mock
    private GitHubSyncProperties properties;

    @Mock
    private BackfillRestartProvider state;

    @Mock
    private ActivityLedgerRepair ledger;

    @Mock
    private WorkspaceActorSelector actors;

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private RequestSpec request;

    @Mock
    private ClientGraphQlResponse response;

    @Mock
    private ClientResponseField field;

    private GitHubBackfillRepair repair;
    private SyncTarget target;

    @BeforeEach
    void setUp() {
        repair = new GitHubBackfillRepair(repositories, pullRequests, clients, properties, state, ledger, actors);
        target = SyncTargetTestBuilder.syncTarget()
                .id(7L)
                .scopeId(3L)
                .repositoryNameWithOwner("org/repo")
                .pullRequestBackfillHighWaterMark(1000)
                .pullRequestBackfillCheckpoint(0)
                .issueBackfillHighWaterMark(900)
                .issueBackfillCheckpoint(0)
                .build();
        var repo = new Repository();
        repo.setId(8L);
        repo.setNameWithOwner("org/repo");
        when(actors.connectedProviderId(3L)).thenReturn(Optional.of(2L));
        when(repositories.findByNameWithOwnerAndProviderId("org/repo", 2L)).thenReturn(Optional.of(repo));
        lenient().when(pullRequests.countStoredByRepositoryId(8L)).thenReturn(100L);
        lenient().when(clients.forScope(3L)).thenReturn(client);
        lenient().when(client.documentName(anyString())).thenReturn(request);
        lenient().when(request.variable(anyString(), anyString())).thenReturn(request);
        lenient().when(request.execute()).thenReturn(Mono.just(response));
        lenient().when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(1));
        lenient().when(response.isValid()).thenReturn(true);
        lenient().when(response.field("repository.pullRequests.totalCount")).thenReturn(field);
    }

    @Test
    void shouldRestartOnlyAfterProviderConfirmsTheGap() {
        when(field.toEntity(Integer.class)).thenReturn(500);
        var fresh = SyncTargetTestBuilder.syncTarget()
                .id(7L)
                .scopeId(3L)
                .repositoryNameWithOwner("org/repo")
                .build();
        when(state.restartCompletedBackfill(3, 7)).thenReturn(Optional.of(fresh));
        assertThat(repair.inspect(target, false)).isEqualTo(fresh);
        verify(state).restartCompletedBackfill(3, 7);
    }

    @ParameterizedTest
    @CsvSource({"79,100,true", "80,100,false", "0,19,false"})
    void shouldRestartOnlyForAMaterialProviderGap(long stored, int total, boolean expectedRestart) {
        when(pullRequests.countStoredByRepositoryId(8L)).thenReturn(stored);
        when(field.toEntity(Integer.class)).thenReturn(total);
        var restarted = SyncTargetTestBuilder.syncTarget()
                .id(7L)
                .scopeId(3L)
                .repositoryNameWithOwner("org/repo")
                .build();
        if (expectedRestart) when(state.restartCompletedBackfill(3, 7)).thenReturn(Optional.of(restarted));

        assertThat(repair.inspect(target, false)).isEqualTo(expectedRestart ? restarted : target);
        if (!expectedRestart) verify(state, never()).restartCompletedBackfill(3, 7);
    }

    @Test
    void shouldNotTreatSharedIssueNumbersAsMissingPullRequests() {
        when(field.toEntity(Integer.class)).thenReturn(100);
        assertThat(repair.inspect(target, false)).isEqualTo(target);
        assertThat(repair.inspect(target, false)).isEqualTo(target);
        verify(request).execute();
        verify(state, never()).restartCompletedBackfill(3, 7);
    }

    @Test
    void shouldLeaveCompletedStateUnchangedWhenProviderFails() {
        when(request.execute()).thenReturn(Mono.error(new IllegalStateException("Unavailable")));
        assertThat(repair.inspect(target, false)).isEqualTo(target);
        verify(state, never()).restartCompletedBackfill(3, 7);
    }

    @Test
    void shouldFailAdminJobWhenProviderVerificationFails() {
        when(request.execute()).thenReturn(Mono.error(new IllegalStateException("Unavailable")));
        assertThatThrownBy(() -> repair.inspect(target, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unavailable");
        verify(state, never()).restartCompletedBackfill(3, 7);
    }

    @Test
    void shouldRepairStoredLedgerOnAdminActionEvenWithoutAProviderGap() {
        when(field.toEntity(Integer.class)).thenReturn(100);
        repair.inspect(target, true);
        verify(ledger).reconcileRepository(3, 8);
        verify(state, never()).restartCompletedBackfill(3, 7);
    }
}

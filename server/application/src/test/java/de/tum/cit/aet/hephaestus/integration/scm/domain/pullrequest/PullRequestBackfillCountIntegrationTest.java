package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.config.SpringAsyncConfig;
import de.tum.cit.aet.hephaestus.core.tenancy.TenancyBypass;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillRestartProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.GitHubBackfillRepair;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Backfill reaches this count with no bypass open, on a manual run and on the scheduled GitHub cycle: a scheduler's
 * marker does not follow the work onto the monitoring executor, which the test context replaces with a no-op.
 */
class PullRequestBackfillCountIntegrationTest extends BaseIntegrationTest {

    private static final long SCOPE_ID = 1L;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldCountStoredPullRequestsWhenNoBypassIsOpen() {
        Repository repository = repository();
        pullRequest(repository);
        pullRequest(repository);

        assertThat(TenancyBypass.isActive()).isFalse();
        assertThat(pullRequests.countStoredByRepositoryId(repository.getId())).isEqualTo(2);
    }

    @Test
    void shouldInspectACompletedRepositoryWhenTheCallersBypassStaysOnItsThread() throws Exception {
        Repository repository = repository();
        pullRequest(repository);
        var actors = mock(WorkspaceActorSelector.class);
        when(actors.connectedProviderId(SCOPE_ID))
                .thenReturn(Optional.ofNullable(repository.getProvider().getId()));
        var repair = new GitHubBackfillRepair(
                repositories,
                pullRequests,
                mock(GitHubGraphQlClientProvider.class),
                mock(GitHubSyncProperties.class),
                mock(BackfillRestartProvider.class),
                mock(ActivityLedgerRepair.class),
                actors,
                mock(GitHubIssueCommentSyncService.class));
        var target = SyncTargetTestBuilder.syncTarget()
                .id(1L)
                .scopeId(SCOPE_ID)
                .repositoryNameWithOwner(repository.getNameWithOwner())
                .pullRequestBackfillHighWaterMark(1)
                .pullRequestBackfillCheckpoint(0)
                .backfillLastRunAt(Instant.now().minus(Duration.ofDays(2)))
                .build();
        var executor = new SpringAsyncConfig().monitoringExecutor();

        TenancyBypass.Scope scheduler = TenancyBypass.open("The scheduler's marker");
        try {
            var inspected = CompletableFuture.supplyAsync(() -> repair.inspect(target, false), executor)
                    .get(30, TimeUnit.SECONDS);

            assertThat(inspected).isEqualTo(target);
        } finally {
            scheduler.close();
        }
    }

    private Repository repository() {
        IdentityProvider provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        String name = "backfill-" + UUID.randomUUID();
        var created = new Repository();
        created.setNativeId(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE));
        created.setName(name);
        created.setNameWithOwner("backfill/" + name);
        created.setHtmlUrl("https://github.com/backfill/" + name);
        created.setVisibility(Repository.Visibility.PRIVATE);
        created.setProvider(provider);
        return repositories.save(created);
    }

    private void pullRequest(Repository repository) {
        // Rows from earlier tests stay: a fixed native id on the shared provider could collide with one of them.
        long nativeId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        jdbc.update("""
                INSERT INTO issue(issue_type,native_id,provider_id,repository_id,number,
                    created_at,state,comments_count,is_locked,is_merged,title,is_draft,additions,deletions,changed_files,commits)
                VALUES ('PULL_REQUEST',?,?,?,?,now(),'OPEN',0,false,false,'Work',false,0,0,0,0)
                """, nativeId, repository.getProvider().getId(), repository.getId(), nativeId % 1_000_000_000L);
    }
}

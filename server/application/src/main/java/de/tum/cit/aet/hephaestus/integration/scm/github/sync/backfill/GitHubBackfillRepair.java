package de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillRestartProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubRepositoryNameParser;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Checks provider counts before reopening a completed historical scan. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubBackfillRepair {
    private final RepositoryRepository repositories;
    private final PullRequestRepository pullRequests;
    private final GitHubGraphQlClientProvider clients;
    private final GitHubSyncProperties properties;
    private final BackfillRestartProvider backfillState;
    private final ActivityLedgerRepair ledger;
    private final WorkspaceActorSelector actors;
    private final Cache<Long, Boolean> inspections = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public SyncTarget inspect(SyncTarget target, boolean manual) {
        if (!manual && (!target.isPullRequestBackfillComplete() || inspections.getIfPresent(target.id()) != null))
            return target;
        var repository = findRepository(target);
        if (repository.isEmpty()) {
            if (manual) throw new IllegalStateException("Stored repository not found");
            return target;
        }
        var repo = repository.get();
        if (manual) ledger.reconcileRepository(target.scopeId(), repo.getId());
        if (!target.isPullRequestBackfillComplete()) return target;
        if (!manual
                && target.backfillLastRunAt() != null
                && target.backfillLastRunAt().isAfter(Instant.now().minus(Duration.ofDays(1)))) return target;
        if (!manual && !isSuspicious(target, repo)) return target;
        if (!manual && inspections.asMap().putIfAbsent(target.id(), true) != null) return target;
        try {
            var name = GitHubRepositoryNameParser.parse(repo.getNameWithOwner());
            if (name.isEmpty()) return target;
            var response = clients.forScope(target.scopeId())
                    .documentName("GetRepositoryPullRequestLatestUpdate")
                    .variable("owner", name.get().owner())
                    .variable("name", name.get().name())
                    .execute()
                    .block(properties.graphqlTimeout());
            if (response == null || !response.isValid()) {
                if (manual) throw new IllegalStateException("Provider coverage check returned no valid response");
                return target;
            }
            clients.trackRateLimit(target.scopeId(), response);
            var total = response.field("repository.pullRequests.totalCount").toEntity(Integer.class);
            if (total == null) {
                if (manual) throw new IllegalStateException("Provider coverage check returned no pull request count");
                return target;
            }
            long stored = count(repo);
            if (!BackfillRestartProvider.hasMaterialGap(stored, total)) return target;
            var restarted = backfillState.restartCompletedBackfill(target.scopeId(), target.id(), total, stored);
            if (restarted.isPresent()) {
                log.info(
                        "Restarted incomplete historical scan: workspaceId={}, syncTargetId={}, providerCount={}",
                        target.scopeId(),
                        target.id(),
                        total);
                return restarted.get();
            }
        } catch (RuntimeException e) {
            if (manual) throw e;
            log.warn(
                    "Historical coverage check failed: workspaceId={}, syncTargetId={}",
                    target.scopeId(),
                    target.id(),
                    e);
        }
        return target;
    }

    public Optional<Repository> findRepository(SyncTarget target) {
        return actors.connectedProviderId(target.scopeId())
                .flatMap(providerId ->
                        repositories.findByNameWithOwnerAndProviderId(target.repositoryNameWithOwner(), providerId));
    }

    private boolean isSuspicious(SyncTarget target, Repository repo) {
        var highWaterMark = target.pullRequestBackfillHighWaterMark();
        return highWaterMark != null && BackfillRestartProvider.hasMaterialGap(count(repo), highWaterMark);
    }

    private long count(Repository repo) {
        return pullRequests.countStoredByRepositoryId(repo.getId());
    }
}

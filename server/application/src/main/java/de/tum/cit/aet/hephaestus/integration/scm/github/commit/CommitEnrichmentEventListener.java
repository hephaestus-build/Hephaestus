package de.tum.cit.aet.hephaestus.integration.scm.github.commit;

import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Triggers commit metadata enrichment when new commits arrive via webhooks.
 *
 * <p>Listens for {@link ScmDomainEvent.CommitCreated} events and calls
 * {@link CommitMetadataEnrichmentService#enrichCommitMetadata} to resolve
 * multi-author contributor data, signature details, and associated PR links.
 *
 * <p>Only fires for <em>webhook</em>-originated events. Sync-originated commits
 * are already enriched by {@code GitHubDataSyncService} at the end of each sync
 * cycle, so triggering enrichment again would be redundant.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class CommitEnrichmentEventListener {

    private final CommitMetadataEnrichmentService commitMetadataEnrichmentService;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitCreated(ScmDomainEvent.CommitCreated event) {
        var context = event.context();

        // Sync events are already followed by enrichment in GitHubDataSyncService
        if (!context.isWebhook()) {
            return;
        }

        // Only enrich GitHub commits — this service uses the GitHub GraphQL API
        if (context.providerType() != null && !context.isGitHub()) {
            return;
        }

        RepositoryRef repoRef = context.repository();
        if (repoRef == null) {
            log.warn(
                    "Skipping commit enrichment: no repository in event context, commitId={}",
                    event.commit().id());
            return;
        }

        Long scopeId = context.scopeId();
        if (scopeId == null) {
            log.debug(
                    "Skipping commit enrichment: no scopeId, commitId={}, repo={}",
                    event.commit().id(),
                    repoRef.nameWithOwner());
            return;
        }

        try {
            int enriched = commitMetadataEnrichmentService.enrichCommitMetadata(
                    repoRef.id(), repoRef.nameWithOwner(), scopeId);
            log.debug(
                    "Webhook commit enrichment completed: repo={}, enrichedCount={}",
                    repoRef.nameWithOwner(),
                    enriched);
        } catch (Exception e) {
            log.warn("Webhook commit enrichment failed: repo={}, error={}", repoRef.nameWithOwner(), e.getMessage());
        }
    }
}

package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Restores facts still present in provider rows, never inferred lifecycle transitions. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityLedgerReconciler implements ActivityLedgerRepair {
    private static final int CHUNK_SIZE = 1000;
    private final ActivityLedgerRepository repository;
    private final PersonDataWriteFence writeFence;
    private final PlatformTransactionManager transactionManager;
    private final WorkspaceActorSelector actors;

    @Override
    public int reconcileRepository(long workspaceId, long repositoryId) {
        var providerId = actors.connectedProviderId(workspaceId);
        if (providerId.isEmpty() || !repository.isMonitored(workspaceId, repositoryId, providerId.get())) return 0;
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int inserted = 0;
        for (Source source : Source.values()) {
            long ceiling = repository.findCeiling(repositoryId, source.name());
            long after = 0;
            while (after < ceiling) {
                long lower = after;
                var chunk = Objects.requireNonNull(transaction.execute(
                        status -> reconcileChunk(workspaceId, repositoryId, source, lower, ceiling)));
                inserted += chunk.inserted();
                if (chunk.lastId() == after) break;
                after = chunk.lastId();
            }
        }
        if (inserted > 0) {
            log.info(
                    "Repaired activity ledger: workspaceId={}, repositoryId={}, inserted={}",
                    workspaceId,
                    repositoryId,
                    inserted);
        }
        return inserted;
    }

    private Chunk reconcileChunk(long workspaceId, long repositoryId, Source source, long after, long ceiling) {
        var ids = repository.findChunkIds(repositoryId, source.name(), after, ceiling, CHUNK_SIZE);
        if (ids.isEmpty()) return new Chunk(after, 0);
        long last = ids.getLast();
        if (source == Source.REPLY_REVIEW) {
            repository.deleteReplyEvents(workspaceId, repositoryId, source.name(), after, last);
            return new Chunk(last, 0);
        }
        var candidates = repository.findChunkActors(repositoryId, source.name(), after, last);
        var admitted = writeFence.holdForUserWrites(candidates);
        if (admitted.isEmpty()) return new Chunk(last, 0);
        return new Chunk(last, repository.insertChunk(workspaceId, repositoryId, source.name(), after, last, admitted));
    }

    private record Chunk(long lastId, int inserted) {}

    private enum Source {
        ISSUE,
        REVIEW,
        REPLY_REVIEW,
        COMMENT,
        REVIEW_COMMENT
    }
}

package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Restores facts still present in provider rows, never inferred lifecycle transitions. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityLedgerReconciler implements ActivityLedgerRepair {
    static final int CHUNK_SIZE = 200;
    private final ActivityLedgerRepository repository;
    private final ActivityPersonWriteAdmission admission;
    private final PlatformTransactionManager transactionManager;
    private final WorkspaceActorSelector actors;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int correctCommitActors(long resolutionId) {
        var admitted = admission.holdActors(repository.findCorrectedCommitAuthors(resolutionId));
        return repository.correctCommitActors(resolutionId, admitted);
    }

    @Override
    public int reconcileRepository(long workspaceId, long repositoryId) {
        var providerId = actors.connectedProviderId(workspaceId);
        if (providerId.isEmpty() || !repository.isMonitored(workspaceId, repositoryId, providerId.get())) return 0;
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int inserted = 0;
        long ceiling = repository.findCeiling(repositoryId);
        long after = 0;
        while (after < ceiling) {
            var parents = repository.findChunkParents(repositoryId, after, ceiling, CHUNK_SIZE);
            if (parents.isEmpty()) break;
            long lower = after;
            long last = parents.getLast();
            // One transaction per source: each holds its own sorted set of actor locks, and two sets in one
            // transaction could deadlock with an erasure that locks them in the other order.
            for (Source source : Source.values()) {
                inserted += Objects.requireNonNull(
                        transaction.execute(status -> reconcileSource(workspaceId, repositoryId, source, lower, last)));
            }
            after = last;
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

    private int reconcileSource(long workspaceId, long repositoryId, Source source, long after, long last) {
        if (source == Source.REPLY_REVIEW) {
            repository.deleteReplyEvents(workspaceId, repositoryId, source.name(), after, last);
            return 0;
        }
        if (source == Source.INVALID_ISSUE) {
            repository.deleteInvalidIssueEvents(workspaceId, repositoryId, source.name(), after, last);
            return 0;
        }
        var candidates = repository.findChunkActors(repositoryId, source.name(), after, last);
        var admitted = admission.holdActors(candidates);
        if (admitted.isEmpty()) return 0;
        return repository.insertChunk(workspaceId, repositoryId, source.name(), after, last, admitted);
    }

    private enum Source {
        INVALID_ISSUE,
        ISSUE,
        REVIEW,
        REPLY_REVIEW,
        COMMENT,
        REVIEW_COMMENT
    }
}

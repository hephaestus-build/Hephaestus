package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.practices.spi.AdmissionReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ResultStoreService implements ResultStore {
    private final ResultAdmissionWriter admission;
    private final ItemIndexService itemLocks;
    private final ResultRepository results;
    private final ResultMarkRepository marks;
    private final ResultMarkService marking;
    private final PlatformTransactionManager transactions;

    @Override
    public AdmissionReceipt admit(
            long workspaceId, UUID reviewId, UUID jobId, int attempt, List<ResultCandidate> candidates) {
        var frozen = List.copyOf(candidates);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            return admission.admitLocked(workspaceId, reviewId, jobId, attempt, frozen);
        var retry = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(3)
                .predicate(ResultStoreService::retryable)
                .build());
        return retry.invoke(() -> Objects.requireNonNull(new TransactionTemplate(transactions)
                .execute(status -> admission.admitLocked(workspaceId, reviewId, jobId, attempt, frozen))));
    }

    private static boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql
                    && ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) return true;
        return false;
    }

    @Override
    public int endCurrent(long workspaceId, long itemId, ResultEndReason reason, Instant at) {
        return Objects.requireNonNull(new TransactionTemplate(transactions).execute(status -> {
            itemLocks.lock(workspaceId, itemId);
            return results.endCurrent(workspaceId, itemId, reason, at);
        }));
    }

    @Override
    public UUID mark(long workspaceId, UUID resultId, ResultMarkKind kind, long actorId, String reason) {
        return marking.create(workspaceId, resultId, kind, actorId, reason).getId();
    }

    @Override
    public boolean removeMark(long workspaceId, UUID markId, long actorId, String reason) {
        return Objects.requireNonNull(new TransactionTemplate(transactions).execute(status -> {
            var mark = marks.findByIdAndWorkspaceId(markId, workspaceId).orElseThrow();
            var active = mark.getRemovedAt() == null;
            marking.remove(workspaceId, mark.getResultId(), markId, actorId, reason);
            return active;
        }));
    }
}

package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.advice.PracticeAdviceReceipt.Kind;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceRef;
import de.tum.cit.aet.hephaestus.practices.spi.RetireAdvice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviseAdvice;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rows that an advice write reads, locks and writes, in the writer's transaction. {@link PracticeAdviceWriter}
 * decides; this class only touches rows, in the lock order the writer's Javadoc gives.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AdviceRows {
    private final ResultRepository results;
    private final ProblemRepository problems;
    private final PracticeAdviceRepository entries;
    private final PracticeAdviceRevisionRepository revisions;
    private final PracticeAdviceSourceRepository sources;
    private final PracticeAdviceReceiptRepository receipts;
    private final EntityManager entities;

    /** The live observations among {@code ids}, each key-share locked in id order. */
    public Map<UUID, Result> shareObservations(long workspaceId, List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var live = results.shareKeysInIdOrder(workspaceId, ids.toArray(UUID[]::new));
        if (live.isEmpty()) return Map.of();
        return results.findByWorkspaceIdAndIdIn(workspaceId, live).stream()
                .collect(Collectors.toMap(Result::getId, Function.identity()));
    }

    /** The live problems among {@code ids}, each key-share locked in id order. */
    public Map<UUID, Problem> shareProblems(long workspaceId, List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var live = problems.shareKeysInIdOrder(workspaceId, ids.toArray(UUID[]::new));
        if (live.isEmpty()) return Map.of();
        return problems.findByWorkspaceIdAndIdIn(workspaceId, live).stream()
                .collect(Collectors.toMap(Problem::getId, Function.identity()));
    }

    /**
     * Locks the entry and reads it as it is now. The native lock returns the instance that this transaction may already
     * hold, from an earlier read or before a native update, so it is refreshed under the lock: a version check or a
     * dismissal check never runs on a stale copy.
     */
    public Optional<PracticeAdvice> lockEntry(long workspaceId, long accountId, long practiceId) {
        var entry = entries.lockEntry(workspaceId, accountId, practiceId);
        entry.ifPresent(entities::refresh);
        return entry;
    }

    /** Whether this write created the entry, at version 0. */
    public boolean insertFirst(UUID id, long workspaceId, long accountId, long practiceId) {
        return entries.insertFirst(id, workspaceId, accountId, practiceId) == 1;
    }

    public Optional<PracticeAdviceReceipt> receipt(UUID adviceId, long workspaceId, UUID reviewId) {
        return receipts.findByAdviceIdAndWorkspaceIdAndReviewId(adviceId, workspaceId, reviewId);
    }

    /** Writes the next revision, its sources and its receipt; returns the entry's new version. */
    public long writeRevision(
            ReviseAdvice command, UUID adviceId, long version, @Nullable Integer latest, byte[] digest) {
        long ws = command.workspaceId();
        int number = latest == null ? 1 : latest + 1;
        var revisionId = UUID.randomUUID();
        revisions.insertWritten(
                revisionId,
                ws,
                command.accountId(),
                adviceId,
                number,
                command.text(),
                command.reviewId(),
                command.citations().size(),
                command.citations().size() + command.otherObservations().size());
        int ordinal = 0;
        for (var ref : command.citations()) insertSource(command, revisionId, ref, true, ordinal++);
        for (var ref : command.otherObservations()) insertSource(command, revisionId, ref, false, ordinal++);
        entries.markRevised(adviceId, ws, number);
        receipts.insertReceipt(adviceId, ws, command.reviewId(), Kind.REVISE.name(), digest, version + 1);
        return version + 1;
    }

    /** Retires the entry and writes the receipt; returns the entry's new version. */
    public long retire(PracticeAdvice entry, RetireAdvice command, byte[] digest) {
        entries.markRetired(
                entry.getId(), command.workspaceId(), command.reason().name());
        long version = entry.getVersion() + 1;
        receipts.insertReceipt(
                entry.getId(), command.workspaceId(), command.reviewId(), Kind.RETIRE.name(), digest, version);
        return version;
    }

    private void insertSource(ReviseAdvice command, UUID revisionId, EvidenceRef ref, boolean cited, int ordinal) {
        var problemId = ref.problemId();
        sources.insertSource(
                UUID.randomUUID(),
                command.workspaceId(),
                command.accountId(),
                revisionId,
                ref.resultId(),
                problemId == null ? "" : problemId.toString(),
                cited,
                ordinal);
    }
}

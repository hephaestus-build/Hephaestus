package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ResultHistoryQueryService implements ResultHistoryQuery {
    private final ResultRepository results;
    private final ProblemRepository problems;
    private final ResultMarkRepository marks;
    private final PracticeVersionRepository versions;
    private final ReviewRepository reviews;
    private final ProductItemQuery items;
    private final ResultSourceAuthorization authorization;
    private final ResultHistoryRepository historyRows;
    private final ReviewAssignmentQuery assignments;
    private final AccountMembershipQuery memberships;
    private final HistoryCustodyQuery custody;

    @Override
    @Transactional(readOnly = true)
    public List<ResultHistoryEntry> forDeveloper(long workspaceId, long developerAccountId, Instant since, int limit) {
        limit(limit);
        if (!memberships.isActiveVisibleMember(workspaceId, developerAccountId)) return List.of();
        return history(
                workspaceId,
                historyRows.forDeveloper(workspaceId, developerAccountId, since, PageRequest.of(0, limit)),
                SourceUsePurpose.CONVERSATIONAL_MENTORING);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResultHistoryEntry> forItem(long workspaceId, long itemId, long practiceId, int limit) {
        limit(limit);
        if (items.find(workspaceId, itemId).isEmpty()) return List.of();
        return history(
                workspaceId,
                historyRows.forItem(workspaceId, itemId, practiceId, PageRequest.of(0, limit)),
                SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResultHistoryEntry> find(
            long workspaceId, UUID resultId, long viewerAccountId, SourceUsePurpose purpose) {
        return readable(workspaceId, resultId, viewerAccountId, purpose);
    }

    private Optional<ResultHistoryEntry> readable(
            long workspaceId, UUID resultId, long viewerAccountId, SourceUsePurpose purpose) {
        if (!memberships.isActiveVisibleMember(workspaceId, viewerAccountId)) return Optional.empty();
        return results.findByIdAndWorkspaceId(resultId, workspaceId)
                .filter(r -> Objects.equals(r.getDeveloperId(), viewerAccountId)
                        || (purpose == SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW
                                && memberships.isAdministrator(workspaceId, viewerAccountId)))
                .flatMap(r -> entry(r, purpose, new Read(workspaceId, List.of(r))))
                .filter(ResultHistoryEntry::copyAuthorityComplete);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResultHistoryEntry> findOfProblem(
            long workspaceId, UUID problemId, long viewerAccountId, SourceUsePurpose purpose) {
        return problems.findByIdAndWorkspaceId(problemId, workspaceId)
                .flatMap(problem -> readable(workspaceId, problem.getResultId(), viewerAccountId, purpose));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResultHistoryEntry> forAssignment(long workspaceId, ReviewAssignmentId id, int limit) {
        limit(limit);
        var assignment = assignments.find(workspaceId, id);
        if (assignment.isEmpty()) return List.of();
        return history(
                workspaceId,
                historyRows.forAssignment(
                        workspaceId,
                        id.itemId(),
                        id.practiceId(),
                        assignment.orElseThrow().developerId(),
                        PageRequest.of(0, limit)),
                SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW);
    }

    private List<ResultHistoryEntry> history(long workspaceId, List<Result> rows, SourceUsePurpose purpose) {
        var read = new Read(workspaceId, rows);
        return rows.stream()
                .map(r -> entry(r, purpose, read))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<ResultHistoryEntry> entry(Result result, SourceUsePurpose purpose, Read read) {
        if (result.getPracticeVersionId() == null || result.getItemVersion() == null) return Optional.empty();
        var version = versions.findByIdAndWorkspaceId(result.getPracticeVersionId(), result.getWorkspaceId());
        var item = items.find(result.getWorkspaceId(), result.getItemId());
        var review = reviews.findByIdAndWorkspaceId(result.getReviewId(), result.getWorkspaceId());
        if (version.isEmpty() || item.isEmpty() || review.isEmpty()) return Optional.empty();
        var facts = authorization.custody(result);
        boolean permitted = authorization.authorized(result, facts, purpose);
        var children = permitted
                ? problems
                        .findByWorkspaceIdAndResultIdOrderByPositionAsc(result.getWorkspaceId(), result.getId())
                        .stream()
                        .map(p -> {
                            var citation = p.getCitations().isArray()
                                            && !p.getCitations().isEmpty()
                                    ? p.getCitations().get(0)
                                    : null;
                            return new ProblemHistoryEntry(
                                    p.getId(),
                                    p.getSeverity(),
                                    p.getSummary(),
                                    citation == null || !citation.path("path").isString()
                                            ? null
                                            : citation.path("path").asString(),
                                    citation == null
                                                    || !citation.path("startLine")
                                                            .isInt()
                                            ? null
                                            : citation.path("startLine").intValue());
                        })
                        .toList()
                : List.<ProblemHistoryEntry>of();
        var questions = new ArrayList<String>();
        if (permitted && result.getEvidence() != null)
            for (var question : result.getEvidence().path("openQuestions"))
                if (question.path("question").isString())
                    questions.add(question.path("question").asString());
        var criteria = version.orElseThrow().getDefinition().get("criteria");
        return Optional.of(new ResultHistoryEntry(
                result.getId(),
                result.getReviewId(),
                result.getPracticeId(),
                version.orElseThrow().getPractice().getSlug(),
                version.orElseThrow().getDefinition().path("name").stringValue(null),
                version.orElseThrow().getNumber(),
                result.getItemId(),
                item.orElseThrow().kind().value(),
                result.getItemVersion(),
                result.getDeveloperId(),
                result.getOutcome(),
                result.getSeverity(),
                permitted ? result.getSummary() : null,
                result.getRecordedAt(),
                result.getEndedAt(),
                result.getEndReason(),
                permitted ? read.capturedAt(result) : null,
                permitted ? read.storedWorkVersion(result) : ResultHistoryEntry.StoredWorkVersion.UNKNOWN,
                marks
                        .findByWorkspaceIdAndResultIdOrderByCreatedAtDescIdDesc(result.getWorkspaceId(), result.getId())
                        .stream()
                        .filter(m -> m.getRemovedAt() == null)
                        .map(m -> m.getKind())
                        .distinct()
                        .toList(),
                children,
                questions,
                item.orElseThrow(),
                review.orElseThrow().getOrigin(),
                permitted ? result.getExplanation() : null,
                permitted ? result.getEvidence() : null,
                permitted ? result.getExample() : null,
                permitted ? result.getCoverage() : null,
                permitted ? criteria : null,
                result.getEvidenceVersion(),
                facts.sourceJobIds(),
                facts.copiedIdentities(),
                facts.repositoryIds(),
                permitted));
    }

    /**
     * The lookups of one history read: each work item's current version and each producing job's capture time are
     * read once, and only for a result whose words may be read. Nothing outlives the read, and nothing it reads ends a
     * result.
     */
    private final class Read {
        private final long workspaceId;
        private final List<Result> rows;
        private final Map<Long, ProductItemQuery.CurrentWorkVersion> works = new HashMap<>();
        private final Map<Receipt, Optional<Instant>> receipts = new HashMap<>();
        private @Nullable Map<UUID, UUID> producers;

        Read(long workspaceId, List<Result> rows) {
            this.workspaceId = workspaceId;
            this.rows = rows;
        }

        ResultHistoryEntry.StoredWorkVersion storedWorkVersion(Result result) {
            // A row removed after the history read found it active is not compared.
            return switch (works.computeIfAbsent(
                    result.getItemId(), itemId -> items.currentWorkVersion(workspaceId, itemId))) {
                case ProductItemQuery.CurrentWorkVersion.Known known ->
                    known.value().equals(result.getItemVersion())
                            ? ResultHistoryEntry.StoredWorkVersion.SAME
                            : ResultHistoryEntry.StoredWorkVersion.DIFFERENT;
                case ProductItemQuery.CurrentWorkVersion.Unknown.INSTANCE ->
                    ResultHistoryEntry.StoredWorkVersion.UNKNOWN;
                case ProductItemQuery.CurrentWorkVersion.Removed.INSTANCE ->
                    ResultHistoryEntry.StoredWorkVersion.UNKNOWN;
            };
        }

        @Nullable
        Instant capturedAt(Result result) {
            var known = producers;
            if (known == null) {
                known = authorization.producingJobs(workspaceId, rows);
                producers = known;
            }
            var job = known.get(result.getId());
            if (job == null) return null;
            return receipts.computeIfAbsent(
                            new Receipt(result.getReviewId(), job),
                            receipt -> custody.capturedAt(workspaceId, receipt.reviewId(), receipt.jobId()))
                    .orElse(null);
        }
    }

    private record Receipt(UUID reviewId, UUID jobId) {}

    private static void limit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Use a history limit from 1 to 1000.");
    }
}

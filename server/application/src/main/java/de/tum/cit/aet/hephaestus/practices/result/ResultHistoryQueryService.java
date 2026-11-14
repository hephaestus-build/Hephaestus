package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private static final int MAX_LIMIT = 1000;

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

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ResultHistoryEntry> findAll(
            long workspaceId, Collection<UUID> resultIds, long viewerAccountId, SourceUsePurpose purpose) {
        return readable(workspaceId, resultIds, viewerAccountId, purpose);
    }

    private Map<UUID, ResultHistoryEntry> readable(
            long workspaceId, Collection<UUID> resultIds, long viewerAccountId, SourceUsePurpose purpose) {
        if (resultIds.isEmpty() || !memberships.isActiveVisibleMember(workspaceId, viewerAccountId)) return Map.of();
        var ids = List.copyOf(Set.copyOf(resultIds));
        Map<UUID, ResultHistoryEntry> found = new HashMap<>();
        @Nullable Boolean administrator = null;
        // One read holds at most a history limit of rows, so its collection reads stay far below the bind limit.
        for (int from = 0; from < ids.size(); from += MAX_LIMIT) {
            var rows = results.findByWorkspaceIdAndIdIn(
                    workspaceId, ids.subList(from, Math.min(ids.size(), from + MAX_LIMIT)));
            // Only an operator reads another developer's result, so only such a row asks whether they administer.
            if (administrator == null
                    && purpose == SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW
                    && rows.stream().anyMatch(r -> !Objects.equals(r.getDeveloperId(), viewerAccountId)))
                administrator = memberships.isAdministrator(workspaceId, viewerAccountId);
            boolean others = Boolean.TRUE.equals(administrator);
            history(
                            workspaceId,
                            rows.stream()
                                    .filter(r -> others || Objects.equals(r.getDeveloperId(), viewerAccountId))
                                    .toList(),
                            purpose)
                    .stream()
                    .filter(ResultHistoryEntry::copyAuthorityComplete)
                    .forEach(entry -> found.put(entry.resultId(), entry));
        }
        return found;
    }

    private Optional<ResultHistoryEntry> readable(
            long workspaceId, UUID resultId, long viewerAccountId, SourceUsePurpose purpose) {
        return Optional.ofNullable(readable(workspaceId, List.of(resultId), viewerAccountId, purpose)
                .get(resultId));
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
        var read = new Read(workspaceId, rows, purpose);
        return rows.stream().map(read::entry).flatMap(Optional::stream).toList();
    }

    /**
     * One history read. What its rows show is read for all of them at once: their practice versions, work items,
     * reviews, custody and source decisions, problems, marks and capture times. Only each work item's current version
     * is read per item, and only for a result whose words may be read. Nothing outlives the read, and nothing it reads
     * ends a result.
     */
    private final class Read {
        private final long workspaceId;
        private final List<Result> rows;
        private final Map<Long, PracticeVersion> versionsById = new HashMap<>();
        private final Map<Long, ItemRef> itemsById;
        private final Map<UUID, Review> reviewsById = new HashMap<>();
        private final Map<UUID, ResultSourceAuthorization.Check> checks;
        private final Map<UUID, List<ResultMarkKind>> activeMarks = new HashMap<>();
        private final Map<UUID, List<ProblemHistoryEntry>> problemsOf = new HashMap<>();
        private final Map<Long, ProductItemQuery.CurrentWorkVersion> works = new HashMap<>();
        private @Nullable Map<UUID, Instant> captured;

        Read(long workspaceId, List<Result> rows, SourceUsePurpose purpose) {
            this.workspaceId = workspaceId;
            this.rows = rows;
            var listed = rows.stream()
                    .filter(r -> r.getPracticeVersionId() != null && r.getItemVersion() != null)
                    .toList();
            var versionIds = listed.stream()
                    .map(Result::getPracticeVersionId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!versionIds.isEmpty())
                versions.findByWorkspaceIdAndIdIn(workspaceId, versionIds)
                        .forEach(version -> versionsById.put(version.getId(), version));
            itemsById = items.findAll(
                    workspaceId,
                    listed.stream().map(Result::getItemId).distinct().toList());
            var reviewIds = listed.stream().map(Result::getReviewId).distinct().toList();
            if (!reviewIds.isEmpty())
                reviews.findByWorkspaceIdAndIdIn(workspaceId, reviewIds)
                        .forEach(review -> reviewsById.put(review.getId(), review));
            // A row without its version, work item or review is not listed, and nothing else is read for it.
            var shown = listed.stream()
                    .filter(r -> versionsById.containsKey(r.getPracticeVersionId())
                            && itemsById.containsKey(r.getItemId())
                            && reviewsById.containsKey(r.getReviewId()))
                    .toList();
            checks = authorization.checkAll(
                    workspaceId, shown.stream().map(Result::facts).toList(), purpose);
            var ids = shown.stream().map(Result::getId).toList();
            if (!ids.isEmpty())
                for (var mark : marks.findByWorkspaceIdAndResultIdInOrderByCreatedAtDescIdDesc(workspaceId, ids)) {
                    if (mark.getRemovedAt() != null) continue;
                    var kinds = activeMarks.computeIfAbsent(mark.getResultId(), ignored -> new ArrayList<>());
                    if (!kinds.contains(mark.getKind())) kinds.add(mark.getKind());
                }
            // Only a result whose words may be read shows its problems.
            var readable = shown.stream()
                    .map(Result::getId)
                    .filter(id -> {
                        var check = checks.get(id);
                        return check != null && check.permitted();
                    })
                    .toList();
            if (!readable.isEmpty())
                problems.findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(workspaceId, readable)
                        .forEach(problem -> problemsOf
                                .computeIfAbsent(problem.getResultId(), ignored -> new ArrayList<>())
                                .add(problem(problem)));
        }

        Optional<ResultHistoryEntry> entry(Result result) {
            if (result.getPracticeVersionId() == null || result.getItemVersion() == null) return Optional.empty();
            var version = versionsById.get(result.getPracticeVersionId());
            var item = itemsById.get(result.getItemId());
            var review = reviewsById.get(result.getReviewId());
            var check = checks.get(result.getId());
            if (version == null || item == null || review == null || check == null) return Optional.empty();
            var facts = check.custody();
            boolean permitted = check.permitted();
            var children =
                    permitted ? problemsOf.getOrDefault(result.getId(), List.of()) : List.<ProblemHistoryEntry>of();
            var questions = new ArrayList<String>();
            if (permitted && result.getEvidence() != null)
                for (var question : result.getEvidence().path("openQuestions"))
                    if (question.path("question").isString())
                        questions.add(question.path("question").asString());
            var criteria = version.getDefinition().get("criteria");
            return Optional.of(new ResultHistoryEntry(
                    result.getId(),
                    result.getReviewId(),
                    result.getPracticeId(),
                    version.getPractice().getSlug(),
                    version.getDefinition().path("name").stringValue(null),
                    version.getNumber(),
                    result.getItemId(),
                    item.kind().value(),
                    result.getItemVersion(),
                    result.getDeveloperId(),
                    result.getOutcome(),
                    result.getSeverity(),
                    permitted ? result.getSummary() : null,
                    result.getRecordedAt(),
                    result.getEndedAt(),
                    result.getEndReason(),
                    permitted ? capturedAt(result) : null,
                    permitted ? storedWorkVersion(result) : ResultHistoryEntry.StoredWorkVersion.UNKNOWN,
                    activeMarks.getOrDefault(result.getId(), List.of()),
                    children,
                    questions,
                    item,
                    review.getOrigin(),
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

        private static ProblemHistoryEntry problem(Problem p) {
            var citation = p.getCitations().isArray() && !p.getCitations().isEmpty()
                    ? p.getCitations().get(0)
                    : null;
            return new ProblemHistoryEntry(
                    p.getId(),
                    p.getSeverity(),
                    p.getSummary(),
                    citation == null || !citation.path("path").isString()
                            ? null
                            : citation.path("path").asString(),
                    citation == null || !citation.path("startLine").isInt()
                            ? null
                            : citation.path("startLine").intValue());
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

        /** The capture time of the job that produced the result, read at once for every row whose words may be read. */
        @Nullable
        Instant capturedAt(Result result) {
            var known = captured;
            if (known == null) {
                known = captureTimes();
                captured = known;
            }
            return known.get(result.getId());
        }

        private Map<UUID, Instant> captureTimes() {
            var producers = authorization.producingJobs(
                    workspaceId, rows.stream().map(Result::facts).toList());
            Map<UUID, HistoryCustodyQuery.Receipt> receipts = new HashMap<>();
            for (var row : rows) {
                var check = checks.get(row.getId());
                var job = producers.get(row.getId());
                if (check != null && check.permitted() && job != null)
                    receipts.put(row.getId(), new HistoryCustodyQuery.Receipt(row.getReviewId(), job));
            }
            var times = custody.capturedAt(workspaceId, Set.copyOf(receipts.values()));
            Map<UUID, Instant> byResult = new HashMap<>();
            receipts.forEach((resultId, receipt) -> {
                var at = times.get(receipt);
                if (at != null) byResult.put(resultId, at);
            });
            return byResult;
        }
    }

    private static void limit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT)
            throw new InvalidInputException("Use a history limit from 1 to " + MAX_LIMIT + ".");
    }
}

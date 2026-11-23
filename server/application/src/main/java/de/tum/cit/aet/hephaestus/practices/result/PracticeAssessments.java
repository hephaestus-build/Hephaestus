package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.practices.spi.AssessmentPort;
import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PracticeAssessments implements AssessmentPort {
    private final ResultHistoryQuery history;
    private final ReviewAssignmentQuery assignments;
    private final RegisteredItemLabels labels;
    private final DeveloperPracticeStandings standings;

    @Override
    public ObservationHistory observations(long workspaceId, long accountId, Instant since, int limit) {
        if (limit < 1 || limit >= ResultHistoryQuery.MAX_LIMIT)
            throw new InvalidInputException(
                    "Use an observation limit from 1 to " + (ResultHistoryQuery.MAX_LIMIT - 1) + ".");
        var candidates = history.forDeveloper(workspaceId, accountId, since, limit + 1);
        var own = candidates.stream()
                .limit(limit)
                .filter(entry -> ownObservation(entry, workspaceId, accountId))
                .toList();
        var readable =
                own.stream().filter(ResultHistoryEntry::copyAuthorityComplete).toList();
        var work = labels.labels(
                workspaceId, readable.stream().map(ResultHistoryEntry::item).toList());
        return new ObservationHistory(
                readable.stream()
                        .map(entry -> project(entry, work.get(entry.itemId())))
                        .toList(),
                own.size() - readable.size(),
                candidates.size() > limit);
    }

    @Override
    public Optional<AssessmentRecord> assessment(long workspaceId, long accountId, AssessmentKind kind, UUID id) {
        return switch (kind) {
            case OBSERVATION ->
                project(
                                workspaceId,
                                accountId,
                                history.find(workspaceId, id, accountId, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                        .map(AssessmentRecord.class::cast);
            case PRIVATE_CHECK_OUTCOME, SELF_REPORT -> Optional.empty();
        };
    }

    @Override
    public Optional<Observation> observationOfProblem(long workspaceId, long accountId, UUID problemId) {
        return project(
                workspaceId,
                accountId,
                history.findOfProblem(workspaceId, problemId, accountId, SourceUsePurpose.CONVERSATIONAL_MENTORING));
    }

    @Override
    public List<Standing> standings(long workspaceId, long accountId) {
        return standings.standings(workspaceId, accountId);
    }

    @Override
    public Map<ReviewedItem, ReviewCheck> reviewChecks(
            long workspaceId, long accountId, Collection<ReviewedItem> reviewed) {
        var pairs = reviewed.stream()
                .map(pair -> new ReviewAssignmentQuery.ReviewedItem(pair.reviewId(), pair.itemId()))
                .collect(Collectors.toUnmodifiableSet());
        return assignments.recordedForDeveloper(workspaceId, accountId, pairs).entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> new ReviewedItem(
                                entry.getKey().reviewId(), entry.getKey().itemId()),
                        entry -> project(entry.getValue())));
    }

    private static ReviewCheck project(ReviewAssignmentQuery.Recorded review) {
        var reason = review.stopReason();
        var counts = review.counts();
        var status = review.status().isTerminal()
                        && (reason == ReviewStopReason.PREPARATION_FAILED
                                || reason == ReviewStopReason.EVIDENCE_TOO_LARGE)
                        && counts != null
                        && counts.judged() == 0
                        && counts.reused() == 0
                        && counts.pending() == 0
                ? CheckStatus.COULD_NOT_CHECK
                : null;
        return new ReviewCheck(
                review.status().name(),
                reason == null ? null : reason.name(),
                counts == null
                        ? null
                        : new CheckCounts(
                                counts.total(), counts.judged(), counts.reused(), counts.withheld(), counts.pending()),
                status);
    }

    private Optional<Observation> project(long workspaceId, long accountId, Optional<ResultHistoryEntry> entry) {
        return entry.filter(ResultHistoryEntry::copyAuthorityComplete)
                .filter(value -> ownObservation(value, workspaceId, accountId))
                .map(value -> project(
                        value, labels.labels(workspaceId, List.of(value.item())).get(value.itemId())));
    }

    private static boolean ownObservation(ResultHistoryEntry entry, long workspaceId, long accountId) {
        return Objects.equals(entry.developerAccountId(), accountId)
                && entry.item().workspaceId() == workspaceId
                && entry.origin() != ReviewOrigin.TRIAL;
    }

    private static Observation project(ResultHistoryEntry entry, @Nullable ItemLabel label) {
        return new Observation(
                entry.resultId(),
                entry.reviewId(),
                entry.practiceSlug(),
                entry.practiceName(),
                entry.practiceVersion(),
                entry.itemId(),
                entry.itemKind(),
                entry.itemVersion(),
                entry.outcome(),
                entry.summary(),
                entry.recordedAt(),
                entry.endedAt(),
                entry.endReason(),
                entry.capturedAt(),
                switch (entry.storedWorkVersion()) {
                    case SAME -> StoredWorkVersion.SAME;
                    case DIFFERENT -> StoredWorkVersion.DIFFERENT;
                    case UNKNOWN -> StoredWorkVersion.UNKNOWN;
                },
                entry.activeMarks(),
                entry.problems().stream()
                        .map(problem -> new Problem(
                                problem.problemId(),
                                problem.severity(),
                                problem.summary(),
                                problem.path(),
                                problem.startLine()))
                        .toList(),
                entry.openQuestions(),
                label == null ? null : new Work(label.title(), label.number(), label.url()),
                entry.explanation(),
                entry.evidence(),
                entry.example(),
                entry.coverage(),
                entry.criteria(),
                new HistoryCustody(
                        entry.copyAuthorityComplete(),
                        false,
                        entry.copiedIdentities(),
                        entry.repositoryIds(),
                        entry.sourceJobIds()));
    }
}

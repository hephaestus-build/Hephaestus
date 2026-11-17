package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class ResultTestFixtures {
    private ResultTestFixtures() {}

    public static Result.ResultBuilder resultBuilder(Result row) {
        return Result.builder()
                .id(row.getId())
                .workspaceId(row.getWorkspaceId())
                .reviewId(row.getReviewId())
                .practiceId(row.getPracticeId())
                .practiceVersionId(row.getPracticeVersionId())
                .itemId(row.getItemId())
                .itemVersion(row.getItemVersion())
                .developerId(row.getDeveloperId())
                .outcome(row.getOutcome())
                .severity(row.getSeverity())
                .summary(row.getSummary())
                .explanation(row.getExplanation())
                .evidence(row.getEvidence())
                .example(row.getExample())
                .coverage(row.getCoverage())
                .evidenceVersion(row.getEvidenceVersion())
                .recordedAt(row.getRecordedAt())
                .endedAt(row.getEndedAt())
                .endReason(row.getEndReason());
    }

    /** The facts of each of {@code rows}, as a read of results passes them on. */
    public static List<ResultFacts> facts(Collection<Result> rows) {
        return rows.stream().map(Result::facts).toList();
    }

    /** {@code view} with its result ended for {@code reason} at {@code at}. */
    public static ResultView ended(ResultView view, ResultEndReason reason, Instant at) {
        var f = view.result();
        return new ResultView(
                copy(f, f.itemId(), f.practiceVersionId(), f.migratedHistory(), at, reason, f.sourceKinds()),
                view.practice(),
                view.version(),
                view.review(),
                view.item(),
                view.sourceFreshness(),
                view.sealedFeedbackMode());
    }

    /** {@code facts} of the same result on another work item. */
    public static ResultFacts onItem(ResultFacts f, long itemId) {
        return copy(f, itemId, f.practiceVersionId(), f.migratedHistory(), f.endedAt(), f.endReason(), f.sourceKinds());
    }

    /** {@code facts} of the same result recorded before the current result model. */
    public static ResultFacts migrated(ResultFacts f) {
        return copy(f, f.itemId(), f.practiceVersionId(), true, f.endedAt(), f.endReason(), f.sourceKinds());
    }

    /** {@code facts} of the same result, recorded under no practice version. */
    public static ResultFacts withoutPracticeVersion(ResultFacts f) {
        return copy(f, f.itemId(), null, f.migratedHistory(), f.endedAt(), f.endReason(), f.sourceKinds());
    }

    /** {@code facts} as a history read loads a row that recorded no source kinds. */
    public static ResultFacts withoutSourceKinds(ResultFacts f) {
        return copy(f, f.itemId(), f.practiceVersionId(), f.migratedHistory(), f.endedAt(), f.endReason(), null);
    }

    private static ResultFacts copy(
            ResultFacts f,
            long itemId,
            @Nullable Long practiceVersionId,
            boolean migratedHistory,
            @Nullable Instant endedAt,
            @Nullable ResultEndReason endReason,
            @Nullable List<String> sourceKinds) {
        return new ResultFacts(
                f.id(),
                f.workspaceId(),
                f.reviewId(),
                f.practiceId(),
                practiceVersionId,
                itemId,
                f.itemVersion(),
                f.developerId(),
                f.outcome(),
                f.severity(),
                f.summary(),
                migratedHistory,
                f.recordedAt(),
                endedAt,
                endReason,
                f.hasExplanation(),
                f.hasEvidence(),
                f.hasCoverage(),
                sourceKinds);
    }
}

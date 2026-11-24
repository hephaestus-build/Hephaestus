package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Reads assessment records about one account in one workspace for conversational use. */
public interface AssessmentPort {
    ObservationHistory observations(long workspaceId, long accountId, Instant since, int limit);

    Optional<AssessmentRecord> assessment(long workspaceId, long accountId, AssessmentKind kind, UUID id);

    default Optional<Observation> observation(long workspaceId, long accountId, UUID observationId) {
        return assessment(workspaceId, accountId, AssessmentKind.OBSERVATION, observationId)
                .filter(Observation.class::isInstance)
                .map(Observation.class::cast);
    }

    Optional<Observation> observationOfProblem(long workspaceId, long accountId, UUID problemId);

    /** The existing practice profile projection from observations permitted for conversational use. */
    List<Standing> standings(long workspaceId, long accountId);

    /** Status receipts for this account's own readable review and item pairs. */
    Map<ReviewedItem, ReviewCheck> reviewChecks(long workspaceId, long accountId, Collection<ReviewedItem> reviewed);

    enum AssessmentKind {
        OBSERVATION,
        PRIVATE_CHECK_OUTCOME,
        SELF_REPORT
    }

    /**
     * Coverage can end before all readable observations have been found.
     *
     * @param withheld the account's observations in this page whose source or copy custody excludes them
     */
    record ObservationHistory(List<Observation> entries, int withheld, boolean hasMore) {
        public ObservationHistory {
            entries = List.copyOf(entries);
        }
    }

    /** The practice profile's projection for conversational use. */
    record Standing(
            String slug,
            String name,
            @Nullable String group,
            String standing,
            boolean verdict,
            int decidedWork,
            @Nullable String direction,
            int toWorkOn,
            int strengths,
            boolean holding) {}

    /** The complete set of records that can support a claim about the account's work. */
    sealed interface AssessmentRecord permits Observation, PrivateCheckOutcome, SelfReport {}

    /** The domain pack's admitted observation of one practice on one version of an item. */
    record Observation(
            UUID resultId,
            UUID reviewId,
            String practiceSlug,
            @Nullable String practiceName,
            int practiceVersion,
            long itemId,
            String itemKind,
            String itemVersion,
            ResultOutcome outcome,
            @Nullable String summary,
            Instant recordedAt,
            @Nullable Instant endedAt,
            @Nullable ResultEndReason endReason,
            @Nullable Instant capturedAt,
            StoredWorkVersion storedWorkVersion,
            List<ResultMarkKind> activeMarks,
            List<Problem> problems,
            List<String> openQuestions,
            @Nullable Work work,
            @Nullable String explanation,
            @Nullable JsonNode evidence,
            @Nullable JsonNode example,
            @Nullable JsonNode coverage,
            @Nullable JsonNode criteria,
            HistoryCustody custody)
            implements AssessmentRecord {
        public Observation {
            activeMarks = List.copyOf(activeMarks);
            problems = List.copyOf(problems);
            openQuestions = List.copyOf(openQuestions);
            evidence = evidence == null ? null : evidence.deepCopy();
            example = example == null ? null : example.deepCopy();
            coverage = coverage == null ? null : coverage.deepCopy();
            criteria = criteria == null ? null : criteria.deepCopy();
        }

        @Override
        public @Nullable JsonNode evidence() {
            return evidence == null ? null : evidence.deepCopy();
        }

        @Override
        public @Nullable JsonNode example() {
            return example == null ? null : example.deepCopy();
        }

        @Override
        public @Nullable JsonNode coverage() {
            return coverage == null ? null : coverage.deepCopy();
        }

        @Override
        public @Nullable JsonNode criteria() {
            return criteria == null ? null : criteria.deepCopy();
        }
    }

    record PrivateCheckOutcome(
            UUID checkId, String practiceSlug, String itemVersion, ResultOutcome outcome, List<Problem> problems)
            implements AssessmentRecord {
        public PrivateCheckOutcome {
            problems = List.copyOf(problems);
        }
    }

    record SelfReport(UUID reportId, String practiceSlug, String statement) implements AssessmentRecord {}

    record Problem(
            UUID problemId,
            Severity severity,
            String summary,
            @Nullable String path,
            @Nullable Integer startLine) {}

    /** Status of a check. This type cannot carry an assessment outcome or a problem. */
    enum CheckStatus {
        COULD_NOT_CHECK
    }

    record ReviewedItem(UUID reviewId, long itemId) {}

    record ReviewCheck(
            String reviewStatus,
            @Nullable String stopReason,
            @Nullable CheckCounts counts,
            @Nullable CheckStatus checkStatus) {}

    record CheckCounts(long total, long judged, long reused, long withheld, long pending) {}

    enum StoredWorkVersion {
        SAME,
        DIFFERENT,
        UNKNOWN
    }

    record Work(
            String title,
            @Nullable Integer number,
            @Nullable String url) {}
}

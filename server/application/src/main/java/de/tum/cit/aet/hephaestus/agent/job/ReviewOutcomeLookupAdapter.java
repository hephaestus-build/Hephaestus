package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeCoverageLedger;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewOutcomeRow;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Reads persisted review decisions used to derive practice traces. */
@Component
@RequiredArgsConstructor
class ReviewOutcomeLookupAdapter implements ReviewOutcomeLookup {

    private final AgentJobRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ReviewOutcome> findByIds(long workspaceId, Collection<UUID> reviewIds) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ReviewOutcome> outcomes = new HashMap<>();
        for (ReviewOutcomeRow row : repository.findReviewOutcomes(workspaceId, reviewIds)) {
            outcomes.put(row.getId(), toOutcome(row));
        }
        return Map.copyOf(outcomes);
    }

    private static ReviewOutcome toOutcome(ReviewOutcomeRow row) {
        boolean refusedEvidence = row.getStatus() == AgentJobStatus.COMPLETED
                && row.getOutput() != null
                && ReviewRunOutcome.fromJobOutput(row.getOutput()) == ReviewRunOutcome.INSUFFICIENT_EVIDENCE;
        return new ReviewOutcome(
                state(row.getStatus()),
                refusedEvidence,
                row.getCompletedAt(),
                readiness(row.getReviewReadiness()),
                coverage(row.getOutput()));
    }

    private static Map<String, PracticeCoverageOutcome> coverage(@Nullable JsonNode output) {
        JsonNode outcomes = PracticeCoverageLedger.from(output).outcomes();
        if (!outcomes.isArray()) return Map.of();
        Map<String, PracticeCoverageOutcome> bySlug = new HashMap<>();
        for (JsonNode outcome : outcomes) {
            String slug = outcome.path("practiceSlug").asString(null);
            try {
                if (slug == null || slug.isBlank() || bySlug.containsKey(slug)) return Map.of();
                bySlug.put(
                        slug,
                        PracticeCoverageOutcome.valueOf(outcome.path("outcome").asString()));
            } catch (IllegalArgumentException ignored) {
                return Map.of();
            }
        }
        return Map.copyOf(bySlug);
    }

    /**
     * A timed-out run and a cancelled one are both "it did not finish" to a reader; keeping the
     * distinction here would put a vocabulary on the wire that no surface renders.
     */
    private static ReviewRunState state(AgentJobStatus status) {
        return switch (status) {
            case QUEUED, RUNNING -> ReviewRunState.IN_PROGRESS;
            case COMPLETED -> ReviewRunState.COMPLETED;
            case FAILED, TIMED_OUT, CANCELLED -> ReviewRunState.FAILED;
        };
    }

    private static Map<String, PracticeReadinessOutcome> readiness(@Nullable JsonNode report) {
        if (report == null || !report.path("decisions").isArray()) {
            return Map.of();
        }
        Map<String, PracticeReadinessOutcome> bySlug = new HashMap<>();
        for (JsonNode decision : report.path("decisions")) {
            String slug = decision.path("practiceSlug").asString(null);
            if (slug == null || slug.isBlank()) {
                continue;
            }
            bySlug.put(
                    slug,
                    new PracticeReadinessOutcome(
                            decision.path("ready").asBoolean(false), blockers(decision), notApplicable(decision)));
        }
        return Map.copyOf(bySlug);
    }

    /**
     * The practice author's own sentence for "the thing this judges was not in this work", or null.
     *
     * <p>Read from the subject check rather than reconstructed from the clause findings: the sentence is
     * the record, and a surface that paraphrased it would drift from the catalogue the moment somebody
     * edited the declaration. Guarded on {@code absent} because a check is also recorded when the
     * subject was found, and that decision is a ready one with nothing to explain.
     */
    private static @Nullable String notApplicable(JsonNode decision) {
        JsonNode check = decision.path("subjectCheck");
        if (!check.path("absent").asBoolean(false)) {
            return null;
        }
        String sentence = check.path("describedAs").asString(null);
        return sentence == null || sentence.isBlank() ? null : sentence;
    }

    /** What could not be read, in words, so no consumer has to learn the evidence vocabulary. */
    private static List<String> blockers(JsonNode decision) {
        List<String> blockers = new ArrayList<>();
        for (JsonNode reason : decision.path("reasonCodes")) {
            String code = reason.asString(null);
            if ("NO_AUTOMATED_REVIEW".equals(code)) {
                blockers.add("this practice declares no automated review");
            } else if ("DECLARED_EVIDENCE_INSUFFICIENT".equals(code)) {
                blockers.add("this practice declares its evidence insufficient for an automated claim");
            }
        }
        for (JsonNode check : decision.path("sourceChecks")) {
            if (check.path("meetsRequirements").asBoolean(true)) {
                continue;
            }
            String source = check.path("sourceKind").asString("a required source");
            for (JsonNode reason : check.path("reasonCodes")) {
                blockers.add(source + " " + sourceProblem(reason.asString(null)));
            }
        }
        return List.copyOf(blockers);
    }

    private static String sourceProblem(@Nullable String code) {
        if (code == null) {
            return "could not be read";
        }
        return switch (code) {
            case "SOURCE_NOT_AVAILABLE" -> "was not captured";
            case "SOURCE_INCOMPLETE" -> "was captured only in part";
            case "SOURCE_EMPTY" -> "was captured empty";
            default -> "could not be read";
        };
    }
}

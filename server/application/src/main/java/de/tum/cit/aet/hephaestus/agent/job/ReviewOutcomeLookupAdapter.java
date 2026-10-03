package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeCoverageLedger;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewOutcomeRow;
import de.tum.cit.aet.hephaestus.core.UnknownVocabulary;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceContract;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final ArtifactSourceCatalogRegistry sources;

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

    private ReviewOutcome toOutcome(ReviewOutcomeRow row) {
        boolean refusedEvidence = row.getStatus() == AgentJobStatus.COMPLETED
                && row.getOutput() != null
                && ReviewRunOutcome.fromJobOutput(row.getOutput()) == ReviewRunOutcome.INSUFFICIENT_EVIDENCE;
        return new ReviewOutcome(
                AgentJobReviewRunStates.of(row.getStatus()),
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

    private Map<String, PracticeReadinessOutcome> readiness(@Nullable JsonNode report) {
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
                            decision.path("ready").asBoolean(false),
                            blockers(decision),
                            limitation(decision),
                            notApplicable(decision)));
        }
        return Map.copyOf(bySlug);
    }

    /**
     * The practice author's own sentence for "the thing this judges was not in this work", or null.
     *
     * <p>Read from the subject check rather than reconstructed from the clause results: the sentence is
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

    /**
     * The practice's own declaration that it is not reviewed automatically, as a sentence, or null. Each
     * choice is named in the words the practice editor gives it — Guidance only, Human review needed.
     */
    static @Nullable String limitation(JsonNode decision) {
        for (JsonNode reason : decision.path("reasonCodes")) {
            String code = reason.asString(null);
            if ("NO_AUTOMATED_REVIEW".equals(code)) {
                return "This practice is guidance only, so it is not reviewed automatically.";
            }
            if ("DECLARED_EVIDENCE_INSUFFICIENT".equals(code)) {
                return "This practice needs human review, so it is not reviewed automatically.";
            }
        }
        return null;
    }

    /**
     * What could not be read, one sentence each, so no consumer has to learn the evidence vocabulary: a
     * source is named in quotes by the catalogue's display name, never by its kind, and the sentence reads
     * right whether that name is singular or plural ("Code changes").
     */
    List<String> blockers(JsonNode decision) {
        List<String> blockers = new ArrayList<>();
        for (JsonNode check : decision.path("sourceChecks")) {
            if (check.path("meetsRequirements").asBoolean(true)) {
                continue;
            }
            String source = sourceName(check.path("sourceKind").asString(null));
            for (JsonNode reason : check.path("reasonCodes")) {
                blockers.add(sourceProblem(reason.asString(null), source));
            }
        }
        return List.copyOf(blockers);
    }

    /** The source's quoted display name, or a generic phrase, written as it reads mid-sentence. */
    private String sourceName(@Nullable String kind) {
        if (kind == null || kind.isBlank()) {
            return "a required source";
        }
        try {
            Optional<String> declared =
                    sources.current().source(new SourceKind(kind)).map(ArtifactSourceContract::displayName);
            if (declared.isPresent()) {
                return "“" + declared.get() + "”";
            }
        } catch (IllegalArgumentException malformed) {
            // Not a source kind at all; named generically below like one the catalogue no longer declares.
        }
        return UnknownVocabulary.label("source kind", kind, "a required source");
    }

    private static String sourceProblem(@Nullable String code, String source) {
        String sentence =
                switch (code == null ? "" : code) {
                    case "SOURCE_NOT_AVAILABLE" -> source + " was not captured.";
                    case "SOURCE_INCOMPLETE" -> "Only part of " + source + " was captured.";
                    case "SOURCE_EMPTY" -> "Nothing was captured from " + source + ".";
                    default -> source + " could not be read.";
                };
        // Character.toUpperCase rather than String.toUpperCase, which is locale-sensitive and banned by
        // LocaleSafetyArchTest; a quoted name starts with a quotation mark and is left as it is.
        return Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
    }
}

package de.tum.cit.aet.hephaestus.evidence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record AutomatedReviewReadinessReport(
        SourceContractVersion contractVersion,
        String catalogDigest,
        String itemKind,
        Instant manifestCapturedAt,
        Instant decidedAt,
        List<AutomatedReviewReadinessDecision> decisions,
        Map<String, AppliesWhenCheck> applicability,
        // What this attempt kept instead of running. Not part of the report's schema: nothing reads it after
        // preparation.
        @JsonIgnore Set<String> reused) {
    public AutomatedReviewReadinessReport(
            SourceContractVersion contractVersion,
            String catalogDigest,
            String itemKind,
            Instant manifestCapturedAt,
            Instant decidedAt,
            List<AutomatedReviewReadinessDecision> decisions,
            Map<String, AppliesWhenCheck> applicability) {
        this(
                contractVersion,
                catalogDigest,
                itemKind,
                manifestCapturedAt,
                decidedAt,
                decisions,
                applicability,
                Set.of());
    }

    public AutomatedReviewReadinessReport {
        applicability = Map.copyOf(applicability);
        reused = reused == null ? Set.of() : Set.copyOf(reused);
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(catalogDigest, "catalogDigest");
        if (!catalogDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid catalog digest: " + catalogDigest);
        }
        Objects.requireNonNull(itemKind, "itemKind");
        Objects.requireNonNull(manifestCapturedAt, "manifestCapturedAt");
        Objects.requireNonNull(decidedAt, "decidedAt");
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        if (decisions.isEmpty()) {
            throw new IllegalArgumentException("A readiness report requires at least one decision");
        }
        if (new HashSet<>(decisions.stream()
                                .map(AutomatedReviewReadinessDecision::practiceSlug)
                                .toList())
                        .size()
                != decisions.size()) {
            throw new IllegalArgumentException("A readiness report cannot repeat a practice");
        }
        if (decisions.stream().anyMatch(decision -> !decision.decidedAt().equals(decidedAt))) {
            throw new IllegalArgumentException("Every readiness decision must use the report decision time");
        }
    }

    /** A practice runs when its evidence is ready, its conditions are not proven absent, and no result was reused. */
    public boolean runs(AutomatedReviewReadinessDecision decision) {
        var check = applicability.get(decision.practiceSlug());
        return decision.ready() && (check == null || !check.absent()) && !reused.contains(decision.practiceSlug());
    }

    public AutomatedReviewReadinessReport withReused(Set<String> practiceSlugs) {
        return new AutomatedReviewReadinessReport(
                contractVersion,
                catalogDigest,
                itemKind,
                manifestCapturedAt,
                decidedAt,
                decisions,
                applicability,
                practiceSlugs);
    }
}

package de.tum.cit.aet.hephaestus.practices.observation.trend;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Builds the provenance a trend direction is published with.
 *
 * <p>Two entry points rather than one with optional arguments. {@link TrendSupport#comparablePractices()} and
 * {@link TrendSupport#eligiblePractices()} are absent at practice scope and present at group scope, and that
 * absence reaches the client, so it is part of the contract rather than a gap. Encoding it in the method name
 * means neither caller passes a null to say which case it is in, and adding a scope later forces a new
 * factory instead of a fourth nullable argument.
 */
final class TrendSupportFactory {

    private TrendSupportFactory() {}

    /** Support for one practice's own comparison — no practice counts, because it is the practice. */
    static TrendSupport forPractice(
            TrendProperties properties,
            int currentOpportunities,
            int previousOpportunities,
            int opportunitiesUntilComparable,
            List<EvidenceOpportunity> trail) {
        // One practice's bundles are disjoint, so no piece of reviewed work is in both.
        return build(
                properties,
                currentOpportunities,
                previousOpportunities,
                currentOpportunities + previousOpportunities,
                opportunitiesUntilComparable,
                null,
                null,
                trail);
    }

    /**
     * Support for an aggregate — additionally says how many of the group's practices could be compared.
     *
     * <p>{@code opportunities} is passed in rather than summed, because a group's two bundles can name the same
     * piece of reviewed work through different practices.
     */
    static TrendSupport forGroup(
            TrendProperties properties,
            int currentOpportunities,
            int previousOpportunities,
            int opportunities,
            int opportunitiesUntilComparable,
            int comparablePractices,
            int eligiblePractices,
            List<EvidenceOpportunity> trail) {
        return build(
                properties,
                currentOpportunities,
                previousOpportunities,
                opportunities,
                opportunitiesUntilComparable,
                comparablePractices,
                eligiblePractices,
                trail);
    }

    private static TrendSupport build(
            TrendProperties properties,
            int currentOpportunities,
            int previousOpportunities,
            int opportunities,
            int opportunitiesUntilComparable,
            @Nullable Integer comparablePractices,
            @Nullable Integer eligiblePractices,
            List<EvidenceOpportunity> trail) {
        // The comparison span excludes work that offered no opportunity to apply the practice.
        List<Instant> dated = trail.stream()
                .filter(EvidenceOpportunity::applicable)
                .map(EvidenceOpportunity::occurredAt)
                .toList();
        Instant first = dated.stream().min(Instant::compareTo).orElse(null);
        Instant last = dated.stream().max(Instant::compareTo).orElse(null);
        Integer span = first == null || last == null
                ? null
                : (int) ChronoUnit.DAYS.between(
                                first.atZone(ZoneOffset.UTC).toLocalDate(),
                                last.atZone(ZoneOffset.UTC).toLocalDate())
                        + 1;
        return new TrendSupport(
                currentOpportunities,
                previousOpportunities,
                opportunities,
                opportunitiesUntilComparable,
                comparablePractices,
                eligiblePractices,
                first,
                last,
                span,
                properties.getBundleSize(),
                properties.getRopeHalfWidth(),
                properties.getCredibilityThreshold());
    }
}

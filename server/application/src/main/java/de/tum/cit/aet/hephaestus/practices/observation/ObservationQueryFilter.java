package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ObservationQueryFilter(
        @Nullable List<String> practiceSlugs,
        @Nullable List<String> groupSlugs,
        @Nullable List<AssessmentStatus> assessmentStatuses,
        @Nullable List<Presence> presences,
        @Nullable List<Assessment> assessments,
        @Nullable List<Severity> severities,
        @Nullable UUID agentJobId,
        @Nullable ArtifactKind artifactKind,
        @Nullable Long artifactId,
        @Nullable Long aboutUserId,
        @Nullable List<ObservationOrigin> origins,
        @Nullable Instant from,
        @Nullable Instant to) {
    public String @Nullable [] practiceSlugArray() {
        if (practiceSlugs == null || practiceSlugs.isEmpty()) {
            return null;
        }
        return practiceSlugs.toArray(String[]::new);
    }

    public String @Nullable [] groupSlugArray() {
        if (groupSlugs == null || groupSlugs.isEmpty()) {
            return null;
        }
        return groupSlugs.toArray(String[]::new);
    }

    public String @Nullable [] assessmentStatusNames() {
        return names(assessmentStatuses);
    }

    public String @Nullable [] presenceNames() {
        return names(presences);
    }

    public String @Nullable [] assessmentNames() {
        return names(assessments);
    }

    public String @Nullable [] severityNames() {
        return names(severities);
    }

    public String @Nullable [] originNames() {
        return names(origins);
    }

    public @Nullable String artifactKindValue() {
        return artifactKind == null ? null : artifactKind.value();
    }

    private static String @Nullable [] names(@Nullable List<? extends Enum<?>> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().map(Enum::name).toArray(String[]::new);
    }
}

package de.tum.cit.aet.hephaestus.evidence;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * @param subjectCheck what looking for the practice's declared subject established, or {@code null}
 *                     where the practice declares no subject or the sources it needed were not
 *                     readable. The subject is only ever asked about evidence we actually have, so
 *                     "we could not look" continues to outrank "there was nothing of this kind here".
 */
public record AutomatedReviewReadinessDecision(
        String practiceSlug,
        Instant decidedAt,
        boolean ready,
        List<AutomatedReviewReadinessReason> reasonCodes,
        List<SourceReadinessCheck> sourceChecks,
        @Nullable PracticePreconditionCheck subjectCheck) {
    /** A decision taken before subject declarations existed, or by a practice that declares none. */
    public AutomatedReviewReadinessDecision(
            String practiceSlug,
            Instant decidedAt,
            boolean ready,
            List<AutomatedReviewReadinessReason> reasonCodes,
            List<SourceReadinessCheck> sourceChecks) {
        this(practiceSlug, decidedAt, ready, reasonCodes, sourceChecks, null);
    }

    public AutomatedReviewReadinessDecision {
        Objects.requireNonNull(practiceSlug, "practiceSlug");
        if (practiceSlug.isBlank()) {
            throw new IllegalArgumentException("practiceSlug must not be blank");
        }
        Objects.requireNonNull(decidedAt, "decidedAt");
        reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
        if (new HashSet<>(reasonCodes).size() != reasonCodes.size()) {
            throw new IllegalArgumentException("reasonCodes must not contain duplicates");
        }
        sourceChecks = List.copyOf(Objects.requireNonNull(sourceChecks, "sourceChecks"));
        if (sourceChecks.isEmpty() && reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("a readiness decision needs source readiness checks or a skip reason");
        }
        if (new HashSet<>(sourceChecks.stream()
                                .map(SourceReadinessCheck::sourceKind)
                                .toList())
                        .size()
                != sourceChecks.size()) {
            throw new IllegalArgumentException("sourceChecks must contain each source kind at most once");
        }
        boolean allMeetRequirements = sourceChecks.stream().allMatch(SourceReadinessCheck::meetsRequirements);
        if (ready != (reasonCodes.isEmpty() && allMeetRequirements)) {
            throw new IllegalArgumentException(
                    "ready must equal whether the practice and every source readiness check meet their requirements");
        }
        // The reason and the evidence for it travel together or not at all. A decision that withheld a
        // practice for want of a subject but carries no check has nothing a reader could audit, and a
        // check reporting an absence the reason codes do not name would withhold nothing while looking
        // as though it had.
        boolean subjectAbsent = reasonCodes.contains(AutomatedReviewReadinessReason.SUBJECT_NOT_IN_THE_WORK);
        if (subjectAbsent != (subjectCheck != null && subjectCheck.absent())) {
            throw new IllegalArgumentException(
                    "SUBJECT_NOT_IN_THE_WORK must be recorded exactly when a subject check found the subject absent");
        }
    }
}

package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

@Schema(description = "Whether an observation still has current review rules and supporting work snapshot")
public enum ReviewClaimCurrentness {
    CURRENT,
    STALE,
    UNVERIFIABLE;

    public static ReviewClaimCurrentness of(
            @Nullable PracticeRevision evaluated, Practice practice, @Nullable Instant supersededAt) {
        return supersededAt != null ? STALE : of(evaluated, practice);
    }

    public static ReviewClaimCurrentness of(
            @Nullable String evaluatedFingerprint,
            @Nullable String currentFingerprint,
            @Nullable Instant supersededAt) {
        return supersededAt != null ? STALE : of(evaluatedFingerprint, currentFingerprint);
    }

    public static ReviewClaimCurrentness of(@Nullable PracticeRevision evaluated, Practice practice) {
        PracticeRevision current = practice.getCurrentRevision();
        if (evaluated != null
                && current != null
                && !evaluated.equals(current)
                && evaluated.getAutomatedReviewPolicy() == null
                && current.getAutomatedReviewPolicy() != null) {
            return STALE;
        }
        return of(fingerprint(evaluated), fingerprint(current));
    }

    public static ReviewClaimCurrentness of(
            @Nullable String evaluatedFingerprint, @Nullable String currentFingerprint) {
        if (evaluatedFingerprint == null || currentFingerprint == null) {
            return UNVERIFIABLE;
        }
        return evaluatedFingerprint.equals(currentFingerprint) ? CURRENT : STALE;
    }

    private static @Nullable String fingerprint(@Nullable PracticeRevision revision) {
        return revision == null ? null : revision.getReviewRuleFingerprint();
    }
}

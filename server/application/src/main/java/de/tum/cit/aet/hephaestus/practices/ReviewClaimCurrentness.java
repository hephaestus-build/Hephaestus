package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

@Schema(
        description =
                "Whether an observation's claim still stands: its practice's review rules are unchanged and it was"
                        + " not superseded, which an issue's observations are when a change to its reviewable content is"
                        + " recorded. It says neither whether a later review ran nor whether the work changed since it was"
                        + " reviewed. Earlier observation standards and missing fingerprints are unverifiable under the"
                        + " current whole-practice standard")
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
        return of(fingerprint(evaluated), fingerprint(practice.getCurrentRevision()));
    }

    public static ReviewClaimCurrentness of(
            @Nullable String evaluatedFingerprint, @Nullable String currentFingerprint) {
        if (evaluatedFingerprint == null
                || currentFingerprint == null
                || !ReviewRuleFingerprint.isCurrentScheme(evaluatedFingerprint)
                || !ReviewRuleFingerprint.isCurrentScheme(currentFingerprint)) {
            return UNVERIFIABLE;
        }
        return evaluatedFingerprint.equals(currentFingerprint) ? CURRENT : STALE;
    }

    private static @Nullable String fingerprint(@Nullable PracticeRevision revision) {
        return revision == null ? null : revision.getReviewRuleFingerprint();
    }
}

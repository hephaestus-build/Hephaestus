package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How every locus in a window of one person's measurements moved, as set arithmetic over recurrence keys.
 *
 * <p>This is the bookkeeping half of the measurement/intervention split: the composition stage does all
 * the writing, and this does the counting, because a set difference over hashes is what code is good at
 * and a model is bad at — and getting it wrong produces the worst artefact the system can emit, "the gap
 * from last week is closed" when it is not.
 *
 * <p><b>{@link Status#RESOLVED} is the reason this exists.</b> A locus that was a problem and is absent
 * from the newest review of its artifact originates from no current measurement at all, so a mapping that
 * walks this run's observations can never reach it. It is feedback with nothing to map from.
 *
 * <p>Pure and side-effect free: it takes a flat list of loci and returns the classification. Nothing here
 * reads a repository, a clock or a file, so every rule below is a unit test with no database in it.
 *
 * <p><b>The comparison is per artifact, and that is forced by the key, not chosen.</b>
 * {@link ObservationFingerprint} folds {@code artifactId} into the digest, so the same practice failing on
 * two different merge requests carries two different recurrence keys and can never be compared as one
 * locus. "This keeps happening across your work" is therefore a claim about a <em>practice</em> over the
 * window — which the staged observation history already carries — and not a claim this class can make.
 * What it does say is what happened at each locus between the runs that looked at it.
 */
public record ObservationDelta(List<LocusChange> loci) {
    public ObservationDelta {
        loci = List.copyOf(loci);
    }

    /**
     * How one locus moved, over the runs in the window that looked at its artifact.
     *
     * <p>The four are disjoint and exhaustive over the loci in the window, and each one licenses a
     * different thing to say.
     */
    public enum Status {
        /** Seen only in the newest run of its artifact. Nothing has been said about it before. */
        NEW,
        /**
         * Seen in the newest run of its artifact and in an earlier one, and something moved — the
         * outcome or the severity is not what it was. There is a change to point at.
         */
        RECURRING,
        /**
         * Seen in the newest run of its artifact and in an earlier one, with the same outcome and the
         * same severity throughout. Nothing moved, so there is nothing new to say: a message about an
         * UNCHANGED locus must rest on a fact from the current run rather than on the fact that it is
         * still there.
         */
        UNCHANGED,
        /**
         * Was a problem in an earlier run of its artifact and is absent from the newest one. A locus that
         * was a strength and simply was not re-observed is <em>not</em> resolved — that would credit
         * somebody with fixing what was already right — and is dropped rather than classified.
         */
        RESOLVED,
    }

    /**
     * One measurement, projected to what the arithmetic needs. Deliberately not an {@code Observation}:
     * this record is what makes the classification unit-testable without persistence, and the caller that
     * has entities does the projecting.
     *
     * @param recurrenceKey the locus identity ({@link ObservationFingerprint}); rows without one are
     *     skipped by {@link #classify}, because a locus with no identity cannot be compared with anything
     * @param runId the agent job that produced this measurement — the grain "an earlier run" is counted at
     * @param observedAt when it was measured; orders the runs within an artifact
     */
    public record Locus(
            @Nullable String recurrenceKey,
            String practiceSlug,
            @Nullable ArtifactKind artifactKind,
            @Nullable Long artifactId,
            @Nullable UUID runId,
            @Nullable Instant observedAt,
            @Nullable Outcome outcome,
            @Nullable Severity severity) {}

    /**
     * One locus's verdict.
     *
     * @param runsSeen how many distinct runs in the window measured this locus
     * @param latestOutcome the outcome at the newest run that measured it — for {@link Status#RESOLVED}
     *     that is the last run it was still a problem in, because it is absent from the newest one
     */
    public record LocusChange(
            String recurrenceKey,
            String practiceSlug,
            Status status,
            int runsSeen,
            @Nullable Instant firstSeenAt,
            @Nullable Instant lastSeenAt,
            @Nullable Outcome latestOutcome,
            @Nullable Severity latestSeverity) {}

    /**
     * Classify every locus in one window.
     *
     * <p>The window is whatever the caller read — typically one person's bounded observation history. It
     * is grouped by artifact, the newest run per artifact becomes "now", and every earlier run in the
     * window is "before". An artifact measured exactly once contributes only {@link Status#NEW} loci,
     * which is the honest answer: with one run there is nothing to compare against.
     *
     * @param window the measurements to compare; order is irrelevant, duplicates are collapsed
     * @return one verdict per locus, ordered RESOLVED, RECURRING, NEW, UNCHANGED and then by key, so a
     *     serialized delta is stable across runs and a truncating reader keeps what moved
     */
    public static ObservationDelta classify(Collection<Locus> window) {
        Map<ArtifactRef, List<Locus>> byArtifact = new LinkedHashMap<>();
        for (Locus locus : window) {
            if (locus == null
                    || locus.recurrenceKey() == null
                    || locus.recurrenceKey().isBlank()) {
                continue;
            }
            byArtifact
                    .computeIfAbsent(ArtifactRef.of(locus), ref -> new ArrayList<>())
                    .add(locus);
        }
        List<LocusChange> changes = new ArrayList<>();
        byArtifact.values().forEach(loci -> changes.addAll(classifyArtifact(loci)));
        changes.sort(Comparator.comparingInt((LocusChange change) -> statusOrder(change.status()))
                .thenComparing(LocusChange::recurrenceKey));
        return new ObservationDelta(changes);
    }

    private static List<LocusChange> classifyArtifact(List<Locus> loci) {
        UUID newestRun = newestRunOf(loci);
        Map<String, List<Locus>> byKey = new LinkedHashMap<>();
        for (Locus locus : loci) {
            byKey.computeIfAbsent(locus.recurrenceKey(), key -> new ArrayList<>())
                    .add(locus);
        }
        List<LocusChange> changes = new ArrayList<>();
        byKey.forEach((key, occurrences) -> {
            LocusChange change = classifyLocus(key, occurrences, newestRun);
            if (change != null) {
                changes.add(change);
            }
        });
        return changes;
    }

    /**
     * The run that is "now" for one artifact: the one holding the newest measurement. Ties break on the
     * job id so a window whose timestamps collide still classifies deterministically rather than
     * according to whatever order the query happened to return.
     */
    private static @Nullable UUID newestRunOf(List<Locus> loci) {
        return loci.stream()
                .filter(locus -> locus.runId() != null)
                .max(Comparator.comparing(
                                (Locus locus) -> locus.observedAt() == null ? Instant.MIN : locus.observedAt())
                        .thenComparing(
                                locus -> Objects.requireNonNull(locus.runId()).toString()))
                .map(Locus::runId)
                .orElse(null);
    }

    private static @Nullable LocusChange classifyLocus(String key, List<Locus> occurrences, @Nullable UUID newestRun) {
        List<Locus> inNewestRun = occurrences.stream()
                .filter(locus -> newestRun != null && newestRun.equals(locus.runId()))
                .toList();
        long runsSeen = occurrences.stream()
                .map(Locus::runId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        Instant firstSeen = occurrences.stream()
                .map(Locus::observedAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        Instant lastSeen = occurrences.stream()
                .map(Locus::observedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        if (newestRun == null) {
            // No run identifies itself, so no measurement can be said to be the current one. Absence from
            // "now" is then unknowable, and RESOLVED — the only status that claims something is gone —
            // must not be reachable by default.
            Locus any = worstOf(occurrences);
            return new LocusChange(
                    key,
                    any.practiceSlug(),
                    Status.NEW,
                    (int) runsSeen,
                    firstSeen,
                    lastSeen,
                    any.outcome(),
                    any.severity());
        }
        if (inNewestRun.isEmpty()) {
            Locus worst = worstOf(occurrences);
            // Only a vanished PROBLEM is resolved. A strength that was not re-observed is not a fix, and
            // saying so would hand somebody credit for work they did not do.
            if (worst.outcome() != Outcome.NEGATIVE) {
                return null;
            }
            return new LocusChange(
                    key,
                    worst.practiceSlug(),
                    Status.RESOLVED,
                    (int) runsSeen,
                    firstSeen,
                    lastSeen,
                    worst.outcome(),
                    worst.severity());
        }
        Locus current = worstOf(inNewestRun);
        if (runsSeen <= 1) {
            return new LocusChange(
                    key,
                    current.practiceSlug(),
                    Status.NEW,
                    (int) runsSeen,
                    firstSeen,
                    lastSeen,
                    current.outcome(),
                    current.severity());
        }
        List<Locus> earlier = occurrences.stream()
                .filter(locus -> !inNewestRun.contains(locus))
                .toList();
        Locus prior = worstOf(earlier);
        boolean moved = prior.outcome() != current.outcome() || prior.severity() != current.severity();
        return new LocusChange(
                key,
                current.practiceSlug(),
                moved ? Status.RECURRING : Status.UNCHANGED,
                (int) runsSeen,
                firstSeen,
                lastSeen,
                current.outcome(),
                current.severity());
    }

    /**
     * One run can measure the same locus twice — the fingerprint deliberately collapses two observations of
     * one practice in one file — so a representative is chosen the same way the rendered trend chooses
     * one: worst severity, then highest confidence is unavailable here, so the first wins.
     */
    private static Locus worstOf(List<Locus> occurrences) {
        return occurrences.stream()
                .min(Comparator.comparingInt(ObservationDelta::severityOrder))
                .orElseThrow(() -> new IllegalArgumentException("worstOf requires at least one occurrence"));
    }

    private static int severityOrder(Locus locus) {
        return locus.severity() == null ? Integer.MAX_VALUE : locus.severity().ordinal();
    }

    /** What moved first: a win, then a change, then something new, then the tail that did not move. */
    private static int statusOrder(Status status) {
        return switch (status) {
            case RESOLVED -> 0;
            case RECURRING -> 1;
            case NEW -> 2;
            case UNCHANGED -> 3;
        };
    }

    /** The artifact a locus belongs to; the grain the "newest run" question is asked at. */
    private record ArtifactRef(
            @Nullable ArtifactKind kind, @Nullable Long id) {
        static ArtifactRef of(Locus locus) {
            return new ArtifactRef(locus.artifactKind(), locus.artifactId());
        }
    }
}

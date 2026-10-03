package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * What the page may say about the developers other than its reader, and the only place that decides it.
 *
 * <p>Every figure about other developers is a count of developers. A part of a split is shown only when it holds
 * more than {@link #MINIMUM_OTHERS} developers, counted over every observed developer: whoever reads it, that part
 * holds at least {@link #MINIMUM_OTHERS} others, and every reader sees the same shape.
 *
 * <p>The observed total shows only while it holds {@link #MINIMUM_OTHERS} others. A practice group and a practice
 * are split by the same rule, over the same observed developers: each developer falls in one {@link Bucket}, read off
 * their group standing or their practice standing. A split shows Needs attention, Mixed feedback, Going well and
 * none yet only when every one of the four holds enough; otherwise the whole split is withheld, never a part of it,
 * since the page states how many developers were observed and a missing part would be that total less the rest.
 * Omit rather than show a zero. The reader's own standing is shown in every case, since it is theirs.
 *
 * <p>A group's standing is read off its practices, so its has a standing is everyone with a standing in any of
 * them, and its split and its practices' splits can be subtracted from each other: two practices of sixteen and a
 * group of thirty one name the one developer with a standing in both. A practice therefore shows only while the
 * developers its group has and it lacks are none or at least {@link #MINIMUM_OTHERS}, and a group's practices show
 * only while the overlap they add up to beyond the group is none or at least {@link #MINIMUM_OTHERS}
 * ({@link #group}).
 *
 * <p>Each window is checked on its own and the figures are live, so two windows, or two reads at different times,
 * can still be subtracted from each other; ADR 0051 records that limit.
 */
public final class CohortPrivacyPolicy {

    /** K: the fewest developers other than the reader a shown count may stand for. */
    public static final int MINIMUM_OTHERS = 3;

    private CohortPrivacyPolicy() {}

    /** Where one developer falls in a split: one of the three standings, or none yet. */
    public enum Bucket {
        NEEDS_ATTENTION,
        MIXED_FEEDBACK,
        GOING_WELL,
        NONE_YET;

        public static Bucket of(PracticeGroupStandingDTO.Standing standing) {
            return switch (standing) {
                case DEVELOPING -> NEEDS_ATTENTION;
                case MIXED -> MIXED_FEEDBACK;
                case STRENGTH -> GOING_WELL;
                case NOT_OBSERVED, NO_OPPORTUNITY -> NONE_YET;
            };
        }

        public static Bucket of(PracticeStandingDTO.Standing standing) {
            return switch (standing) {
                case DEVELOPING -> NEEDS_ATTENTION;
                case MIXED -> MIXED_FEEDBACK;
                case STRENGTH -> GOING_WELL;
                case NOT_OBSERVED, NO_OPPORTUNITY -> NONE_YET;
            };
        }
    }

    /** How one split is shown. */
    public enum Shape {
        /** Needs attention, Mixed feedback, Going well and none yet, each counted. */
        SPLIT,
        /** No split: one of the four would cover too few. */
        WITHHELD,
    }

    /**
     * One split as it may be shown. Counts include the reader when the reader is counted, so the bar and
     * the reader's place on it agree; every count is null outside the shape that shows it.
     */
    public record Split(
            Shape shape,
            @Nullable Integer needsAttention,
            @Nullable Integer mixedFeedback,
            @Nullable Integer goingWell,
            @Nullable Integer noneYet) {

        static final Split WITHHELD = new Split(Shape.WITHHELD, null, null, null, null);
    }

    /**
     * The observed total as it may be shown, the reader included when the reader is counted: only while it holds K
     * others, otherwise null.
     *
     * @param observedOthers observed developers other than the reader
     */
    public static @Nullable Integer observedTotal(int observedOthers, boolean readerCounted) {
        return observedOthers >= MINIMUM_OTHERS ? observedOthers + (readerCounted ? 1 : 0) : null;
    }

    /**
     * One developer's buckets in a practice group and in each of its practices, the practices in the order the
     * page lists them.
     */
    public record Row(Bucket group, List<Bucket> practices) {}

    /** A practice group's split and its practices' splits, in the order of the rows' practices. */
    public record GroupRelease(Split group, List<Split> practices) {}

    /**
     * The splits of one practice group and its practices.
     *
     * <p>Each split is first decided on its own. Then the cells a reader can work out by inclusion and exclusion
     * must each hold none or at least K developers: a practice is withheld where its has a standing falls short of
     * its group's by 1 to K - 1, and every practice of the group is withheld where the practices shown add up to 1
     * to K - 1 more developers with a standing than the group has, which is how many hold a standing in more than
     * one of them. The group's own split stands, since on its own every part it shows already holds enough.
     *
     * @param observed every observed developer's buckets, the reader's included when the reader is observed
     * @param practiceCount how many practices each row carries, which no row says when nobody is observed
     */
    public static GroupRelease group(List<Row> observed, int practiceCount) {
        List<Bucket> groupBuckets = observed.stream().map(Row::group).toList();
        Split group = split(groupBuckets);
        List<Split> practices = IntStream.range(0, practiceCount)
                .mapToObj(index -> {
                    Split practice = split(observed.stream()
                            .map(row -> row.practices().get(index))
                            .toList());
                    return safeCell(hasStanding(group) - hasStanding(practice), group, practice)
                            ? practice
                            : Split.WITHHELD;
                })
                .toList();
        List<Split> shown = practices.stream()
                .filter(practice -> practice.shape() != Shape.WITHHELD)
                .toList();
        if (group.shape() != Shape.WITHHELD && shown.size() > 1) {
            int overlap =
                    shown.stream().mapToInt(CohortPrivacyPolicy::hasStanding).sum() - hasStanding(group);
            if (!safeCell(overlap, group, group)) {
                return new GroupRelease(
                        group,
                        practices.stream().map(practice -> Split.WITHHELD).toList());
            }
        }
        return new GroupRelease(group, practices);
    }

    /** Whether a cell two shown splits let a reader work out is none or at least K, or not worked out at all. */
    private static boolean safeCell(int developers, Split one, Split other) {
        if (one.shape() == Shape.WITHHELD || other.shape() == Shape.WITHHELD) {
            return true;
        }
        int size = Math.abs(developers);
        return size == 0 || size >= MINIMUM_OTHERS;
    }

    /** How many a shown split counts with a standing. */
    private static int hasStanding(Split split) {
        return switch (split.shape()) {
            case SPLIT ->
                Objects.requireNonNull(split.needsAttention())
                        + Objects.requireNonNull(split.mixedFeedback())
                        + Objects.requireNonNull(split.goingWell());
            case WITHHELD -> 0;
        };
    }

    /**
     * One split counted over every observed developer, the reader included when observed: all four parts, each
     * holding more than K of them, or nothing.
     */
    static Split split(Collection<Bucket> observed) {
        int needs = count(observed, Bucket.NEEDS_ATTENTION);
        int mixed = count(observed, Bucket.MIXED_FEEDBACK);
        int well = count(observed, Bucket.GOING_WELL);
        int none = observed.size() - needs - mixed - well;
        if (shows(needs) && shows(mixed) && shows(well) && shows(none)) {
            return new Split(Shape.SPLIT, needs, mixed, well, none);
        }
        return Split.WITHHELD;
    }

    /** The middle half of a figure across the workspace, as the two values that bound it. */
    public record MiddleHalf(int low, int high) {}

    /**
     * The middle half of one figure across the observed developers, the reader's own value among them, or null when
     * fewer than twice {@link #MINIMUM_OTHERS} others are observed. Only the quartiles leave, each interpolated
     * between the two values around it and rounded, so a quartile falls on one developer's value only where the
     * values around it agree: a minimum, a maximum or a count of developers at one value would single someone out.
     *
     * @param values the figure for every observed developer, the reader's included when the reader is observed
     * @param others how many of {@code values} are other developers'
     */
    public static @Nullable MiddleHalf middleHalf(List<Integer> values, int others) {
        if (others < 2 * MINIMUM_OTHERS) {
            return null;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        return new MiddleHalf(quartile(sorted, 0.25), quartile(sorted, 0.75));
    }

    /** The value at {@code fraction} of the way through {@code sorted}, interpolated linearly and rounded. */
    private static int quartile(List<Integer> sorted, double fraction) {
        double position = (sorted.size() - 1) * fraction;
        int below = (int) Math.floor(position);
        int above = (int) Math.ceil(position);
        double value = sorted.get(below) + (position - below) * (sorted.get(above) - sorted.get(below));
        return (int) Math.round(value);
    }

    /** Whether a part counted over every observed developer holds K others whoever reads it. */
    private static boolean shows(int developers) {
        return developers > MINIMUM_OTHERS;
    }

    private static int count(Collection<Bucket> buckets, Bucket bucket) {
        return (int) buckets.stream().filter(each -> each == bucket).count();
    }
}

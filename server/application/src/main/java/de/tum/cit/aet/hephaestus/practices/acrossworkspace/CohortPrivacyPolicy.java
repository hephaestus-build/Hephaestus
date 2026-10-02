package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the page may say about the developers other than its reader, and the only place that decides it.
 *
 * <p>Every figure about other developers is a count of developers, and a count is shown only when it holds at
 * least {@link #MINIMUM_OTHERS} developers other than the reader. The reader is left out of that test because the
 * reader knows their own standing: a part of five that includes the reader hides only four others.
 *
 * <p>A practice group and a practice are split by the same rule, over the same observed developers: each
 * developer falls in one {@link Bucket}, read off their group standing or their practice standing.
 *
 * <p>A split is checked on "has a standing" against "none yet" first, and both parts must hold
 * enough others before any split shows. The page states how many developers were observed, so a three way split
 * also states "none yet" as the rest: 23 at a standing among 24 observed names the one without. When that holds,
 * the split shows Needs attention, Mixed feedback and Going well, or collapses to the two parts when one of the
 * three holds too few. When it fails, the group shows no split and the page's total is the only count left.
 * Collapse before omit, and omit rather than show a zero. The reader's own standing is shown as a word in every
 * case, since it is theirs.
 */
public final class CohortPrivacyPolicy {

    /** K: the fewest developers other than the reader a shown count may stand for. */
    public static final int MINIMUM_OTHERS = 5;

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
        /** Needs attention, Mixed feedback and Going well, each counted. */
        SPLIT,
        /** Only "has a standing" against "none yet": one of the three would cover too few others. */
        COLLAPSED,
        /** No split, only the observed total: "has a standing" or "none yet" would cover too few others. */
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
            @Nullable Integer hasStanding,
            @Nullable Integer noneYet) {

        static final Split WITHHELD = new Split(Shape.WITHHELD, null, null, null, null, null);
    }

    /**
     * The split of one practice group or one practice.
     *
     * @param others the bucket of every observed developer other than the reader, none yet included
     * @param reader the reader's bucket when the reader is one of the observed developers, else null
     */
    public static Split split(Collection<Bucket> others, @Nullable Bucket reader) {
        int needs = count(others, Bucket.NEEDS_ATTENTION);
        int mixed = count(others, Bucket.MIXED_FEEDBACK);
        int well = count(others, Bucket.GOING_WELL);
        int has = needs + mixed + well;
        int none = others.size() - has;
        if (!shows(has) || !shows(none)) {
            return Split.WITHHELD;
        }
        if (shows(needs) && shows(mixed) && shows(well)) {
            return new Split(
                    Shape.SPLIT,
                    needs + (reader == Bucket.NEEDS_ATTENTION ? 1 : 0),
                    mixed + (reader == Bucket.MIXED_FEEDBACK ? 1 : 0),
                    well + (reader == Bucket.GOING_WELL ? 1 : 0),
                    null,
                    null);
        }
        boolean readerHas = reader != null && reader != Bucket.NONE_YET;
        return new Split(
                Shape.COLLAPSED,
                null,
                null,
                null,
                has + (readerHas ? 1 : 0),
                none + (reader != null && !readerHas ? 1 : 0));
    }

    /** The middle half of a figure across the workspace, as the two values that bound it. */
    public record MiddleHalf(int low, int high) {}

    /**
     * The middle half of one figure across the observed developers, the reader's own value among them, or null when
     * fewer than {@link #MINIMUM_OTHERS} others are observed. Only the quartiles leave: a minimum, a maximum or a
     * count of developers at one value would single someone out.
     *
     * @param values the figure for every observed developer, the reader's included when the reader is observed
     * @param others how many of {@code values} are other developers'
     */
    public static @Nullable MiddleHalf middleHalf(List<Integer> values, int others) {
        if (!shows(others)) {
            return null;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        int last = sorted.size() - 1;
        return new MiddleHalf(sorted.get((int) Math.floor(last * 0.25)), sorted.get((int) Math.ceil(last * 0.75)));
    }

    private static boolean shows(int others) {
        return others >= MINIMUM_OTHERS;
    }

    private static int count(Collection<Bucket> buckets, Bucket bucket) {
        return (int) buckets.stream().filter(each -> each == bucket).count();
    }
}

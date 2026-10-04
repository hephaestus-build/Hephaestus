package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * What the page may say about the developers other than its reader, and the only place that decides it.
 *
 * <p>Every figure about other developers is a count of developers. A part of a split is shown only when it holds
 * more than {@link #MINIMUM_OTHERS} developers, counted over every developer with a standing: whoever reads it, that
 * part holds at least {@link #MINIMUM_OTHERS} others, and every reader sees the same shape.
 *
 * <p>The total with a standing shows only while it holds {@link #MINIMUM_OTHERS} others. A practice group and a
 * practice are split by the same rule, over the same developers with a standing: each developer falls in the part of
 * their group standing or their practice standing, and both standings that are no verdict fall in none yet. A split
 * shows Needs attention, Mixed feedback, Going well and none yet only when every one of the four holds enough; otherwise the whole split is
 * withheld, never a part of it, since the page states how many developers have a standing and a missing part would
 * be that total less the rest. Omit rather than show a zero. The reader's own standing shows only as the marker on
 * their part of a split, and a split withheld marks no one.
 *
 * <p>A group's standing is read off its practices, so its developers with a standing are everyone with a standing
 * in any of them, and its split and its practices' splits can be subtracted from each other: two practices of
 * sixteen and a group of thirty one name the one developer with a standing in both. A practice therefore shows only
 * while the developers its group has and it lacks are none or at least {@link #MINIMUM_OTHERS}, and a group's
 * practices show only while the overlap they add up to beyond the group is none or at least
 * {@link #MINIMUM_OTHERS} ({@link #group}).
 *
 * <p>The splits count the current standing and take no window, so two windows of one split never exist to subtract.
 * The tiles' middle halves are checked per window, and the figures are live, so two windows of a tile, or two reads
 * at different times, can still be subtracted from each other; ADR 0051 records that limit.
 */
public final class CohortPrivacyPolicy {

    /** K: the fewest developers other than the reader a shown count may stand for. */
    public static final int MINIMUM_OTHERS = 3;

    /**
     * The fewest developers other than the reader a middle half may be read over: twice K, so neither quarter
     * outside the middle half can be one developer's value.
     */
    public static final int MINIMUM_OTHERS_FOR_MIDDLE_HALF = 2 * MINIMUM_OTHERS;

    private CohortPrivacyPolicy() {}

    /** The verdicts a split counts a part for, in the order the practice profile lists them. */
    static final List<Standing> VERDICTS = List.of(Standing.DEVELOPING, Standing.MIXED, Standing.STRENGTH);

    /** How one split is shown. */
    public enum Shape {
        /** Needs attention, Mixed feedback, Going well and none yet, each counted. */
        SPLIT,
        /** No split: one of the four would cover too few. */
        WITHHELD,
    }

    /** The developers at one verdict in a split. */
    public record Part(Standing standing, int developers) {}

    /**
     * One split as it may be shown: a part per verdict, in {@link #VERDICTS} order, and none yet. Counts include
     * the reader when the reader is counted, so the bar and the reader's place on it agree; a withheld split has
     * no parts and no none yet.
     */
    public record Split(
            Shape shape, List<Part> parts, @Nullable Integer noneYet) {

        static final Split WITHHELD = new Split(Shape.WITHHELD, List.of(), null);
    }

    /**
     * The total of developers with a standing as it may be shown, the reader included when the reader is counted:
     * only while it holds K others, otherwise null.
     *
     * @param othersWithAStanding developers with a standing other than the reader
     */
    public static @Nullable Integer totalWithAStanding(int othersWithAStanding, boolean readerCounted) {
        return othersWithAStanding >= MINIMUM_OTHERS ? othersWithAStanding + (readerCounted ? 1 : 0) : null;
    }

    /**
     * One developer's standing in a practice group and in each of its practices, the practices in the order the
     * page lists them.
     */
    public record Row(Standing group, List<Standing> practices) {}

    /** A practice group's split and its practices' splits, in the order of the rows' practices. */
    public record GroupRelease(Split group, List<Split> practices) {}

    /**
     * The splits of one practice group and its practices.
     *
     * <p>Each split is first decided on its own. Then the cells a reader can work out by inclusion and exclusion
     * must each hold none or at least K developers: a practice is withheld where its developers with a standing
     * fall short of its group's by 1 to K - 1, and every practice of the group is withheld where the practices
     * shown add up to 1 to K - 1 more developers with a standing than the group has, which is how many hold a
     * standing in more than one of them. The group's own split stands, since on its own every part it shows
     * already holds enough.
     *
     * @param withAStanding the standings of every developer with a standing, the reader's included when the
     *     reader has one
     * @param practiceCount how many practices each row carries, which no row says when nobody has a standing
     */
    public static GroupRelease group(List<Row> withAStanding, int practiceCount) {
        Split group = split(withAStanding.stream().map(Row::group).toList());
        List<Split> practices = IntStream.range(0, practiceCount)
                .mapToObj(index -> {
                    Split practice = split(withAStanding.stream()
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

    /** How many a split counts with a standing; none for a withheld split, which has no parts. */
    private static int hasStanding(Split split) {
        return split.parts().stream().mapToInt(Part::developers).sum();
    }

    /**
     * One split counted over every developer with a standing, the reader included when they have one: all four
     * parts, each holding more than K of them, or nothing.
     */
    static Split split(Collection<Standing> withAStanding) {
        List<Part> parts = VERDICTS.stream()
                .map(verdict -> new Part(verdict, (int) withAStanding.stream()
                        .filter(standing -> standing == verdict)
                        .count()))
                .toList();
        int none =
                withAStanding.size() - parts.stream().mapToInt(Part::developers).sum();
        if (parts.stream().allMatch(part -> shows(part.developers())) && shows(none)) {
            return new Split(Shape.SPLIT, parts, none);
        }
        return Split.WITHHELD;
    }

    /** The middle half of a figure across the workspace, as the two values that bound it. */
    public record MiddleHalf(int low, int high) {}

    /**
     * The middle half of one figure across the developers counted, the reader's own value among them, or null when
     * fewer than {@link #MINIMUM_OTHERS_FOR_MIDDLE_HALF} others are counted. Only the quartiles leave, each interpolated
     * between the two values around it and rounded, so a quartile falls on one developer's value only where the
     * values around it agree: a minimum, a maximum or a count of developers at one value would single someone out.
     *
     * @param values the figure for every developer counted, the reader's included when the reader is counted
     * @param others how many of {@code values} are other developers'
     */
    public static @Nullable MiddleHalf middleHalf(List<Integer> values, int others) {
        if (others < MINIMUM_OTHERS_FOR_MIDDLE_HALF) {
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

    /** Whether a part counted over every developer with a standing holds K others whoever reads it. */
    private static boolean shows(int developers) {
        return developers > MINIMUM_OTHERS;
    }
}

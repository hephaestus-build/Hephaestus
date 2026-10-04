package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * What the page may say about the developers other than its reader, and the only place that decides it (ADR 0051).
 *
 * <p>Every figure about other developers is a count of developers. A part of a split shows only when it holds more
 * than {@link #MINIMUM_OTHERS} developers, counted over every developer with a standing, so whoever reads it the part
 * stands for at least {@link #MINIMUM_OTHERS} others and every reader sees the same shape. A split shows all four
 * parts or none of them: the page states how many developers have a standing, so one missing part would be that
 * total less the rest. A split held back still shows its total while the total itself would show as a part.
 *
 * <p>A group's developers with a standing are everyone with a standing in any of its practices, so the group's split
 * and its practices' splits can be subtracted from each other; {@link #group} guards those differences.
 *
 * <p>Each read is guarded on its own. Two windows of a tile, or two reads at different times, are not guarded
 * against each other; ADR 0051 records that limit.
 */
public final class CohortPrivacyPolicy {

    /** K: the fewest developers other than the reader a shown count may stand for. */
    public static final int MINIMUM_OTHERS = 3;

    /**
     * The fewest developers other than the reader a middle half may be read over: twice K, so each quarter outside
     * the middle half spans more than one developer.
     */
    public static final int MINIMUM_OTHERS_FOR_MIDDLE_HALF = 2 * MINIMUM_OTHERS;

    private CohortPrivacyPolicy() {}

    /** The verdicts a split counts a part for, in the order the practice profile lists them. */
    static final List<Standing> VERDICTS = List.of(Standing.DEVELOPING, Standing.MIXED, Standing.STRENGTH);

    public enum Shape {
        /** Needs attention, Mixed feedback, Going well and none yet, each counted. */
        SPLIT,
        /** Only the total: one of the four would cover too few, but the total holds enough. */
        TOTAL_ONLY,
        /** Nothing: the total itself would cover too few. */
        WITHHELD,
    }

    public record Part(Standing standing, int developers) {}

    /**
     * One split as it may be shown: a part per verdict, in {@link #VERDICTS} order, none yet, and the total they add
     * up to. Counts include the reader when the reader is counted, so the bar and the reader's place on it agree. A
     * split shown as its total only has no parts and no none yet, and a withheld split has no total either.
     */
    public record Split(
            Shape shape,
            List<Part> parts,
            @Nullable Integer noneYet,
            @Nullable Integer developers) {

        static final Split WITHHELD = new Split(Shape.WITHHELD, List.of(), null, null);

        /** A split whose parts may not show: its total alone while the total holds enough, otherwise nothing. */
        static Split heldBack(int developers) {
            return shows(developers) ? new Split(Shape.TOTAL_ONLY, List.of(), null, developers) : WITHHELD;
        }
    }

    /**
     * The reader's standing as a split may mark it: only where the reader sits in a part the split shows, so a split
     * held back, or a reader it does not count, carries no marker.
     */
    public static <S> @Nullable S marker(Split split, boolean readerCounted, S standing) {
        return readerCounted && split.shape() == Shape.SPLIT ? standing : null;
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
     * already holds enough. A split withheld for either reason still shows its total, the same for every split of
     * the group.
     *
     * @param withAStanding the standings of every developer with a standing, the reader's included when the
     *     reader has one
     * @param practiceCount how many practices each row carries, which no row says when nobody has a standing
     */
    public static GroupRelease group(List<Row> withAStanding, int practiceCount) {
        Split heldBack = Split.heldBack(withAStanding.size());
        Split group = split(withAStanding.stream().map(Row::group).toList());
        List<Split> practices = IntStream.range(0, practiceCount)
                .mapToObj(index -> {
                    Split practice = split(withAStanding.stream()
                            .map(row -> row.practices().get(index))
                            .toList());
                    return safeCell(hasStanding(group) - hasStanding(practice), group, practice) ? practice : heldBack;
                })
                .toList();
        List<Split> shown = practices.stream()
                .filter(practice -> practice.shape() == Shape.SPLIT)
                .toList();
        if (group.shape() == Shape.SPLIT && shown.size() > 1) {
            int overlap =
                    shown.stream().mapToInt(CohortPrivacyPolicy::hasStanding).sum() - hasStanding(group);
            if (!safeCell(overlap, group, group)) {
                return new GroupRelease(
                        group, practices.stream().map(practice -> heldBack).toList());
            }
        }
        return new GroupRelease(group, practices);
    }

    /** Whether a cell two shown splits let a reader work out is none or at least K, or not worked out at all. */
    private static boolean safeCell(int developers, Split one, Split other) {
        if (one.shape() != Shape.SPLIT || other.shape() != Shape.SPLIT) {
            return true;
        }
        int size = Math.abs(developers);
        return size == 0 || size >= MINIMUM_OTHERS;
    }

    /** How many a split counts with a standing; none for a split held back, which has no parts. */
    private static int hasStanding(Split split) {
        return split.parts().stream().mapToInt(Part::developers).sum();
    }

    /**
     * One split counted over every developer with a standing, the reader included when they have one: all four
     * parts, each holding more than K of them, or the split held back.
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
            return new Split(Shape.SPLIT, parts, none, withAStanding.size());
        }
        return Split.heldBack(withAStanding.size());
    }

    public record MiddleHalf(int low, int high) {}

    /**
     * The middle half of one figure across the developers counted, the reader's own value among them, or null when
     * fewer than {@link #MINIMUM_OTHERS_FOR_MIDDLE_HALF} others are counted. Only the two quartiles leave, each
     * interpolated linearly and rounded. A quartile is an order statistic and can equal some developer's value, but
     * it never says whose: a minimum, a maximum or a count of developers at one value would.
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

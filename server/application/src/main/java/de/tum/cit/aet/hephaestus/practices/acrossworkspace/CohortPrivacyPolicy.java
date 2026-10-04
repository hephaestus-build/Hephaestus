package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * The only place that decides what the page may show about developers (ADR 0051). One rule: every count the page
 * shows, and every count a reader can derive from them by subtraction, holds none or at least
 * {@link #MINIMUM_DEVELOPERS_PER_COUNT} developers, whoever reads it. No threshold depends on the reader, so every
 * reader sees the same shape, and each count stands for at least {@link #MINIMUM_OTHERS} others besides any reader.
 * A middle half needs {@link #MINIMUM_DEVELOPERS_FOR_MIDDLE_HALF} developers.
 */
public final class CohortPrivacyPolicy {

    /** K: the fewest developers other than the reader a shown count stands for. */
    public static final int MINIMUM_OTHERS = 3;

    /** K + 1: the fewest developers a shown or derivable count may hold, the reader among them or not. */
    public static final int MINIMUM_DEVELOPERS_PER_COUNT = MINIMUM_OTHERS + 1;

    /** 2K + 1: the fewest developers a middle half may be read over, so 2K others besides any reader. */
    public static final int MINIMUM_DEVELOPERS_FOR_MIDDLE_HALF = 2 * MINIMUM_OTHERS + 1;

    /** 2K: the fewest developers other than the reader a middle half stands for. */
    public static final int MINIMUM_OTHERS_FOR_MIDDLE_HALF = MINIMUM_DEVELOPERS_FOR_MIDDLE_HALF - 1;

    private CohortPrivacyPolicy() {}

    /** A verdict a split counts a part for, in the order the practice profile lists them. */
    public enum Verdict {
        DEVELOPING,
        MIXED,
        STRENGTH;

        /** The verdict a practice standing names, or null for one that is no verdict. */
        public static @Nullable Verdict of(Standing standing) {
            return switch (standing) {
                case DEVELOPING -> DEVELOPING;
                case MIXED -> MIXED;
                case STRENGTH -> STRENGTH;
                case NOT_OBSERVED, NO_OPPORTUNITY -> null;
            };
        }
    }

    public enum Shape {
        /** Needs attention, Mixed feedback, Going well and none yet, each counted. */
        SPLIT,
        /** Only the total: one of the four would cover too few, but the total holds enough. */
        TOTAL_ONLY,
        /** Nothing: the total itself would cover too few. */
        WITHHELD,
    }

    public record Part(Verdict standing, int developers) {}

    /**
     * One split as it may be shown: a part per verdict in {@link Verdict} order, none yet, and their total. A split
     * shown as its total only has no parts and no none yet, and a withheld split has no total either.
     */
    public record Split(
            Shape shape,
            List<Part> parts,
            @Nullable Integer noneYet,
            @Nullable Integer developers) {

        static final Split WITHHELD = new Split(Shape.WITHHELD, List.of(), null, null);

        static Split heldBack(int developers) {
            return shows(developers) ? new Split(Shape.TOTAL_ONLY, List.of(), null, developers) : WITHHELD;
        }

        private int withAVerdict() {
            return parts.stream().mapToInt(Part::developers).sum();
        }
    }

    /** One developer with a standing: their standings in each group the page shows, in the order the page lists them. */
    public record Developer(List<Standings> groups) {}

    /** One developer's standing in a group and in each of its practices; a standing that is no verdict is none yet. */
    public record Standings(Standing group, List<Standing> practices) {}

    /** A practice group's split and its practices' splits, in the order the page lists them. */
    public record GroupRelease(Split group, List<Split> practices) {}

    /**
     * Every split of the page. Each split is decided on its own, then held back where a difference between shown
     * splits would count 1 to K developers: a practice against its group, a group's shown practices against the
     * group, and the groups whose size a reader knows against the developers with a standing. A reader knows a group's size from its
     * own split, or from its one practice's split, since a group verdict exists exactly where a verdict on one of its
     * listed practices does.
     *
     * @param withAStanding every developer with a standing in a group the page shows
     * @param practicesPerGroup how many practices each group lists, which no developer says when nobody has a standing
     */
    public static List<GroupRelease> page(List<Developer> withAStanding, List<Integer> practicesPerGroup) {
        int total = withAStanding.size();
        List<GroupRelease> groups = IntStream.range(0, practicesPerGroup.size())
                .mapToObj(index -> group(
                        withAStanding.stream()
                                .map(developer -> developer.groups().get(index))
                                .toList(),
                        practicesPerGroup.get(index)))
                .toList();
        int knownGroups = 0;
        int inSeveralGroups = -total;
        for (GroupRelease group : groups) {
            Split sized =
                    group.group().shape() == Shape.SPLIT || group.practices().size() != 1
                            ? group.group()
                            : group.practices().getFirst();
            if (sized.shape() == Shape.SPLIT) {
                knownGroups++;
                inSeveralGroups += sized.withAVerdict();
            }
        }
        if (knownGroups > 1 && !derivable(inSeveralGroups)) {
            Split heldBack = Split.heldBack(total);
            groups = groups.stream()
                    .map(group -> new GroupRelease(
                            heldBack,
                            group.practices().stream().map(practice -> heldBack).toList()))
                    .toList();
        }
        return groups;
    }

    /** One group's splits, with its practices held back where they and the group would count 1 to K developers. */
    static GroupRelease group(List<Standings> withAStanding, int practiceCount) {
        Split heldBack = Split.heldBack(withAStanding.size());
        Split group = split(withAStanding.stream().map(Standings::group).toList());
        List<Split> practices = IntStream.range(0, practiceCount)
                .mapToObj(index -> split(withAStanding.stream()
                        .map(standings -> standings.practices().get(index))
                        .toList()))
                .map(practice ->
                        bothShown(group, practice) && !derivable(group.withAVerdict() - practice.withAVerdict())
                                ? heldBack
                                : practice)
                .toList();
        List<Split> shown = practices.stream()
                .filter(practice -> bothShown(group, practice))
                .toList();
        int inSeveralPractices = shown.stream().mapToInt(Split::withAVerdict).sum() - group.withAVerdict();
        if (shown.size() > 1 && !derivable(inSeveralPractices)) {
            return new GroupRelease(
                    group, practices.stream().map(practice -> heldBack).toList());
        }
        return new GroupRelease(group, practices);
    }

    /** One split: all four parts, each holding enough, or the split held back whole. */
    static Split split(Collection<Standing> withAStanding) {
        List<Part> parts = List.of(Verdict.values()).stream()
                .map(verdict -> new Part(verdict, (int) withAStanding.stream()
                        .filter(standing -> Verdict.of(standing) == verdict)
                        .count()))
                .toList();
        int none =
                withAStanding.size() - parts.stream().mapToInt(Part::developers).sum();
        if (parts.stream().allMatch(part -> shows(part.developers())) && shows(none)) {
            return new Split(Shape.SPLIT, parts, none, withAStanding.size());
        }
        return Split.heldBack(withAStanding.size());
    }

    /** A total as it may be shown: only while it holds enough. */
    public static @Nullable Integer count(int developers) {
        return shows(developers) ? developers : null;
    }

    /**
     * The reader's standing as a split may mark it: only where the reader sits in a part the split shows, so a split
     * held back, or a reader it does not count, carries no marker.
     */
    public static <S> @Nullable S marker(Split split, boolean readerCounted, S standing) {
        return readerCounted && split.shape() == Shape.SPLIT ? standing : null;
    }

    public record MiddleHalf(int low, int high) {}

    /**
     * The middle half of one figure across the developers counted, or null below
     * {@link #MINIMUM_DEVELOPERS_FOR_MIDDLE_HALF} of them. Only the two quartiles leave, each interpolated linearly
     * and rounded. A quartile can equal some developer's value but never says whose: a minimum, a maximum or a count
     * of developers at one value would.
     */
    public static @Nullable MiddleHalf middleHalf(List<Integer> values) {
        if (values.size() < MINIMUM_DEVELOPERS_FOR_MIDDLE_HALF) {
            return null;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        return new MiddleHalf(quartile(sorted, 0.25), quartile(sorted, 0.75));
    }

    private static int quartile(List<Integer> sorted, double fraction) {
        double position = (sorted.size() - 1) * fraction;
        int below = (int) Math.floor(position);
        int above = (int) Math.ceil(position);
        double value = sorted.get(below) + (position - below) * (sorted.get(above) - sorted.get(below));
        return (int) Math.round(value);
    }

    private static boolean bothShown(Split one, Split other) {
        return one.shape() == Shape.SPLIT && other.shape() == Shape.SPLIT;
    }

    /** Whether a count a reader can derive is none or holds enough, either sign. */
    private static boolean derivable(int developers) {
        return developers == 0 || shows(Math.abs(developers));
    }

    private static boolean shows(int developers) {
        return developers >= MINIMUM_DEVELOPERS_PER_COUNT;
    }
}

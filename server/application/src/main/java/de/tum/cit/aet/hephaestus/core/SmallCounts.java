package de.tum.cit.aet.hephaestus.core;

import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * Which counts of people a view may show: the one home of the threshold, for members and staff alike. A count of
 * fewer than five people, or a figure read from so few, can tell a reader who it is about, so no view shows one.
 *
 * <p>A split is cells that add up to its total. It shows either every cell or none. Hiding only the small cells does
 * not work, because a reader who knows the rule can bound or rebuild them from the total and the cells shown. So a
 * split with any cell of one to four, or with everyone in one cell, shows only its total.
 */
public final class SmallCounts {

    /** The fewest people a shown count may hold, and the fewest others a figure such as a range may be read from. */
    public static final int MINIMUM = 5;

    private SmallCounts() {}

    /** Whether a figure read from this many people may be shown, such as a typical range. */
    public static boolean enough(long people) {
        return people >= MINIMUM;
    }

    /** A count of people as a view may show it: null for one to four. */
    public static @Nullable Integer shown(int people) {
        return people == 0 || enough(people) ? people : null;
    }

    /** A count of people as a view may show it: null for one to four. */
    public static @Nullable Long shown(long people) {
        return people == 0 || enough(people) ? people : null;
    }

    /**
     * Whether a split of these cells may show its cells, not only its total: every cell is empty or holds enough
     * people, and no cell holds everyone. An empty split shows its empty cells.
     */
    public static boolean spread(int... cells) {
        int total = IntStream.of(cells).sum();
        return total == 0
                || (IntStream.of(cells).allMatch(people -> people == 0 || enough(people))
                        && IntStream.of(cells).noneMatch(people -> people == total));
    }
}

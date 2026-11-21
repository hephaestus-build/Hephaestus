package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.WorkspaceSplits.Verdict;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitPartDTO;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * Which counts of people Practices across the workspace may show its members (ADR 0112). The one home of the
 * threshold: a count of one or two people tells a small team who it is.
 *
 * <p>A split is four cells, one per verdict and none yet, that add up to its total. It shows either every cell or
 * none. Hiding only the small cells does not work, because a reader who knows the rule can bound or rebuild them
 * from the total and the cells shown. So a split with any cell of one or two, or with everyone in one cell, shows
 * only its total.
 */
public final class SmallCounts {

    /** The fewest people a shown count may hold, and the fewest others a typical range may be read from. */
    public static final int MINIMUM = 3;

    private SmallCounts() {}

    /** Whether a figure read from this many people may be shown, such as a typical range. */
    public static boolean enough(int people) {
        return people >= MINIMUM;
    }

    /** A count of people as members may see it: null for one or two. */
    public static @Nullable Integer shown(int people) {
        return people == 0 || enough(people) ? people : null;
    }

    /** The split as members may see it: every cell, or only the total. */
    public static WorkspaceSplitDTO shown(WorkspaceSplits.Split split) {
        int[] cells = {split.developing(), split.mixed(), split.strength(), split.noneYet()};
        int total = IntStream.of(cells).sum();
        boolean everyCellSafe = IntStream.of(cells).allMatch(people -> people == 0 || enough(people));
        boolean everyoneInOneCell = IntStream.of(cells).anyMatch(people -> people == total);
        boolean spread = total == 0 || (everyCellSafe && !everyoneInOneCell);
        Verdict[] verdicts = Verdict.values();
        List<WorkspaceSplitPartDTO> parts = IntStream.range(0, verdicts.length)
                .mapToObj(cell -> new WorkspaceSplitPartDTO(verdicts[cell], spread ? cells[cell] : null))
                .toList();
        return new WorkspaceSplitDTO(parts, spread ? split.noneYet() : null, shown(total));
    }
}

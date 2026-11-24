package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.core.SmallCounts;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceRangeDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitPartDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/** How the page counts developers (ADR 0051): a split per practice group and practice, and a middle half per figure. */
public final class WorkspaceSplits {

    private WorkspaceSplits() {}

    /** A verdict a split counts a part for, in the order the practice profile lists them. */
    public enum Verdict {
        DEVELOPING,
        MIXED,
        STRENGTH;

        /** The verdict a practice standing names, or null for one that is no verdict. */
        public static @Nullable Verdict of(PracticeStanding standing) {
            return switch (standing) {
                case DEVELOPING -> DEVELOPING;
                case MIXED -> MIXED;
                case STRENGTH -> STRENGTH;
                case NOT_OBSERVED, NO_OPPORTUNITY -> null;
            };
        }
    }

    /** The developers at each verdict and with none yet, before {@link SmallCounts} decides what members see. */
    public record Split(int developing, int mixed, int strength, int noneYet) {}

    /** How the developers with a standing split over their standings in one group or practice, one each. */
    public static Split split(Collection<PracticeStanding> withAStanding) {
        int developing = count(withAStanding, Verdict.DEVELOPING);
        int mixed = count(withAStanding, Verdict.MIXED);
        int strength = count(withAStanding, Verdict.STRENGTH);
        return new Split(developing, mixed, strength, withAStanding.size() - developing - mixed - strength);
    }

    /** The split as members may see it: every cell, or only the total ({@link SmallCounts}). */
    public static WorkspaceSplitDTO shown(Split split) {
        int[] cells = {split.developing(), split.mixed(), split.strength(), split.noneYet()};
        boolean spread = SmallCounts.spread(cells);
        Verdict[] verdicts = Verdict.values();
        List<WorkspaceSplitPartDTO> parts = IntStream.range(0, verdicts.length)
                .mapToObj(cell ->
                        new WorkspaceSplitPartDTO(verdicts[cell], spread ? SmallCounts.count(cells[cell]) : null))
                .toList();
        return new WorkspaceSplitDTO(
                parts,
                spread ? SmallCounts.count(split.noneYet()) : null,
                SmallCounts.count(IntStream.of(cells).sum()));
    }

    private static int count(Collection<PracticeStanding> standings, Verdict verdict) {
        return (int) standings.stream()
                .filter(standing -> Verdict.of(standing) == verdict)
                .count();
    }

    /**
     * The middle half of one figure across the other developers counted, or null when fewer than
     * {@link SmallCounts#MINIMUM} are. The reader is left out: with their own value beside the two quartiles of a
     * group they belong to, a reader could rebuild the others' values. A range whose ends meet is null too: it says
     * that every other developer counted is within one of that value. Only the two quartiles leave, each interpolated
     * linearly and rounded.
     */
    public static @Nullable WorkspaceRangeDTO middleHalf(List<Integer> values) {
        if (!SmallCounts.enough(values.size())) {
            return null;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        int low = quartile(sorted, 0.25);
        int high = quartile(sorted, 0.75);
        return low == high ? null : new WorkspaceRangeDTO(low, high);
    }

    private static int quartile(List<Integer> sorted, double fraction) {
        double position = (sorted.size() - 1) * fraction;
        int below = (int) Math.floor(position);
        int above = (int) Math.ceil(position);
        double value = sorted.get(below) + (position - below) * (sorted.get(above) - sorted.get(below));
        return (int) Math.round(value);
    }
}

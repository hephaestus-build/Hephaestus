package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceRangeDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitPartDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing;
import java.util.Collection;
import java.util.List;
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
        public static @Nullable Verdict of(Standing standing) {
            return switch (standing) {
                case DEVELOPING -> DEVELOPING;
                case MIXED -> MIXED;
                case STRENGTH -> STRENGTH;
                case NOT_OBSERVED, NO_OPPORTUNITY -> null;
            };
        }
    }

    /** How the developers with a standing split over their standings in one group or practice. */
    public static WorkspaceSplitDTO split(Collection<Standing> withAStanding) {
        List<WorkspaceSplitPartDTO> parts = List.of(Verdict.values()).stream()
                .map(verdict -> new WorkspaceSplitPartDTO(verdict, (int) withAStanding.stream()
                        .filter(standing -> Verdict.of(standing) == verdict)
                        .count()))
                .toList();
        int withAVerdict =
                parts.stream().mapToInt(WorkspaceSplitPartDTO::developers).sum();
        return new WorkspaceSplitDTO(parts, withAStanding.size() - withAVerdict, withAStanding.size());
    }

    /**
     * The middle half of one figure across the developers counted, or null when nobody is counted. Only the two
     * quartiles leave, each interpolated linearly and rounded.
     */
    public static @Nullable WorkspaceRangeDTO middleHalf(List<Integer> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        return new WorkspaceRangeDTO(quartile(sorted, 0.25), quartile(sorted, 0.75));
    }

    private static int quartile(List<Integer> sorted, double fraction) {
        double position = (sorted.size() - 1) * fraction;
        int below = (int) Math.floor(position);
        int above = (int) Math.ceil(position);
        double value = sorted.get(below) + (position - below) * (sorted.get(above) - sorted.get(below));
        return (int) Math.round(value);
    }
}

package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How the page counts developers (ADR 0051): a split per practice group and practice, and the middle half of a
 * figure. Every split shows all its parts and every middle half shows, however few developers they count. Who and
 * what is counted at all is decided before: a developer's AI choice, hidden members, hidden repositories and
 * invalidated observations apply before these counts.
 */
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

    public record Part(Verdict standing, int developers) {}

    /** One split: a part per verdict in {@link Verdict} order, none yet, and their total. */
    public record Split(List<Part> parts, int noneYet, int developers) {}

    /** How the developers with a standing split over their standings in one group or practice. */
    public static Split split(Collection<Standing> withAStanding) {
        List<Part> parts = List.of(Verdict.values()).stream()
                .map(verdict -> new Part(verdict, (int) withAStanding.stream()
                        .filter(standing -> Verdict.of(standing) == verdict)
                        .count()))
                .toList();
        int none =
                withAStanding.size() - parts.stream().mapToInt(Part::developers).sum();
        return new Split(parts, none, withAStanding.size());
    }

    public record MiddleHalf(int low, int high) {}

    /**
     * The middle half of one figure across the developers counted, or null when nobody is counted. Only the two
     * quartiles leave, each interpolated linearly and rounded.
     */
    public static @Nullable MiddleHalf middleHalf(List<Integer> values) {
        if (values.isEmpty()) {
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
}

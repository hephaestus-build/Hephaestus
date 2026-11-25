package de.tum.cit.aet.hephaestus.practices.usage;

import de.tum.cit.aet.hephaestus.core.settings.spi.UsageFigure;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The disclosure rules of a public figure (ADR 0153 decisions 7 to 10). Pure: it turns the raw counts of one period
 * into the figures that may be stored, and drops everything else.
 */
final class UsageDisclosure {

    /** The fewest workspaces, and the fewest developers, that a period needs to publish figures. */
    static final int FLOOR = 5;

    /** A figure is hidden when one workspace adds more than this share of it: more than a third. */
    private static final int DOMINANCE_PARTS = 3;

    private UsageDisclosure() {}

    /** The figures of one period that may leave the server, from its raw counts. */
    static UsageFigures disclose(Collection<UsageCount> counts) {
        Map<String, UsageCount> byFigure =
                counts.stream().collect(Collectors.toMap(UsageCount::figure, Function.identity()));
        long workspaces = count(byFigure, UsageFigure.PRACTICE_REVIEWS.name()).workspaces();
        long developers = count(byFigure, UsageCount.DISTINCT_PEOPLE).total();
        if (workspaces < FLOOR || developers < FLOOR) {
            return UsageFigures.notEnoughActivity();
        }
        Map<UsageFigure, @Nullable Long> figures = new EnumMap<>(UsageFigure.class);
        // Each workspace adds one to the count of workspaces, so no workspace dominates it.
        figures.put(UsageFigure.WORKSPACES, published(workspaces, 1));
        figures.put(
                UsageFigure.REVIEWED_PEOPLE,
                published(
                        developers,
                        count(byFigure, UsageFigure.REVIEWED_PEOPLE.name()).largest()));
        for (var figure : new UsageFigure[] {
            UsageFigure.REVIEWED_WORK, UsageFigure.PRACTICE_REVIEWS, UsageFigure.FEEDBACK_DELIVERED
        }) {
            var count = count(byFigure, figure.name());
            figures.put(figure, published(count.total(), count.largest()));
        }
        return UsageFigures.published(figures);
    }

    /** A figure as published: rounded down, or null when one workspace dominates it or it rounds to zero. */
    static @Nullable Long published(long total, long largest) {
        if (largest * DOMINANCE_PARTS > total) {
            return null;
        }
        long rounded = round(total);
        return rounded == 0 ? null : rounded;
    }

    /** Rounds a count down: to a multiple of 10 below 1,000, and to a multiple of 100 from 1,000. */
    static long round(long count) {
        long step = count < 1_000 ? 10 : 100;
        return count / step * step;
    }

    /** Whether a stored figure could be the result of {@link #round}: a reader refuses any other value. */
    static boolean isRounded(long figure) {
        return figure >= 10 && figure == round(figure);
    }

    private static UsageCount count(Map<String, UsageCount> byFigure, String figure) {
        return Optional.ofNullable(byFigure.get(figure)).orElseGet(() -> new UsageCount("", figure, 0, 0, 0));
    }
}

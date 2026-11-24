package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding.DEVELOPING;
import static de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding.MIXED;
import static de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding.NOT_OBSERVED;
import static de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding.NO_OPPORTUNITY;
import static de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding.STRENGTH;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.SmallCounts;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceRangeDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStanding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WorkspaceSplitsTest {

    private static final int MOST_VALUE = 12;

    private static List<PracticeStanding> developers(int needs, int mixed, int well, int none) {
        List<PracticeStanding> standings = new ArrayList<>();
        standings.addAll(Collections.nCopies(needs, DEVELOPING));
        standings.addAll(Collections.nCopies(mixed, MIXED));
        standings.addAll(Collections.nCopies(well, STRENGTH));
        standings.addAll(Collections.nCopies(none, NOT_OBSERVED));
        return standings;
    }

    @Test
    void shouldCountEveryVerdictAndNoneYet() {
        assertThat(WorkspaceSplits.split(developers(1, 0, 6, 4))).isEqualTo(new WorkspaceSplits.Split(1, 0, 6, 4));
    }

    @Test
    void shouldCountBothSilencesAsNoneYetWhenNoVerdictWasRead() {
        List<PracticeStanding> standings = new ArrayList<>(developers(4, 4, 4, 2));
        standings.addAll(Collections.nCopies(2, NO_OPPORTUNITY));

        assertThat(WorkspaceSplits.split(standings).noneYet()).isEqualTo(4);
    }

    @Test
    void shouldShowAMiddleHalfOnlyForFiveDevelopersOrMore() {
        assertThat(WorkspaceSplits.middleHalf(List.of())).isNull();
        assertThat(WorkspaceSplits.middleHalf(List.of(5))).isNull();
        assertThat(WorkspaceSplits.middleHalf(List.of(2, 4, 6, 8))).isNull();
        assertThat(WorkspaceSplits.middleHalf(List.of(2, 4, 6, 8, 10))).isEqualTo(new WorkspaceRangeDTO(4, 8));
        assertThat(WorkspaceSplits.middleHalf(List.of(1, 2, 3, 4, 5, 6))).isEqualTo(new WorkspaceRangeDTO(2, 5));
    }

    @Test
    void shouldWithholdARangeWhoseEndsMeet() {
        assertThat(WorkspaceSplits.middleHalf(List.of(0, 0, 0, 0, 0))).isNull();
        assertThat(WorkspaceSplits.middleHalf(List.of(3, 4, 4, 4, 5))).isNull();
    }

    /**
     * Every set of 5 to 7 other values from 0 to {@value #MOST_VALUE}, grouped by the range shown: each range fits at
     * least two sets, so no range tells a member every other developer's value. Ranges near the top are left out,
     * because the loop's own ceiling, not the data, would pin them.
     */
    @Test
    void shouldLeaveNoRangeThatFixesEveryOtherValue() {
        for (int others = SmallCounts.MINIMUM; others <= SmallCounts.MINIMUM + 2; others++) {
            Map<WorkspaceRangeDTO, Set<List<Integer>>> setsByRange = new HashMap<>();
            collect(others, 0, new ArrayList<>(), setsByRange);
            int counted = others;
            setsByRange.forEach((range, sets) -> {
                if (range.high() <= MOST_VALUE / 2) {
                    assertThat(sets).as("%d others, range %s", counted, range).hasSizeGreaterThanOrEqualTo(2);
                }
            });
        }
    }

    private static void collect(
            int left, int from, List<Integer> values, Map<WorkspaceRangeDTO, Set<List<Integer>>> setsByRange) {
        if (left == 0) {
            var range = WorkspaceSplits.middleHalf(values);
            if (range != null) {
                setsByRange.computeIfAbsent(range, key -> new HashSet<>()).add(List.copyOf(values));
            }
            return;
        }
        for (int value = from; value <= MOST_VALUE; value++) {
            values.add(value);
            collect(left - 1, value, values, setsByRange);
            values.removeLast();
        }
    }

    @Test
    void shouldBoundTheMiddleHalfByTheQuartilesWhenManyAreCounted() {
        List<Integer> values = IntStream.rangeClosed(1, 13).boxed().toList();

        assertThat(WorkspaceSplits.middleHalf(values)).isEqualTo(new WorkspaceRangeDTO(4, 10));
    }

    @Test
    void shouldInterpolateAQuartileWhenItFallsBetweenTwoValues() {
        // The upper quartile falls halfway from 6 to 10: 8, where nobody stands.
        assertThat(WorkspaceSplits.middleHalf(List.of(10, 0, 5, 0, 6, 0, 10, 2, 0, 10, 0)))
                .isEqualTo(new WorkspaceRangeDTO(0, 8));
    }
}

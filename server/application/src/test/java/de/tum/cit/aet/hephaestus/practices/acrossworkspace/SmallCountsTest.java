package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.WorkspaceSplits.Split;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.WorkspaceSplits.Verdict;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitPartDTO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SmallCountsTest {

    private static final int MOST = 12;

    @Test
    void shouldShowEveryCellWhenEachHoldsNoneOrThreeOrMore() {
        assertThat(SmallCounts.shown(new Split(3, 0, 6, 4)))
                .isEqualTo(new WorkspaceSplitDTO(
                        List.of(
                                new WorkspaceSplitPartDTO(Verdict.DEVELOPING, 3),
                                new WorkspaceSplitPartDTO(Verdict.MIXED, 0),
                                new WorkspaceSplitPartDTO(Verdict.STRENGTH, 6)),
                        4,
                        13));
    }

    @Test
    void shouldShowOnlyTheTotalWhenACellHoldsOneOrTwo() {
        var split = SmallCounts.shown(new Split(1, 0, 6, 4));

        assertThat(cells(split)).containsOnlyNulls();
        assertThat(split.developers()).isEqualTo(11);
    }

    @Test
    void shouldShowOnlyTheTotalWhenEveryoneSitsInOneCell() {
        var split = SmallCounts.shown(new Split(0, 0, 5, 0));

        assertThat(cells(split)).containsOnlyNulls();
        assertThat(split.developers()).isEqualTo(5);
    }

    @Test
    void shouldHideTheTotalBelowThree() {
        var split = SmallCounts.shown(new Split(0, 2, 0, 0));

        assertThat(cells(split)).containsOnlyNulls();
        assertThat(split.developers()).isNull();
    }

    @Test
    void shouldShowAnEmptySplit() {
        assertThat(cells(SmallCounts.shown(new Split(0, 0, 0, 0)))).containsExactly(0, 0, 0, 0);
    }

    /**
     * A reader who knows this rule sees one output and asks which splits could have produced it. For every split of up
     * to {@value #MOST} people per cell: a shown cell holds 0 or at least 3, and every hidden cell could hold at least
     * two different counts, one of them 3 or more, so no hidden cell is pinned down or known to be small.
     */
    @Test
    void shouldLeaveEveryHiddenCellOpenToAReaderWhoKnowsTheRule() {
        Map<List<@Nullable Integer>, List<int[]>> inputsByOutput = new HashMap<>();
        for (int a = 0; a <= MOST; a++) {
            for (int b = 0; b <= MOST; b++) {
                for (int c = 0; c <= MOST; c++) {
                    for (int d = 0; d <= MOST; d++) {
                        var split = SmallCounts.shown(new Split(a, b, c, d));
                        List<@Nullable Integer> output = new ArrayList<>(cells(split));
                        output.add(split.developers());
                        inputsByOutput
                                .computeIfAbsent(output, key -> new ArrayList<>())
                                .add(new int[] {a, b, c, d});
                    }
                }
            }
        }
        inputsByOutput.forEach((output, inputs) -> {
            int total = inputs.getFirst()[0] + inputs.getFirst()[1] + inputs.getFirst()[2] + inputs.getFirst()[3];
            for (int cell = 0; cell < 4; cell++) {
                Integer shown = output.get(cell);
                if (shown != null) {
                    assertThat(shown == 0 || shown >= SmallCounts.MINIMUM)
                            .as("shown cell %d of %s", cell, output)
                            .isTrue();
                    continue;
                }
                if (output.get(4) == null || total > MOST) {
                    // A hidden total, or one whose splits this loop cannot all reach: nothing to bound here.
                    continue;
                }
                Set<Integer> possible = new HashSet<>();
                for (int[] input : inputs) {
                    possible.add(input[cell]);
                }
                assertThat(possible).as("hidden cell %d of %s", cell, output).hasSizeGreaterThanOrEqualTo(2);
                assertThat(possible.stream().anyMatch(people -> people >= SmallCounts.MINIMUM))
                        .as("hidden cell %d of %s could be large", cell, output)
                        .isTrue();
            }
        });
    }

    private static List<@Nullable Integer> cells(WorkspaceSplitDTO split) {
        List<@Nullable Integer> cells = new ArrayList<>();
        split.parts().stream().map(WorkspaceSplitPartDTO::developers).forEach(cells::add);
        cells.add(split.noneYet());
        return cells;
    }
}

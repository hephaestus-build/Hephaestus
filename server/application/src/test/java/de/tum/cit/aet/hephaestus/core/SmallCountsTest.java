package de.tum.cit.aet.hephaestus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class SmallCountsTest {

    private static final int MOST = 14;

    /** The webapp words the floor, and applies none: its number must be this one. */
    private static final Path WEBAPP_WORDING = Path.of("..", "..", "webapp", "src", "lib", "small-counts.ts");

    @Test
    void shouldWordTheSameFloorInTheWebapp() throws IOException {
        assertThat(Files.readString(WEBAPP_WORDING))
                .contains("export const SMALLEST_COUNT = " + SmallCounts.MINIMUM + ";");
    }

    @Test
    void shouldShowZeroAndFiveOrMoreButNoCountBetween() {
        assertThat(SmallCounts.shown(0)).isZero();
        assertThat(SmallCounts.shown(1)).isNull();
        assertThat(SmallCounts.shown(4)).isNull();
        assertThat(SmallCounts.shown(5)).isEqualTo(5);
        assertThat(SmallCounts.shown(6)).isEqualTo(6);
    }

    /** A response field of this type, as Jackson writes it. */
    record Sent(@Nullable ShownCount people) {}

    @Test
    void shouldSendACountFromFivePeopleAsABareNumberAndWithholdOneToFour() {
        var json = new JsonMapper();

        assertThat(json.writeValueAsString(new Sent(SmallCounts.count(1)))).isEqualTo("{\"people\":null}");
        assertThat(json.writeValueAsString(new Sent(SmallCounts.count(4)))).isEqualTo("{\"people\":null}");
        assertThat(json.writeValueAsString(new Sent(SmallCounts.count(5)))).isEqualTo("{\"people\":5}");
        assertThat(json.writeValueAsString(new Sent(SmallCounts.count(0)))).isEqualTo("{\"people\":0}");
    }

    @Test
    void shouldRefuseToBuildAShownCountOfOneToFour() {
        assertThatThrownBy(() -> ShownCount.of(2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShownCount.of(4)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ShownCount.of(5)).hasToString("5");
    }

    @Test
    void shouldReadAFigureOnlyFromFivePeopleOrMore() {
        assertThat(SmallCounts.enough(4)).isFalse();
        assertThat(SmallCounts.enough(5)).isTrue();
    }

    @Test
    void shouldSpreadASplitWhenEveryCellHoldsNoneOrFiveOrMore() {
        assertThat(SmallCounts.spread(5, 0, 6, 7)).isTrue();
        assertThat(SmallCounts.spread(0, 0, 0, 0)).isTrue();
    }

    @Test
    void shouldNotSpreadASplitWhenOneCellHoldsOneToFour() {
        assertThat(SmallCounts.spread(4, 0, 6, 7)).isFalse();
        assertThat(SmallCounts.spread(1, 0, 6, 5)).isFalse();
    }

    @Test
    void shouldNotSpreadASplitWhenEveryoneSitsInOneCell() {
        assertThat(SmallCounts.spread(0, 0, 6, 0)).isFalse();
    }

    /**
     * A reader who knows this rule sees one output and asks which splits could have produced it. For every split of up
     * to {@value #MOST} people per cell: a shown cell holds 0 or at least 5, and every hidden cell could hold at least
     * two different counts, one of them 5 or more, so no hidden cell is pinned down or known to be small.
     */
    @Test
    void shouldLeaveEveryHiddenCellOpenToAReaderWhoKnowsTheRule() {
        Map<List<@Nullable Integer>, List<int[]>> inputsByOutput = new HashMap<>();
        for (int a = 0; a <= MOST; a++) {
            for (int b = 0; b <= MOST; b++) {
                for (int c = 0; c <= MOST; c++) {
                    for (int d = 0; d <= MOST; d++) {
                        int[] cells = {a, b, c, d};
                        boolean spread = SmallCounts.spread(cells);
                        List<@Nullable Integer> output = new ArrayList<>();
                        for (int cell : cells) {
                            output.add(spread ? cell : null);
                        }
                        output.add(SmallCounts.shown(a + b + c + d));
                        inputsByOutput
                                .computeIfAbsent(output, key -> new ArrayList<>())
                                .add(cells);
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
}

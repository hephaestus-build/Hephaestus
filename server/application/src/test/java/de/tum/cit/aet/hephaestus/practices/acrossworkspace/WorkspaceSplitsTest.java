package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.DEVELOPING;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.MIXED;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.NOT_OBSERVED;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.NO_OPPORTUNITY;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.STRENGTH;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.WorkspaceSplits.Verdict;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceRangeDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceSplitPartDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WorkspaceSplitsTest {

    private static List<Standing> developers(int needs, int mixed, int well, int none) {
        List<Standing> standings = new ArrayList<>();
        standings.addAll(Collections.nCopies(needs, DEVELOPING));
        standings.addAll(Collections.nCopies(mixed, MIXED));
        standings.addAll(Collections.nCopies(well, STRENGTH));
        standings.addAll(Collections.nCopies(none, NOT_OBSERVED));
        return standings;
    }

    @Test
    @DisplayName("a split counts every part, however small, the verdicts in profile order")
    void shouldCountEveryPartInProfileOrder() {
        assertThat(WorkspaceSplits.split(developers(1, 0, 6, 4)))
                .isEqualTo(new WorkspaceSplitDTO(
                        List.of(
                                new WorkspaceSplitPartDTO(Verdict.DEVELOPING, 1),
                                new WorkspaceSplitPartDTO(Verdict.MIXED, 0),
                                new WorkspaceSplitPartDTO(Verdict.STRENGTH, 6)),
                        4,
                        11));
    }

    @Test
    @DisplayName("both standings that are no verdict count as none yet")
    void shouldCountBothSilencesAsNoneYet() {
        List<Standing> standings = new ArrayList<>(developers(4, 4, 4, 2));
        standings.addAll(Collections.nCopies(2, NO_OPPORTUNITY));

        assertThat(WorkspaceSplits.split(standings).noneYet()).isEqualTo(4);
    }

    @Test
    @DisplayName("a middle half shows for any number of developers, and not for nobody")
    void shouldShowAMiddleHalfForAnyNumberOfDevelopers() {
        assertThat(WorkspaceSplits.middleHalf(List.of())).isNull();
        assertThat(WorkspaceSplits.middleHalf(List.of(5))).isEqualTo(new WorkspaceRangeDTO(5, 5));
        assertThat(WorkspaceSplits.middleHalf(List.of(1, 2, 3, 4, 5, 6))).isEqualTo(new WorkspaceRangeDTO(2, 5));
    }

    @Test
    @DisplayName("the middle half is the quartiles of every value counted")
    void shouldBoundTheMiddleHalfByTheQuartiles() {
        List<Integer> values = IntStream.rangeClosed(1, 13).boxed().toList();

        assertThat(WorkspaceSplits.middleHalf(values)).isEqualTo(new WorkspaceRangeDTO(4, 10));
    }

    @Test
    @DisplayName("a quartile between two values is interpolated, not the value of the developer next to it")
    void shouldInterpolateAQuartileThatFallsBetweenTwoValues() {
        // The upper quartile falls halfway from 6 to 10: 8, where nobody stands.
        assertThat(WorkspaceSplits.middleHalf(List.of(10, 0, 5, 0, 6, 0, 10, 2, 0, 10, 0)))
                .isEqualTo(new WorkspaceRangeDTO(0, 8));
    }
}

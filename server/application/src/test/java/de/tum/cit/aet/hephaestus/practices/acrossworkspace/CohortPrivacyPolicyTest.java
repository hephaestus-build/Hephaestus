package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.DEVELOPING;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.MIXED;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.NOT_OBSERVED;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.NO_OPPORTUNITY;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.STRENGTH;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.GroupRelease;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.MiddleHalf;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Part;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Row;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Shape;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Split;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CohortPrivacyPolicyTest {

    private static List<Standing> developers(int needs, int mixed, int well, int none) {
        List<Standing> standings = new ArrayList<>();
        standings.addAll(Collections.nCopies(needs, DEVELOPING));
        standings.addAll(Collections.nCopies(mixed, MIXED));
        standings.addAll(Collections.nCopies(well, STRENGTH));
        standings.addAll(Collections.nCopies(none, NOT_OBSERVED));
        return standings;
    }

    @Test
    @DisplayName("every part holding four developers with a standing shows the split, the verdicts in profile order")
    void shouldShowTheSplitWhenEveryPartHoldsFourDevelopers() {
        Split split = CohortPrivacyPolicy.split(developers(4, 5, 6, 4));

        assertThat(split.shape()).isEqualTo(Shape.SPLIT);
        assertThat(split.parts()).containsExactly(new Part(DEVELOPING, 4), new Part(MIXED, 5), new Part(STRENGTH, 6));
        assertThat(split.noneYet()).isEqualTo(4);
    }

    @Test
    @DisplayName("both standings that are no verdict count as none yet")
    void shouldCountBothSilencesAsNoneYet() {
        List<Standing> withAStanding = new ArrayList<>(developers(4, 4, 4, 2));
        withAStanding.addAll(Collections.nCopies(2, NO_OPPORTUNITY));

        assertThat(CohortPrivacyPolicy.split(withAStanding).noneYet()).isEqualTo(4);
    }

    /** Three in a part are three others to a reader outside it and two to a reader inside: every reader is held back. */
    @Test
    @DisplayName("a part of three holds back the whole split for every reader, inside it or not, and shows its total")
    void shouldShowOnlyTheTotalWhenAStandingHoldsThree() {
        assertThat(CohortPrivacyPolicy.split(developers(3, 7, 8, 6))).isEqualTo(totalOnly(24));
    }

    @Test
    @DisplayName("an empty part holds back the split")
    void shouldShowOnlyTheTotalWhenAPartIsEmpty() {
        assertThat(CohortPrivacyPolicy.split(developers(0, 9, 9, 6))).isEqualTo(totalOnly(24));
    }

    @Test
    @DisplayName("three without a standing hold back the split")
    void shouldShowOnlyTheTotalWhenNoneYetHoldsThree() {
        assertThat(CohortPrivacyPolicy.split(developers(6, 9, 9, 3))).isEqualTo(totalOnly(27));
    }

    @Test
    @DisplayName("everyone at a standing is a none yet of zero and holds back the split")
    void shouldShowOnlyTheTotalWhenEveryoneHasAStanding() {
        assertThat(CohortPrivacyPolicy.split(developers(6, 6, 6, 0))).isEqualTo(totalOnly(18));
    }

    /** The total is a part of its own: it shows from K + 1, so it stands for K others whoever reads it. */
    @Test
    @DisplayName("a split held back shows its total from four developers and nothing below")
    void shouldWithholdTheTotalWhenItHoldsThree() {
        assertThat(CohortPrivacyPolicy.split(developers(1, 1, 1, 1))).isEqualTo(totalOnly(4));
        assertThat(CohortPrivacyPolicy.split(developers(1, 1, 1, 0))).isEqualTo(Split.WITHHELD);
        assertThat(CohortPrivacyPolicy.split(developers(4, 5, 6, 4)).developers())
                .isEqualTo(19);
    }

    @Test
    @DisplayName("the middle half is the quartiles of every value counted")
    void shouldBoundTheMiddleHalfByTheQuartiles() {
        List<Integer> values = IntStream.rangeClosed(1, 13).boxed().toList();

        assertThat(CohortPrivacyPolicy.middleHalf(values, 12)).isEqualTo(new MiddleHalf(4, 10));
    }

    @Test
    @DisplayName("a quartile between two values is interpolated, not the value of the developer next to it")
    void shouldInterpolateAQuartileThatFallsBetweenTwoValues() {
        // The upper quartile falls halfway from 6 to 10: 8, where nobody stands.
        assertThat(CohortPrivacyPolicy.middleHalf(List.of(10, 0, 5, 0, 6, 0, 10, 2, 0, 10, 0), 10))
                .isEqualTo(new MiddleHalf(0, 8));
    }

    @Test
    @DisplayName("fewer than six others counted leaves no middle half")
    void shouldLeaveNoMiddleHalfWhenFewerThanSixOthersAreCounted() {
        List<Integer> values = IntStream.rangeClosed(1, 7).boxed().toList();

        assertThat(CohortPrivacyPolicy.middleHalf(values, 5)).isNull();
        assertThat(CohortPrivacyPolicy.middleHalf(values, 6)).isNotNull();
    }

    @Test
    @DisplayName("the total with a standing shows from three others, the reader counted in it")
    void shouldShowTheTotalWithAStandingOnlyWhileItHoldsThreeOthers() {
        assertThat(CohortPrivacyPolicy.totalWithAStanding(17, true)).isEqualTo(18);
        assertThat(CohortPrivacyPolicy.totalWithAStanding(3, false)).isEqualTo(3);
        assertThat(CohortPrivacyPolicy.totalWithAStanding(2, true)).isNull();
    }

    @Test
    @DisplayName("practices that move with their group show beside it")
    void shouldShowPracticesThatMoveWithTheirGroup() {
        List<Row> others = new ArrayList<>();
        for (Standing standing : developers(4, 4, 5, 4)) {
            others.add(new Row(standing, List.of(standing, standing)));
        }

        GroupRelease release =
                CohortPrivacyPolicy.group(others, others.getFirst().practices().size());

        assertThat(release.group().shape()).isEqualTo(Shape.SPLIT);
        assertThat(release.practices()).extracting(Split::shape).containsExactly(Shape.SPLIT, Shape.SPLIT);
        assertThat(at(release.practices().getFirst(), STRENGTH)).isEqualTo(5);
    }

    /**
     * ADR 0051's case: fifteen judged only on A, fifteen only on B, four on neither and one, X, on both. Each split
     * shows on its own, but 16 + 16 - 31 = 1 has a standing in both, and the group's counts less A's and B's give
     * X's parts.
     */
    @Test
    @DisplayName("two practices of sixteen and a group of thirty one hold the practices back")
    void shouldHoldThePracticesBackWhenTheyAndTheGroupWouldSingleOutOneDeveloper() {
        List<Row> withAStanding = new ArrayList<>();
        for (Standing standing : developers(5, 5, 5, 0)) {
            withAStanding.add(new Row(standing, List.of(standing, NOT_OBSERVED)));
            withAStanding.add(new Row(standing, List.of(NOT_OBSERVED, standing)));
        }
        for (int index = 0; index < 4; index++) {
            withAStanding.add(new Row(NOT_OBSERVED, List.of(NOT_OBSERVED, NOT_OBSERVED)));
        }
        withAStanding.add(new Row(MIXED, List.of(DEVELOPING, STRENGTH)));
        assertThat(CohortPrivacyPolicy.split(withAStanding.stream()
                                .map(row -> row.practices().get(0))
                                .toList())
                        .parts())
                .containsExactly(new Part(DEVELOPING, 6), new Part(MIXED, 5), new Part(STRENGTH, 5));

        GroupRelease release = CohortPrivacyPolicy.group(withAStanding, 2);

        assertThat(release.group().parts())
                .containsExactly(new Part(DEVELOPING, 10), new Part(MIXED, 11), new Part(STRENGTH, 10));
        assertThat(release.practices()).containsExactly(totalOnly(35), totalOnly(35));
    }

    /**
     * Whatever the cohort, every cell a reader can subtract from the group and its practices holds none or at least
     * K: the group less one practice, and A plus B less the group, which counts those with a standing in both.
     */
    @Test
    @DisplayName("no cohort lets the group and its practices name 1 to K - 1 developers")
    void shouldNeverLetTheGroupAndItsPracticesNameFewerThanKOthers() {
        Random random = new Random(51);
        Standing[] standings = Standing.values();
        int checked = 0;
        for (int cohort = 0; cohort < 2000; cohort++) {
            List<Row> others = new ArrayList<>();
            int size = 8 + random.nextInt(30);
            for (int index = 0; index < size; index++) {
                Standing a = standings[random.nextInt(standings.length)];
                Standing b = standings[random.nextInt(standings.length)];
                others.add(new Row(PracticeStandingDTO.isVerdict(a) ? a : b, List.of(a, b)));
            }

            GroupRelease release = CohortPrivacyPolicy.group(
                    others, others.getFirst().practices().size());

            Integer group = hasStanding(release.group());
            Integer a = hasStanding(release.practices().get(0));
            Integer b = hasStanding(release.practices().get(1));
            for (Integer practice : new Integer[] {a, b}) {
                if (group != null && practice != null) {
                    checked++;
                    assertSafe(group - practice, "cohort %d shows %d in the group but not a practice", cohort);
                }
            }
            if (group != null && a != null && b != null) {
                assertSafe(a + b - group, "cohort %d shows %d with a standing in both practices", cohort);
            }
        }
        // The invariant is only proven over cohorts that showed a group and a practice side by side.
        assertThat(checked).isGreaterThan(50);
    }

    private static void assertSafe(int cell, String description, int cohort) {
        assertThat(cell == 0 || cell >= CohortPrivacyPolicy.MINIMUM_OTHERS)
                .as(description, cohort, cell)
                .isTrue();
    }

    /**
     * A practice whose developers with a standing fall short of its group's by 1 to K - 1 is withheld: the group
     * less the practice would count those developers.
     */
    @Test
    @DisplayName("a practice whose split falls short of its group's by fewer than three is withheld")
    void shouldWithholdAPracticeThatFallsShortOfItsGroupByFewerThanThree() {
        List<Row> withAStanding = new ArrayList<>();
        for (Standing standing : developers(4, 4, 4, 6)) {
            withAStanding.add(new Row(standing, List.of(standing)));
        }
        // Two with a group standing from another practice, none yet in this one.
        withAStanding.add(new Row(STRENGTH, List.of(NOT_OBSERVED)));
        withAStanding.add(new Row(STRENGTH, List.of(NOT_OBSERVED)));

        GroupRelease release = CohortPrivacyPolicy.group(
                withAStanding, withAStanding.getFirst().practices().size());

        assertThat(release.group().shape()).isEqualTo(Shape.SPLIT);
        assertThat(release.practices()).containsExactly(totalOnly(20));
    }

    @Test
    @DisplayName("only a split that shows its parts and counts the reader marks the reader")
    void shouldMarkTheReaderOnlyWhenTheSplitShowsItsPartsAndCountsThem() {
        Split shown = CohortPrivacyPolicy.split(developers(4, 4, 4, 4));

        assertThat(CohortPrivacyPolicy.marker(shown, true, MIXED)).isEqualTo(MIXED);
        assertThat(CohortPrivacyPolicy.marker(shown, false, MIXED)).isNull();
        assertThat(CohortPrivacyPolicy.marker(totalOnly(16), true, MIXED)).isNull();
        assertThat(CohortPrivacyPolicy.marker(Split.WITHHELD, true, MIXED)).isNull();
    }

    @Test
    @DisplayName("a window nobody has a standing in withholds every practice it names")
    void shouldWithholdEveryPracticeWhenNobodyHasAStanding() {
        GroupRelease release = CohortPrivacyPolicy.group(List.of(), 2);

        assertThat(release.group()).isEqualTo(Split.WITHHELD);
        assertThat(release.practices()).containsExactly(Split.WITHHELD, Split.WITHHELD);
    }

    /** How many a split shows with a standing, or null when it shows no such count. */
    private static @Nullable Integer hasStanding(Split split) {
        return switch (split.shape()) {
            case SPLIT -> split.parts().stream().mapToInt(Part::developers).sum();
            case TOTAL_ONLY, WITHHELD -> null;
        };
    }

    private static Split totalOnly(int developers) {
        return new Split(Shape.TOTAL_ONLY, List.of(), null, developers);
    }

    /** The developers a split shows at one verdict. */
    private static int at(Split split, Standing verdict) {
        return split.parts().stream()
                .filter(part -> part.standing() == verdict)
                .mapToInt(Part::developers)
                .sum();
    }
}

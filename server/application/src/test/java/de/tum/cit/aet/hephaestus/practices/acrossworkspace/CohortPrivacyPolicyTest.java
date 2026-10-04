package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.DEVELOPING;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.MIXED;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.NOT_OBSERVED;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.NO_OPPORTUNITY;
import static de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing.STRENGTH;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Developer;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.GroupRelease;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.MiddleHalf;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.PageRelease;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Part;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Shape;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Split;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Standings;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Verdict;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO.Standing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CohortPrivacyPolicyTest {

    /** The fewest developers a shown or derivable count may hold, written out rather than read off the policy. */
    private static final int FOUR = 4;

    private static final List<Standing> VERDICTS = List.of(DEVELOPING, MIXED, STRENGTH);

    private static List<Standing> developers(int needs, int mixed, int well, int none) {
        List<Standing> standings = new ArrayList<>();
        standings.addAll(Collections.nCopies(needs, DEVELOPING));
        standings.addAll(Collections.nCopies(mixed, MIXED));
        standings.addAll(Collections.nCopies(well, STRENGTH));
        standings.addAll(Collections.nCopies(none, NOT_OBSERVED));
        return standings;
    }

    @Test
    @DisplayName("every part holding four shows the split, the verdicts in profile order")
    void shouldShowTheSplitWhenEveryPartHoldsFour() {
        Split split = CohortPrivacyPolicy.split(developers(4, 5, 6, 4));

        assertThat(split.shape()).isEqualTo(Shape.SPLIT);
        assertThat(split.parts())
                .containsExactly(
                        new Part(Verdict.DEVELOPING, 4), new Part(Verdict.MIXED, 5), new Part(Verdict.STRENGTH, 6));
        assertThat(split.noneYet()).isEqualTo(4);
        assertThat(split.developers()).isEqualTo(19);
    }

    @Test
    @DisplayName("a part of three, an empty part, or a none yet of three or nought holds back the whole split")
    void shouldShowOnlyTheTotalWhenAnyPartHoldsFewerThanFour() {
        assertThat(CohortPrivacyPolicy.split(developers(3, 7, 8, 6))).isEqualTo(totalOnly(24));
        assertThat(CohortPrivacyPolicy.split(developers(0, 9, 9, 6))).isEqualTo(totalOnly(24));
        assertThat(CohortPrivacyPolicy.split(developers(6, 9, 9, 3))).isEqualTo(totalOnly(27));
        // Everyone at a verdict: a none yet of nought would say that nobody in the workspace lacks one.
        assertThat(CohortPrivacyPolicy.split(developers(6, 6, 6, 0))).isEqualTo(totalOnly(18));
    }

    @Test
    @DisplayName("both standings that are no verdict count as none yet")
    void shouldCountBothSilencesAsNoneYet() {
        List<Standing> standings = new ArrayList<>(developers(4, 4, 4, 2));
        standings.addAll(Collections.nCopies(2, NO_OPPORTUNITY));

        assertThat(CohortPrivacyPolicy.split(standings).noneYet()).isEqualTo(4);
    }

    @Test
    @DisplayName("a split held back shows its total from four developers and nothing below")
    void shouldWithholdTheTotalWhenItHoldsThree() {
        assertThat(CohortPrivacyPolicy.split(developers(1, 1, 1, 1))).isEqualTo(totalOnly(4));
        assertThat(CohortPrivacyPolicy.split(developers(1, 1, 1, 0))).isEqualTo(Split.WITHHELD);
    }

    @Test
    @DisplayName("a total shows from four developers, whoever reads it")
    void shouldShowATotalFromFourDevelopers() {
        assertThat(CohortPrivacyPolicy.count(3)).isNull();
        assertThat(CohortPrivacyPolicy.count(4)).isEqualTo(4);
    }

    @Test
    @DisplayName("a middle half needs seven developers, whoever reads it")
    void shouldLeaveNoMiddleHalfBelowSevenDevelopers() {
        assertThat(CohortPrivacyPolicy.middleHalf(List.of(1, 2, 3, 4, 5, 6))).isNull();
        assertThat(CohortPrivacyPolicy.middleHalf(List.of(1, 2, 3, 4, 5, 6, 7))).isEqualTo(new MiddleHalf(3, 6));
    }

    @Test
    @DisplayName("the middle half is the quartiles of every value counted")
    void shouldBoundTheMiddleHalfByTheQuartiles() {
        List<Integer> values = IntStream.rangeClosed(1, 13).boxed().toList();

        assertThat(CohortPrivacyPolicy.middleHalf(values)).isEqualTo(new MiddleHalf(4, 10));
    }

    @Test
    @DisplayName("a quartile between two values is interpolated, not the value of the developer next to it")
    void shouldInterpolateAQuartileThatFallsBetweenTwoValues() {
        // The upper quartile falls halfway from 6 to 10: 8, where nobody stands.
        assertThat(CohortPrivacyPolicy.middleHalf(List.of(10, 0, 5, 0, 6, 0, 10, 2, 0, 10, 0)))
                .isEqualTo(new MiddleHalf(0, 8));
    }

    /**
     * Twelve judged on the group's practice as on the group, six with none yet, and a few whose group verdict comes
     * from another practice: the group less the practice counts exactly those few.
     */
    @Test
    @DisplayName("a practice whose developers fall short of its group's by three is held back, by four is shown")
    void shouldHoldBackAPracticeThatFallsShortOfItsGroupByThree() {
        assertThat(practiceShortOfItsGroupBy(3).practices()).containsExactly(totalOnly(21));
        assertThat(practiceShortOfItsGroupBy(4).practices().getFirst().shape()).isEqualTo(Shape.SPLIT);
    }

    private static GroupRelease practiceShortOfItsGroupBy(int developers) {
        List<Standings> withAStanding = new ArrayList<>();
        for (Standing standing : developers(4, 4, 4, 6)) {
            withAStanding.add(new Standings(standing, List.of(standing)));
        }
        for (int index = 0; index < developers; index++) {
            withAStanding.add(new Standings(STRENGTH, List.of(NOT_OBSERVED)));
        }
        return CohortPrivacyPolicy.group(withAStanding, 1);
    }

    /**
     * ADR 0051's case: fifteen judged only on A, fifteen only on B, four on neither and one, X, on both. Each split
     * shows on its own, but 16 + 16 - 31 = 1 has a standing in both, and the group's counts less A's and B's give
     * X's parts.
     */
    @Test
    @DisplayName("two practices of sixteen and a group of thirty one hold the practices back")
    void shouldHoldThePracticesBackWhenTheyAndTheGroupWouldSingleOutOneDeveloper() {
        List<Standings> withAStanding = new ArrayList<>();
        for (Standing standing : developers(5, 5, 5, 0)) {
            withAStanding.add(new Standings(standing, List.of(standing, NOT_OBSERVED)));
            withAStanding.add(new Standings(standing, List.of(NOT_OBSERVED, standing)));
        }
        for (int index = 0; index < 4; index++) {
            withAStanding.add(new Standings(NOT_OBSERVED, List.of(NOT_OBSERVED, NOT_OBSERVED)));
        }
        withAStanding.add(new Standings(MIXED, List.of(DEVELOPING, STRENGTH)));

        GroupRelease release = CohortPrivacyPolicy.group(withAStanding, 2);

        assertThat(release.group().parts())
                .containsExactly(
                        new Part(Verdict.DEVELOPING, 10), new Part(Verdict.MIXED, 11), new Part(Verdict.STRENGTH, 10));
        assertThat(release.practices()).containsExactly(totalOnly(35), totalOnly(35));
    }

    /**
     * Eleven with a standing only in A, one in both, eleven only in B: two 4/4/4 splits with none yet 11 over a total
     * of 23, so 12 + 12 - 23 names the one developer in both.
     */
    @Test
    @DisplayName("two group splits whose sizes add up to one more than the total hold back every split of the page")
    void shouldHoldBackEverySplitWhenTwoGroupsWouldSingleOutOneDeveloper() {
        List<Developer> withAStanding = new ArrayList<>();
        for (Standing standing : developers(4, 4, 3, 0)) {
            withAStanding.add(developer(standing, NOT_OBSERVED));
            withAStanding.add(developer(NOT_OBSERVED, standing));
        }
        withAStanding.add(developer(STRENGTH, STRENGTH));
        // On their own, both groups show all four parts.
        assertThat(CohortPrivacyPolicy.group(
                                withAStanding.stream()
                                        .map(developer -> developer.groups().getFirst())
                                        .toList(),
                                1)
                        .group()
                        .parts())
                .containsExactly(
                        new Part(Verdict.DEVELOPING, 4), new Part(Verdict.MIXED, 4), new Part(Verdict.STRENGTH, 4));

        PageRelease release = CohortPrivacyPolicy.page(withAStanding, List.of(1, 1));

        assertThat(release.developersWithAStanding()).isEqualTo(23);
        assertThat(release.groups())
                .containsExactly(
                        new GroupRelease(totalOnly(23), List.of(totalOnly(23))),
                        new GroupRelease(totalOnly(23), List.of(totalOnly(23))));
    }

    /**
     * The same eleven, one and eleven, with each group's verdicts spread so thinly that its own split is held back,
     * while each group's one practice shows all four parts: the practices give the groups' sizes all the same.
     */
    @Test
    @DisplayName("two one-practice groups whose practice splits name one developer in both hold back every split")
    void shouldHoldBackEverySplitWhenTwoOnePracticeGroupsWouldSingleOutOneDeveloper() {
        List<Developer> withAStanding = new ArrayList<>();
        for (Standing standing : developers(4, 4, 3, 0)) {
            withAStanding.add(new Developer(List.of(
                    new Standings(STRENGTH, List.of(standing)), new Standings(NOT_OBSERVED, List.of(NOT_OBSERVED)))));
            withAStanding.add(new Developer(List.of(
                    new Standings(NOT_OBSERVED, List.of(NOT_OBSERVED)), new Standings(STRENGTH, List.of(standing)))));
        }
        withAStanding.add(developer(STRENGTH, STRENGTH));

        PageRelease release = CohortPrivacyPolicy.page(withAStanding, List.of(1, 1));

        assertThat(release.groups())
                .containsExactly(
                        new GroupRelease(totalOnly(23), List.of(totalOnly(23))),
                        new GroupRelease(totalOnly(23), List.of(totalOnly(23))));
    }

    @Test
    @DisplayName("a window nobody has a standing in withholds every split it names")
    void shouldWithholdEverySplitWhenNobodyHasAStanding() {
        PageRelease release = CohortPrivacyPolicy.page(List.of(), List.of(2));

        assertThat(release.developersWithAStanding()).isNull();
        assertThat(release.groups())
                .containsExactly(new GroupRelease(Split.WITHHELD, List.of(Split.WITHHELD, Split.WITHHELD)));
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

    /**
     * Random cohorts built developer by developer, released through {@link CohortPrivacyPolicy#page}, then checked
     * against cells counted from the developers themselves: every count shown, and every difference of shown counts
     * a reader can take, holds none or at least four, and moving any developer to the front, as a reader, changes
     * nothing that is released.
     */
    @Test
    @DisplayName("no random cohort releases or lets a reader derive a count of one to three developers")
    void shouldNeverReleaseOrLetAReaderDeriveACountOfOneToThree() {
        Random random = new Random(51);
        Checks checks = new Checks();
        for (int cohort = 0; cohort < 3000; cohort++) {
            List<Integer> practicesPerGroup = IntStream.range(0, 2 + random.nextInt(2))
                    .mapToObj(group -> 1 + random.nextInt(3))
                    .toList();
            List<Developer> withAStanding = randomCohort(random, practicesPerGroup);

            PageRelease release = CohortPrivacyPolicy.page(withAStanding, practicesPerGroup);

            checkAgainstTheDevelopers(cohort, withAStanding, practicesPerGroup, release, checks);
            List<Developer> readerFirst = new ArrayList<>(withAStanding);
            Collections.rotate(readerFirst, -random.nextInt(Math.max(1, withAStanding.size())));
            assertThat(CohortPrivacyPolicy.page(readerFirst, practicesPerGroup)).isEqualTo(release);
        }
        // Each rule is proven only over cohorts that showed the splits it compares.
        assertThat(checks.groupLessPractice).isGreaterThan(100);
        assertThat(checks.practiceOverlap).isGreaterThan(50);
        assertThat(checks.groupOverlap).isGreaterThan(50);
        assertThat(checks.twoGroupIntersection).isGreaterThan(20);
    }

    private static final class Checks {
        int groupLessPractice;
        int practiceOverlap;
        int groupOverlap;
        int twoGroupIntersection;
    }

    private static void checkAgainstTheDevelopers(
            int cohort,
            List<Developer> developers,
            List<Integer> practicesPerGroup,
            PageRelease release,
            Checks checks) {
        int total = developers.size();
        assertThat(release.developersWithAStanding())
                .as("cohort %d total", cohort)
                .isEqualTo(total >= FOUR ? total : null);
        List<Integer> shownGroupSizes = new ArrayList<>();
        for (int group = 0; group < practicesPerGroup.size(); group++) {
            int g = group;
            GroupRelease released = release.groups().get(group);
            Predicate<Developer> inGroup =
                    developer -> isVerdict(developer.groups().get(g).group());
            checkSplit(
                    cohort,
                    released.group(),
                    developers,
                    developer -> developer.groups().get(g).group());
            int groupSize = count(developers, inGroup);
            // A group verdict exists exactly where a verdict on one of its practices does, so a group with one
            // practice shows its size through that practice's split too.
            if (released.group().shape() == Shape.SPLIT
                    || (practicesPerGroup.get(group) == 1
                            && released.practices().getFirst().shape() == Shape.SPLIT)) {
                shownGroupSizes.add(groupSize);
            }
            int shownPracticeSizes = 0;
            int shownPractices = 0;
            for (int practice = 0; practice < practicesPerGroup.get(group); practice++) {
                int p = practice;
                Split split = released.practices().get(practice);
                checkSplit(
                        cohort,
                        split,
                        developers,
                        developer -> developer.groups().get(g).practices().get(p));
                Predicate<Developer> inPractice = developer ->
                        isVerdict(developer.groups().get(g).practices().get(p));
                if (released.group().shape() == Shape.SPLIT && split.shape() == Shape.SPLIT) {
                    checks.groupLessPractice++;
                    assertCell(
                            cohort,
                            "in the group, not the practice",
                            count(developers, inGroup.and(inPractice.negate())));
                    shownPracticeSizes += count(developers, inPractice);
                    shownPractices++;
                }
            }
            if (shownPractices > 1) {
                checks.practiceOverlap++;
                assertCell(cohort, "the shown practices beyond the group", shownPracticeSizes - groupSize);
            }
        }
        if (shownGroupSizes.size() > 1) {
            checks.groupOverlap++;
            int sum = shownGroupSizes.stream().mapToInt(Integer::intValue).sum();
            assertCell(cohort, "the groups of known size beyond the total", sum - total);
            if (practicesPerGroup.size() == 2) {
                checks.twoGroupIntersection++;
                assertCell(
                        cohort,
                        "in both groups",
                        count(
                                developers,
                                developer -> isVerdict(developer.groups().get(0).group())
                                        && isVerdict(developer.groups().get(1).group())));
            }
        }
    }

    /** A split shown as SPLIT names exactly the developers at each verdict, each part and none yet holding four. */
    private static void checkSplit(
            int cohort, Split split, List<Developer> developers, Function<Developer, Standing> standingOf) {
        if (split.shape() != Shape.SPLIT) {
            assertThat(split.parts()).isEmpty();
            assertThat(split.noneYet()).isNull();
            return;
        }
        for (Part part : split.parts()) {
            int truth = count(developers, developer -> Verdict.of(standingOf.apply(developer)) == part.standing());
            assertThat(part.developers()).isEqualTo(truth);
            assertThat(truth).as("cohort %d part %s", cohort, part.standing()).isGreaterThanOrEqualTo(FOUR);
        }
        int none = count(developers, developer -> !isVerdict(standingOf.apply(developer)));
        assertThat(split.noneYet()).isEqualTo(none);
        assertThat(none).as("cohort %d none yet", cohort).isGreaterThanOrEqualTo(FOUR);
    }

    private static void assertCell(int cohort, String cell, int developers) {
        assertThat(developers == 0 || Math.abs(developers) >= FOUR)
                .as("cohort %d derives %d %s", cohort, developers, cell)
                .isTrue();
    }

    private static int count(List<Developer> developers, Predicate<Developer> which) {
        return (int) developers.stream().filter(which).count();
    }

    /**
     * Developers with a verdict on each practice or none, and a group verdict exactly when they have a verdict on one
     * of its practices, as the group standing is read; those with no group verdict at all have no standing.
     */
    private static List<Developer> randomCohort(Random random, List<Integer> practicesPerGroup) {
        List<Developer> cohort = new ArrayList<>();
        int size = 12 + random.nextInt(40);
        for (int index = 0; index < size; index++) {
            List<Standings> groups = new ArrayList<>();
            for (int practices : practicesPerGroup) {
                List<Standing> standings = new ArrayList<>();
                for (int practice = 0; practice < practices; practice++) {
                    standings.add(random.nextInt(5) < 2 ? NOT_OBSERVED : VERDICTS.get(random.nextInt(3)));
                }
                boolean judged = standings.stream().anyMatch(CohortPrivacyPolicyTest::isVerdict);
                groups.add(new Standings(judged ? VERDICTS.get(random.nextInt(3)) : NOT_OBSERVED, standings));
            }
            if (groups.stream().anyMatch(group -> isVerdict(group.group()))) {
                cohort.add(new Developer(groups));
            }
        }
        return cohort;
    }

    private static boolean isVerdict(Standing standing) {
        return Verdict.of(standing) != null;
    }

    private static Developer developer(Standing a, Standing b) {
        return new Developer(List.of(new Standings(a, List.of(a)), new Standings(b, List.of(b))));
    }

    private static Split totalOnly(int developers) {
        return new Split(Shape.TOTAL_ONLY, List.of(), null, developers);
    }
}

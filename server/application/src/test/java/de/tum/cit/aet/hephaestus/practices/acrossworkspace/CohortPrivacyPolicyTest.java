package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Bucket.GOING_WELL;
import static de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Bucket.MIXED_FEEDBACK;
import static de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Bucket.NEEDS_ATTENTION;
import static de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Bucket.NONE_YET;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Bucket;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.GroupRelease;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.MiddleHalf;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Row;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Shape;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Split;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CohortPrivacyPolicyTest {

    private static List<Bucket> developers(int needs, int mixed, int well, int none) {
        List<Bucket> buckets = new ArrayList<>();
        buckets.addAll(Collections.nCopies(needs, NEEDS_ATTENTION));
        buckets.addAll(Collections.nCopies(mixed, MIXED_FEEDBACK));
        buckets.addAll(Collections.nCopies(well, GOING_WELL));
        buckets.addAll(Collections.nCopies(none, NONE_YET));
        return buckets;
    }

    private static Split split(List<Bucket> observed) {
        return CohortPrivacyPolicy.split(observed);
    }

    @Test
    @DisplayName("a group standing and a practice standing fall in the same bucket, both silences in none yet")
    void shouldPutAGroupAndAPracticeStandingInTheSameBucket() {
        for (PracticeStandingDTO.Standing standing : PracticeStandingDTO.Standing.values()) {
            assertThat(Bucket.of(standing))
                    .isEqualTo(Bucket.of(PracticeGroupStandingDTO.Standing.valueOf(standing.name())));
        }
        assertThat(Bucket.of(PracticeStandingDTO.Standing.NO_OPPORTUNITY)).isEqualTo(NONE_YET);
        assertThat(Bucket.of(PracticeGroupStandingDTO.Standing.NOT_OBSERVED)).isEqualTo(NONE_YET);
    }

    @Test
    @DisplayName("every part holding four observed developers shows the split")
    void shouldShowTheSplitWhenEveryPartHoldsFourDevelopers() {
        Split split = split(developers(4, 5, 6, 4));

        assertThat(split.shape()).isEqualTo(Shape.SPLIT);
        assertThat(split.needsAttention()).isEqualTo(4);
        assertThat(split.mixedFeedback()).isEqualTo(5);
        assertThat(split.goingWell()).isEqualTo(6);
        assertThat(split.noneYet()).isEqualTo(4);
    }

    /** Three in a part are three others to a reader outside it and two to a reader inside: every reader is held back. */
    @Test
    @DisplayName("a part of three withholds the whole split for every reader, inside it or not")
    void shouldWithholdTheSplitWhenAStandingHoldsThree() {
        assertThat(split(developers(3, 7, 8, 6))).isEqualTo(Split.WITHHELD);
    }

    @Test
    @DisplayName("an empty part withholds the split")
    void shouldWithholdTheSplitWhenAPartIsEmpty() {
        assertThat(split(developers(0, 9, 9, 6))).isEqualTo(Split.WITHHELD);
    }

    @Test
    @DisplayName("three without a standing withhold the split")
    void shouldWithholdTheSplitWhenNoneYetHoldsThree() {
        assertThat(split(developers(6, 9, 9, 3))).isEqualTo(Split.WITHHELD);
    }

    @Test
    @DisplayName("everyone at a standing is a none yet of zero and withholds the split")
    void shouldWithholdTheSplitWhenEveryoneHasAStanding() {
        assertThat(split(developers(6, 6, 6, 0))).isEqualTo(Split.WITHHELD);
    }

    @Test
    @DisplayName("the middle half is the quartiles of every observed value")
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
    @DisplayName("fewer than six others observed leaves no middle half")
    void shouldLeaveNoMiddleHalfWhenFewerThanSixOthersAreObserved() {
        List<Integer> values = IntStream.rangeClosed(1, 7).boxed().toList();

        assertThat(CohortPrivacyPolicy.middleHalf(values, 5)).isNull();
        assertThat(CohortPrivacyPolicy.middleHalf(values, 6)).isNotNull();
    }

    @Test
    @DisplayName("the observed total shows from three others, the reader counted in it")
    void shouldShowTheObservedTotalOnlyWhileItHoldsThreeOthers() {
        assertThat(CohortPrivacyPolicy.observedTotal(17, true)).isEqualTo(18);
        assertThat(CohortPrivacyPolicy.observedTotal(3, false)).isEqualTo(3);
        assertThat(CohortPrivacyPolicy.observedTotal(2, true)).isNull();
    }

    @Test
    @DisplayName("practices that move with their group show beside it")
    void shouldShowPracticesThatMoveWithTheirGroup() {
        List<Row> others = new ArrayList<>();
        for (Bucket bucket : developers(4, 4, 5, 4)) {
            others.add(new Row(bucket, List.of(bucket, bucket)));
        }

        GroupRelease release =
                CohortPrivacyPolicy.group(others, others.getFirst().practices().size());

        assertThat(release.group().shape()).isEqualTo(Shape.SPLIT);
        assertThat(release.practices()).extracting(Split::shape).containsExactly(Shape.SPLIT, Shape.SPLIT);
        assertThat(release.practices().getFirst().goingWell()).isEqualTo(5);
    }

    /**
     * The review's case: twelve judged only on A, twelve only on B, four on neither and one, X, on both. Each
     * split holds four in every part on its own, and the group less either practice leaves twelve, but A and B
     * against the group would name X: 13 + 13 - 25 = 1 with a standing in both, and the group's counts less A's and
     * B's give X's buckets.
     */
    @Test
    @DisplayName("a developer whom the group and two practices single out together holds the practices back")
    void shouldHoldThePracticesBackWhenTheyAndTheGroupWouldSingleOutOneDeveloper() {
        List<Row> others = new ArrayList<>();
        for (Bucket bucket : developers(4, 4, 4, 0)) {
            others.add(new Row(bucket, List.of(bucket, NONE_YET)));
            others.add(new Row(bucket, List.of(NONE_YET, bucket)));
        }
        for (int index = 0; index < 4; index++) {
            others.add(new Row(NONE_YET, List.of(NONE_YET, NONE_YET)));
        }
        others.add(new Row(MIXED_FEEDBACK, List.of(NEEDS_ATTENTION, GOING_WELL)));
        // On their own, each of the three splits holds three others in every part.
        assertThat(split(others.stream().map(row -> row.practices().get(0)).toList())
                        .shape())
                .isEqualTo(Shape.SPLIT);
        assertThat(split(others.stream().map(row -> row.practices().get(1)).toList())
                        .shape())
                .isEqualTo(Shape.SPLIT);

        GroupRelease release =
                CohortPrivacyPolicy.group(others, others.getFirst().practices().size());

        assertThat(release.group().shape()).isEqualTo(Shape.SPLIT);
        assertThat(release.group().mixedFeedback()).isEqualTo(9);
        assertThat(release.practices()).extracting(Split::shape).containsExactly(Shape.WITHHELD, Shape.WITHHELD);
    }

    /**
     * Whatever the cohort, nothing the page shows lets a reader work out how many others have a standing in both
     * practices of a group but a number that is none or at least K: those with a standing in A, plus in B, less
     * in the group, which counts everyone with a standing in either.
     */
    @Test
    @DisplayName("no cohort lets the group and its practices name 1 to K - 1 developers with a standing in both")
    void shouldNeverLetTheGroupAndItsPracticesNameFewerThanKOthersInBoth() {
        Random random = new Random(51);
        Bucket[] buckets = Bucket.values();
        for (int cohort = 0; cohort < 2000; cohort++) {
            List<Row> others = new ArrayList<>();
            int size = 8 + random.nextInt(30);
            for (int index = 0; index < size; index++) {
                Bucket a = buckets[random.nextInt(buckets.length)];
                Bucket b = buckets[random.nextInt(buckets.length)];
                others.add(new Row(a != NONE_YET ? a : b, List.of(a, b)));
            }

            GroupRelease release = CohortPrivacyPolicy.group(
                    others, others.getFirst().practices().size());

            Integer group = hasStanding(release.group());
            Integer a = hasStanding(release.practices().get(0));
            Integer b = hasStanding(release.practices().get(1));
            if (group != null && a != null && b != null) {
                int both = a + b - group;
                assertThat(both == 0 || both >= CohortPrivacyPolicy.MINIMUM_OTHERS)
                        .as("cohort %d shows %d others with a standing in both", cohort, both)
                        .isTrue();
            }
        }
    }

    /**
     * A practice whose developers with a standing fall short of its group's by 1 to K - 1 is withheld: the group
     * less the practice would count those developers.
     */
    @Test
    @DisplayName("a practice whose split falls short of its group's by fewer than three is withheld")
    void shouldWithholdAPracticeThatFallsShortOfItsGroupByFewerThanThree() {
        List<Row> observed = new ArrayList<>();
        for (Bucket bucket : developers(4, 4, 4, 6)) {
            observed.add(new Row(bucket, List.of(bucket)));
        }
        // Two with a group standing from another practice, none yet in this one.
        observed.add(new Row(GOING_WELL, List.of(NONE_YET)));
        observed.add(new Row(GOING_WELL, List.of(NONE_YET)));

        GroupRelease release = CohortPrivacyPolicy.group(
                observed, observed.getFirst().practices().size());

        assertThat(release.group().shape()).isEqualTo(Shape.SPLIT);
        assertThat(release.practices()).extracting(Split::shape).containsExactly(Shape.WITHHELD);
    }

    @Test
    @DisplayName("a window nobody was observed in withholds every practice it names")
    void shouldWithholdEveryPracticeWhenNobodyIsObserved() {
        GroupRelease release = CohortPrivacyPolicy.group(List.of(), 2);

        assertThat(release.group()).isEqualTo(Split.WITHHELD);
        assertThat(release.practices()).containsExactly(Split.WITHHELD, Split.WITHHELD);
    }

    /** How many a split shows with a standing, or null when it shows no such count. */
    private static @Nullable Integer hasStanding(Split split) {
        return switch (split.shape()) {
            case SPLIT ->
                Objects.requireNonNull(split.needsAttention())
                        + Objects.requireNonNull(split.mixedFeedback())
                        + Objects.requireNonNull(split.goingWell());
            case WITHHELD -> null;
        };
    }
}

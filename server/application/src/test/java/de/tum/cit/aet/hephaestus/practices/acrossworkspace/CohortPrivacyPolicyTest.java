package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO.Standing.DEVELOPING;
import static de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO.Standing.MIXED;
import static de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO.Standing.NOT_OBSERVED;
import static de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO.Standing.NO_OPPORTUNITY;
import static de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO.Standing.STRENGTH;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.MiddleHalf;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Shape;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Split;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO.Standing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
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
        for (int index = 0; index < none; index++) {
            standings.add(index % 2 == 0 ? NOT_OBSERVED : NO_OPPORTUNITY);
        }
        return standings;
    }

    @Test
    @DisplayName("every bucket with five others shows the split, the reader counted in their own bucket")
    void shouldShowTheSplitWithTheReaderInTheirBucketWhenEveryBucketHoldsFiveOthers() {
        Split split = CohortPrivacyPolicy.split(developers(5, 6, 7, 3), MIXED);

        assertThat(split.shape()).isEqualTo(Shape.SPLIT);
        assertThat(split.needsAttention()).isEqualTo(5);
        assertThat(split.mixedFeedback()).isEqualTo(7);
        assertThat(split.goingWell()).isEqualTo(7);
        assertThat(split.hasStanding()).isNull();
        assertThat(split.noneYet()).isNull();
    }

    @Test
    @DisplayName("the reader does not count towards the five: four others and the reader collapse the split")
    void shouldCollapseWhenABucketHoldsFiveOnlyWithTheReader() {
        Split split = CohortPrivacyPolicy.split(developers(4, 6, 7, 5), DEVELOPING);

        assertThat(split.shape()).isEqualTo(Shape.COLLAPSED);
        assertThat(split.needsAttention()).isNull();
        assertThat(split.hasStanding()).isEqualTo(18);
        assertThat(split.noneYet()).isEqualTo(5);
    }

    @Test
    @DisplayName("a reader without a group standing counts among none yet in the collapsed split")
    void shouldCountTheReaderAmongNoneYetWhenTheyHaveNoGroupStanding() {
        Split split = CohortPrivacyPolicy.split(developers(2, 6, 7, 5), NOT_OBSERVED);

        assertThat(split.shape()).isEqualTo(Shape.COLLAPSED);
        assertThat(split.hasStanding()).isEqualTo(15);
        assertThat(split.noneYet()).isEqualTo(6);
    }

    @Test
    @DisplayName("an empty bucket is a bucket of fewer than five and collapses the split")
    void shouldCollapseWhenABucketIsEmpty() {
        assertThat(CohortPrivacyPolicy.split(developers(0, 9, 9, 6), null).shape())
                .isEqualTo(Shape.COLLAPSED);
    }

    @Test
    @DisplayName("too few others without a standing withholds even the collapsed split")
    void shouldWithholdWhenEvenTheCollapsedSplitWouldCoverTooFew() {
        Split split = CohortPrivacyPolicy.split(developers(3, 9, 9, 4), STRENGTH);

        assertThat(split.shape()).isEqualTo(Shape.WITHHELD);
        assertThat(split.hasStanding()).isNull();
        assertThat(split.noneYet()).isNull();
    }

    @Test
    @DisplayName("a reader outside the observed developers adds to no count")
    void shouldAddNothingForAReaderWhoIsNotCounted() {
        Split split = CohortPrivacyPolicy.split(developers(5, 5, 5, 0), null);

        assertThat(split.shape()).isEqualTo(Shape.SPLIT);
        assertThat(split.needsAttention()).isEqualTo(5);
        assertThat(split.mixedFeedback()).isEqualTo(5);
        assertThat(split.goingWell()).isEqualTo(5);
    }

    @Test
    @DisplayName("the middle half is the quartiles of every observed value")
    void shouldBoundTheMiddleHalfByTheQuartiles() {
        List<Integer> values = IntStream.rangeClosed(1, 9).boxed().toList();

        assertThat(CohortPrivacyPolicy.middleHalf(values, 8)).isEqualTo(new MiddleHalf(3, 7));
    }

    @Test
    @DisplayName("fewer than five others observed leaves no middle half")
    void shouldLeaveNoMiddleHalfWhenFewerThanFiveOthersAreObserved() {
        assertThat(CohortPrivacyPolicy.middleHalf(List.of(1, 2, 3, 4, 5), 4)).isNull();
        assertThat(CohortPrivacyPolicy.middleHalf(List.of(1, 2, 3, 4, 5), 5)).isNotNull();
    }
}

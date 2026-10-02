package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Bucket.GOING_WELL;
import static de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Bucket.MIXED_FEEDBACK;
import static de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Bucket.NEEDS_ATTENTION;
import static de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Bucket.NONE_YET;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Bucket;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.MiddleHalf;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Shape;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy.Split;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
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
    @DisplayName("every bucket with five others shows the split, the reader counted in their own bucket")
    void shouldShowTheSplitWithTheReaderInTheirBucketWhenEveryBucketHoldsFiveOthers() {
        Split split = CohortPrivacyPolicy.split(developers(5, 6, 7, 5), MIXED_FEEDBACK);

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
        Split split = CohortPrivacyPolicy.split(developers(4, 6, 7, 5), NEEDS_ATTENTION);

        assertThat(split.shape()).isEqualTo(Shape.COLLAPSED);
        assertThat(split.needsAttention()).isNull();
        assertThat(split.hasStanding()).isEqualTo(18);
        assertThat(split.noneYet()).isEqualTo(5);
    }

    @Test
    @DisplayName("a reader without a group standing counts among none yet in the collapsed split")
    void shouldCountTheReaderAmongNoneYetWhenTheyHaveNoGroupStanding() {
        Split split = CohortPrivacyPolicy.split(developers(2, 6, 7, 5), NONE_YET);

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
        Split split = CohortPrivacyPolicy.split(developers(3, 9, 9, 4), GOING_WELL);

        assertThat(split.shape()).isEqualTo(Shape.WITHHELD);
        assertThat(split.hasStanding()).isNull();
        assertThat(split.noneYet()).isNull();
    }

    @Test
    @DisplayName("one other without a standing withholds a full split: the observed total would name them")
    void shouldWithholdAFullSplitWhenNoneYetHoldsFewerThanFiveOthers() {
        Split split = CohortPrivacyPolicy.split(developers(7, 8, 7, 1), MIXED_FEEDBACK);

        assertThat(split).isEqualTo(Split.WITHHELD);
    }

    @Test
    @DisplayName("every other at a standing is a none yet of zero and withholds the split")
    void shouldWithholdTheSplitWhenEveryOtherHasAStanding() {
        assertThat(CohortPrivacyPolicy.split(developers(6, 6, 6, 0), GOING_WELL))
                .isEqualTo(Split.WITHHELD);
    }

    @Test
    @DisplayName("fewer than five others with a standing withholds the split however many have none")
    void shouldWithholdTheSplitWhenHasAStandingHoldsFewerThanFiveOthers() {
        assertThat(CohortPrivacyPolicy.split(developers(1, 1, 2, 20), NEEDS_ATTENTION))
                .isEqualTo(Split.WITHHELD);
    }

    @Test
    @DisplayName("a reader outside the observed developers adds to no count")
    void shouldAddNothingForAReaderWhoIsNotCounted() {
        Split split = CohortPrivacyPolicy.split(developers(5, 5, 5, 5), null);

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

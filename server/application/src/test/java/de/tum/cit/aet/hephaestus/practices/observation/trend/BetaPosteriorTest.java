package de.tum.cit.aet.hephaestus.practices.observation.trend;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class BetaPosteriorTest {

    @Test
    void shouldExposeKnownBetaMoments() {
        BetaPosterior posterior = BetaPosterior.from(4, 3.0);

        assertThat(posterior.mean()).isEqualTo(3.5 / 5.0);
        assertThat(posterior.variance()).isEqualTo((3.5 * 1.5) / (25.0 * 6.0));
    }

    @Test
    void shouldClaimNoDirectionForEquivalentBundles() {
        BetaPosterior.Difference difference = BetaPosterior.from(80, 40).differenceFrom(BetaPosterior.from(80, 40));

        assertThat(TrendDirectionRule.classify(difference, 0.15, 0.90)).isEqualTo(TrendDirection.UNCERTAIN);
    }

    @Test
    void shouldClassifyClearlySeparatedBundlesAsImproving() {
        BetaPosterior.Difference difference = BetaPosterior.from(12, 11).differenceFrom(BetaPosterior.from(12, 1));

        assertThat(TrendDirectionRule.classify(difference, 0.15, 0.90)).isEqualTo(TrendDirection.IMPROVING);
    }

    @Test
    void shouldKeepClearLookingSmallSampleSplitUncertain() {
        BetaPosterior.Difference difference = BetaPosterior.from(1, 1).differenceFrom(BetaPosterior.from(1, 0));

        assertThat(TrendDirectionRule.classify(difference, 0.15, 0.90)).isEqualTo(TrendDirection.UNCERTAIN);
    }

    @Test
    void shouldDeriveDifferenceMomentsFromTheDeterministicGrid() {
        BetaPosterior.Difference difference = BetaPosterior.from(1, 1).differenceFrom(BetaPosterior.from(1, 0), 2);

        assertThat(difference.mean()).isCloseTo(0.25, Offset.offset(1.0e-12));
        assertThat(difference.variance()).isCloseTo(0.09375, Offset.offset(1.0e-12));
    }

    @Test
    void shouldMatchTheClosedFormForASymmetricDifference() {
        BetaPosterior.Difference difference = BetaPosterior.from(9, 6.0).differenceFrom(BetaPosterior.from(9, 6.0));

        assertThat(difference.mean()).isCloseTo(0.0, Offset.offset(1.0e-12));
        assertThat(difference.probabilityAbove(0.0))
                .isCloseTo(difference.probabilityBelow(0.0), Offset.offset(1.0e-12));
        assertThat(difference.probabilityAbove(0.15))
                .isCloseTo(difference.probabilityBelow(-0.15), Offset.offset(1.0e-12));
        double tails = difference.probabilityAbove(0.15) + difference.probabilityBelow(-0.15);
        assertThat(tails).isBetween(0.0, 1.0);
        assertThat(1.0 - tails).isGreaterThan(0.0);
        assertThat(1.0 - difference.probabilityAbove(0.0) - difference.probabilityBelow(0.0))
                .isLessThan(0.01);
    }

    @Test
    void shouldConvergeAtTheSpecifiedGridSize() {
        BetaPosterior current = BetaPosterior.from(7, 5.5);
        BetaPosterior previous = BetaPosterior.from(7, 2.5);

        double at256 = current.differenceFrom(previous, 256).probabilityAbove(0.15);
        double at1024 = current.differenceFrom(previous, 1024).probabilityAbove(0.15);

        assertThat(at256).isCloseTo(at1024, Offset.offset(1.0e-3));
    }
}

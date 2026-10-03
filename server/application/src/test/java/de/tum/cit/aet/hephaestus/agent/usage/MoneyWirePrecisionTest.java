package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.UpdateLlmModelPriceRequestDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/**
 * Money leaves this server as an exact decimal ({@code BigDecimal}, {@code NUMERIC} column) and lands
 * in the browser as a binary64 {@code number}, since JavaScript has no decimal type — so every value we
 * can emit must round-trip through that conversion losslessly. Each test below takes its bound from the
 * code that enforces it (the ledger's cost clamp, the cap request validator, the mapped column scales),
 * not from an invented literal, so the round-trip is a claim about this system rather than about binary64.
 */
class MoneyWirePrecisionTest extends BaseUnitTest {

    private static final int SIGNIFICANT_DIGITS = 15;

    /**
     * Kills widening {@code MAX_COST} to the column's real maximum: {@code 999999999999.999999} is
     * eighteen significant digits and comes back from the browser as a different number.
     */
    @Test
    void everyCostTheLedgerWillStoreSurvivesTheWire() {
        BigDecimal largest = clampedCost(new BigDecimal("1000000000"), 1_000_000_000L);
        BigDecimal smallest = clampedCost(new BigDecimal("0.00000001"), 1L);

        assertThat(largest)
                .as("the maximum clamp must be reached by this input")
                .isGreaterThan(BigDecimal.ONE);
        assertThat(smallest)
                .as("the minimum clamp must be reached by this input")
                .isLessThan(BigDecimal.ONE);
        assertRoundTrips(largest);
        assertRoundTrips(smallest);

        // The cliff is real and measured, not assumed: one integer digit past the ceiling is where the
        // shortest decimal that reproduces the double stops being the decimal we sent.
        BigDecimal oneDigitTooWide = largestAt(largest.precision() + 1, largest.scale());
        assertThat(asBrowserWouldSee(oneDigitTooWide)).isNotEqualByComparingTo(oneDigitTooWide);
    }

    /**
     * The cap is the one money field a human types: its request validator and its column must agree,
     * or a typo becomes a database error instead of a 400, or an accepted cap renders differently in
     * the browser.
     *
     * <p>Kills {@code @Digits(integer = 9)} on the request (past the column) and
     * {@code precision = 9} on the column (under the request).
     */
    @Test
    void theBudgetCapFitsBothItsValidatorAndItsColumn() throws NoSuchMethodException, NoSuchFieldException {
        Digits accepted =
                UpdateLlmBudgetRequestDTO.class.getMethod("monthlyBudgetUsd").getAnnotation(Digits.class);
        Column stored = Workspace.class.getDeclaredField("monthlyLlmBudgetUsd").getAnnotation(Column.class);

        assertThat(accepted)
                .as("the cap's width must stay declared, or nothing bounds it")
                .isNotNull();
        assertThat(accepted.integer() + accepted.fraction()).isLessThanOrEqualTo(SIGNIFICANT_DIGITS);
        assertThat(accepted.fraction()).isLessThanOrEqualTo(stored.scale());
        assertThat(accepted.integer() + accepted.fraction()).isLessThanOrEqualTo(stored.precision());

        // Only the column's own width is put through the trip: the request's width is already bounded
        // by SIGNIFICANT_DIGITS two lines above, so round-tripping it would restate DBL_DIG rather than
        // anything this system declares. Nothing bounds the COLUMN, so widening it to NUMERIC(20,6)
        // fails here and nowhere else.
        assertRoundTrips(largestAt(stored.precision(), stored.scale()));
    }

    /**
     * A frozen rate is copied from the catalog onto every ledger row it prices, so the two columns are
     * one number in two places: a narrower ledger column would silently truncate the rate an event was
     * actually billed at.
     *
     * <p>Kills changing either column's {@code scale} without the other.
     */
    @Test
    void aFrozenRateIsTheSameNumberInTheCatalogAndTheLedger() throws NoSuchFieldException, NoSuchMethodException {
        Column catalog = LlmModelPrice.class.getDeclaredField("per1mInputUsd").getAnnotation(Column.class);
        Column ledger =
                LlmUsageEvent.class.getDeclaredField("appliedPer1mInputUsd").getAnnotation(Column.class);

        assertThat(ledger.scale()).isEqualTo(catalog.scale());
        assertThat(ledger.precision()).isGreaterThanOrEqualTo(catalog.precision());

        // The column is NUMERIC(18,8), wider than the wire; what actually bounds a rate is the request
        // validator. Taking the width from that annotation rather than from a literal is what makes the
        // round-trip a statement about this system: widening it to @Digits(integer = 8) fails here.
        Digits acceptedRate =
                UpdateLlmModelPriceRequestDTO.class.getMethod("per1mInputUsd").getAnnotation(Digits.class);
        assertThat(acceptedRate)
                .as("a rate's width must stay declared, or nothing bounds it")
                .isNotNull();
        assertThat(acceptedRate.fraction())
                .as("the accepted decimals and the stored decimals must be the same number of places")
                .isEqualTo(catalog.scale());
        assertThat(acceptedRate.integer() + acceptedRate.fraction()).isLessThanOrEqualTo(SIGNIFICANT_DIGITS);
        assertRoundTrips(largestAt(acceptedRate.integer() + acceptedRate.fraction(), acceptedRate.fraction()));
    }

    private static BigDecimal clampedCost(BigDecimal per1mInputUsd, long inputTokens) {
        LlmPriceSnapshot price = new LlmPriceSnapshot(
                FundingSource.INSTANCE,
                PricingState.PRICED,
                1L,
                null,
                per1mInputUsd,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        LlmPriceSnapshot.Cost cost = price.calculateCost(inputTokens, 0, 0, 0);
        assertThat(cost.clamp())
                .as("this input must reach a clamp, or the test is asserting nothing")
                .isNotNull();
        BigDecimal usd = cost.usd();
        assertThat(usd).isNotNull();
        return usd;
    }

    /** The largest value {@code NUMERIC(precision, scale)} holds, e.g. {@code 99999999.99}. */
    private static BigDecimal largestAt(int precision, int scale) {
        return BigDecimal.ONE
                .movePointRight(precision - scale)
                .subtract(BigDecimal.ONE.movePointLeft(scale))
                .setScale(scale, RoundingMode.UNNECESSARY);
    }

    private static void assertRoundTrips(BigDecimal amount) {
        assertThat(asBrowserWouldSee(amount))
                .as("%s must survive JSON number -> binary64 -> shortest decimal", amount.toPlainString())
                .isEqualByComparingTo(amount);
    }

    /**
     * The trip a money field actually makes: the text Jackson emits for the number, parsed into a
     * binary64, then rendered back the way a JS runtime renders one. {@code Double.toString} has produced
     * the shortest round-tripping decimal since JDK 19 (JDK-4511638), which is the same rule
     * {@code Number.prototype.toString} follows — so this is the browser's view, not an approximation of
     * it.
     *
     * <p>It also covers the E-notation {@code BigDecimal#toString} switches to once the adjusted
     * exponent drops below -6, which is how a scale-8 rate of {@code 0.00000001} goes out as
     * {@code 1E-8}: a well-formed JSON number that {@code JSON.parse} reads as {@code 1e-8}.
     */
    private static BigDecimal asBrowserWouldSee(BigDecimal amount) {
        String onTheWire = amount.toString();
        return new BigDecimal(Double.toString(Double.parseDouble(onTheWire)));
    }
}

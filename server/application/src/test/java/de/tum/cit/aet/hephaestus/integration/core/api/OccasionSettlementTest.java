package de.tum.cit.aet.hephaestus.integration.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import java.util.Arrays;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Every cell of the settlement table: occasion state × role × outcome → next step. */
@Tag("unit")
class OccasionSettlementTest {

    /** The open states. {@code next} is DECIDE:state:reason, HOLD:reason or NONE. */
    @ParameterizedTest(name = "{0} {1} after {2} -> {4}")
    @CsvSource(
            nullValues = "-",
            value = {
                "RECORDED, NEWEST, RECORDED, -, DECIDE:STARTED:-",
                "RECORDED, OLDER, RECORDED, -, DECIDE:WITHHELD:COALESCED",
                "WAITING, NEWEST, RECORDED, -, DECIDE:STARTED:-",
                "WAITING, OLDER, RECORDED, -, DECIDE:WITHHELD:COALESCED",
                "RECORDED, NEWEST, REPLAYED, -, DECIDE:STARTED:-",
                "RECORDED, OLDER, REPLAYED, -, NONE",
                "WAITING, NEWEST, REPLAYED, -, DECIDE:STARTED:-",
                "WAITING, OLDER, REPLAYED, -, NONE",
                "RECORDED, NEWEST, QUIET, -, HOLD:QUIET_WINDOW",
                "RECORDED, OLDER, QUIET, -, HOLD:QUIET_WINDOW",
                "WAITING, NEWEST, QUIET, -, HOLD:QUIET_WINDOW",
                "WAITING, OLDER, QUIET, -, HOLD:QUIET_WINDOW",
                "RECORDED, NEWEST, WAIT, BUDGET_EXHAUSTED, DECIDE:WAITING:BUDGET_EXHAUSTED",
                "RECORDED, OLDER, WAIT, BUDGET_EXHAUSTED, DECIDE:WAITING:BUDGET_EXHAUSTED",
                "WAITING, NEWEST, WAIT, BUDGET_EXHAUSTED, DECIDE:WAITING:BUDGET_EXHAUSTED",
                "WAITING, OLDER, WAIT, BUDGET_EXHAUSTED, DECIDE:WAITING:BUDGET_EXHAUSTED",
                "RECORDED, NEWEST, HOLD, -, HOLD:REVIEW_IN_PROGRESS",
                "RECORDED, OLDER, HOLD, -, HOLD:REVIEW_IN_PROGRESS",
                "WAITING, NEWEST, HOLD, -, HOLD:REVIEW_IN_PROGRESS",
                "WAITING, OLDER, HOLD, -, HOLD:REVIEW_IN_PROGRESS",
                "RECORDED, NEWEST, REFUSED, NO_ACTIVE_PRACTICE, DECIDE:WITHHELD:NO_ACTIVE_PRACTICE",
                "RECORDED, OLDER, REFUSED, NO_ACTIVE_PRACTICE, DECIDE:WITHHELD:NO_ACTIVE_PRACTICE",
                "WAITING, NEWEST, REFUSED, NO_ACTIVE_PRACTICE, DECIDE:WITHHELD:NO_ACTIVE_PRACTICE",
                "WAITING, OLDER, REFUSED, NO_ACTIVE_PRACTICE, DECIDE:WITHHELD:NO_ACTIVE_PRACTICE",
                "RECORDED, NEWEST, REFUSED_NEWEST, VERSION_SUPERSEDED, DECIDE:WITHHELD:VERSION_SUPERSEDED",
                "RECORDED, OLDER, REFUSED_NEWEST, VERSION_SUPERSEDED, NONE",
                "WAITING, NEWEST, REFUSED_NEWEST, VERSION_SUPERSEDED, DECIDE:WITHHELD:VERSION_SUPERSEDED",
                "WAITING, OLDER, REFUSED_NEWEST, VERSION_SUPERSEDED, NONE",
                "RECORDED, NEWEST, OCCASION_UNAVAILABLE, -, DECIDE:WITHHELD:OCCASION_EVIDENCE_UNAVAILABLE",
                "RECORDED, OLDER, OCCASION_UNAVAILABLE, -, NONE",
                "WAITING, NEWEST, OCCASION_UNAVAILABLE, -, DECIDE:WITHHELD:OCCASION_EVIDENCE_UNAVAILABLE",
                "WAITING, OLDER, OCCASION_UNAVAILABLE, -, NONE",
                "RECORDED, NEWEST, ITEM_UNAVAILABLE, ITEM_NOT_VISIBLE, DECIDE:WITHHELD:ITEM_NOT_VISIBLE",
                "RECORDED, OLDER, ITEM_UNAVAILABLE, ITEM_NOT_VISIBLE, DECIDE:WITHHELD:ITEM_NOT_VISIBLE",
                "WAITING, NEWEST, ITEM_UNAVAILABLE, ITEM_NOT_VISIBLE, DECIDE:WITHHELD:ITEM_NOT_VISIBLE",
                "WAITING, OLDER, ITEM_UNAVAILABLE, ITEM_NOT_VISIBLE, DECIDE:WITHHELD:ITEM_NOT_VISIBLE",
                "RECORDED, NEWEST, FAULT, -, NONE",
                "RECORDED, OLDER, FAULT, -, NONE",
                "WAITING, NEWEST, FAULT, -, NONE",
                "WAITING, OLDER, FAULT, -, NONE",
                "RECORDED, NEWEST, WITHDRAWN, -, DECIDE:WITHHELD:ITEM_NOT_VISIBLE",
                "RECORDED, OLDER, WITHDRAWN, -, NONE",
                "WAITING, NEWEST, WITHDRAWN, -, DECIDE:WITHHELD:ITEM_NOT_VISIBLE",
                "WAITING, OLDER, WITHDRAWN, -, NONE",
                "RECORDED, NEWEST, DUPLICATE, -, DECIDE:WITHHELD:CONCURRENT_DUPLICATE",
                "RECORDED, OLDER, DUPLICATE, -, DECIDE:WITHHELD:CONCURRENT_DUPLICATE",
                "WAITING, NEWEST, DUPLICATE, -, DECIDE:WITHHELD:CONCURRENT_DUPLICATE",
                "WAITING, OLDER, DUPLICATE, -, DECIDE:WITHHELD:CONCURRENT_DUPLICATE",
                "RECORDED, NEWEST, EXPIRED, -, DECIDE:EXPIRED:PENDING_DEADLINE_EXCEEDED",
                "RECORDED, OLDER, EXPIRED, -, DECIDE:EXPIRED:PENDING_DEADLINE_EXCEEDED",
                "WAITING, NEWEST, EXPIRED, -, DECIDE:EXPIRED:PENDING_DEADLINE_EXCEEDED",
                "WAITING, OLDER, EXPIRED, -, DECIDE:EXPIRED:PENDING_DEADLINE_EXCEEDED",
            })
    void shouldSettleAnOpenOccasionAsTheTableSays(
            ItemEventState state,
            OccasionSettlement.Role role,
            OccasionSettlement.Outcome outcome,
            @Nullable ReviewRequestReason reason,
            String next) {
        assertThat(OccasionSettlement.next(state, role, outcome, reason)
                        .map(OccasionSettlementTest::describe)
                        .orElse("NONE"))
                .isEqualTo(next);
    }

    @Test
    void shouldListEveryOpenCellInTheTable() {
        // 2 open states x 2 roles x every outcome: a new outcome needs its rows above.
        assertThat(OccasionSettlement.Outcome.values()).hasSize(13);
    }

    @ParameterizedTest(name = "{0} {1} after {2} stays")
    @MethodSource("terminalCells")
    void shouldNeverChangeADecidedOccasion(
            ItemEventState state, OccasionSettlement.Role role, OccasionSettlement.Outcome outcome) {
        assertThat(OccasionSettlement.next(state, role, outcome, ReviewRequestReason.NO_ACTIVE_PRACTICE))
                .isEmpty();
    }

    static Stream<Arguments> terminalCells() {
        return Arrays.stream(ItemEventState.values())
                .filter(ItemEventState::isTerminal)
                .flatMap(state -> Arrays.stream(OccasionSettlement.Role.values())
                        .flatMap(role -> Arrays.stream(OccasionSettlement.Outcome.values())
                                .map(outcome -> Arguments.of(state, role, outcome))));
    }

    private static String describe(OccasionSettlement.Next next) {
        return switch (next) {
            case OccasionSettlement.Next.Decide decide ->
                "DECIDE:" + decide.state() + ":" + (decide.reason() == null ? "-" : decide.reason());
            case OccasionSettlement.Next.Hold hold -> "HOLD:" + hold.reason();
        };
    }
}

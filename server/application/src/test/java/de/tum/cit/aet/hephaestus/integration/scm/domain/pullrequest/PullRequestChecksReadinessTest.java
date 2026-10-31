package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemChecks;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PullRequestChecksReadinessTest extends BaseUnitTest {
    private static final String HEAD = "b".repeat(40);
    private static final Instant READ_AT = Instant.parse("2026-10-30T12:00:00Z");

    @Test
    void shouldBeSettledOnlyWhenEveryListedRunOfACompleteListEnded() {
        assertThat(readiness(CheckState.SUCCESS, runs(true, null, run(true), run(true))))
                .isEqualTo(ItemChecks.SETTLED);
        assertThat(readiness(CheckState.PENDING, runs(true, null, run(true), run(false))))
                .isEqualTo(ItemChecks.PENDING);
        assertThat(readiness(CheckState.SUCCESS, runs(false, null, run(true)))).isEqualTo(ItemChecks.PENDING);
    }

    @Test
    void shouldWaitForApprovalAlthoughTheRollupReadsSuccess() {
        assertThat(readiness(CheckState.SUCCESS, runs(false, HeadCheckRuns.AWAITING_APPROVAL)))
                .isEqualTo(ItemChecks.AWAITING_APPROVAL);
    }

    @Test
    void shouldReportNoChecksForAnEmptyCompleteListOrNoRollup() {
        assertThat(readiness(CheckState.NONE, runs(true, null))).isEqualTo(ItemChecks.NONE_REPORTED);
        assertThat(readiness(null, null)).isEqualTo(ItemChecks.NONE_REPORTED);
        assertThat(readiness(CheckState.NONE, null)).isEqualTo(ItemChecks.NONE_REPORTED);
    }

    @Test
    void shouldWaitForTheRunListWhenOnlyTheRollupWasRead() {
        assertThat(readiness(CheckState.PENDING, null)).isEqualTo(ItemChecks.PENDING);
        assertThat(readiness(CheckState.FAILURE, null)).isEqualTo(ItemChecks.PENDING);
    }

    private static ItemChecks readiness(@Nullable CheckState state, @Nullable HeadCheckRuns runs) {
        var pull = new PullRequest();
        pull.setHeadRefOid(HEAD);
        if (state != null) pull.observeHeadChecks(HEAD, state, true);
        if (runs != null) pull.observeHeadCheckRuns(runs);
        return PullRequestChecksRecord.readiness(PullRequestChecksRecord.current(pull, HEAD));
    }

    private static HeadCheckRuns runs(boolean complete, @Nullable String limitation, HeadCheckRuns.Run... runs) {
        return new HeadCheckRuns(HEAD, List.of(runs), complete, READ_AT, limitation);
    }

    private static HeadCheckRuns.Run run(boolean finished) {
        return new HeadCheckRuns.Run(
                finished ? "build" : "test",
                "ci",
                finished ? "success" : "in_progress",
                finished,
                null,
                null,
                finished ? READ_AT : null);
    }
}

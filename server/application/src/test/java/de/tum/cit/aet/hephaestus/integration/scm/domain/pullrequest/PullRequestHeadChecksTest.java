package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PullRequestHeadChecksTest extends BaseUnitTest {

    private static final String HEAD = "a".repeat(40);
    private static final String NEXT_HEAD = "b".repeat(40);

    @Test
    void shouldTakeTheFirstObservationOfAHeadAsGiven() {
        PullRequest pr = new PullRequest();

        assertThat(pr.observeHeadChecks(HEAD, CheckState.SUCCESS, false)).isTrue();

        assertThat(pr.getHeadCheckSha()).isEqualTo(HEAD);
        assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.SUCCESS);
    }

    @Test
    void shouldOnlyWorsenTheStateOfAHeadFromASingleSuiteOrStatus() {
        PullRequest pr = new PullRequest();
        pr.observeHeadChecks(HEAD, CheckState.SUCCESS, false);

        assertThat(pr.observeHeadChecks(HEAD, CheckState.FAILURE, false)).isTrue();
        assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.FAILURE);
        assertThat(pr.observeHeadChecks(HEAD, CheckState.SUCCESS, false)).isFalse();
        assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.FAILURE);
    }

    @Test
    void shouldReplaceTheStateOutrightWhenTheProviderRollupIsRead() {
        PullRequest pr = new PullRequest();
        pr.observeHeadChecks(HEAD, CheckState.FAILURE, false);

        assertThat(pr.observeHeadChecks(HEAD, CheckState.SUCCESS, true)).isTrue();

        assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.SUCCESS);
    }

    @Test
    void shouldStartOverForANewHead() {
        PullRequest pr = new PullRequest();
        pr.observeHeadChecks(HEAD, CheckState.FAILURE, false);

        assertThat(pr.observeHeadChecks(NEXT_HEAD, CheckState.PENDING, false)).isTrue();

        assertThat(pr.getHeadCheckSha()).isEqualTo(NEXT_HEAD);
        assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.PENDING);
    }

    @Test
    void shouldReportNoChangeWhenTheObservationRepeats() {
        PullRequest pr = new PullRequest();
        pr.observeHeadChecks(HEAD, CheckState.PENDING, true);

        assertThat(pr.observeHeadChecks(HEAD, CheckState.PENDING, true)).isFalse();
    }

    private static final Instant READ_AT = Instant.parse("2026-09-30T10:00:00.123456Z");

    @Test
    void shouldKeepALaterObservationWhenAnEarlierOneOfTheSameHeadComesLast() {
        PullRequest failedLater = new PullRequest();
        failedLater.observeHeadChecks(HEAD, CheckState.FAILURE, true, READ_AT);
        PullRequest passedLater = new PullRequest();
        passedLater.observeHeadChecks(HEAD, CheckState.SUCCESS, true, READ_AT);
        PullRequest ranLater = new PullRequest();
        ranLater.observeHeadChecks(HEAD, CheckState.SUCCESS, true, READ_AT);

        assertThat(failedLater.observeHeadChecks(HEAD, CheckState.SUCCESS, true, READ_AT.minusMillis(1)))
                .isFalse();
        assertThat(passedLater.observeHeadChecks(HEAD, CheckState.FAILURE, true, READ_AT.minusMillis(1)))
                .isFalse();
        assertThat(ranLater.observeHeadChecks(HEAD, CheckState.NO_PIPELINE, true, READ_AT.minusMillis(1)))
                .isFalse();

        assertThat(failedLater.getHeadCheckState()).isEqualTo(CheckState.FAILURE);
        assertThat(passedLater.getHeadCheckState()).isEqualTo(CheckState.SUCCESS);
        assertThat(ranLater.getHeadCheckState()).isEqualTo(CheckState.SUCCESS);
        assertThat(ranLater.getHeadCheckObservedAt()).isEqualTo(READ_AT);
    }

    @Test
    void shouldTakeALaterRollupThatRecoversOrFailsTheHead() {
        PullRequest pr = new PullRequest();
        pr.observeHeadChecks(HEAD, CheckState.FAILURE, true, READ_AT);

        assertThat(pr.observeHeadChecks(HEAD, CheckState.SUCCESS, true, READ_AT.plusSeconds(1)))
                .isTrue();
        assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.SUCCESS);
        assertThat(pr.observeHeadChecks(HEAD, CheckState.FAILURE, true, READ_AT.plusSeconds(2)))
                .isTrue();
        assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.FAILURE);
    }

    @Test
    void shouldKeepALaterObservationOfOneHeadOverAnEarlierOneOfAnother() {
        PullRequest pr = new PullRequest();
        pr.observeHeadChecks(NEXT_HEAD, CheckState.PENDING, true, READ_AT);

        assertThat(pr.observeHeadChecks(HEAD, CheckState.SUCCESS, true, READ_AT.minusSeconds(1)))
                .isFalse();

        assertThat(pr.getHeadCheckSha()).isEqualTo(NEXT_HEAD);
    }

    @Test
    void shouldApplyAnObservationAtTheSameMicrosecondAsTheStoredOne() {
        PullRequest pr = new PullRequest();
        pr.observeHeadChecks(HEAD, CheckState.PENDING, true, READ_AT);

        assertThat(pr.observeHeadChecks(HEAD, CheckState.SUCCESS, true, READ_AT.plusNanos(999)))
                .isTrue();

        assertThat(pr.getHeadCheckState()).isEqualTo(CheckState.SUCCESS);
        assertThat(pr.getHeadCheckObservedAt()).isEqualTo(READ_AT);
    }

    @Test
    void shouldLetADatedObservationReplaceOneOfUnknownAgeAndAnUndatedOneLeaveTheAgeUnknown() {
        PullRequest pr = new PullRequest();
        pr.observeHeadChecks(HEAD, CheckState.FAILURE, true);

        assertThat(pr.observeHeadChecks(HEAD, CheckState.SUCCESS, true, READ_AT))
                .isTrue();
        assertThat(pr.getHeadCheckObservedAt()).isEqualTo(READ_AT);

        assertThat(pr.observeHeadChecks(HEAD, CheckState.FAILURE, true)).isTrue();
        assertThat(pr.getHeadCheckObservedAt()).isNull();
    }

    @Test
    void shouldStoreChangedRunsAndKeepTheStoredReadWhenTheSameRunsOrAnOlderReadArrive() {
        PullRequest pr = new PullRequest();
        HeadCheckRuns running = runs(HEAD, false, READ_AT);
        HeadCheckRuns ended = runs(HEAD, true, READ_AT.plusSeconds(60));

        assertThat(pr.observeHeadCheckRuns(running)).isTrue();
        assertThat(pr.observeHeadCheckRuns(ended)).isTrue();
        assertThat(pr.observeHeadCheckRuns(runs(HEAD, true, READ_AT.plusSeconds(120))))
                .isFalse();
        assertThat(pr.observeHeadCheckRuns(runs(HEAD, false, READ_AT.plusSeconds(30))))
                .isFalse();
        assertThat(pr.getHeadCheckRuns()).isEqualTo(ended);

        assertThat(pr.observeHeadCheckRuns(runs(NEXT_HEAD, false, READ_AT))).isTrue();
        assertThat(pr.getHeadCheckRuns())
                .isNotNull()
                .extracting(HeadCheckRuns::sha)
                .isEqualTo(NEXT_HEAD);
    }

    @Test
    void shouldCallRunsFinishedOnlyWhenTheListIsCompleteNonEmptyAndEveryRunEnded() {
        assertThat(runs(HEAD, true, READ_AT).finished()).isTrue();
        assertThat(runs(HEAD, false, READ_AT).finished()).isFalse();
        assertThat(new HeadCheckRuns(HEAD, runs(HEAD, true, READ_AT).runs(), false, READ_AT).finished())
                .isFalse();
        assertThat(new HeadCheckRuns(HEAD, List.of(), true, READ_AT).finished()).isFalse();
    }

    private static HeadCheckRuns runs(String sha, boolean ended, Instant observedAt) {
        return new HeadCheckRuns(
                sha,
                List.of(
                        new HeadCheckRuns.Run("build", "CI", "success", true, null, null, null),
                        new HeadCheckRuns.Run(
                                "test", "CI", ended ? "failure" : "in_progress", ended, null, null, null)),
                true,
                observedAt);
    }

    @Test
    void shouldBoundTheRunsThatTheWorkItselfNamesAndCallALongerListIncomplete() {
        var runs = IntStream.range(0, 201)
                .mapToObj(n -> new HeadCheckRuns.Run("run " + n, null, "success", true, null, null, null))
                .toList();

        var bounded = new HeadCheckRuns(HEAD, runs, true, READ_AT);

        assertThat(bounded.runs()).hasSize(200);
        assertThat(bounded.complete()).isFalse();
        assertThat(new HeadCheckRuns.Run("x".repeat(500), "y".repeat(500), "success", true, null, null, null).name())
                .hasSize(200);
    }

    @Test
    void shouldKeepALinkWithoutTheCredentialsOrQueryThatAStatusPosterCanPutInIt() {
        var run = new HeadCheckRuns.Run(
                "ci/jenkins",
                null,
                "success",
                true,
                "https://user:token@jenkins.example/job/7?token=abc#log",
                null,
                null);

        assertThat(run.url()).isEqualTo("https://jenkins.example/job/7");
        assertThat(new HeadCheckRuns.Run("x", null, "success", true, "not a link", null, null).url())
                .isNull();
    }
}

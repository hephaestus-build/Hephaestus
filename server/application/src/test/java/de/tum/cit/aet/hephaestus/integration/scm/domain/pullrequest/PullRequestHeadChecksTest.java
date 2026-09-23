package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
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
}

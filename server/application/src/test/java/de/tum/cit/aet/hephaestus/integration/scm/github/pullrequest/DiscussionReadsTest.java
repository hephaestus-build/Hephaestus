package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class DiscussionReadsTest extends BaseUnitTest {
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);

    @Test
    void shouldRecordAWorkItemReadWholeOnlyWhenEveryOneOfItsListsReachedTheEnd() {
        var reads = new DiscussionReads();
        reads.expect(1);
        reads.expect(1);
        reads.expect(2);
        reads.expect(2);
        reads.record(1, SyncResult.completed(3));
        reads.record(1, SyncResult.completed(4));
        reads.record(2, SyncResult.completed(3));
        reads.record(2, SyncResult.completedWithWarnings(1));

        reads.settle(pullRequests);

        verify(pullRequests).recordDiscussionRead(1, true);
        verify(pullRequests, never()).recordDiscussionRead(2, true);
        assertThat(reads.status()).isEqualTo(SyncResult.Status.COMPLETED_WITH_WARNINGS);
    }

    @Test
    void shouldLeaveAListThatWasNeverReadUnsettled() {
        var reads = new DiscussionReads();
        reads.expect(1);
        reads.expect(1);
        reads.record(1, SyncResult.completed(3));

        reads.settle(pullRequests);

        verify(pullRequests, never()).recordDiscussionRead(1, true);
    }

    @Test
    void shouldKeepAskingNoMoreOnceARateLimitEndedARead() {
        var reads = new DiscussionReads();
        reads.stop(1, SyncResult.Status.ABORTED_ERROR);
        reads.record(2, SyncResult.abortedRateLimit(0));

        assertThat(reads.rateLimited()).isTrue();
        assertThat(reads.status()).isEqualTo(SyncResult.Status.ABORTED_ERROR);
    }
}

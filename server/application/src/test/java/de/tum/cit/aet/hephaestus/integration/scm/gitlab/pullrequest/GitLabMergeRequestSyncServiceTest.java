package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class GitLabMergeRequestSyncServiceTest extends BaseUnitTest {

    @Test
    void shouldReadDiscussionsOfAnApprovedOrSettledMergeRequestWithNoUserNotes() {
        // The approval's time lives in a system note, which userNotesCount does not count: an approved
        // merge request with no user notes must still have its discussions read, or the approval keeps
        // the merge time and "approved before merging" is true of every merge.
        assertThat(GitLabMergeRequestSyncService.readsDiscussions(0, true, "opened"))
                .isTrue();
        assertThat(GitLabMergeRequestSyncService.readsDiscussions(0, false, "merged"))
                .isTrue();
        assertThat(GitLabMergeRequestSyncService.readsDiscussions(0, false, "closed"))
                .isTrue();
        assertThat(GitLabMergeRequestSyncService.readsDiscussions(3, false, "opened"))
                .isTrue();
    }

    @Test
    void shouldSpareTheRequestForAnUntouchedOpenMergeRequest() {
        assertThat(GitLabMergeRequestSyncService.readsDiscussions(0, false, "opened"))
                .isFalse();
    }
}

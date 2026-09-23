package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class GitLabMergeRequestSyncServiceTest extends BaseUnitTest {

    @Test
    void shouldReadDiscussionsOfAnApprovedOrSettledMergeRequestWithNoUserNotes() {
        // userNotesCount excludes approval system notes, so zero user notes must not skip discussions.
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

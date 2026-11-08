package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.RetainedFeedbackTestData;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup.CommentIds;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The comments on one pull request that a workspace's own delivery records name, matched against the mirror. */
class DeliveredPullRequestCommentAdapterIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ItemIndexService itemWriter;

    @Autowired
    private JdbcTemplate jdbc;

    private long recipientAccountId;

    @Autowired
    private DeliveredPullRequestCommentLookup lookup;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private IssueCommentRepository comments;

    @Autowired
    private PullRequestReviewThreadRepository threads;

    @Autowired
    private PullRequestReviewCommentRepository inlineComments;

    @Autowired
    private FeedbackRepository feedback;

    @Autowired
    private PullRequestReviewRepository reviews;

    @Autowired
    private FeedbackCopyRepository copies;

    private Workspace workspace;
    private Workspace elsewhere;
    private User owner;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        owner = persistUser("provenance-owner");
        workspace = createWorkspace("provenance", "Provenance", "provenance", AccountType.ORG, owner);
        elsewhere = createWorkspace("provenance-other", "Other", "provenance-other", AccountType.ORG, owner);
        recipientAccountId = accountId(owner);
    }

    @Test
    void shouldReturnEveryRecordedGitLabNoteOnTheMergeRequestWhateverBecameOfItsFeedback() {
        IdentityProvider gitlab = ensureGitLabProvider();
        PullRequest work = pullRequest(gitlab, 91_001L, 7);
        PullRequest otherWork = pullRequest(gitlab, 91_002L, 8);
        // Markerless and edited bodies: provenance does not read them.
        for (long note : new long[] {101, 102, 103, 104, 109}) general(work, note, "Edited without a marker");
        for (long note : new long[] {201, 202, 209}) inline(work, note);
        general(otherWork, 301, "Another merge request");

        copy(save(workspace, work, FeedbackState.REPLACED), FeedbackCopyKind.SUMMARY, "gid://gitlab/Note/101", null);
        copy(save(workspace, work, FeedbackState.FAILED), FeedbackCopyKind.FILE_NOTE, "gid://gitlab/Note/102", null);
        copy(
                save(workspace, work, FeedbackState.DELIVERED),
                FeedbackCopyKind.LINE_NOTE,
                "gid://gitlab/DiffNote/201",
                null);
        copy(save(workspace, work, FeedbackState.DELIVERED), FeedbackCopyKind.SUMMARY, null, null);
        copy(save(workspace, work, FeedbackState.DELIVERED), FeedbackCopyKind.SUMMARY, "103", null);
        copy(save(workspace, work, FeedbackState.DELIVERED), FeedbackCopyKind.SUMMARY, "gid://gitlab/Note/104", null);
        copy(
                save(workspace, work, FeedbackState.DELIVERED),
                FeedbackCopyKind.LINE_NOTE,
                "gid://gitlab/DiffNote/202",
                null);
        // Identities that exist on this merge request, but were recorded for other work or another workspace.
        copy(
                save(workspace, otherWork, FeedbackState.DELIVERED),
                FeedbackCopyKind.SUMMARY,
                "gid://gitlab/Note/109",
                null);
        copy(save(elsewhere, work, FeedbackState.DELIVERED), FeedbackCopyKind.LINE_NOTE, "gid://gitlab/Note/209", null);

        assertThat(lookup.findForPullRequest(workspace.getId(), work.getId(), ScmItemEvents.PULL_REQUEST))
                .isEqualTo(new CommentIds(Set.of(101L, 102L, 103L, 104L), Set.of(201L, 202L), Set.of()));
        assertThat(lookup.findForPullRequest(elsewhere.getId(), work.getId(), ScmItemEvents.PULL_REQUEST))
                .isEqualTo(new CommentIds(Set.of(), Set.of(209L), Set.of()));
        assertThat(lookup.findForPullRequest(workspace.getId(), otherWork.getId(), ScmItemEvents.PULL_REQUEST))
                .isEqualTo(new CommentIds(Set.of(), Set.of(), Set.of()));
    }

    @Test
    void shouldMatchGitHubCopiesOnlyByThePermalinkOfTheirOwnCommentKind() {
        IdentityProvider github = ensureGitHubProvider();
        PullRequest work = pullRequest(github, 92_001L, 7);
        String url = work.getHtmlUrl();
        general(work, 5_001, "Summary");
        general(work, 7_001, "A reviewer's comment");
        inline(work, 5_001);
        inline(work, 6_001);

        copy(
                save(workspace, work, FeedbackState.REPLACED),
                FeedbackCopyKind.SUMMARY,
                "IC_kwDOsummary",
                url + "#issuecomment-5001");
        copy(
                save(workspace, work, FeedbackState.DELIVERED),
                FeedbackCopyKind.LINE_NOTE,
                "PRRC_kwDOinline",
                url + "#discussion_r6001");
        // Neither a GitLab identity nor an opaque reference without its permalink names a GitHub comment.
        copy(save(workspace, work, FeedbackState.DELIVERED), FeedbackCopyKind.SUMMARY, "gid://gitlab/Note/7001", null);
        copy(save(workspace, work, FeedbackState.DELIVERED), FeedbackCopyKind.SUMMARY, "IC_kwDOunmapped", null);

        assertThat(lookup.findForPullRequest(workspace.getId(), work.getId(), ScmItemEvents.PULL_REQUEST))
                .isEqualTo(new CommentIds(Set.of(5_001L), Set.of(6_001L), Set.of()));
    }

    @Test
    void shouldIdentifyARecordedGitHubReviewWithoutUsingItsBodyOrAnotherWorkspacesReceipt() {
        var work = pullRequest(ensureGitHubProvider(), 93_001L, 7);
        var review = new PullRequestReview();
        review.setProvider(work.getProvider());
        review.setPullRequest(work);
        review.setNativeId(8_001L);
        review.setState(PullRequestReview.State.COMMENTED);
        review.setBody("Edited review without a marker");
        review.setHtmlUrl(work.getHtmlUrl() + "#pullrequestreview-8001");
        reviews.saveAndFlush(review);
        copy(
                save(workspace, work, FeedbackState.REPLACED),
                FeedbackCopyKind.SUMMARY,
                "PRR_recorded",
                review.getHtmlUrl());
        assertThat(lookup.findForPullRequest(workspace.getId(), work.getId(), ScmItemEvents.PULL_REQUEST))
                .isEqualTo(new CommentIds(Set.of(), Set.of(), Set.of(8_001L)));
        assertThat(lookup.findForPullRequest(elsewhere.getId(), work.getId(), ScmItemEvents.PULL_REQUEST))
                .isEqualTo(new CommentIds(Set.of(), Set.of(), Set.of()));
    }

    private PullRequest pullRequest(IdentityProvider provider, long nativeId, int number) {
        Repository project = new Repository();
        project.setNativeId(nativeId);
        project.setProvider(provider);
        project.setName("project-" + nativeId);
        project.setNameWithOwner("team/project-" + nativeId);
        project.setHtmlUrl(provider.getServerUrl() + "/team/project-" + nativeId);
        project.setDefaultBranch("main");
        project = repositories.save(project);
        PullRequest work = new PullRequest();
        work.setNativeId(nativeId);
        work.setProvider(provider);
        work.setRepository(project);
        work.setNumber(number);
        work.setTitle("Provider work");
        work.setState(Issue.State.OPEN);
        work.setAuthor(owner);
        work.setHtmlUrl(project.getHtmlUrl() + "/pull/" + number);
        work.setCreatedAt(Instant.now());
        work.setUpdatedAt(Instant.now());
        return pullRequests.save(work);
    }

    private void general(PullRequest work, long nativeId, String body) {
        IssueComment comment = new IssueComment();
        comment.setNativeId(nativeId);
        comment.setProvider(work.getProvider());
        comment.setIssue(work);
        comment.setAuthor(owner);
        comment.setAuthorAssociation(AuthorAssociation.MEMBER);
        comment.setHtmlUrl(work.getHtmlUrl() + "#note_" + nativeId);
        comment.setBody(body);
        comments.save(comment);
    }

    private void inline(PullRequest work, long nativeId) {
        PullRequestReviewThread thread = new PullRequestReviewThread();
        thread.setNativeId(nativeId);
        thread.setNodeId("thread-" + nativeId);
        thread.setProvider(work.getProvider());
        thread.setPullRequest(work);
        thread = threads.save(thread);
        PullRequestReviewComment comment = new PullRequestReviewComment();
        comment.setNativeId(nativeId);
        comment.setProvider(work.getProvider());
        comment.setPullRequest(work);
        comment.setThread(thread);
        comment.setPath("src/A.java");
        comment.setHtmlUrl(work.getHtmlUrl() + "#discussion_r" + nativeId);
        comment.setBody("Edited without a marker");
        inlineComments.save(comment);
    }

    private Feedback save(Workspace scope, PullRequest work, FeedbackState state) {
        var item = itemWriter.upsert(
                new ItemRegistration(scope.getId(), new ItemKind("scm.pull_request"), work.getId(), null, null));
        return RetainedFeedbackTestData.onWork(jdbc, feedback, item, recipientAccountId, state);
    }

    private void copy(Feedback parent, FeedbackCopyKind type, @Nullable String ref, @Nullable String url) {
        copies.saveAndFlush(RetainedFeedbackTestData.posted(parent, type, 0, ref, url));
    }
}

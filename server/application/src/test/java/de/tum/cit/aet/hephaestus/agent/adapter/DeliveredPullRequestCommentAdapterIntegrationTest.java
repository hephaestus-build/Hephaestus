package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup.CommentIds;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchCompletion;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchInsert;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementAnchorKind;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementAnchorSide;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** The comments on one pull request that a workspace's own delivery records name, matched against the mirror. */
class DeliveredPullRequestCommentAdapterIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private DeliveredPullRequestCommentAdapter lookup;

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
    private AgentJobRepository jobs;

    @Autowired
    private FeedbackRepository feedback;

    @Autowired
    private FeedbackPlacementRepository placements;

    @Autowired
    private FeedbackDispatchRepository dispatches;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TransactionTemplate transactions;

    private Workspace workspace;
    private Workspace elsewhere;
    private User owner;
    private int position;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        owner = persistUser("provenance-owner");
        workspace = createWorkspace("provenance", "Provenance", "provenance", AccountType.ORG, owner);
        elsewhere = createWorkspace("provenance-other", "Other", "provenance-other", AccountType.ORG, owner);
        position = 0;
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

        placement(
                save(workspace, work, FeedbackDeliveryState.SUPERSEDED),
                PlacementType.SUMMARY,
                "gid://gitlab/Note/101",
                null);
        placement(
                save(workspace, work, FeedbackDeliveryState.FAILED),
                PlacementType.LOCATION_COMMENT,
                "gid://gitlab/Note/102",
                null);
        placement(
                save(workspace, work, FeedbackDeliveryState.DELIVERED),
                PlacementType.INLINE,
                "gid://gitlab/DiffNote/201",
                null);
        placement(save(workspace, work, FeedbackDeliveryState.DELIVERED), PlacementType.SUMMARY, null, null);
        AgentJob job = job(workspace, work, "103");
        dispatch(job, "gid://gitlab/Note/104", null, """
                [{"deliveryKey":"a","path":"src/A.java","startLine":4,"disposition":"POSTED",
                  "externalRef":"gid://gitlab/Note/202","placement":"LINE"},
                 {"deliveryKey":"b","path":"src/A.java","startLine":9,"disposition":"FAILED",
                  "externalRef":null,"writeMayHaveStarted":false}]""");
        // Identities that exist on this merge request, but were recorded for other work or another workspace.
        placement(
                save(workspace, otherWork, FeedbackDeliveryState.DELIVERED),
                PlacementType.SUMMARY,
                "gid://gitlab/Note/109",
                null);
        placement(
                save(elsewhere, work, FeedbackDeliveryState.DELIVERED),
                PlacementType.INLINE,
                "gid://gitlab/Note/209",
                null);

        assertThat(lookup.findForPullRequest(workspace.getId(), work.getId()))
                .isEqualTo(new CommentIds(Set.of(101L, 102L, 103L, 104L), Set.of(201L, 202L)));
        assertThat(lookup.findForPullRequest(elsewhere.getId(), work.getId()))
                .isEqualTo(new CommentIds(Set.of(), Set.of(209L)));
        assertThat(lookup.findForPullRequest(workspace.getId(), otherWork.getId()))
                .isEqualTo(new CommentIds(Set.of(), Set.of()));
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

        placement(
                save(workspace, work, FeedbackDeliveryState.SUPERSEDED),
                PlacementType.SUMMARY,
                "IC_kwDOsummary",
                url + "#issuecomment-5001");
        placement(
                save(workspace, work, FeedbackDeliveryState.DELIVERED),
                PlacementType.INLINE,
                "PRRC_kwDOinline",
                url + "#discussion_r6001");
        // Neither a GitLab identity nor an opaque reference without its permalink names a GitHub comment.
        placement(
                save(workspace, work, FeedbackDeliveryState.DELIVERED),
                PlacementType.SUMMARY,
                "gid://gitlab/Note/7001",
                null);
        job(workspace, work, "IC_kwDOunmapped");

        assertThat(lookup.findForPullRequest(workspace.getId(), work.getId()))
                .isEqualTo(new CommentIds(Set.of(5_001L), Set.of(6_001L)));
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

    private Feedback save(Workspace scope, PullRequest work, FeedbackDeliveryState state) {
        AgentJob job = job(scope, work, null);
        return feedback.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(scope.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(work.getId())
                .recipientUserId(owner.getId())
                .aboutUserId(owner.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(position++)
                .deliveryState(state)
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .build());
    }

    private void placement(Feedback parent, PlacementType type, @Nullable String ref, @Nullable String url) {
        boolean located = type == PlacementType.INLINE || type == PlacementType.LOCATION_COMMENT;
        placements.save(FeedbackPlacement.builder()
                .feedback(parent)
                .placementType(type)
                .postedCommentRef(ref)
                .postedCommentUrl(url)
                .anchorKind(located ? PlacementAnchorKind.LINE : null)
                .anchorPath(located ? "src/A.java" : null)
                .anchorStartLine(located ? 4 : null)
                .anchorSide(located ? PlacementAnchorSide.NEW : null)
                .build());
    }

    private AgentJob job(Workspace scope, PullRequest work, @Nullable String legacySummary) {
        AgentJob job = new AgentJob();
        job.setWorkspace(scope);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setArtifactKind(ArtifactKinds.PULL_REQUEST);
        job.setMetadata(mapper.valueToTree(Map.of("pull_request_id", work.getId())));
        job.setConfigSnapshot(mapper.valueToTree(Map.of("model", "test")));
        job.setDeliveryCommentId(legacySummary);
        return jobs.save(job);
    }

    private void dispatch(AgentJob job, String ref, @Nullable String url, String deliveredPlacements) {
        UUID id = UUID.randomUUID();
        long scope = job.getWorkspace().getId();
        transactions.executeWithoutResult(status -> {
            assertThat(dispatches.insertIfAbsent(new FeedbackDispatchInsert(
                            id,
                            "delivery-" + id,
                            scope,
                            job.getId(),
                            null,
                            "AUTOMATIC_REVIEW_PACKAGE",
                            "Body",
                            "[]",
                            "{}")))
                    .isOne();
            assertThat(dispatches.claim(id, scope, "test", Instant.now().plusSeconds(60), 8, 0))
                    .isOne();
            assertThat(dispatches.finish(new FeedbackDispatchCompletion(
                            id, scope, "test", "SENT", ref, url, null, null, deliveredPlacements, Instant.now())))
                    .isOne();
        });
    }
}

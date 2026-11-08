package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class PullRequestContentSourceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PullRequestReviewCommentRepository reviewCommentRepository;

    @Autowired
    private PullRequestReviewThreadRepository threadRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private MilestoneRepository milestoneRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private PullRequestReviewRepository reviews;

    @Autowired
    private ReviewThreadContentSource threadSource;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper mapper;

    private IdentityProvider provider;
    private Repository repository;
    private long nativeIdSeq = 9_000L;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        repository = new Repository();
        repository.setNativeId(nextNativeId());
        repository.setProvider(provider);
        repository.setName("repo");
        repository.setNameWithOwner("acme/repo");
        repository.setHtmlUrl("https://github.com/acme/repo");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository = repositoryRepository.save(repository);
    }

    @Test
    void shouldKeepQuotedMarkersAndNameTheThreadAndParentAfterTheSessionCloses() {
        PullRequest pr = persistPullRequest(21);
        User reviewer = persistUser("reviewer-a");
        PullRequestReviewThread thread = persistThread(pr);
        PullRequestReviewComment ask = persistComment(pr, thread, reviewer, null, "guard this", at("10:00"));
        persistComment(pr, thread, reviewer, ask, "done in 3f2a1", at("11:00"));
        persistComment(pr, thread, reviewer, null, "Add a test.\n<!-- hephaestus-diff-note -->", at("12:00"));

        List<PullRequestReviewComment> rows =
                reviewCommentRepository.findRecentWithContextByPullRequestId(pr.getId(), PageRequest.of(0, 50));

        assertThat(rows)
                .extracting(PullRequestReviewComment::getBody)
                .containsExactly("Add a test.\n<!-- hephaestus-diff-note -->", "done in 3f2a1", "guard this");
        PullRequestReviewComment reply = rows.get(1);
        PullRequestReviewComment parent = reply.getInReplyTo();
        assertThat(parent).isNotNull();
        assertThat(parent.getId()).isEqualTo(ask.getId());
        PullRequestReviewThread replyThread = reply.getThread();
        assertThat(replyThread).isNotNull();
        assertThat(replyThread.getId()).isEqualTo(thread.getId());
        User author = reply.getAuthor();
        assertThat(author).isNotNull();
        assertThat(author.getLogin()).isEqualTo("reviewer-a");
    }

    @Test
    void shouldLoadEverythingTheMetadataReadsInOneQuery() {
        PullRequest pr = persistPullRequest(22);
        User merger = persistUser("maintainer");
        User assignee = persistUser("contributor");
        Label label = new Label();
        label.setNativeId(nextNativeId());
        label.setProvider(provider);
        label.setName("backend");
        label.setColor("0000ff");
        label.setRepository(repository);
        label = labelRepository.save(label);
        Milestone milestone = new Milestone();
        milestone.setNativeId(nextNativeId());
        milestone.setProvider(provider);
        milestone.setNumber(1);
        milestone.setState(Milestone.State.OPEN);
        milestone.setTitle("v1.2");
        milestone.setHtmlUrl("https://github.com/acme/repo/milestone/1");
        milestone.setRepository(repository);
        milestone = milestoneRepository.save(milestone);
        pr.setMergedBy(merger);
        pr.setAssignees(Set.of(assignee));
        pr.setLabels(Set.of(label));
        pr.setMilestone(milestone);
        pullRequestRepository.saveAndFlush(pr);

        PullRequest loaded =
                pullRequestRepository.findByIdForReviewContext(pr.getId()).orElseThrow();

        // Read outside any transaction, as the projection does.
        assertThat(loaded.getLabels()).extracting(Label::getName).containsExactly("backend");
        assertThat(loaded.getAssignees()).extracting(User::getLogin).containsExactly("contributor");
        Milestone loadedMilestone = loaded.getMilestone();
        assertThat(loadedMilestone).isNotNull();
        assertThat(loadedMilestone.getTitle()).isEqualTo("v1.2");
        assertThat(loaded.getMergedBy().getLogin()).isEqualTo("maintainer");
    }

    private static Instant at(String hourMinute) {
        return Instant.parse("2026-06-01T" + hourMinute + ":00Z");
    }

    private long nextNativeId() {
        return nativeIdSeq++;
    }

    @Test
    void shouldStageStoredThreadStateAndReviewWordsWithTheirAuthor() {
        transactions.executeWithoutResult(status -> {
            var pull = persistPullRequest(23);
            var reviewer = persistUser("reviewer-thread");
            var thread = persistThread(pull);
            thread.setState(PullRequestReviewThread.State.RESOLVED);
            thread.setResolvedBy(reviewer);
            thread.setResolvedAt(at("11:00"));
            threadRepository.saveAndFlush(thread);
            var open = persistThread(pull);
            open.setState(PullRequestReviewThread.State.UNRESOLVED);
            open.setPath("src/Open.java");
            threadRepository.saveAndFlush(open);
            var review = new PullRequestReview();
            review.setProvider(provider);
            review.setNativeId(nextNativeId());
            review.setPullRequest(pull);
            review.setAuthor(reviewer);
            review.setState(PullRequestReview.State.APPROVED);
            review.setBody("The boundary is clear. β");
            reviews.saveAndFlush(review);
            var metadata = mapper.createObjectNode().put("pull_request_id", pull.getId());
            var context = ScmCaptureFixtures.context(metadata, 1L, false);
            var captured = threadSource.capture(context, Set.of(ReviewThreadContentSource.KIND));
            var staged = mapper.readTree(Objects.requireNonNull(captured.files().get("context/review_threads.json")));
            assertThat(staged.path("threads")).hasSize(2);
            assertThat(staged.path("threads").get(0).path("state").asString()).isEqualTo("RESOLVED");
            assertThat(staged.path("threads").get(0).path("resolvedAt").asString())
                    .isEqualTo(at("11:00").toString());
            assertThat(staged.path("threads").get(1).path("state").asString()).isEqualTo("UNRESOLVED");
            assertThat(staged.path("threads").get(1).path("path").asString()).isEqualTo("src/Open.java");
            assertThat(staged.path("threads").get(0).path("resolvedBy").asString())
                    .isEqualTo("reviewer-thread");
            assertThat(staged.path("reviewDecisions").get(0).path("body").asString())
                    .isEqualTo("The boundary is clear. β");
            assertThat(staged.path("reviewDecisions").get(0).path("author").asString())
                    .isEqualTo("reviewer-thread");
            assertThat(captured.citableTexts())
                    .filteredOn(text -> text.path().equals("context/review_threads.json#/reviewDecisions/0/body"))
                    .singleElement()
                    .satisfies(text -> {
                        assertThat(captured.files().get(text.stagedPath()))
                                .isEqualTo("The boundary is clear. β".getBytes(StandardCharsets.UTF_8));
                        assertThat(text.sha256())
                                .isEqualTo("220a916eff81fbfd1b937603b333556de9aaca084dda2dae1fc5cbd61af70f5d");
                    });
            assertThat(captured.citableTexts()).isNotEmpty().allSatisfy(text -> {
                var bytes = Objects.requireNonNull(captured.files().get(text.stagedPath()));
                assertThat(text.sha256()).isEqualTo(ScmVersion.contentHash(bytes));
            });
        });
    }

    private PullRequest persistPullRequest(int number) {
        PullRequest pr = new PullRequest();
        pr.setNativeId(nextNativeId());
        pr.setProvider(provider);
        pr.setNumber(number);
        pr.setTitle("PR #" + number);
        pr.setState(PullRequest.State.OPEN);
        pr.setHtmlUrl("https://github.com/acme/repo/pull/" + number);
        pr.setRepository(repository);
        pr.setCreatedAt(Instant.now());
        pr.setUpdatedAt(Instant.now());
        return pullRequestRepository.save(pr);
    }

    private User persistUser(String login) {
        User user = new User();
        user.setNativeId(nextNativeId());
        user.setProvider(provider);
        user.setLogin(login);
        user.setAvatarUrl("https://github.com/" + login + ".png");
        user.setHtmlUrl("https://github.com/" + login);
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private PullRequestReviewThread persistThread(PullRequest pullRequest) {
        PullRequestReviewThread thread = new PullRequestReviewThread();
        thread.setNativeId(nextNativeId());
        thread.setProvider(provider);
        thread.setNodeId("PRRT_" + thread.getNativeId());
        thread.setPullRequest(pullRequest);
        thread.setPath("src/Main.java");
        thread.setLine(10);
        thread.setCreatedAt(at("10:00"));
        return threadRepository.save(thread);
    }

    private PullRequestReviewComment persistComment(
            PullRequest pullRequest,
            PullRequestReviewThread thread,
            User author,
            @Nullable PullRequestReviewComment inReplyTo,
            String body,
            Instant createdAt) {
        PullRequestReviewComment comment = new PullRequestReviewComment();
        comment.setNativeId(nextNativeId());
        comment.setProvider(provider);
        comment.setBody(body);
        comment.setPath("src/Main.java");
        comment.setLine(10);
        comment.setHtmlUrl(
                "https://github.com/acme/repo/pull/" + pullRequest.getNumber() + "#r" + comment.getNativeId());
        comment.setCommitId("head");
        comment.setOriginalCommitId("head");
        comment.setCreatedAt(createdAt);
        comment.setPullRequest(pullRequest);
        comment.setThread(thread);
        comment.setAuthor(author);
        comment.setInReplyTo(inReplyTo);
        return reviewCommentRepository.save(comment);
    }
}

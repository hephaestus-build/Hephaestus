package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.MergeStateStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabHeadPipeline;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestReadinessReader;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** What Heph reads before merge advice, against a real schema: the staged MR !1 and its neighbours. */
class MergeReadinessContentSourceIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String INSTANCE = "https://gitlab.course.example";
    private static final String HEAD = "a".repeat(40);
    private static final String NOTE = "<!-- hephaestus-diff-note --> A practice note.";

    @Autowired
    private MergeReadinessContentSource source;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    @Autowired
    private IssueCommentRepository commentRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private PullRequestReviewThreadRepository threadRepository;

    @Autowired
    private PullRequestReviewCommentRepository reviewCommentRepository;

    @Autowired
    private FeedbackPlacementRepository placementRepository;

    @Autowired
    private MentorContextQueryRepository queryRepository;

    @Autowired
    private WorkspaceActorSelector actorSelector;

    @Autowired
    private TransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private GitLabMergeRequestProcessor mergeRequestProcessor;

    private final AtomicLong nativeIds = new AtomicLong(90_000);

    private IdentityProvider instance;
    private Workspace workspace;
    private Repository course;
    private User student;
    private User tutor;

    @BeforeEach
    void setUp() {
        instance = gitLabInstance(INSTANCE);
        student = userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "student", instance));
        tutor = userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "tutor", instance));
        workspace = createWorkspace("intro-course", "Intro course", "course", AccountType.ORG, student);
        connect(workspace, INSTANCE);
        course = repository(instance, "course/intro");
        monitor(workspace, "course/intro");
    }

    @Test
    void shouldKeepTheTutorsCiConditionBesideAnEmptyApprovalWhenTheMergeRequestIsBlocked() {
        PullRequest mr = mergeRequest(course, 1, false, MergeStateStatus.UNSTABLE, CheckState.FAILURE, HEAD);
        review(mr, tutor, PullRequestReview.State.APPROVED, "", at("10:00"));
        note(mr, tutor, "Looks good. Please merge only once the CI pipeline is green.", at("10:01"));
        note(mr, tutor, "Reminder: a green pipeline is required before merging.", at("10:05"));

        JsonNode payload = source.buildPayload(workspace.getId(), student.getId());

        assertThat(payload.path("notLoaded")).isEmpty();
        JsonNode entry = payload.path("pullRequests").get(0);
        assertThat(entry.path("number").asInt()).isEqualTo(1);
        assertThat(entry.path("mergeable").asString()).isEqualTo("NO");
        assertThat(entry.path("mergeStateStatus").asString()).isEqualTo("UNSTABLE");
        assertThat(entry.path("reviewDecision").asString()).isEqualTo("APPROVED");
        assertThat(entry.path("checks").asString()).isEqualTo("FAILURE");
        assertThat(entry.path("checksFor").asString()).isEqualTo("CURRENT_HEAD");
        JsonNode approval = entry.path("latestReviews").get(0);
        assertThat(approval.path("reviewer").asString()).isEqualTo("tutor");
        assertThat(approval.path("state").asString()).isEqualTo("APPROVED");
        assertThat(approval.has("body")).isFalse();
        assertThat(entry.path("generalNotes")
                        .valueStream()
                        .map(n -> n.path("author").asString() + ": "
                                + n.path("body").asString()))
                .containsExactly(
                        "tutor: Looks good. Please merge only once the CI pipeline is green.",
                        "tutor: Reminder: a green pipeline is required before merging.");
        assertThat(entry.path("generalNotesStatus").asString()).isEqualTo("COMPLETE");
    }

    @Test
    void shouldKeepTheCurrentUndatedApprovalAheadOfDatedHistoryAndADismissedOneBehindIt() {
        PullRequest mr = mergeRequest(course, 24, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        review(mr, tutor, PullRequestReview.State.CHANGES_REQUESTED, "Please fix this.", at("10:00"));
        PullRequestReview approval = review(mr, tutor, PullRequestReview.State.APPROVED, "", at("10:01"));
        approval.setSubmittedAt(null);
        reviewRepository.save(approval);

        JsonNode current = inspect(mr).path("latestReviews").get(0);
        assertThat(current.path("state").asString()).isEqualTo("APPROVED");
        assertThat(current.has("submittedAt")).isFalse();

        approval.setDismissed(true);
        approval.setState(PullRequestReview.State.DISMISSED);
        reviewRepository.save(approval);

        JsonNode withdrawn = inspect(mr).path("latestReviews").get(0);
        assertThat(withdrawn.path("state").asString()).isEqualTo("CHANGES_REQUESTED");
        assertThat(withdrawn.path("submittedAt").asString())
                .isEqualTo(at("10:00").toString());
    }

    @Test
    void shouldReportAGreenMergeRequestWithNoReviewerNotes() {
        PullRequest mr = mergeRequest(course, 2, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        review(mr, tutor, PullRequestReview.State.APPROVED, "", at("10:00"));

        JsonNode entry = source.buildPayload(workspace.getId(), student.getId())
                .path("pullRequests")
                .get(0);

        assertThat(entry.path("mergeable").asString()).isEqualTo("YES");
        assertThat(entry.path("mergeStateStatus").asString()).isEqualTo("CLEAN");
        assertThat(entry.path("checks").asString()).isEqualTo("SUCCESS");
        assertThat(entry.path("checksFor").asString()).isEqualTo("CURRENT_HEAD");
        assertThat(entry.path("generalNotes")).isEmpty();
        assertThat(entry.path("generalNotesStatus").asString()).isEqualTo("COMPLETE");
        assertThat(entry.path("threads")).isEmpty();
        assertThat(entry.path("threadsStatus").asString()).isEqualTo("COMPLETE");
    }

    @Test
    void shouldKeepATutorConditionInAThreadHephaestusStartedAndInAResolvedThreadOnAGreenMergeRequest() {
        PullRequest mr = mergeRequest(course, 9, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        review(mr, tutor, PullRequestReview.State.APPROVED, "", at("10:00"));
        // A personal-token connection posts Hephaestus's notes under the tutor's own account.
        PullRequestReviewThread answered = thread(mr, PullRequestReviewThread.State.UNRESOLVED);
        posted(mr, "gid://gitlab/DiffNote/" + reply(answered, tutor, NOTE).getNativeId());
        reply(answered, tutor, "Agreed, and please set up CI before merging.");
        PullRequestReviewThread resolved =
                thread(mr, PullRequestReviewThread.State.RESOLVED, tutor, "Merge only after CI is enabled.");
        resolved.setResolvedBy(student);
        threadRepository.save(resolved);
        PullRequestReviewThread own = thread(mr, PullRequestReviewThread.State.UNRESOLVED);
        posted(mr, "gid://gitlab/DiffNote/" + reply(own, tutor, NOTE).getNativeId());

        JsonNode entry = source.buildPayload(workspace.getId(), student.getId())
                .path("pullRequests")
                .get(0);

        assertThat(entry.path("threadsStatus").asString()).isEqualTo("COMPLETE");
        assertThat(entry.path("threads")).hasSize(2);
        JsonNode open = entry.path("threads").get(0);
        assertThat(open.path("state").asString()).isEqualTo("UNRESOLVED");
        assertThat(open.path("repliesToHephaestusNote").asBoolean()).isTrue();
        assertThat(open.path("comments")
                        .valueStream()
                        .map(c -> c.path("author").asString() + ": "
                                + c.path("body").asString()))
                .containsExactly("tutor: Agreed, and please set up CI before merging.");
        JsonNode closed = entry.path("threads").get(1);
        assertThat(closed.path("state").asString()).isEqualTo("RESOLVED");
        assertThat(closed.path("resolvedBy").asString()).isEqualTo("student");
        assertThat(closed.path("comments").get(0).path("body").asString()).isEqualTo("Merge only after CI is enabled.");
    }

    @Test
    void shouldKeepATutorConditionQuotingHephaestusInNotesAndThreadsWhileLeavingOutWhatItPosted() {
        PullRequest mr = mergeRequest(course, 10, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        review(mr, tutor, PullRequestReview.State.APPROVED, "", at("10:00"));
        String summary = "<!-- hephaestus:practice-review:1 --> Summary.";
        posted(mr, "gid://gitlab/Note/" + note(mr, tutor, summary, at("10:01")).getNativeId());
        note(mr, tutor, "> " + summary + "\n\nSet up CI before merging.", at("10:02"));
        // A GitHub node id cannot be matched to a stored comment, so that note stays, flagged.
        posted(mr, "IC_kwDOA1b2c3");
        note(mr, tutor, "<!-- hephaestus:practice-review:2 --> Summary.", at("10:03"));
        PullRequestReviewThread thread = thread(mr, PullRequestReviewThread.State.UNRESOLVED);
        posted(mr, "gid://gitlab/DiffNote/" + reply(thread, tutor, NOTE).getNativeId());
        reply(thread, tutor, "> " + NOTE + "\n\nPlease set up CI before merging.");

        JsonNode entry = source.buildPayload(workspace.getId(), student.getId())
                .path("pullRequests")
                .get(0);

        assertThat(entry.path("generalNotesStatus").asString()).isEqualTo("COMPLETE");
        assertThat(entry.path("generalNotes")
                        .valueStream()
                        .map(n -> n.path("body").asString()))
                .containsExactly(
                        "> " + summary + "\n\nSet up CI before merging.",
                        "<!-- hephaestus:practice-review:2 --> Summary.");
        assertThat(entry.path("generalNotes")
                        .valueStream()
                        .map(n -> n.path("quotesHephaestusMarker").asBoolean()))
                .containsOnly(true);
        assertThat(entry.path("threadsStatus").asString()).isEqualTo("COMPLETE");
        JsonNode condition = entry.path("threads").get(0).path("comments").get(0);
        assertThat(condition.path("author").asString()).isEqualTo("tutor");
        assertThat(condition.path("body").asString()).endsWith("Please set up CI before merging.");
        assertThat(condition.path("quotesHephaestusMarker").asBoolean()).isTrue();
    }

    @Test
    void shouldTellTheDevelopersOwnRepliesFromOtherParticipantsAndAnAutomatedReviewFromAPerson() {
        User bot = TestUserFactory.createUser(nativeIds.incrementAndGet(), "ci-bot", instance);
        bot.setType(User.Type.BOT);
        bot = userRepository.save(bot);
        PullRequest mr = mergeRequest(course, 20, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        review(mr, bot, PullRequestReview.State.APPROVED, "", at("10:00"));
        review(mr, tutor, PullRequestReview.State.COMMENTED, "", at("10:01"));
        note(mr, tutor, "Please say which issue this closes.", at("10:02"));
        posted(mr, "gid://gitlab/Note/" + note(mr, tutor, NOTE, at("10:03")).getNativeId());
        note(mr, student, "Added the issue to the description.", at("10:04"));
        note(mr, bot, "Pipeline passed.", at("10:05"));
        PullRequestReviewThread thread =
                thread(mr, PullRequestReviewThread.State.RESOLVED, tutor, "Why is the timeout this long?");
        reply(thread, student, "The course server is slow; I explained it in the description.");

        JsonNode entry = inspect(mr);

        assertThat(entry.path("latestReviews")
                        .valueStream()
                        .map(r -> r.path("reviewer").asString() + " "
                                + r.path("state").asString() + " bot="
                                + r.path("bot").asBoolean()))
                .containsExactly("tutor COMMENTED bot=false", "ci-bot APPROVED bot=true");
        assertThat(entry.path("generalNotes")
                        .valueStream()
                        .map(n -> n.path("author").asString() + " "
                                + n.path("authorRelation").asString() + " bot="
                                + n.path("bot").asBoolean()))
                .containsExactly(
                        "tutor OTHER_PARTICIPANT bot=false",
                        "student WORK_AUTHOR bot=false",
                        "ci-bot OTHER_PARTICIPANT bot=true");
        assertThat(entry.path("threads")
                        .get(0)
                        .path("comments")
                        .valueStream()
                        .map(c -> c.path("author").asString() + " "
                                + c.path("authorRelation").asString()))
                .containsExactly("tutor OTHER_PARTICIPANT", "student WORK_AUTHOR");
    }

    @Test
    void shouldFindATutorConditionBehindAPageOfHephaestusNotes() {
        JsonNode entry = mergeRequestBehindOwnNotes(11, 60);

        assertThat(entry.path("generalNotesStatus").asString()).isEqualTo("COMPLETE");
        assertThat(entry.path("generalNotes")
                        .valueStream()
                        .map(n -> n.path("body").asString()))
                .containsExactly("Set up CI before merging.");
    }

    @Test
    void shouldCallTheNotesCutWhenHephaestusNotesOutlastTheScan() {
        JsonNode entry = mergeRequestBehindOwnNotes(12, 210);

        assertThat(entry.path("generalNotesStatus").asString()).isEqualTo("TRUNCATED");
        assertThat(entry.path("generalNotes")).isEmpty();
    }

    /** A green MR whose tutor condition is older than {@code own} notes Hephaestus posted on it. */
    private JsonNode mergeRequestBehindOwnNotes(int number, int own) {
        PullRequest mr = mergeRequest(course, number, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        Instant start = at("10:00");
        note(mr, tutor, "Set up CI before merging.", start);
        String[] refs = new String[own];
        for (int i = 0; i < own; i++) {
            refs[i] = "gid://gitlab/Note/"
                    + note(mr, tutor, NOTE, start.plusSeconds(i + 1)).getNativeId();
        }
        posted(mr, refs);
        return source.buildPayload(workspace.getId(), student.getId())
                .path("pullRequests")
                .get(0);
    }

    @Test
    void shouldReadEveryPageFromOneSnapshotWhenASyncReopensAThreadBetweenPages() throws Exception {
        PullRequest mr = mergeRequest(course, 13, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        review(mr, tutor, PullRequestReview.State.APPROVED, "", at("10:00"));
        PullRequestReviewThread own = thread(mr, PullRequestReviewThread.State.UNRESOLVED);
        String[] refs = new String[50];
        for (int i = 0; i < refs.length; i++) {
            refs[i] = "gid://gitlab/DiffNote/" + reply(own, tutor, NOTE).getNativeId();
        }
        posted(mr, refs);
        PullRequestReviewThread condition =
                thread(mr, PullRequestReviewThread.State.RESOLVED, tutor, "Set up CI before merging.");
        // Reopening moves the tutor's thread ahead of the first page, past the second page's offset.
        AtomicInteger pages = new AtomicInteger();
        MentorContextQueryRepository reopenBeforeSecondPage = (MentorContextQueryRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {MentorContextQueryRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findThreadCommentsUnresolvedFirst") && pages.getAndIncrement() == 1) {
                        CompletableFuture.runAsync(() -> jdbcTemplate.update(
                                        "UPDATE pull_request_review_thread SET state = 'UNRESOLVED' WHERE id = ?",
                                        condition.getId()))
                                .join();
                    }
                    try {
                        return method.invoke(queryRepository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        ProxyFactory transactional = new ProxyFactory(new MergeReadinessContentSource(
                actorSelector, reopenBeforeSecondPage, reviewRepository, commentRepository, objectMapper));
        transactional.setProxyTargetClass(true);
        transactional.addAdvice(
                new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        Map<String, byte[]> files = new HashMap<>();

        ((MergeReadinessContentSource) transactional.getProxy())
                .contribute(
                        new ContextRequest.MentorChatRequest(
                                workspace.getId(), student.getId(), UUID.randomUUID(), null),
                        files);

        assertThat(pages.get()).isEqualTo(2);
        JsonNode entry = objectMapper
                .readTree(files.get(MergeReadinessContentSource.OUTPUT_KEY))
                .path("pullRequests")
                .get(0);
        assertThat(entry.path("threadsStatus").asString()).isEqualTo("COMPLETE");
        assertThat(entry.path("threads")
                        .valueStream()
                        .map(t -> t.path("comments").get(0).path("body").asString()))
                .containsExactly("Set up CI before merging.");
    }

    @Test
    void shouldMarkWhatIsUnknownFromAnotherCommitOrCutOff() {
        PullRequest oldest = mergeRequest(course, 20, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        for (int number = 21; number < 25; number++) {
            mergeRequest(course, number, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        }
        PullRequest mr = mergeRequest(course, 3, null, null, CheckState.SUCCESS, "b".repeat(40));
        for (int minute = 10; minute <= 19; minute++) {
            note(mr, tutor, "note " + minute, at("10:" + minute));
        }
        note(mr, tutor, "x".repeat(5_000), at("10:20"));
        for (int reviewer = 0; reviewer < 7; reviewer++) {
            User user = userRepository.save(
                    TestUserFactory.createUser(nativeIds.incrementAndGet(), "reviewer-" + reviewer, instance));
            review(mr, user, PullRequestReview.State.COMMENTED, "y".repeat(5_000), at("11:0" + reviewer));
        }
        for (int t = 0; t < 6; t++) {
            thread(mr, PullRequestReviewThread.State.UNRESOLVED, tutor, "open thread " + t);
        }

        JsonNode payload = source.buildPayload(workspace.getId(), student.getId());
        JsonNode entry = payload.path("pullRequests").get(0);

        assertThat(entry.path("number").asInt()).isEqualTo(3);
        assertThat(entry.path("mergeable").asString()).isEqualTo("UNKNOWN");
        assertThat(entry.path("mergeStateStatus").asString()).isEqualTo("UNKNOWN");
        assertThat(entry.path("checksFor").asString()).isEqualTo("OTHER_COMMIT");
        assertThat(entry.path("recordUpdatedAt").asString()).isEqualTo("UNKNOWN");
        assertThat(entry.path("generalNotesStatus").asString()).isEqualTo("TRUNCATED");
        assertThat(entry.path("generalNotes")).hasSize(10);
        assertThat(entry.path("generalNotes").get(8).path("body").asString()).isEqualTo("note 19");
        assertThat(entry.path("generalNotes").get(9).path("body").asString()).hasSize(1_000);
        assertThat(entry.path("generalNotes").get(9).path("bodyTruncated").asBoolean())
                .isTrue();
        assertThat(entry.path("latestReviewsStatus").asString()).isEqualTo("TRUNCATED");
        assertThat(entry.path("latestReviews")).hasSize(5);
        assertThat(entry.path("latestReviews").get(0).path("body").asString()).hasSize(1_000);
        assertThat(entry.path("latestReviews").get(0).path("bodyTruncated").asBoolean())
                .isTrue();
        assertThat(entry.path("threads")).hasSize(5);
        assertThat(entry.path("threadsStatus").asString()).isEqualTo("TRUNCATED");
        assertThat(payload.path("pullRequests")).hasSize(5);
        assertThat(payload.path("notLoaded")
                        .valueStream()
                        .map(pr -> pr.path("number").asInt()))
                .containsExactly(20);
        assertThat(payload.path("notLoadedTruncated").asBoolean()).isFalse();
        JsonNode inspected = source.inspect(workspace.getId(), student.getId(), oldest.getId());
        assertThat(inspected.path("pullRequests").get(0).path("number").asInt()).isEqualTo(20);
        assertThat(inspected.path("pullRequests").get(0).path("checks").asString())
                .isEqualTo("SUCCESS");

        Workspace disconnected = createWorkspace("no-connection", "No connection", "other", AccountType.ORG, student);
        monitor(disconnected, "course/intro");
        JsonNode unavailable = source.buildPayload(disconnected.getId(), student.getId());
        assertThat(unavailable.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.has("pullRequests")).isFalse();
    }

    @Test
    void shouldReportADismissedApprovalAndTheStoredSyncTimeRatherThanFreshness() {
        PullRequest mr = mergeRequest(course, 7, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        Instant synced = at("09:00");
        mr.setLastSyncAt(synced);
        pullRequestRepository.save(mr);
        review(mr, tutor, PullRequestReview.State.COMMENTED, "Almost there.", at("09:30"));
        PullRequestReview approval = review(mr, tutor, PullRequestReview.State.APPROVED, "", at("10:00"));
        approval.setDismissed(true);
        reviewRepository.save(approval);

        JsonNode payload = source.buildPayload(workspace.getId(), student.getId());
        JsonNode entry = payload.path("pullRequests").get(0);

        assertThat(payload.path("providerFreshness").asString()).isEqualTo("UNKNOWN");
        assertThat(entry.path("recordUpdatedAt").asString()).isEqualTo(synced.toString());
        assertThat(entry.path("checks").asString()).isEqualTo("SUCCESS");
        assertThat(entry.path("latestReviews")).hasSize(1);
        JsonNode latest = entry.path("latestReviews").get(0);
        assertThat(latest.path("state").asString()).isEqualTo("DISMISSED");
        assertThat(latest.path("dismissedState").asString()).isEqualTo("APPROVED");
    }

    @Test
    void shouldReadOnlyTheConnectedInstanceThisWorkspaceAndLiveWorkWhateverItsState() {
        mergeRequest(course, 4, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        // Merged, so that only the scope, not the open-state filter of the list, keeps each of these out of a detail.
        // The same path on another GitLab instance; its monitor row would match by path alone.
        Repository samePathElsewhere = repository(gitLabInstance("https://gitlab.other.example"), "course/intro");
        PullRequest elsewhere =
                merged(mergeRequest(samePathElsewhere, 5, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD));
        Repository unmonitored = repository(instance, "course/other");
        PullRequest notMonitored =
                merged(mergeRequest(unmonitored, 6, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD));
        PullRequest deleted = merged(mergeRequest(course, 7, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD));
        deleted.setDeletedAt(Instant.now());
        pullRequestRepository.save(deleted);
        PullRequest ours = merged(mergeRequest(course, 8, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD));
        // Connected to the same instance, but not monitoring this project.
        Workspace other = createWorkspace("other-course", "Other course", "other-course", AccountType.ORG, student);
        connect(other, INSTANCE);

        JsonNode payload = source.buildPayload(workspace.getId(), student.getId());

        assertThat(payload.path("pullRequests")
                        .valueStream()
                        .map(pr -> pr.path("number").asInt()))
                .containsExactly(4);
        for (PullRequest hidden : List.of(elsewhere, notMonitored, deleted)) {
            assertThat(source.inspect(workspace.getId(), student.getId(), hidden.getId())
                            .path("status")
                            .asString())
                    .as("MR !%d", hidden.getNumber())
                    .isEqualTo("NOT_FOUND");
        }
        assertThat(source.inspect(workspace.getId(), tutor.getId(), ours.getId())
                        .path("status")
                        .asString())
                .isEqualTo("NOT_FOUND");
        assertThat(source.inspect(other.getId(), student.getId(), ours.getId())
                        .path("status")
                        .asString())
                .isEqualTo("NOT_FOUND");
        assertThat(inspect(ours).path("state").asString()).isEqualTo("MERGED");
    }

    @Test
    void shouldGiveMergedAndClosedWorkItsStoredStateDiscussionAndClosingIssueOutsideTheOpenList() {
        User bot = TestUserFactory.createUser(nativeIds.incrementAndGet(), "ci-bot", instance);
        bot.setType(User.Type.BOT);
        bot = userRepository.save(bot);
        PullRequest mr = mergeRequest(course, 9, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        review(mr, bot, PullRequestReview.State.APPROVED, "", at("10:00"));
        note(mr, tutor, "Link the issue before this goes in.", at("10:01"));
        PullRequestReviewThread thread =
                thread(mr, PullRequestReviewThread.State.RESOLVED, tutor, "Why is the timeout this long?");
        reply(thread, student, "The course server is slow; I explained it in the description.");
        JsonNode beforeMerge = inspect(mr);
        mr.setMergedBy(bot);
        mr = merged(mr);
        Issue linked = issue(course, 1, "- [ ] The setup guide builds");
        linked.setState(Issue.State.CLOSED);
        closes(mr, issueRepository.save(linked));
        PullRequest abandoned = mergeRequest(course, 10, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        abandoned.setState(PullRequest.State.CLOSED);
        pullRequestRepository.save(abandoned);

        JsonNode payload = source.buildPayload(workspace.getId(), student.getId());
        JsonNode entry = inspect(mr);
        JsonNode closed = inspect(abandoned);

        assertThat(payload.path("pullRequests")).isEmpty();
        assertThat(payload.path("notLoaded")).isEmpty();
        assertThat(beforeMerge.path("state").asString()).isEqualTo("OPEN");
        assertThat(entry.path("state").asString()).isEqualTo("MERGED");
        assertThat(entry.path("isMerged").asBoolean()).isTrue();
        assertThat(entry.path("mergedAt").asString()).isEqualTo(NOW.toString());
        assertThat(entry.path("mergedBy").asString()).isEqualTo("ci-bot");
        assertThat(entry.path("mergedByBot").asBoolean()).isTrue();
        // The stored discussion reads the same after the merge as before it.
        for (String field : List.of("latestReviews", "generalNotes", "threads")) {
            assertThat(entry.path(field)).as(field).isEqualTo(beforeMerge.path(field));
        }
        assertThat(entry.path("latestReviews").get(0).path("bot").asBoolean()).isTrue();
        assertThat(entry.path("threads")
                        .get(0)
                        .path("comments")
                        .valueStream()
                        .map(c -> c.path("author").asString() + " "
                                + c.path("authorRelation").asString()))
                .containsExactly("tutor OTHER_PARTICIPANT", "student WORK_AUTHOR");
        assertThat(entry.path("closingIssues")
                        .valueStream()
                        .map(issue -> issue.path("number").asInt() + " "
                                + issue.path("state").asString()))
                .containsExactly("1 CLOSED");
        assertThat(closed.path("state").asString()).isEqualTo("CLOSED");
        assertThat(closed.path("isMerged").asBoolean()).isFalse();
        assertThat(closed.has("mergedAt")).isFalse();
        assertThat(closed.has("mergedBy")).isFalse();
    }

    @Test
    void shouldMarkNotesPartialWhenATutorConditionLiesPastTheCut() {
        PullRequest mr = mergeRequest(course, 8, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        review(mr, tutor, PullRequestReview.State.APPROVED, "", at("10:00"));
        note(mr, tutor, "z".repeat(1_000) + " Do not merge until CI is configured.", at("10:01"));

        JsonNode entry = source.buildPayload(workspace.getId(), student.getId())
                .path("pullRequests")
                .get(0);

        assertThat(entry.path("generalNotesStatus").asString()).isEqualTo("TRUNCATED");
        assertThat(entry.path("generalNotes").get(0).path("body").asString()).doesNotContain("Do not merge");
        assertThat(entry.path("generalNotes").get(0).path("bodyTruncated").asBoolean())
                .isTrue();
    }

    @Test
    void shouldStayWholeJsonUnderTheRunnerCapWhenEveryBodyIsEscaped() {
        String escaped = "\u0001".repeat(1_000);
        List<User> reviewers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            reviewers.add(userRepository.save(
                    TestUserFactory.createUser(nativeIds.incrementAndGet(), "escaper-" + i, instance)));
        }
        for (int number = 30; number < 35; number++) {
            PullRequest mr = mergeRequest(course, number, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
            for (int n = 0; n < 10; n++) {
                note(mr, tutor, escaped, at("10:" + (10 + n)));
            }
            for (int r = 0; r < 5; r++) {
                review(mr, reviewers.get(r), PullRequestReview.State.COMMENTED, escaped, at("11:0" + r));
            }
        }

        ObjectNode payload = source.buildPayload(workspace.getId(), student.getId());
        String json = objectMapper.writeValueAsString(payload);

        assertThat(json.length()).isLessThanOrEqualTo(MentorContextKeys.FETCH_CONTEXT_MAX_CHARS);
        assertThat(objectMapper.readTree(json).path("sizeLimited").asBoolean()).isTrue();
        assertThat(payload.path("pullRequests").size()
                        + payload.path("notLoaded").size())
                .isEqualTo(5);
        assertThat(payload.path("notLoaded")).isNotEmpty();
    }

    @Test
    void shouldGiveTheDescriptionAndTheOpenConditionOfTheIssueItCloses() {
        PullRequest mr = mergeRequest(course, 12, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        mr.setBody("Adds the setup guide.\n\nCloses #1");
        closes(mr, issue(course, 1, "- [ ] The tutor confirmed the setup guide builds"));

        JsonNode entry = inspect(mr);

        assertThat(entry.path("description").asString()).isEqualTo("Adds the setup guide.\n\nCloses #1");
        assertThat(entry.path("closingIssuesStatus").asString()).isEqualTo("COMPLETE");
        JsonNode closing = entry.path("closingIssues").get(0);
        assertThat(closing.path("number").asInt()).isEqualTo(1);
        assertThat(closing.path("state").asString()).isEqualTo("OPEN");
        assertThat(closing.path("relation").asString()).isEqualTo("PROVIDER_RECORDED_CLOSING_CANDIDATE");
        assertThat(closing.path("body").asString()).contains("- [ ] The tutor confirmed");
    }

    @Test
    void shouldGiveAGitHubPullRequestsDescriptionAndClosingIssueInTheSameShape() {
        IdentityProvider github = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        User author = userRepository.save(TestUserFactory.createUser(nativeIds.incrementAndGet(), "gh-dev", github));
        Workspace team = createWorkspace("gh-course", "GH course", "gh-course", AccountType.ORG, author);
        Connection connection = new Connection(
                team, IntegrationKind.GITHUB, "pat", new ConnectionConfig.GitHubPatConfig("gh-course", null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        connectionRepository.saveAndFlush(connection);
        Repository app = repository(github, "gh-course/app");
        monitor(team, "gh-course/app");
        PullRequest pr = mergeRequest(app, 3, true, MergeStateStatus.CLEAN, CheckState.NONE, HEAD);
        pr.setAuthor(author);
        pr.setBody("Fixes #2");
        closes(pr, issue(app, 2, "Acceptance: the login page shows an error message."));

        JsonNode entry = source.inspect(team.getId(), author.getId(), pr.getId())
                .path("pullRequests")
                .get(0);

        assertThat(entry.path("description").asString()).isEqualTo("Fixes #2");
        assertThat(entry.path("closingIssues").get(0).path("body").asString())
                .isEqualTo("Acceptance: the login page shows an error message.");
        assertThat(entry.path("checks").asString()).isEqualTo("NONE");
        assertThat(entry.path("checksObserved").asString()).isEqualTo("NONE_REPORTED");
    }

    @Test
    void shouldCutTheClosingIssuesAtFiveAndEachBodyWithoutSplittingACharacter() {
        PullRequest mr = mergeRequest(course, 13, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        mr.setBody("d".repeat(999) + "\uD83D\uDE00 Condition past the cut.");
        List<Issue> closing = new ArrayList<>();
        closing.add(issue(course, 1, "a".repeat(999) + "\uD83D\uDE00 Condition past the cut."));
        for (int number = 2; number <= 6; number++) {
            closing.add(issue(course, number, "Issue " + number));
        }
        closes(mr, closing.toArray(Issue[]::new));

        JsonNode entry = inspect(mr);

        assertThat(entry.path("description").asString()).hasSize(999);
        assertThat(entry.path("descriptionTruncated").asBoolean()).isTrue();
        assertThat(entry.path("closingIssuesStatus").asString()).isEqualTo("TRUNCATED");
        assertThat(entry.path("closingIssues")
                        .valueStream()
                        .map(issue -> issue.path("number").asInt()))
                .containsExactly(1, 2, 3, 4, 5);
        JsonNode clipped = entry.path("closingIssues").get(0);
        assertThat(clipped.path("body").asString()).hasSize(999).doesNotContain("Condition");
        assertThat(clipped.path("bodyTruncated").asBoolean()).isTrue();
    }

    @Test
    void shouldGiveOnlyTheLiveIssuesOfTheSameRepositoryAsClosingIssues() {
        PullRequest mr = mergeRequest(course, 14, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD);
        Issue tombstoned = issue(course, 3, "Deleted upstream.");
        tombstoned.setDeletedAt(Instant.now());
        issueRepository.save(tombstoned);
        closes(
                mr,
                issue(course, 1, "This one."),
                issue(repository(instance, "course/other"), 2, "Another repository's issue."),
                tombstoned,
                mergeRequest(course, 4, true, MergeStateStatus.CLEAN, CheckState.SUCCESS, HEAD));

        JsonNode entry = inspect(mr);

        assertThat(entry.path("closingIssues")
                        .valueStream()
                        .map(issue -> issue.path("number").asInt()))
                .containsExactly(1);
        assertThat(entry.path("closingIssuesStatus").asString()).isEqualTo("COMPLETE");
    }

    @Test
    void shouldSayWhatWasObservedForTheCurrentHeadAndWhichCommitEachReviewWasFor() {
        PullRequest noPipeline = mergeRequest(course, 15, true, MergeStateStatus.CLEAN, CheckState.NO_PIPELINE, HEAD);
        PullRequestReview earlier = review(noPipeline, tutor, PullRequestReview.State.APPROVED, "", at("10:00"));
        earlier.setCommitId("b".repeat(40));
        reviewRepository.save(earlier);
        PullRequest skipped = mergeRequest(course, 16, true, MergeStateStatus.CLEAN, CheckState.SKIPPED, HEAD);
        PullRequest otherCommit =
                mergeRequest(course, 17, true, MergeStateStatus.CLEAN, CheckState.FAILURE, "c".repeat(40));

        JsonNode none = inspect(noPipeline);
        assertThat(none.path("checksObserved").asString()).isEqualTo("NO_PIPELINE_REPORTED");
        assertThat(none.path("headSha").asString()).isEqualTo(HEAD);
        JsonNode approval = none.path("latestReviews").get(0);
        assertThat(approval.path("state").asString()).isEqualTo("APPROVED");
        assertThat(approval.path("commit").asString()).isEqualTo("b".repeat(40));
        assertThat(approval.path("commitFor").asString()).isEqualTo("OTHER_COMMIT");
        assertThat(inspect(skipped).path("checksObserved").asString()).isEqualTo("SKIPPED_PIPELINE_REPORTED");
        JsonNode stale = inspect(otherCommit);
        assertThat(stale.path("checks").asString()).isEqualTo("FAILURE");
        assertThat(stale.path("checksSha").asString()).isEqualTo("c".repeat(40));
        assertThat(stale.path("checksObserved").asString()).isEqualTo("NOT_CAPTURED");
    }

    @Test
    void shouldReportTheNoPipelineAGitLabReadRecordedAfterTheWebhook() {
        // The webhook stored the head; the checks on record are an earlier commit's.
        PullRequest mr = mergeRequest(course, 18, null, null, CheckState.SUCCESS, "e".repeat(40));
        assertThat(inspect(mr).path("checksObserved").asString()).isEqualTo("NOT_CAPTURED");
        Instant readAt = Instant.now();

        boolean recorded = mergeRequestProcessor.applyReadiness(
                course,
                18,
                new GitLabMergeRequestReadinessReader.Facts(
                        course.getNativeId(),
                        mr.getNativeId(),
                        "opened",
                        readAt,
                        HEAD,
                        true,
                        "MERGEABLE",
                        true,
                        GitLabHeadPipeline.NO_PIPELINE,
                        List.of(),
                        List.of(),
                        GitLabMergeRequestReadinessReader.Merge.UNKNOWN),
                readAt,
                ProcessingContext.forSync(null, course));

        assertThat(recorded).isTrue();
        JsonNode entry = inspect(mr);
        assertThat(entry.path("checks").asString()).isEqualTo("NO_PIPELINE");
        assertThat(entry.path("checksFor").asString()).isEqualTo("CURRENT_HEAD");
        assertThat(entry.path("checksObserved").asString()).isEqualTo("NO_PIPELINE_REPORTED");
        assertThat(entry.path("mergeable").asString()).isEqualTo("YES");
    }

    private JsonNode inspect(PullRequest pr) {
        return source.inspect(workspace.getId(), student.getId(), pr.getId())
                .path("pullRequests")
                .get(0);
    }

    private Issue issue(Repository repository, int number, String body) {
        Issue issue = new Issue();
        issue.setNativeId(nativeIds.incrementAndGet());
        issue.setProvider(repository.getProvider());
        issue.setRepository(repository);
        issue.setNumber(number);
        issue.setTitle("Issue " + number);
        issue.setBody(body);
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/-/issues/" + number);
        issue.setAuthor(tutor);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        return issueRepository.save(issue);
    }

    /** Records, as the provider's closing references do, that {@code pr} closes {@code issues}. */
    private void closes(PullRequest pr, Issue... issues) {
        pr.getClosingIssues().addAll(List.of(issues));
        pullRequestRepository.save(pr);
    }

    private IdentityProvider gitLabInstance(String serverUrl) {
        return gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, serverUrl)
                .orElseGet(
                        () -> gitProviderRepository.save(new IdentityProvider(IdentityProviderType.GITLAB, serverUrl)));
    }

    private void connect(Workspace target, String serverUrl) {
        Connection connection = new Connection(
                target,
                IntegrationKind.GITLAB,
                "GITLAB",
                new ConnectionConfig.GitLabConfig(
                        serverUrl, null, null, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of(), null));
        connection.setState(IntegrationState.ACTIVE);
        connectionRepository.saveAndFlush(connection);
    }

    private Repository repository(IdentityProvider provider, String path) {
        Repository repository = new Repository();
        repository.setNativeId(nativeIds.incrementAndGet());
        repository.setProvider(provider);
        repository.setName(path.substring(path.indexOf('/') + 1));
        repository.setNameWithOwner(path);
        repository.setHtmlUrl(provider.getServerUrl() + "/" + path);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        return repositoryRepository.save(repository);
    }

    private void monitor(Workspace target, String path) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(target);
        monitor.setNameWithOwner(path);
        monitorRepository.save(monitor);
    }

    private PullRequest mergeRequest(
            Repository repository,
            int number,
            @Nullable Boolean mergeable,
            @Nullable MergeStateStatus mergeState,
            CheckState checks,
            String checkedSha) {
        PullRequest mr = new PullRequest();
        mr.setNativeId(nativeIds.incrementAndGet());
        mr.setProvider(repository.getProvider());
        mr.setRepository(repository);
        mr.setNumber(number);
        mr.setTitle("MR !" + number);
        mr.setState(PullRequest.State.OPEN);
        mr.setHtmlUrl(repository.getHtmlUrl() + "/-/merge_requests/" + number);
        mr.setAuthor(student);
        mr.setMergeable(mergeable);
        mr.setMergeStateStatus(mergeState);
        mr.setReviewDecision(ReviewDecision.APPROVED);
        mr.setHeadRefName("feature-" + number);
        mr.setBaseRefName("main");
        mr.setHeadRefOid(HEAD);
        mr.observeHeadChecks(checkedSha, checks, true);
        mr.setCreatedAt(Instant.now());
        mr.setUpdatedAt(Instant.now());
        return pullRequestRepository.save(mr);
    }

    private PullRequestReview review(
            PullRequest mr, User reviewer, PullRequestReview.State state, String body, Instant at) {
        PullRequestReview review = new PullRequestReview();
        review.setNativeId(nativeIds.incrementAndGet());
        review.setProvider(mr.getProvider());
        review.setPullRequest(mr);
        review.setAuthor(reviewer);
        review.setState(state);
        review.setBody(body);
        review.setHtmlUrl(mr.getHtmlUrl() + "#approvals");
        review.setSubmittedAt(at);
        return reviewRepository.save(review);
    }

    private IssueComment note(PullRequest mr, User author, String body, Instant at) {
        IssueComment comment = new IssueComment();
        comment.setNativeId(nativeIds.incrementAndGet());
        comment.setProvider(mr.getProvider());
        comment.setIssue(mr);
        comment.setAuthor(author);
        comment.setBody(body);
        comment.setHtmlUrl(mr.getHtmlUrl() + "#note_" + comment.getNativeId());
        comment.setAuthorAssociation(AuthorAssociation.MEMBER);
        comment.setCreatedAt(at);
        return commentRepository.save(comment);
    }

    private PullRequestReviewThread thread(
            PullRequest mr, PullRequestReviewThread.State state, User author, String body) {
        PullRequestReviewThread thread = thread(mr, state);
        reply(thread, author, body);
        return thread;
    }

    private PullRequestReviewThread thread(PullRequest mr, PullRequestReviewThread.State state) {
        PullRequestReviewThread thread = new PullRequestReviewThread();
        thread.setNativeId(nativeIds.incrementAndGet());
        thread.setProvider(mr.getProvider());
        thread.setPullRequest(mr);
        thread.setState(state);
        thread.setPath("src/Main.java");
        thread.setLine(12);
        thread.setCreatedAt(Instant.now());
        return threadRepository.save(thread);
    }

    private PullRequestReviewComment reply(PullRequestReviewThread thread, User author, String body) {
        PullRequest mr = Objects.requireNonNull(thread.getPullRequest());
        PullRequestReviewComment comment = new PullRequestReviewComment();
        comment.setNativeId(nativeIds.incrementAndGet());
        comment.setProvider(mr.getProvider());
        comment.setPullRequest(mr);
        comment.setThread(thread);
        comment.setAuthor(author);
        comment.setBody(body);
        comment.setPath("src/Main.java");
        comment.setLine(12);
        comment.setCommitId(HEAD);
        comment.setOriginalCommitId(HEAD);
        comment.setHtmlUrl(mr.getHtmlUrl() + "#note_" + comment.getNativeId());
        comment.setCreatedAt(Instant.now());
        return reviewCommentRepository.save(comment);
    }

    /** Records {@code mr} as merged, as a provider sync stores it. */
    private PullRequest merged(PullRequest mr) {
        mr.setState(PullRequest.State.MERGED);
        mr.setMerged(true);
        mr.setMergedAt(NOW);
        return pullRequestRepository.save(mr);
    }

    /** What the delivery ledger records when Hephaestus posts {@code refs} as feedback on {@code mr}. */
    private void posted(PullRequest mr, String... refs) {
        AgentJob job = persistPullRequestReview(workspace, mr.getNumber(), NOW);
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(mr.getId())
                .recipientUserId(student.getId())
                .aboutUserId(student.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(0)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .body("feedback")
                .source(FeedbackSource.AGENT)
                .createdAt(NOW)
                .deliveredAt(NOW)
                .build());
        for (String ref : refs) {
            placementRepository.save(FeedbackPlacement.builder()
                    .feedback(feedback)
                    .placementType(PlacementType.INLINE)
                    .postedCommentRef(ref)
                    .createdAt(NOW)
                    .build());
        }
    }

    private static Instant at(String hourMinute) {
        return Instant.parse("2026-09-20T" + hourMinute + ":00Z");
    }
}

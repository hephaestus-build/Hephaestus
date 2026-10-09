package de.tum.cit.aet.hephaestus.integration.scm.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection.Format;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection.ProjectedRecord;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
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
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchCompletion;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchInsert;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class ScmJobFolderProjectorIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final Set<SourceKind> CORE =
            Set.of(new SourceKind("scm.issue.core"), new SourceKind("scm.pull-request.core"));
    private static final SourceKind ISSUE_COMMENTS = new SourceKind("scm.issue.comments");
    private static final SourceKind GENERAL = new SourceKind("scm.general-review-comments");
    private static final SourceKind INLINE = new SourceKind("scm.pull-request.comments");
    private static final SourceKind THREADS = new SourceKind("scm.review-threads");
    private static final Set<SourceKind> ALL = Set.of(
            new SourceKind("scm.issue.core"),
            new SourceKind("scm.pull-request.core"),
            ISSUE_COMMENTS,
            GENERAL,
            INLINE,
            THREADS);
    private static final Instant DAY = Instant.parse("2026-09-01T10:00:00Z");

    @Autowired
    private ScmJobFolderProjector projector;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private IssueCommentRepository commentRepository;

    @Autowired
    private PullRequestReviewThreadRepository threadRepository;

    @Autowired
    private PullRequestReviewCommentRepository inlineRepository;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    @Autowired
    private PersonDataCopyRecorder personCopies;

    @Autowired
    private AgentJobRepository jobs;

    @Autowired
    private FeedbackRepository feedback;

    @Autowired
    private FeedbackPlacementRepository placements;

    @Autowired
    private FeedbackDispatchRepository dispatches;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper mapper;

    private final AtomicLong nativeIds = new AtomicLong(238_500);

    private User author;
    private Workspace workspace;
    private int position;

    @BeforeEach
    void seedWorkspace() {
        author = persistUser("label-author");
        workspace = createWorkspace("labels", "Labels", "labels-org", AccountType.ORG, author);
        position = 0;
    }

    @Test
    void shouldProjectStoredLabelsInCodePointOrderWhenIssuesAndPullRequestsAreRecorded() {
        Repository widgets = repository("labels-org/widgets", workspace);
        Label bug = label("bug", widgets);
        issue(widgets, 1, label("area: api", widgets), bug, label("Documentation", widgets));
        issue(widgets, 2);
        pullRequest(widgets, 3, bug);

        List<ProjectedRecord> records = project(workspace, widgets);

        assertThat(records)
                .extracting(ProjectedRecord::path)
                .containsExactly(
                        "issues/1/record.json",
                        "issues/1/description.md",
                        "issues/2/record.json",
                        "issues/2/description.md",
                        "pulls/3/record.json",
                        "pulls/3/description.md");
        assertThat(records)
                .filteredOn(record -> record.format() == Format.JSON)
                .extracting(record -> record.value().path("labels").toString())
                .as("the same order as the captured issue context, and an explicit empty array when unlabelled")
                .containsExactly("[\"Documentation\",\"area: api\",\"bug\"]", "[]", "[\"bug\"]");
    }

    @Test
    void shouldProjectNoRecordsWhenTheWorkspaceDoesNotMonitorTheRepository() {
        User otherOwner = persistUser("other-owner");
        Workspace other = createWorkspace("other", "Other", "other-org", AccountType.ORG, otherOwner);
        Repository widgets = repository("labels-org/widgets", workspace);
        Repository elsewhere = repository("labels-org/elsewhere", other);
        Issue mine = issue(widgets, 1, label("bug", widgets));
        Issue theirs = issue(elsewhere, 1, label("bug", elsewhere));
        comment(mine, 8_001, "Mine");
        comment(theirs, 8_002, "Theirs");

        assertThat(project(other, widgets, ALL)).isEmpty();
        assertThat(project(workspace, elsewhere, ALL)).isEmpty();
        assertThat(project(workspace, widgets, ALL))
                .extracting(ProjectedRecord::path)
                .containsExactly("issues/1/record.json", "issues/1/description.md", "issues/1/comments.jsonl");
    }

    @Test
    void shouldWriteEachFileWithItsOwnRowsInOrderWhenIssuesAndPullRequestsAreReadByFamily() {
        Repository widgets = repository("labels-org/families", workspace);
        Issue first = issue(widgets, 1);
        PullRequest change = pullRequest(widgets, 2);
        Issue removed = issue(widgets, 3);
        comment(first, 1_002, "Second on the issue");
        comment(change, 2_001, "Asked on the pull request");
        comment(change, 2_002, "A note of our own " + WorkspaceScmProjection.HEPHAESTUS_MARKER);
        comment(first, 1_003, "Third on the issue");
        comment(removed, 3_001, "On work that was deleted");
        inline(change, 4_001);
        User reviewer = persistUser("family-reviewer");
        PullRequestReview review = new PullRequestReview();
        review.setNativeId(4_002L);
        review.setProvider(change.getProvider());
        review.setPullRequest(change);
        review.setAuthor(reviewer);
        review.setState(PullRequestReview.State.COMMENTED);
        review.setBody("The empty case is useful.");
        review.setHtmlUrl(change.getHtmlUrl() + "#pullrequestreview-4002");
        reviewRepository.save(review);
        removed.setDeletedAt(DAY);
        issueRepository.save(removed);

        List<ProjectedRecord> records;
        try (var capture = personCopies.begin()) {
            records = project(workspace, widgets, ALL);
            assertThat(capture.identities())
                    .extracting(PersonCopyIdentity::subject)
                    .containsExactlyInAnyOrder(
                            Long.toString(author.getNativeId()), Long.toString(reviewer.getNativeId()));
        }

        assertThat(records)
                .filteredOn(record -> record.format() != Format.JSONL)
                .extracting(ProjectedRecord::path)
                .containsExactly(
                        "issues/1/record.json",
                        "issues/1/description.md",
                        "pulls/2/record.json",
                        "pulls/2/description.md");
        assertThat(rowsByFile(records))
                .containsExactly(
                        Map.entry("issues/1/comments.jsonl", List.of(1_002L, 1_003L)),
                        Map.entry("pulls/2/comments.jsonl", List.of(2_001L)),
                        Map.entry("pulls/2/inline-comments.jsonl", List.of(4_001L)),
                        Map.entry("pulls/2/reviews.jsonl", List.of(4_002L)),
                        Map.entry("pulls/2/threads.jsonl", List.of(4_001L)));
        assertThat(records)
                .filteredOn(record -> record.format() == Format.JSONL)
                .allSatisfy(record -> {
                    assertThat(record.kind())
                            .isEqualTo(
                                    record.path().startsWith("issues/")
                                            ? ISSUE_COMMENTS
                                            : record.path().endsWith("inline-comments.jsonl")
                                                    ? INLINE
                                                    : record.path().endsWith("threads.jsonl") ? THREADS : GENERAL);
                    // The stored row as it was, without the work it was joined to.
                    assertThat(record.value().path("synced_at").isNull()).isTrue();
                    assertThat(record.value().has("issueId")).isFalse();
                    assertThat(record.value().has("number")).isFalse();
                    assertThat(record.value().has("json")).isFalse();
                });
        assertThat(paths(project(workspace, widgets, Set.of(ISSUE_COMMENTS))))
                .containsExactly("issues/1/comments.jsonl", "issues/1/comments.jsonl");
        assertThat(paths(project(workspace, widgets, Set.of(GENERAL))))
                .containsExactly("pulls/2/comments.jsonl", "pulls/2/reviews.jsonl");
        assertThat(paths(project(workspace, widgets, Set.of(INLINE, THREADS))))
                .containsExactly("pulls/2/inline-comments.jsonl", "pulls/2/threads.jsonl");
    }

    @Test
    void shouldLeaveOutEveryRecordedDeliveryWhileKeepingHumanCommentsWhenNotesLookAlike() {
        User otherOwner = persistUser("delivery-other");
        Workspace other = createWorkspace("delivery-other", "Other", "delivery-other", AccountType.ORG, otherOwner);
        Repository widgets = repository("labels-org/deliveries", workspace);
        Issue work = issue(widgets, 5);
        Issue neighbour = issue(widgets, 6);
        String url = work.getHtmlUrl();
        comment(work, 5_001, "Thanks, fixed in the next commit.");
        comment(work, 5_002, "Consider splitting this issue.");
        comment(work, 5_003, "Consider naming the acceptance criteria.");
        comment(work, 5_004, "Consider linking the design notes.");
        comment(work, 5_005, "Consider a smaller scope.");
        comment(neighbour, 6_001, "Thanks, fixed in the next commit.");

        // A summary placement, a finished dispatch, and a legacy job reference, the last by another workspace.
        placement(work, "IC_kwDOsummary", url + "#issuecomment-5002");
        dispatch(job(workspace, work, null), "IC_kwDOdispatch", url + "#issuecomment-5003");
        job(other, work, "5004");
        // An identity nothing in the mirror carries, and a delivery recorded on other work, exclude nothing here.
        placement(work, "IC_kwDOunmapped", null);
        placement(neighbour, "IC_kwDOneighbour", url + "#issuecomment-5005");

        List<ProjectedRecord> records = project(workspace, widgets, Set.of(ISSUE_COMMENTS));

        assertThat(rowsByFile(records))
                .containsExactly(
                        Map.entry("issues/5/comments.jsonl", List.of(5_001L, 5_005L)),
                        Map.entry("issues/6/comments.jsonl", List.of(6_001L)));
    }

    private List<ProjectedRecord> project(Workspace target, Repository repository) {
        return project(target, repository, CORE);
    }

    private List<ProjectedRecord> project(Workspace target, Repository repository, Set<SourceKind> allowed) {
        List<ProjectedRecord> records = new ArrayList<>();
        projector.forEachRecord(target.getId(), repository.getId(), allowed, records::add);
        return records;
    }

    private static List<String> paths(List<ProjectedRecord> records) {
        return records.stream().map(ProjectedRecord::path).toList();
    }

    /** The native ids each JSONL file receives, in the order its lines are written. */
    private static Map<String, List<Long>> rowsByFile(List<ProjectedRecord> records) {
        Map<String, List<Long>> files = new LinkedHashMap<>();
        records.stream()
                .filter(record -> record.format() == Format.JSONL)
                .forEach(record -> files.computeIfAbsent(record.path(), ignored -> new ArrayList<>())
                        .add(record.value().path("native_id").asLong()));
        return files;
    }

    private Repository repository(String nameWithOwner, Workspace monitoredBy) {
        Repository repository = new Repository();
        repository.setNativeId(nativeIds.incrementAndGet());
        repository.setProvider(ensureGitHubProvider());
        repository.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl("https://github.com/" + nameWithOwner);
        repository.setDefaultBranch("main");
        repository = repositoryRepository.save(repository);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(monitoredBy);
        monitor.setNameWithOwner(nameWithOwner);
        repositoryToMonitorRepository.save(monitor);
        return repository;
    }

    private Label label(String name, Repository repository) {
        Label label = new Label();
        label.setNativeId(nativeIds.incrementAndGet());
        label.setProvider(ensureGitHubProvider());
        label.setName(name);
        label.setColor("0e8a16");
        label.setRepository(repository);
        return labelRepository.save(label);
    }

    private Issue issue(Repository repository, int number, Label... labels) {
        Issue issue = new Issue();
        issue.setNativeId(nativeIds.incrementAndGet());
        issue.setProvider(ensureGitHubProvider());
        issue.setNumber(number);
        issue.setTitle("Issue " + number);
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/issues/" + number);
        issue.setRepository(repository);
        issue.setAuthor(author);
        issue.setCreatedAt(DAY);
        issue.setUpdatedAt(DAY);
        issue.getLabels().addAll(List.of(labels));
        return issueRepository.save(issue);
    }

    private PullRequest pullRequest(Repository repository, int number, Label... labels) {
        PullRequest pullRequest = new PullRequest();
        pullRequest.setNativeId(nativeIds.incrementAndGet());
        pullRequest.setProvider(ensureGitHubProvider());
        pullRequest.setNumber(number);
        pullRequest.setTitle("Pull request " + number);
        pullRequest.setState(Issue.State.OPEN);
        pullRequest.setHtmlUrl(repository.getHtmlUrl() + "/pull/" + number);
        pullRequest.setRepository(repository);
        pullRequest.setAuthor(author);
        pullRequest.setCreatedAt(DAY);
        pullRequest.setUpdatedAt(DAY);
        pullRequest.getLabels().addAll(List.of(labels));
        return pullRequestRepository.save(pullRequest);
    }

    private void comment(Issue work, long nativeId, String body) {
        IssueComment comment = new IssueComment();
        comment.setNativeId(nativeId);
        comment.setProvider(work.getProvider());
        comment.setIssue(work);
        comment.setAuthor(author);
        comment.setAuthorAssociation(AuthorAssociation.MEMBER);
        comment.setHtmlUrl(work.getHtmlUrl() + "#issuecomment-" + nativeId);
        comment.setBody(body);
        comment.setCreatedAt(DAY);
        comment.setUpdatedAt(DAY);
        commentRepository.save(comment);
    }

    private void inline(PullRequest work, long nativeId) {
        PullRequestReviewThread thread = new PullRequestReviewThread();
        thread.setNativeId(nativeId);
        thread.setNodeId("thread-" + nativeId);
        thread.setProvider(work.getProvider());
        thread.setPullRequest(work);
        thread = threadRepository.save(thread);
        PullRequestReviewComment comment = new PullRequestReviewComment();
        comment.setNativeId(nativeId);
        comment.setProvider(work.getProvider());
        comment.setPullRequest(work);
        comment.setThread(thread);
        comment.setPath("src/A.java");
        comment.setHtmlUrl(work.getHtmlUrl() + "#discussion_r" + nativeId);
        comment.setBody("Should this handle an empty list?");
        inlineRepository.save(comment);
    }

    private void placement(Issue work, String ref, @Nullable String url) {
        AgentJob job = job(workspace, work, null);
        Feedback summary = feedback.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.ISSUE)
                .artifactId(work.getId())
                .recipientUserId(author.getId())
                .aboutUserId(author.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(position++)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .source(FeedbackSource.AGENT)
                .createdAt(DAY)
                .build());
        placements.save(FeedbackPlacement.builder()
                .feedback(summary)
                .placementType(PlacementType.SUMMARY)
                .postedCommentRef(ref)
                .postedCommentUrl(url)
                .build());
    }

    private AgentJob job(Workspace scope, Issue work, @Nullable String legacySummary) {
        AgentJob job = new AgentJob();
        job.setWorkspace(scope);
        job.setJobType(AgentJobType.ISSUE_REVIEW);
        job.setArtifactKind(ArtifactKinds.ISSUE);
        job.setMetadata(mapper.valueToTree(Map.of("issue_id", work.getId())));
        job.setConfigSnapshot(mapper.valueToTree(Map.of("model", "test")));
        job.setDeliveryCommentId(legacySummary);
        return jobs.save(job);
    }

    private void dispatch(AgentJob job, String ref, String url) {
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
                            id, scope, "test", "SENT", ref, url, null, null, "[]", Instant.now())))
                    .isOne();
        });
    }
}

package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LinkedWorkItemContentSourceTest extends BaseUnitTest {

    private static final long REPO_ID = 123L;
    private static final long PR_ID = 456L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    @Mock
    private GitDiffOperations gitDiffOperations;

    @Mock
    private ReviewRepositoryPreparer repositoryPreparer;

    private LinkedWorkItemContentSource provider;

    @BeforeEach
    void setUp() {
        provider = new LinkedWorkItemContentSource(
                objectMapper,
                pullRequestRepository,
                issueRepository,
                gitRepositoryManager,
                gitDiffOperations,
                repositoryPreparer);
        lenient()
                .when(repositoryPreparer.prepare(any()))
                .thenReturn(new ReviewRepositoryPreparer.PreparedReview(
                        new RepositoryKey(99L, REPO_ID), "abc123def456", "a".repeat(40)));
        lenient().when(repositoryPreparer.authorize(any())).thenReturn(new RepositoryKey(99L, REPO_ID));
        // Git disabled by default; the commit-subject scan must no-op. Individual tests enable it.
        lenient().when(gitRepositoryManager.isEnabled()).thenReturn(false);
    }

    private ObjectNode sampleMetadata() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("repository_id", REPO_ID);
        metadata.put("pull_request_id", PR_ID);
        metadata.put("source_branch", "feature/auth-fix");
        metadata.put("target_branch", "main");
        metadata.put("base_ref_oid", "a".repeat(40));
        metadata.put("commit_sha", "abc123def456");
        return metadata;
    }

    private ContextRequest.PracticeReviewRequest request(ObjectNode metadata) {
        var job = new AgentJob();
        job.setMetadata(metadata);
        Workspace workspace = new Workspace();
        workspace.setId(99L);
        job.setWorkspace(workspace);
        return new ContextRequest.PracticeReviewRequest(job);
    }

    private Issue issue(int number, String title, String body) {
        Issue issue = new Issue();
        issue.setNumber(number);
        issue.setTitle(title);
        issue.setBody(body);
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl("https://example.com/issues/" + number);
        return issue;
    }

    @Test
    void supportsPracticeReviewOnly() {
        assertThat(provider.supports(request(sampleMetadata()))).isTrue();
    }

    @Test
    void isBestEffort() {
        assertThat(provider.required()).isFalse();
    }

    @Test
    void resolvesClosingRefFromBodyWithAcceptanceCriteriaExcerpt() throws Exception {
        PullRequest pr = new PullRequest();
        pr.setBody("This MR is part of the auth epic.\n\nCloses #42");
        pr.setHeadRefName("feature/auth-fix");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

        Label backend = new Label();
        backend.setName("backend");
        Issue linked = issue(42, "Add token refresh", "Acceptance criteria: the session must refresh silently.");
        linked.setLabels(Set.of(backend));
        linked.setSubIssuesTotal(3);
        linked.setSubIssuesCompleted(1);
        when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42)).thenReturn(Optional.of(linked));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(request(sampleMetadata()), files);

        assertThat(files).containsKey("inputs/context/linked_work_items.json");
        JsonNode root = objectMapper.readTree(files.get("inputs/context/linked_work_items.json"));
        JsonNode items = root.get("workItems");
        assertThat(items).hasSize(1);
        JsonNode item = items.get(0);
        assertThat(item.get("number").asInt()).isEqualTo(42);
        assertThat(item.get("title").asString()).isEqualTo("Add token refresh");
        assertThat(item.get("state").asString()).isEqualTo("OPEN");
        assertThat(item.get("url").asString()).isEqualTo("https://example.com/issues/42");
        assertThat(item.get("closingKeyword").asBoolean()).isTrue();
        assertThat(item.get("bodyExcerpt").asString()).contains("Acceptance criteria");
        assertThat(item.get("labels").get(0).asString()).isEqualTo("backend");
        assertThat(item.get("subIssuesTotal").asInt()).isEqualTo(3);
        assertThat(item.get("subIssuesCompleted").asInt()).isEqualTo(1);

        JsonNode resolvedFrom = root.get("resolvedFrom");
        assertThat(resolvedFrom.get(0).asString()).isEqualTo("body");
    }

    @Test
    void bareMentionIsNotClosing() throws Exception {
        PullRequest pr = new PullRequest();
        pr.setBody("Related to #7 — see context.");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 7))
                .thenReturn(Optional.of(issue(7, "Background", "Some background")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(request(sampleMetadata()), files);

        JsonNode root = objectMapper.readTree(files.get("inputs/context/linked_work_items.json"));
        assertThat(root.get("workItems").get(0).get("closingKeyword").asBoolean())
                .isFalse();
    }

    @Test
    void bareMentionEndingASentenceWithAPeriodIsResolved() throws Exception {
        // "This relates to #42." — the trailing sentence period must NOT swallow the reference, while a
        // version like #1.2 (digit after the dot) is still rejected.
        PullRequest pr = new PullRequest();
        pr.setBody("This work relates to #42. It also touches the version bump #1.2 which is not an issue.");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42))
                .thenReturn(Optional.of(issue(42, "Trailing period ref", "Some body")));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(request(sampleMetadata()), files);

        assertThat(files).containsKey("inputs/context/linked_work_items.json");
        JsonNode root = objectMapper.readTree(files.get("inputs/context/linked_work_items.json"));
        JsonNode items = root.get("workItems");
        // Exactly #42 resolves; the version-looking #1.2 is rejected (and #1 was never looked up).
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("number").asInt()).isEqualTo(42);
        assertThat(items.get(0).get("closingKeyword").asBoolean()).isFalse();
    }

    @Test
    void resolvesIssueIdFromBranchSlugWhenNoBodyRef() throws Exception {
        PullRequest pr = new PullRequest();
        pr.setBody("No references in body.");
        pr.setHeadRefName("feat/18-improve-logging");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 18))
                .thenReturn(Optional.of(issue(18, "Improve logging", "criteria")));

        ObjectNode metadata = sampleMetadata();
        metadata.put("source_branch", "feat/18-improve-logging");

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(request(metadata), files);

        JsonNode root = objectMapper.readTree(files.get("inputs/context/linked_work_items.json"));
        assertThat(root.get("workItems").get(0).get("number").asInt()).isEqualTo(18);
        assertThat(root.get("resolvedFrom").toString()).contains("branch");
    }

    @Test
    void excerptIsCappedAtTheConfiguredWindow() throws Exception {
        PullRequest pr = new PullRequest();
        pr.setBody("Fixes #5");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        String longBody = "x".repeat(2000);
        when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 5))
                .thenReturn(Optional.of(issue(5, "Big", longBody)));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(request(sampleMetadata()), files);

        JsonNode root = objectMapper.readTree(files.get("inputs/context/linked_work_items.json"));
        assertThat(root.get("workItems").get(0).get("bodyExcerpt").asString())
                .hasSize(LinkedWorkItemContentSource.EXCERPT_CHARS);
    }

    @Test
    void keepsEveryLinkedIssueRatherThanTheFirstFew() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            body.append("Closes #").append(i).append(' ');
        }
        PullRequest pr = new PullRequest();
        pr.setBody(body.toString());
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(issueRepository.findByRepositoryIdAndNumber(eq(REPO_ID), anyInt())).thenAnswer(inv -> {
            int n = inv.getArgument(1);
            return Optional.of(issue(n, "Issue " + n, "criteria " + n));
        });

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(request(sampleMetadata()), files);

        // A collector does not decide how many linked issues a reviewer can hold; it reports what the
        // work actually links to.
        JsonNode root = objectMapper.readTree(files.get("inputs/context/linked_work_items.json"));
        assertThat(root.get("workItems")).hasSize(20);
        assertThat(root.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void recordsPartialEmptyWhenCommitReferencesCannotBeScanned() throws Exception {
        PullRequest pr = new PullRequest();
        pr.setBody("A clean description with no issue references.");
        pr.setHeadRefName("feature/no-refs");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

        ObjectNode metadata = sampleMetadata();
        metadata.put("source_branch", "feature/no-refs");

        var captured = provider.capture(request(metadata), provider.sourceKinds());

        assertThat(captured.files()).containsKey("inputs/context/linked_work_items.json");
        assertThat(captured.contentStates()).containsValue(SourceContentState.EMPTY);
        assertThat(captured.completeness()).containsValue(SourceCompleteness.PARTIAL);
    }

    @Test
    void unresolvedReferenceMakesCapturePartial() throws Exception {
        PullRequest pr = new PullRequest();
        pr.setBody("Closes #999");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 999)).thenReturn(Optional.empty());

        var captured = provider.capture(request(sampleMetadata()), provider.sourceKinds());

        assertThat(captured.completeness()).containsValue(SourceCompleteness.PARTIAL);
        assertThat(objectMapper
                        .readTree(captured.files().get(LinkedWorkItemContentSource.OUTPUT_FILE))
                        .path("workItems"))
                .isEmpty();
    }

    @Test
    void missingMetadataIsACollectionError() {
        var job = new AgentJob();
        var req = new ContextRequest.PracticeReviewRequest(job);

        assertThatExceptionOfType(EvidenceCollectionException.class)
                .isThrownBy(() -> provider.capture(req, provider.sourceKinds()));
    }

    @Test
    void missingRepositoryIdIsACollectionError() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("pull_request_id", PR_ID);

        assertThatExceptionOfType(EvidenceCollectionException.class)
                .isThrownBy(() -> provider.capture(request(metadata), provider.sourceKinds()));
    }

    @Test
    void reportsCollectionErrorWhenRepositoryQueryFails() {
        PullRequest pr = new PullRequest();
        pr.setBody("Closes #42");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42)).thenThrow(new RuntimeException("DB down"));

        Map<String, byte[]> files = new LinkedHashMap<>();
        assertThatExceptionOfType(EvidenceCollectionException.class)
                .isThrownBy(() -> provider.contribute(request(sampleMetadata()), files));
        assertThat(files).isEmpty();
    }

    @Test
    void resolvesFromCommitSubjectsWhenGitEnabled() throws Exception {
        // No body/branch refs — the only signal is the commit subject.
        PullRequest pr = new PullRequest();
        pr.setBody("Implementation only.");
        pr.setHeadRefName("feature/plain");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));

        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitDiffOperations.resolveDiffRange(new RepositoryKey(99L, REPO_ID), "a".repeat(40), "abc123def456"))
                .thenReturn(new String[] {"base", "head"});
        doAnswer(invocation -> {
                    java.util.function.Consumer<String> consumer = invocation.getArgument(3);
                    consumer.accept("fix: resolve crash, fixes #77");
                    return null;
                })
                .when(gitRepositoryManager)
                .forEachCommitSubject(eq(new RepositoryKey(99L, REPO_ID)), eq("base"), eq("head"), any());
        when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 77))
                .thenReturn(Optional.of(issue(77, "Crash on launch", "criteria")));

        ObjectNode metadata = sampleMetadata();
        metadata.put("source_branch", "feature/plain");

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(request(metadata), files);

        JsonNode root = objectMapper.readTree(files.get("inputs/context/linked_work_items.json"));
        assertThat(root.get("workItems").get(0).get("number").asInt()).isEqualTo(77);
        assertThat(root.get("workItems").get(0).get("closingKeyword").asBoolean())
                .isTrue();
        assertThat(root.get("resolvedFrom").toString()).contains("commits");
    }

    @Test
    void anExhaustiveScanFindingNothingIsStillNotComplete() throws Exception {
        PullRequest pr = new PullRequest();
        pr.setBody("No issue references.");
        pr.setHeadRefName("feature/plain");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitDiffOperations.resolveDiffRange(new RepositoryKey(99L, REPO_ID), "a".repeat(40), "abc123def456"))
                .thenReturn(new String[] {"base", "head"});

        ObjectNode metadata = sampleMetadata();
        metadata.put("source_branch", "feature/plain");
        var captured = provider.capture(request(metadata), provider.sourceKinds());

        // A link the author never wrote in the description, branch name or a commit subject is invisible
        // to this scan, so even an exhaustive one stays PARTIAL rather than COMPLETE.
        assertThat(captured.completeness()).containsValue(SourceCompleteness.PARTIAL);
        assertThat(captured.contentStates()).containsValue(SourceContentState.EMPTY);
        assertThat(objectMapper
                        .readTree(captured.files().get(LinkedWorkItemContentSource.OUTPUT_FILE))
                        .path("workItems"))
                .isEmpty();
    }

    @Test
    void shouldResolveAReferenceAfterFiveHundredCommitSubjects() throws Exception {
        PullRequest pr = new PullRequest();
        pr.setBody("No issue references.");
        pr.setHeadRefName("feature/plain");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitDiffOperations.resolveDiffRange(new RepositoryKey(99L, REPO_ID), "a".repeat(40), "abc123def456"))
                .thenReturn(new String[] {"base", "head"});
        doAnswer(invocation -> {
                    java.util.function.Consumer<String> consumer = invocation.getArgument(3);
                    for (int index = 0; index < 501; index++) consumer.accept("ordinary change");
                    consumer.accept("Fixes #77");
                    return null;
                })
                .when(gitRepositoryManager)
                .forEachCommitSubject(eq(new RepositoryKey(99L, REPO_ID)), eq("base"), eq("head"), any());
        when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 77))
                .thenReturn(Optional.of(issue(77, "Crash", "criteria")));

        ObjectNode metadata = sampleMetadata();
        metadata.put("source_branch", "feature/plain");

        var captured = provider.capture(request(metadata), provider.sourceKinds());
        assertThat(objectMapper
                        .readTree(captured.files().get(LinkedWorkItemContentSource.OUTPUT_FILE))
                        .path("workItems")
                        .get(0)
                        .path("number")
                        .asInt())
                .isEqualTo(77);
    }

    @Test
    void shouldRejectUnauthorizedDatabaseCaptureWhenGitIsDisabled() {
        when(repositoryPreparer.authorize(any())).thenThrow(new IllegalStateException("Unauthorized repository"));
        assertThatExceptionOfType(EvidenceCollectionException.class)
                .isThrownBy(() -> provider.capture(request(sampleMetadata()), provider.sourceKinds()));
        org.mockito.Mockito.verifyNoInteractions(pullRequestRepository, issueRepository, gitDiffOperations);
    }

    @Test
    void shouldReportCommitScanFailureInsteadOfEmptyEvidence() {
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitDiffOperations.resolveDiffRange(new RepositoryKey(99L, REPO_ID), "a".repeat(40), "abc123def456"))
                .thenThrow(new IllegalStateException("Native Git failed"));
        assertThatExceptionOfType(EvidenceCollectionException.class)
                .isThrownBy(() -> provider.capture(request(sampleMetadata()), provider.sourceKinds()));
    }
}

package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LinkedWorkItemContentSourceTest extends BaseUnitTest {

    private static final long REPO_ID = 123L;
    private static final long PR_ID = 456L;
    private static final RepositoryKey KEY = new RepositoryKey(99L, REPO_ID);
    private static final String HEAD = "abc123def456";
    private static final String BASE = "a".repeat(40);
    private static final SourceKind KIND = new SourceKind("scm.linked-work-items");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    @Mock
    private ReviewRepositoryPreparer repositoryPreparer;

    private LinkedWorkItemContentSource provider;

    @BeforeEach
    void setUp() {
        provider = new LinkedWorkItemContentSource(
                objectMapper, pullRequestRepository, issueRepository, gitRepositoryManager, repositoryPreparer);
        lenient()
                .when(repositoryPreparer.prepare(any()))
                .thenReturn(new ReviewRepositoryPreparer.PreparedReview(KEY, HEAD, BASE));
        lenient().when(repositoryPreparer.authorize(any())).thenReturn(KEY);
        // Git disabled by default; the commit-subject scan must no-op. Individual tests enable it.
        lenient().when(gitRepositoryManager.isEnabled()).thenReturn(false);
    }

    private ObjectNode sampleMetadata() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("repository_id", REPO_ID);
        metadata.put("pull_request_id", PR_ID);
        metadata.put("source_branch", "feature/auth-fix");
        metadata.put("target_branch", "main");
        metadata.put("base_ref_oid", BASE);
        metadata.put("commit_sha", HEAD);
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

    private void pullRequestWithBody(String body) {
        var pr = new PullRequest();
        pr.setBody(body);
        pr.setHeadRefName("feature/auth-fix");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
    }

    private void commitSubjects(String... subjects) {
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        doAnswer(invocation -> {
                    Consumer<String> consumer = invocation.getArgument(3);
                    for (String subject : subjects) consumer.accept(subject);
                    return null;
                })
                .when(gitRepositoryManager)
                .forEachCommitSubject(eq(KEY), eq(BASE), eq(HEAD), any());
    }

    private JsonNode payload(ObjectNode metadata) throws Exception {
        var captured = provider.capture(request(metadata), Set.of(KIND));
        assertThat(captured.files()).containsKey(LinkedWorkItemContentSource.OUTPUT_FILE);
        return objectMapper.readTree(captured.files().get(LinkedWorkItemContentSource.OUTPUT_FILE));
    }

    private static List<Integer> numbers(JsonNode array) {
        return array.valueStream().map(JsonNode::asInt).toList();
    }

    private static List<Integer> itemNumbers(JsonNode root) {
        return root.get("workItems")
                .valueStream()
                .map(item -> item.get("number").asInt())
                .toList();
    }

    @Test
    void supportsPracticeReviewOnly() {
        assertThat(provider.supports(request(sampleMetadata()))).isTrue();
    }

    @Test
    void isBestEffort() {
        assertThat(provider.required()).isFalse();
    }

    @Nested
    class Payload {

        @Test
        void shouldWriteWhatTheRepositoryKnowsAboutEachReferencedIssue() throws Exception {
            pullRequestWithBody("This MR is part of the auth epic.\n\nCloses #42");
            Label backend = new Label();
            backend.setName("backend");
            Issue linked = issue(42, "Add token refresh", "Acceptance criteria: the session must refresh silently.");
            linked.setLabels(Set.of(backend));
            linked.setSubIssuesTotal(3);
            linked.setSubIssuesCompleted(1);
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42)).thenReturn(Optional.of(linked));

            JsonNode root = payload(sampleMetadata());

            assertThat(root.propertyNames())
                    .containsExactlyInAnyOrder("workItems", "unresolvedReferences", "truncated");
            JsonNode items = root.get("workItems");
            assertThat(items).hasSize(1);
            JsonNode item = items.get(0);
            assertThat(item.propertyNames())
                    .containsExactlyInAnyOrder(
                            "number",
                            "title",
                            "state",
                            "url",
                            "body",
                            "labels",
                            "subIssuesTotal",
                            "subIssuesCompleted");
            assertThat(item.get("number").asInt()).isEqualTo(42);
            assertThat(item.get("title").asString()).isEqualTo("Add token refresh");
            assertThat(item.get("state").asString()).isEqualTo("OPEN");
            assertThat(item.get("url").asString()).isEqualTo("https://example.com/issues/42");
            assertThat(item.get("body").asString())
                    .isEqualTo("Acceptance criteria: the session must refresh silently.");
            assertThat(item.get("labels").get(0).asString()).isEqualTo("backend");
            assertThat(item.get("subIssuesTotal").asInt()).isEqualTo(3);
            assertThat(item.get("subIssuesCompleted").asInt()).isEqualTo(1);
            assertThat(root.get("unresolvedReferences")).isEmpty();
            assertThat(root.get("truncated").asBoolean()).isFalse();
        }

        @Test
        void shouldWriteEachResolvedIssueAsTextForQuoting() {
            pullRequestWithBody("Closes #42 and mentions #999");
            Issue linked = issue(42, "Add token refresh", "## Acceptance criteria\n- [ ] refreshes silently\n");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42)).thenReturn(Optional.of(linked));
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 999)).thenReturn(Optional.empty());

            linked.setCreatedAt(java.time.Instant.parse("2026-04-01T09:00:00Z"));

            var captured = provider.capture(request(sampleMetadata()), Set.of(KIND));

            // The body as written, title first, then the dates as one quotable line, so a line of it can
            // be cited by number; nothing for a reference this repository does not resolve.
            assertThat(captured.files())
                    .containsOnlyKeys(
                            LinkedWorkItemContentSource.OUTPUT_FILE,
                            LinkedWorkItemContentSource.ITEMS_PREFIX + "42.md");
            assertThat(new String(
                            captured.files().get(LinkedWorkItemContentSource.ITEMS_PREFIX + "42.md"),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("# Add token refresh\n\nOpened 2026-04-01T09:00:00Z, state OPEN.\n\n"
                            + "## Acceptance criteria\n- [ ] refreshes silently\n");
        }

        @Test
        void shouldWriteTheWholeIssueBodyRatherThanAnExcerpt() throws Exception {
            pullRequestWithBody("Fixes #5");
            String longBody = "x".repeat(2000);
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 5))
                    .thenReturn(Optional.of(issue(5, "Big", longBody)));

            JsonNode root = payload(sampleMetadata());

            assertThat(root.get("workItems").get(0).get("body").asString()).isEqualTo(longBody);
        }

        @Test
        void shouldListUnresolvedNumbersRatherThanClaimingTheyAreIssues() throws Exception {
            pullRequestWithBody("Closes #999 and relates to #42");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 999)).thenReturn(Optional.empty());
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42))
                    .thenReturn(Optional.of(issue(42, "Known", "")));

            JsonNode root = payload(sampleMetadata());

            assertThat(itemNumbers(root)).containsExactly(42);
            assertThat(numbers(root.get("unresolvedReferences"))).containsExactly(999);
        }
    }

    @Nested
    class NumberExtraction {

        @Test
        void shouldReadEveryHashNumberFromTheBodyWhateverWordsSurroundIt() throws Exception {
            pullRequestWithBody("- [ ] Related issue is linked (e.g., `Closes #12`)\n"
                    + "Implemented the requested fix: `Closes #42`.\n"
                    + "Related to #7 — see context.");
            when(issueRepository.findByRepositoryIdAndNumber(eq(REPO_ID), anyInt()))
                    .thenAnswer(inv -> {
                        int number = inv.getArgument(1);
                        return Optional.of(issue(number, "Issue", ""));
                    });

            JsonNode root = payload(sampleMetadata());

            assertThat(itemNumbers(root)).containsExactly(12, 42, 7);
        }

        @Test
        void shouldIgnoreAReferenceInsideAnHtmlComment() throws Exception {
            // A merge request template's commented example, kept verbatim by most authors of one cohort:
            // not rendered, so not the author's reference.
            pullRequestWithBody(
                    "<!-- MR title format: #<IssueNumber>: <Short description> — Example: #12: Add login -->\n"
                            + "Closes #7");
            when(issueRepository.findByRepositoryIdAndNumber(eq(REPO_ID), anyInt()))
                    .thenAnswer(inv -> {
                        int number = inv.getArgument(1);
                        return Optional.of(issue(number, "Issue", ""));
                    });

            JsonNode root = payload(sampleMetadata());

            assertThat(itemNumbers(root)).containsExactly(7);
            verify(issueRepository, never()).findByRepositoryIdAndNumber(REPO_ID, 12);
        }

        @Test
        void shouldKeepASentencePeriodAndRejectAVersionAColourAndAUnit() throws Exception {
            pullRequestWithBody(
                    "This work relates to #42. It bumps version #1.2, uses colour #1a2b and a #42px margin.");
            when(issueRepository.findByRepositoryIdAndNumber(eq(REPO_ID), anyInt()))
                    .thenAnswer(inv -> {
                        int number = inv.getArgument(1);
                        return Optional.of(issue(number, "Issue", ""));
                    });

            JsonNode root = payload(sampleMetadata());

            assertThat(root.get("workItems")).hasSize(1);
            assertThat(root.get("workItems").get(0).get("number").asInt()).isEqualTo(42);
            verify(issueRepository, never()).findByRepositoryIdAndNumber(REPO_ID, 1);
        }

        @Test
        void shouldReadTheIssueNumberOpeningABranchSegment() throws Exception {
            pullRequestWithBody("No references in body.");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 18))
                    .thenReturn(Optional.of(issue(18, "Improve logging", "criteria")));
            ObjectNode metadata = sampleMetadata();
            metadata.put("source_branch", "feat/18-improve-logging");

            JsonNode root = payload(metadata);

            assertThat(root.get("workItems").get(0).get("number").asInt()).isEqualTo(18);
        }

        @Test
        void shouldReadCommitSubjectsWhenGitIsEnabled() throws Exception {
            pullRequestWithBody("Implementation only.");
            commitSubjects("fix: resolve crash, fixes #77");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 77))
                    .thenReturn(Optional.of(issue(77, "Crash on launch", "criteria")));

            JsonNode root = payload(sampleMetadata());

            assertThat(root.get("workItems").get(0).get("number").asInt()).isEqualTo(77);
        }

        @Test
        void shouldResolveAReferenceAfterFiveHundredCommitSubjects() throws Exception {
            pullRequestWithBody("No issue references.");
            String[] subjects = new String[502];
            Arrays.fill(subjects, "ordinary change");
            subjects[501] = "Fixes #77";
            commitSubjects(subjects);
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 77))
                    .thenReturn(Optional.of(issue(77, "Crash", "criteria")));

            JsonNode root = payload(sampleMetadata());

            assertThat(root.get("workItems").get(0).get("number").asInt()).isEqualTo(77);
        }
    }

    @Nested
    class Bounds {

        @Test
        void shouldKeepEveryLinkedIssueBelowTheMemoryBound() throws Exception {
            StringBuilder body = new StringBuilder();
            for (int i = 1; i <= 20; i++) body.append("Closes #").append(i).append(' ');
            pullRequestWithBody(body.toString());
            when(issueRepository.findByRepositoryIdAndNumber(eq(REPO_ID), anyInt()))
                    .thenAnswer(inv -> {
                        int number = inv.getArgument(1);
                        return Optional.of(issue(number, "Issue", ""));
                    });

            JsonNode root = payload(sampleMetadata());

            assertThat(root.get("workItems")).hasSize(20);
            assertThat(root.get("truncated").asBoolean()).isFalse();
        }

        @Test
        void shouldTruncateAtTheMemoryBoundAndSaySo() throws Exception {
            int max = LinkedWorkItemContentSource.MAX_ITEMS;
            StringBuilder body = new StringBuilder();
            for (int i = 1; i <= max + 1; i++) body.append('#').append(i).append(' ');
            pullRequestWithBody(body.toString());
            when(issueRepository.findByRepositoryIdAndNumber(eq(REPO_ID), anyInt()))
                    .thenAnswer(inv -> {
                        int number = inv.getArgument(1);
                        return Optional.of(issue(number, "Issue", ""));
                    });

            JsonNode root = payload(sampleMetadata());

            assertThat(root.get("workItems")).hasSize(max);
            assertThat(root.get("truncated").asBoolean()).isTrue();
            verify(issueRepository, never()).findByRepositoryIdAndNumber(REPO_ID, max + 1);
        }
    }

    @Nested
    class CaptureState {

        @Test
        void shouldReportPartialAndEmptyWhenNothingResolves() {
            pullRequestWithBody("A clean description with no issue references.");

            var captured = provider.capture(request(sampleMetadata()), Set.of(KIND));

            assertThat(captured.files()).containsKey(LinkedWorkItemContentSource.OUTPUT_FILE);
            assertThat(captured.contentStates()).containsEntry(KIND, SourceContentState.EMPTY);
            assertThat(captured.completeness()).containsEntry(KIND, SourceCompleteness.PARTIAL);
        }

        @Test
        void shouldReportEmptyWhenTheOnlyReferenceIsUnresolved() throws Exception {
            pullRequestWithBody("Closes #999");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 999)).thenReturn(Optional.empty());

            var captured = provider.capture(request(sampleMetadata()), Set.of(KIND));

            assertThat(captured.completeness()).containsEntry(KIND, SourceCompleteness.PARTIAL);
            assertThat(captured.contentStates()).containsEntry(KIND, SourceContentState.EMPTY);
            JsonNode root = objectMapper.readTree(captured.files().get(LinkedWorkItemContentSource.OUTPUT_FILE));
            assertThat(root.path("workItems")).isEmpty();
            assertThat(numbers(root.path("unresolvedReferences"))).containsExactly(999);
        }

        @Test
        void anExhaustiveScanFindingNothingIsStillNotComplete() {
            pullRequestWithBody("No issue references.");
            commitSubjects("ordinary change");

            var captured = provider.capture(request(sampleMetadata()), Set.of(KIND));

            // A link the author never wrote in the description, branch name or a commit subject is
            // invisible to this scan, so even an exhaustive one stays PARTIAL rather than COMPLETE.
            assertThat(captured.completeness()).containsEntry(KIND, SourceCompleteness.PARTIAL);
            assertThat(captured.contentStates()).containsEntry(KIND, SourceContentState.EMPTY);
        }

        @Test
        void shouldReportNonEmptyWhenAnItemResolves() {
            pullRequestWithBody("Closes #42");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42))
                    .thenReturn(Optional.of(issue(42, "Known", "")));

            var captured = provider.capture(request(sampleMetadata()), Set.of(KIND));

            assertThat(captured.contentStates()).containsEntry(KIND, SourceContentState.NON_EMPTY);
        }
    }

    @Nested
    class Failures {

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
            pullRequestWithBody("Closes #42");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42)).thenThrow(new RuntimeException("DB down"));

            Map<String, byte[]> files = new LinkedHashMap<>();
            assertThatExceptionOfType(EvidenceCollectionException.class)
                    .isThrownBy(() -> provider.contribute(request(sampleMetadata()), files));
            assertThat(files).isEmpty();
        }

        @Test
        void shouldReportCommitScanFailureInsteadOfEmptyEvidence() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            doThrow(new IllegalStateException("JGit failed"))
                    .when(gitRepositoryManager)
                    .forEachCommitSubject(eq(KEY), eq(BASE), eq(HEAD), any());

            assertThatExceptionOfType(EvidenceCollectionException.class)
                    .isThrownBy(() -> provider.capture(request(sampleMetadata()), provider.sourceKinds()));
        }
    }

    @Nested
    class Authorization {

        @Test
        void shouldOnlyAuthorizeWhenGitIsDisabled() {
            pullRequestWithBody("Closes #42");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42))
                    .thenReturn(Optional.of(issue(42, "Known", "")));

            provider.capture(request(sampleMetadata()), Set.of(KIND));

            verify(repositoryPreparer).authorize(any());
            verify(repositoryPreparer, never()).prepare(any());
            verify(gitRepositoryManager, never()).forEachCommitSubject(any(), any(), any(), any());
        }

        @Test
        void shouldRejectUnauthorizedDatabaseCaptureWhenGitIsDisabled() {
            when(repositoryPreparer.authorize(any())).thenThrow(new IllegalStateException("Unauthorized repository"));

            assertThatExceptionOfType(EvidenceCollectionException.class)
                    .isThrownBy(() -> provider.capture(request(sampleMetadata()), provider.sourceKinds()));
            verifyNoInteractions(pullRequestRepository, issueRepository);
        }
    }
}

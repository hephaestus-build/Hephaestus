package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.evidence.CapturedSource;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceFingerprint;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmCaptureConsent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final String HEAD = "b".repeat(40);
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
                objectMapper,
                pullRequestRepository,
                issueRepository,
                gitRepositoryManager,
                repositoryPreparer,
                mock(ScmCaptureConsent.class));
        lenient()
                .when(repositoryPreparer.prepare(any()))
                .thenReturn(new ReviewRepositoryPreparer.PreparedReview(KEY, HEAD, BASE));
        lenient().when(repositoryPreparer.authorize(any())).thenReturn(KEY);
        // Git disabled by default; the commit-message scan must no-op. Individual tests enable it.
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

    private ScmCaptureContext request(ObjectNode metadata) {
        return ScmCaptureFixtures.context(metadata, KEY.workspaceId(), false);
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
    void tombstonedClosingIssueHasNoCurrentMaterialKey() {
        var pr = new PullRequest();
        pr.setId(PR_ID);
        pr.setHeadRefOid(HEAD);
        pr.setTitle("Closes #18");
        Issue linked = issue(18, "Acceptance criteria", "- [x] Confirm repair");
        assertThat(LinkedWorkItemContentSource.currentClosingMaterialVersion(pr, List.of(linked)))
                .isPresent();
        linked.setDeletedAt(Instant.now());
        assertThat(LinkedWorkItemContentSource.currentClosingMaterialVersion(pr, List.of(linked)))
                .isEmpty();
    }

    @Test
    void comparesOnlyTheCapturedProviderClosingIssueFilesAndTreatsMissingEvidenceAsUnknown() {
        Issue first = issue(18, "Acceptance criteria", "- [x] Confirm repair");
        Issue second = issue(19, "Release check", "- [x] Verify release");
        var captured = closingManifest(List.of(first, second));

        assertThat(LinkedWorkItemContentSource.capturedClosingMaterialMatches(captured, List.of(second, first)))
                .contains(true);
        second.setBody("- [ ] Verify release");
        assertThat(LinkedWorkItemContentSource.capturedClosingMaterialMatches(captured, List.of(first, second)))
                .contains(false);
        second.setBody("- [x] Verify release");
        assertThat(LinkedWorkItemContentSource.capturedClosingMaterialMatches(
                        closingManifest(List.of(first)), List.of(first, second)))
                .isEmpty();
        first.setDeletedAt(Instant.now());
        assertThat(LinkedWorkItemContentSource.capturedClosingMaterialMatches(captured, List.of(first, second)))
                .isEmpty();
    }

    private static List<SourceCapture> closingManifest(List<Issue> issues) {
        var capturedAt = Instant.parse("2026-09-01T00:00:00Z");
        List<SourceCapture> sources = new ArrayList<>();
        sources.add(new SourceCapture(
                KIND,
                new SourceCaptureState.Available(
                        SourceContentState.NON_EMPTY,
                        SourceCompleteness.PARTIAL,
                        new SourceCaptureFacts(capturedAt, null, null, null)),
                EvidenceSnapshotFixtures.citation(KIND),
                issues.stream()
                        .map(issue -> new CapturedSource(
                                LinkedWorkItemContentSource.ITEMS_PREFIX + issue.getNumber() + ".md",
                                "text/markdown",
                                ScmVersion.contentHash(LinkedWorkItemContentSource.asText(issue)),
                                LinkedWorkItemContentSource.asText(issue).length))
                        .toList()));
        return List.copyOf(sources);
    }

    private void pullRequestWithBody(String body) {
        var pr = new PullRequest();
        pr.setBody(body);
        pr.setHeadRefName("feature/auth-fix");
        when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
    }

    private void commitMessages(String... messages) {
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        doAnswer(invocation -> {
                    Consumer<String> consumer = invocation.getArgument(3);
                    for (String message : messages) consumer.accept(message);
                    return null;
                })
                .when(gitRepositoryManager)
                .forEachCommitMessage(eq(KEY), eq(BASE), eq(HEAD), any());
    }

    private JsonNode payload(ObjectNode metadata) throws Exception {
        var captured = provider.capture(request(metadata), Set.of(KIND));
        assertThat(captured.files()).containsKey(LinkedWorkItemContentSource.OUTPUT_FILE);
        return objectMapper.readTree(captured.files().get(LinkedWorkItemContentSource.OUTPUT_FILE));
    }

    private record Rollup(long total, long completed) implements IssueRepository.ChildRollup {
        @Override
        public long getTotal() {
            return total;
        }

        @Override
        public long getCompleted() {
            return completed;
        }
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
                            "how",
                            "title",
                            "state",
                            "url",
                            "body",
                            "labels",
                            "subIssuesTotal",
                            "subIssuesCompleted",
                            "subIssuesSource");
            assertThat(item.get("number").asInt()).isEqualTo(42);
            assertThat(item.get("how").asString()).isEqualTo("mentions");
            assertThat(item.get("title").asString()).isEqualTo("Add token refresh");
            assertThat(item.get("state").asString()).isEqualTo("OPEN");
            assertThat(item.get("url").asString()).isEqualTo("https://example.com/issues/42");
            assertThat(item.get("body").asString())
                    .isEqualTo("Acceptance criteria: the session must refresh silently.");
            assertThat(item.get("labels").get(0).asString()).isEqualTo("backend");
            assertThat(item.get("subIssuesTotal").asInt()).isEqualTo(3);
            assertThat(item.get("subIssuesCompleted").asInt()).isEqualTo(1);
            assertThat(item.get("subIssuesSource").asString()).isEqualTo("provider");
            assertThat(root.get("unresolvedReferences")).isEmpty();
            assertThat(root.get("truncated").asBoolean()).isFalse();
        }

        @Test
        void shouldCountTheChildrenWhenTheProviderSyncedNoRollup() throws Exception {
            // GitLab links a child to its parent and never totals them; the record counts them
            // itself.
            pullRequestWithBody("Closes #42");
            Issue epic = issue(42, "Auth epic", "");
            epic.setId(4200L);
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42)).thenReturn(Optional.of(epic));
            when(issueRepository.countChildrenByParentIssueId(4200L, Issue.State.CLOSED))
                    .thenReturn(new Rollup(3, 1));

            JsonNode item = payload(sampleMetadata()).get("workItems").get(0);

            assertThat(item.get("subIssuesTotal").asInt()).isEqualTo(3);
            assertThat(item.get("subIssuesCompleted").asInt()).isEqualTo(1);
            assertThat(item.get("subIssuesSource").asString()).isEqualTo("children");
        }

        @Test
        void shouldWriteNoRollupWhenThereIsNeitherAProviderCountNorAChild() throws Exception {
            pullRequestWithBody("Closes #42");
            Issue plain = issue(42, "Standalone", "");
            plain.setId(4201L);
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42)).thenReturn(Optional.of(plain));
            when(issueRepository.countChildrenByParentIssueId(4201L, Issue.State.CLOSED))
                    .thenReturn(new Rollup(0, 0));

            JsonNode item = payload(sampleMetadata()).get("workItems").get(0);

            assertThat(item.has("subIssuesTotal")).isFalse();
            assertThat(item.has("subIssuesCompleted")).isFalse();
            assertThat(item.has("subIssuesSource")).isFalse();
        }

        @Test
        void shouldKeepAnOpenIssueOpenWhenAnOpenMergeRequestIsItsClosingCandidate() {
            var mr = new PullRequest();
            mr.setId(PR_ID);
            mr.setState(Issue.State.OPEN);
            mr.setMerged(false);
            mr.setTitle("#1: Add a short demo learner introduction");
            mr.setBody("Closes #1");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(mr));
            when(pullRequestRepository.findClosingIssuesById(PR_ID))
                    .thenReturn(List.of(issue(1, "Add a short demo learner introduction", "")));

            var captured = provider.capture(request(sampleMetadata()), Set.of(KIND));

            JsonNode item = objectMapper
                    .readTree(captured.files().get(LinkedWorkItemContentSource.OUTPUT_FILE))
                    .get("workItems")
                    .get(0);
            assertThat(item.get("how").asString()).isEqualTo("closesOnMerge");
            assertThat(item.get("state").asString()).isEqualTo("OPEN");
            assertThat(item.has("closedAt")).isFalse();
            assertThat(new String(
                            captured.files().get(LinkedWorkItemContentSource.ITEMS_PREFIX + "1.md"),
                            StandardCharsets.UTF_8))
                    .contains("state OPEN")
                    .doesNotContain("closed");
        }

        @Test
        void shouldSayClosesOnMergeForAMentionTheProviderAlsoLinksAsClosing() throws Exception {
            var pr = new PullRequest();
            pr.setId(PR_ID);
            pr.setBody("Closes #42, see also #7");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
            when(pullRequestRepository.findClosingIssuesById(PR_ID)).thenReturn(List.of(issue(42, "Closed one", "")));
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 7))
                    .thenReturn(Optional.of(issue(7, "Mentioned one", "")));

            JsonNode root = payload(sampleMetadata());

            assertThat(root.get("workItems")
                            .valueStream()
                            .map(item -> item.get("how").asString()))
                    .containsExactly("closesOnMerge", "mentions");
        }

        @Test
        void shouldWriteEachResolvedIssueAsTextForQuoting() {
            pullRequestWithBody("Closes #42 and mentions #999");
            Issue linked = issue(42, "Add token refresh", "## Acceptance criteria\n- [ ] refreshes silently\n");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42)).thenReturn(Optional.of(linked));
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 999)).thenReturn(Optional.empty());

            linked.setCreatedAt(Instant.parse("2026-04-01T09:00:00Z"));
            linked.setClosedAt(Instant.parse("2026-04-03T12:00:00Z"));
            linked.setState(Issue.State.CLOSED);

            var captured = provider.capture(request(sampleMetadata()), Set.of(KIND));

            assertThat(captured.files())
                    .containsKeys(
                            LinkedWorkItemContentSource.OUTPUT_FILE,
                            LinkedWorkItemContentSource.ITEMS_PREFIX + "42.md");
            assertThat(new String(
                            captured.files().get(LinkedWorkItemContentSource.ITEMS_PREFIX + "42.md"),
                            StandardCharsets.UTF_8))
                    .isEqualTo("# Add token refresh\n\n"
                            + "Opened 2026-04-01T09:00:00Z, closed 2026-04-03T12:00:00Z, state"
                            + " CLOSED.\n"
                            + "The body below is the issue's text as mirrored when this was"
                            + " captured; these dates are the issue's, not when any line of it"
                            + " was written.\n\n"
                            + "## Acceptance criteria\n"
                            + "- [ ] refreshes silently\n");
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
            pullRequestWithBody("""
                - [ ] Related issue is linked (e.g., `Closes #12`)
                Implemented the requested fix: `Closes #42`.
                Related to #7 — see context.\
                """);
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
            // Template examples inside HTML comments are not author references.
            pullRequestWithBody("<!-- MR title format: #<IssueNumber>: <Short description> — Example: #12: Add"
                    + " login -->\n"
                    + "Closes #7");
            when(issueRepository.findByRepositoryIdAndNumber(eq(REPO_ID), anyInt()))
                    .thenAnswer(inv -> {
                        int number = inv.getArgument(1);
                        return Optional.of(issue(number, "Issue", ""));
                    });

            JsonNode root = payload(sampleMetadata());

            assertThat(itemNumbers(root)).containsExactly(7);
        }

        @Test
        void shouldKeepASentencePeriodAndRejectAVersionAColourAndAUnit() throws Exception {
            pullRequestWithBody(
                    "This work relates to #42. It bumps version #1.2, uses colour #1a2b and a #42px" + " margin.");
            when(issueRepository.findByRepositoryIdAndNumber(eq(REPO_ID), anyInt()))
                    .thenAnswer(inv -> {
                        int number = inv.getArgument(1);
                        return Optional.of(issue(number, "Issue", ""));
                    });

            JsonNode root = payload(sampleMetadata());

            assertThat(root.get("workItems")).hasSize(1);
            assertThat(root.get("workItems").get(0).get("number").asInt()).isEqualTo(42);
        }

        @Test
        void shouldNotResolveANumberThatBelongsToAnotherRepositoryAVersionOrATrackerKey() throws Exception {
            pullRequestWithBody("Follows other/repo#12 and group/project#13, tested against v1.2#3; see GH-14. "
                    + "Also issue#15 is unrelated. Closes #42");
            when(issueRepository.findByRepositoryIdAndNumber(eq(REPO_ID), anyInt()))
                    .thenAnswer(inv -> {
                        int number = inv.getArgument(1);
                        return Optional.of(issue(number, "Issue", ""));
                    });

            JsonNode root = payload(sampleMetadata());

            assertThat(itemNumbers(root)).containsExactly(42);
        }

        @Test
        void shouldReadTheTitleAsWellAsTheBody() throws Exception {
            var pr = new PullRequest();
            pr.setTitle("#18: Improve logging");
            pr.setBody("Implementation only.");
            when(pullRequestRepository.findByIdWithAllForGate(PR_ID)).thenReturn(Optional.of(pr));
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 18))
                    .thenReturn(Optional.of(issue(18, "Improve logging", "criteria")));

            JsonNode root = payload(sampleMetadata());

            assertThat(itemNumbers(root)).containsExactly(18);
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
        void shouldReadCommitMessagesWhenGitIsEnabled() throws Exception {
            pullRequestWithBody("Implementation only.");
            commitMessages("fix: resolve crash, fixes #77");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 77))
                    .thenReturn(Optional.of(issue(77, "Crash on launch", "criteria")));

            JsonNode root = payload(sampleMetadata());

            assertThat(root.get("workItems").get(0).get("number").asInt()).isEqualTo(77);
        }

        @Test
        void shouldReadAReferenceInACommitBodyNotOnlyItsSubject() throws Exception {
            pullRequestWithBody("Implementation only.");
            commitMessages("fix: resolve crash\n\nThe launcher read a stale config.\n\nFixes #77\n");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 77))
                    .thenReturn(Optional.of(issue(77, "Crash on launch", "criteria")));

            JsonNode root = payload(sampleMetadata());

            assertThat(itemNumbers(root)).containsExactly(77);
        }

        @Test
        void shouldResolveAReferenceAfterFiveHundredCommitMessages() throws Exception {
            pullRequestWithBody("No issue references.");
            String[] messages = new String[502];
            Arrays.fill(messages, "ordinary change");
            messages[501] = "Fixes #77";
            commitMessages(messages);
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
            commitMessages("ordinary change");

            var captured = provider.capture(request(sampleMetadata()), Set.of(KIND));

            // A link the author never wrote in the title, description, branch name or a commit
            // message is
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
        void shouldRejectPinnedWorkWithoutARepository() {
            var metadata = sampleMetadata();
            metadata.put("repository_id", 0);
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> request(metadata));
        }

        @Test
        void shouldPropagateProgrammingFailuresWithoutRecordingCaptureRefusals() {
            pullRequestWithBody("Closes #42");
            var failure = new NullPointerException("Invalid issue mapping");
            when(issueRepository.findByRepositoryIdAndNumber(REPO_ID, 42)).thenThrow(failure);

            Map<String, byte[]> files = new LinkedHashMap<>();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> files.putAll(provider.capture(request(sampleMetadata()), provider.sourceKinds())
                            .files()))
                    .isSameAs(failure);
            assertThat(files).isEmpty();
        }

        @Test
        void shouldRecordNoLinkedWorkWhenTheMirrorLacksTheReviewedRange() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            var context = request(sampleMetadata());
            when(repositoryPreparer.prepare(context))
                    .thenThrow(new ItemCaptureException(ItemCaptureCode.VERSION_UNAVAILABLE, "range"));

            var captured = provider.capture(context, Set.of(KIND));

            // An answer from the title alone would fingerprint differently from one that read the commits.
            assertThat(captured.files()).isEmpty();
            assertThat(captured.stateOverrides().get(KIND))
                    .isEqualTo(new SourceCaptureState.Unavailable(SourceAbsenceReason.REVIEW_VERSION_UNAVAILABLE));
            assertThat(SourceFingerprint.of(objectMapper, KIND, captured)).isNull();
        }

        @Test
        void shouldReportCommitScanFailureInsteadOfEmptyEvidence() {
            when(gitRepositoryManager.isEnabled()).thenReturn(true);
            doThrow(new GitRepositoryManager.GitOperationException(
                            "JGit failed", new IOException("Unreadable repository")))
                    .when(gitRepositoryManager)
                    .forEachCommitMessage(eq(KEY), eq(BASE), eq(HEAD), any());

            assertThatExceptionOfType(ItemCaptureException.class)
                    .isThrownBy(() -> provider.capture(request(sampleMetadata()), provider.sourceKinds()));
        }
    }
}

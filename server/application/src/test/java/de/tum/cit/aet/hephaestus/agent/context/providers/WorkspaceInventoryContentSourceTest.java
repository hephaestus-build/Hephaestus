package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WorkspaceInventoryContentSourceTest extends BaseUnitTest {

    private static final long REPO_ID = 123L;
    private static final long WORKSPACE_ID = 9L;
    private static final String OUTPUT = "inputs/context/project_inventory.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    private WorkspaceInventoryContentSource provider;

    @BeforeEach
    void setUp() {
        provider = new WorkspaceInventoryContentSource(
                objectMapper, issueRepository, pullRequestRepository, repositoryRepository);
        lenient()
                .when(issueRepository.findIssueInventoryByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(List.of());
        lenient()
                .when(pullRequestRepository.findPullRequestInventoryByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(List.of());
        lenient()
                .when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID))
                .thenReturn(List.of());
    }

    // --- helpers ---

    private User user(String login) {
        User u = new User();
        u.setLogin(login);
        return u;
    }

    private Issue issue(int number, String title, Issue.State state, @Nullable String author) {
        Issue i = new Issue();
        i.setNumber(number);
        i.setTitle(title);
        i.setState(state);
        i.setHtmlUrl("https://example.com/issues/" + number);
        if (author != null) {
            i.setAuthor(user(author));
        }
        return i;
    }

    private Milestone milestone(String title) {
        Milestone ms = new Milestone();
        ms.setTitle(title);
        return ms;
    }

    private PullRequest pr(int number, String title, Issue.State state, boolean draft) {
        PullRequest p = new PullRequest();
        p.setNumber(number);
        p.setTitle(title);
        p.setState(state);
        p.setDraft(draft);
        p.setHtmlUrl("https://example.com/pull/" + number);
        return p;
    }

    private ContextRequest.IssueReviewRequest issueRequest(int focalNumber) {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("repository_id", REPO_ID);
        meta.put("repository_full_name", "acme/widgets");
        meta.put("issue_number", focalNumber);
        var job = new AgentJob();
        job.setMetadata(meta);
        return new ContextRequest.IssueReviewRequest(job);
    }

    private ContextRequest.PracticeReviewRequest prRequest(int focalNumber) {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("repository_id", REPO_ID);
        meta.put("repository_full_name", "acme/widgets");
        meta.put("pr_number", focalNumber);
        var job = new AgentJob();
        job.setMetadata(meta);
        return new ContextRequest.PracticeReviewRequest(job);
    }

    private Repository repository(long id, String nameWithOwner) {
        Repository r = new Repository();
        r.setId(id);
        r.setNameWithOwner(nameWithOwner);
        return r;
    }

    private ContextRequest.ConversationReviewRequest conversationRequest() {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("slack_channel_id", "C0ABC");
        meta.put("slack_thread_ts", "1700000000.100000");
        var job = new AgentJob();
        var workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        job.setWorkspace(workspace);
        job.setMetadata(meta);
        return new ContextRequest.ConversationReviewRequest(job);
    }

    // --- tests ---

    @Test
    void supportsAllThreeReviewFlows() {
        assertThat(provider.supports(issueRequest(1))).isTrue();
        assertThat(provider.supports(prRequest(1))).isTrue();
        assertThat(provider.supports(conversationRequest())).isTrue();
    }

    @Test
    void isBestEffort() {
        assertThat(provider.required()).isFalse();
    }

    @Test
    void listsEveryIssueAndPullRequestIncludingTheFocalIssue() throws Exception {
        Issue withMilestone = issue(12, "Login fails on Safari", Issue.State.OPEN, "bob");
        withMilestone.setMilestone(milestone("Sprint 7"));
        when(issueRepository.findIssueInventoryByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(List.of(
                        issue(99, "Focal issue under review", Issue.State.OPEN, "alice"),
                        withMilestone,
                        issue(7, "Old bug", Issue.State.CLOSED, null)));
        when(pullRequestRepository.findPullRequestInventoryByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(List.of(pr(70, "Fix login handler", Issue.State.MERGED, false)));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(issueRequest(99), files);

        assertThat(files).containsKey(OUTPUT);
        JsonNode root = objectMapper.readTree(files.get(OUTPUT));
        assertThat(root.propertyNames())
                .containsExactlyInAnyOrder("repository", "focal", "labelScheme", "issues", "pullRequests", "truncated");
        assertThat(root.get("labelScheme").get("issuesLabelled").asLong()).isZero();
        assertThat(root.get("labelScheme").get("labelsInUse")).isEmpty();
        assertThat(root.get("repository").asString()).isEqualTo("acme/widgets");
        assertThat(root.get("focal").get("type").asString()).isEqualTo("scm.issue");
        assertThat(root.get("focal").get("number").asInt()).isEqualTo(99);

        JsonNode issues = root.get("issues");
        assertThat(issues).hasSize(3);
        assertThat(issues.get(0).get("number").asInt()).isEqualTo(99);
        assertThat(issues.get(1).get("number").asInt()).isEqualTo(12);
        assertThat(issues.get(1).get("title").asString()).isEqualTo("Login fails on Safari");
        assertThat(issues.get(1).get("state").asString()).isEqualTo("OPEN");
        assertThat(issues.get(1).get("author").asString()).isEqualTo("bob");
        assertThat(issues.get(1).get("url").asString()).isEqualTo("https://example.com/issues/12");
        assertThat(issues.get(1).get("milestone").asString()).isEqualTo("Sprint 7");
        // Issue nodes never carry a draft flag (that is a PR-only field).
        assertThat(issues.get(1).has("isDraft")).isFalse();
        assertThat(issues.get(2).has("milestone")).isFalse();

        JsonNode prs = root.get("pullRequests");
        assertThat(prs).hasSize(1);
        assertThat(prs.get(0).get("number").asInt()).isEqualTo(70);
        assertThat(prs.get(0).get("state").asString()).isEqualTo("MERGED");
        assertThat(prs.get(0).get("isDraft").asBoolean()).isFalse();

        assertThat(root.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void truncatedIsTrueWhenAFullPageReturns() throws Exception {
        // A listing of exactly MAX_PER_TYPE rows is NOT known to be exhaustive, so absence-of-match
        // cannot be read as uniqueness.
        List<Issue> fullPage = new ArrayList<>();
        for (int n = 1; n <= WorkspaceInventoryContentSource.MAX_PER_TYPE; n++) {
            fullPage.add(issue(n, "Issue " + n, Issue.State.OPEN, "bob"));
        }
        when(issueRepository.findIssueInventoryByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(fullPage);

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(issueRequest(1), files);

        JsonNode root = objectMapper.readTree(files.get(OUTPUT));
        assertThat(root.get("truncated").asBoolean()).isTrue();
        assertThat(root.get("issues")).hasSize(WorkspaceInventoryContentSource.MAX_PER_TYPE);
    }

    @Test
    void listsTheFocalPullRequestInPrFlow() throws Exception {
        when(pullRequestRepository.findPullRequestInventoryByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(List.of(
                        pr(42, "The PR under review", Issue.State.OPEN, false),
                        pr(41, "Another PR", Issue.State.OPEN, true)));

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(prRequest(42), files);

        JsonNode root = objectMapper.readTree(files.get(OUTPUT));
        assertThat(root.get("focal").get("type").asString()).isEqualTo("scm.pull_request");
        assertThat(root.get("focal").get("number").asInt()).isEqualTo(42);
        JsonNode prs = root.get("pullRequests");
        assertThat(prs).hasSize(2);
        assertThat(prs.get(0).get("number").asInt()).isEqualTo(42);
        assertThat(prs.get(1).get("number").asInt()).isEqualTo(41);
        assertThat(prs.get(1).get("isDraft").asBoolean()).isTrue();
    }

    /** A repository tracking no work still stages the inventory, holding empty lists. */
    @Test
    void stagesAnEmptyInventoryWhenRepositoryHasNoArtifacts() {
        var captured = provider.capture(issueRequest(1), provider.sourceKinds());

        assertThat(captured.files()).containsKey(OUTPUT);
        assertThat(captured.contentStates()).containsValue(SourceContentState.EMPTY);
    }

    @Test
    void reportsNonEmptyWhenTheFocalArtifactIsTheOnlyOneListed() {
        when(issueRepository.findIssueInventoryByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenReturn(List.of(issue(1, "Focal issue", Issue.State.OPEN, "alice")));

        var captured = provider.capture(issueRequest(1), provider.sourceKinds());

        assertThat(captured.files()).containsKey(OUTPUT);
        assertThat(captured.contentStates()).containsValue(SourceContentState.NON_EMPTY);
    }

    @Test
    void reportsCollectionErrorWhenRepositoryIdMissing() {
        var job = new AgentJob();
        job.setMetadata(objectMapper.createObjectNode());

        // An empty inventory reads as "this workspace tracks no work" — the inverse of the truth
        // when the job simply arrived without its focal repository.
        assertThatThrownBy(() -> provider.contribute(new ContextRequest.IssueReviewRequest(job), new LinkedHashMap<>()))
                .isInstanceOf(EvidenceCollectionException.class)
                .hasRootCauseMessage("Workspace-inventory collection has no repository_id");
    }

    @Test
    void reportsRepositoryFailure() {
        when(issueRepository.findIssueInventoryByRepositoryId(eq(REPO_ID), any(Pageable.class)))
                .thenThrow(new RuntimeException("db down"));
        Map<String, byte[]> files = new LinkedHashMap<>();
        assertThatThrownBy(() -> provider.contribute(issueRequest(1), files))
                .isInstanceOf(EvidenceCollectionException.class)
                .hasMessageContaining("Workspace-inventory collection failed");
        assertThat(files).doesNotContainKey(OUTPUT);
    }

    // --- conversation flow: aggregated across every repository the workspace monitors ---

    @Test
    void conversationFlow_aggregatesAcrossEveryMonitoredRepository() throws Exception {
        Repository repoA = repository(1L, "acme/widgets");
        Repository repoB = repository(2L, "acme/gadgets");
        when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(repoA, repoB));
        when(issueRepository.findIssueInventoryByRepositoryId(eq(1L), any(Pageable.class)))
                .thenReturn(List.of(issue(5, "Widget bug", Issue.State.OPEN, "alice")));
        when(issueRepository.findIssueInventoryByRepositoryId(eq(2L), any(Pageable.class)))
                .thenReturn(List.of(issue(9, "Gadget bug", Issue.State.CLOSED, "bob")));
        when(pullRequestRepository.findPullRequestInventoryByRepositoryId(eq(1L), any(Pageable.class)))
                .thenReturn(List.of(pr(11, "Fix widget", Issue.State.OPEN, false)));
        when(pullRequestRepository.findPullRequestInventoryByRepositoryId(eq(2L), any(Pageable.class)))
                .thenReturn(List.of());

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(conversationRequest(), files);

        assertThat(files).containsKey(OUTPUT);
        JsonNode root = objectMapper.readTree(files.get(OUTPUT));
        // No single "repository" — the listing spans the workspace's monitored repos.
        assertThat(root.has("repository")).isFalse();
        JsonNode repos = root.get("repositories");
        assertThat(repos).hasSize(2);
        assertThat(repos.get(0).asString()).isEqualTo("acme/widgets");
        assertThat(repos.get(1).asString()).isEqualTo("acme/gadgets");

        assertThat(root.get("focal").get("type").asString()).isEqualTo("chat.conversation_thread");
        assertThat(root.get("focal").has("number")).isFalse();

        JsonNode issues = root.get("issues");
        assertThat(issues).hasSize(2);
        JsonNode prs = root.get("pullRequests");
        assertThat(prs).hasSize(1);

        assertThat(root.propertyNames())
                .containsExactlyInAnyOrder("repositories", "focal", "issues", "pullRequests", "truncated");
        assertThat(root.get("truncated").asBoolean()).isFalse();
    }

    /** A workspace monitoring nothing stages the inventory too — an empty index, not a missing one. */
    @Test
    void conversationFlow_stagesAnEmptyInventoryWhenWorkspaceMonitorsNoRepositories() {
        var captured = provider.capture(conversationRequest(), provider.sourceKinds());

        assertThat(captured.files()).containsKey(OUTPUT);
        assertThat(captured.contentStates()).containsValue(SourceContentState.EMPTY);
    }

    @Test
    void conversationFlow_writesNothingWhenJobHasNoWorkspace() {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("slack_channel_id", "C0ABC");
        meta.put("slack_thread_ts", "1700000000.100000");
        var job = new AgentJob();
        job.setMetadata(meta);
        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(new ContextRequest.ConversationReviewRequest(job), files);
        assertThat(files).doesNotContainKey(OUTPUT);
    }

    @Test
    void conversationFlow_truncatedWhenARepositoryPageIsFull() throws Exception {
        Repository repoA = repository(1L, "acme/widgets");
        when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenReturn(List.of(repoA));
        List<Issue> fullPage = new ArrayList<>();
        for (int n = 1; n <= WorkspaceInventoryContentSource.MAX_PER_TYPE; n++) {
            fullPage.add(issue(n, "Issue " + n, Issue.State.OPEN, "bob"));
        }
        when(issueRepository.findIssueInventoryByRepositoryId(eq(1L), any(Pageable.class)))
                .thenReturn(fullPage);

        Map<String, byte[]> files = new LinkedHashMap<>();
        provider.contribute(conversationRequest(), files);

        JsonNode root = objectMapper.readTree(files.get(OUTPUT));
        assertThat(root.get("truncated").asBoolean()).isTrue();
        assertThat(root.get("issues")).hasSize(WorkspaceInventoryContentSource.MAX_PER_TYPE);
    }

    @Test
    void conversationFlow_reportsRepositoryFailure() {
        when(repositoryRepository.findAllByWorkspaceMonitors(WORKSPACE_ID)).thenThrow(new RuntimeException("db down"));
        Map<String, byte[]> files = new LinkedHashMap<>();
        assertThatThrownBy(() -> provider.contribute(conversationRequest(), files))
                .isInstanceOf(EvidenceCollectionException.class)
                .hasMessageContaining("Workspace-inventory collection failed");
        assertThat(files).doesNotContainKey(OUTPUT);
    }
}

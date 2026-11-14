package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.MentorWorkQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSummaryFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Spy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WorkspaceContentSourceTest extends BaseUnitTest {

    @Mock
    AccountSummaryQuery accounts;

    @Mock
    WorkspaceRepository workspaceRepository;

    @Mock
    MentorWorkQuery queryRepository;

    @Mock
    WorkspaceActorSelector actorSelector;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    WorkspaceContentSource provider;

    @BeforeEach
    void connectedInstance() {
        provider = new WorkspaceContentSource(
                accounts,
                WorkspaceSummaryFixtures.summaries(workspaceRepository),
                queryRepository,
                actorSelector,
                objectMapper);
        lenient().when(actorSelector.connectedProviderId(1L)).thenReturn(Optional.of(5L));
        lenient()
                .when(accounts.findAllByIds(List.of(2L)))
                .thenReturn(Map.of(2L, new AccountSummaryQuery.AccountSummary(2, "Octo", null)));
        Workspace ws = new Workspace();
        ws.setId(1L);
        ws.setWorkspaceSlug("acme");
        ws.setDisplayName("Acme");
        lenient().when(workspaceRepository.findById(eq(1L))).thenReturn(Optional.of(ws));
    }

    @Test
    void writesWorkspaceJson() throws Exception {
        when(queryRepository.findAssignedOpenIssues(eq(1L), eq(2L))).thenReturn(List.of());
        when(queryRepository.findPendingReviewRequestPrs(eq(1L), eq(2L))).thenReturn(List.of());

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(new MentorContextRequest(1L, 2L, UUID.randomUUID(), null), files);

        byte[] bytes = files.get("inputs/context/workspace.json");
        assertThat(bytes).isNotNull();
        JsonNode root = objectMapper.readTree(bytes);
        assertThat(root.at("/user/name").asString()).isEqualTo("Octo");
        assertThat(root.get("workspace").get("slug").asString()).isEqualTo("acme");
        assertThat(root.get("workspace").get("displayName").asString()).isEqualTo("Acme");
        assertThat(root.has("recentSessions")).isFalse();
        assertThat(root.has("status")).isFalse();
        assertThat(root.get("assignedIssues").isArray()).isTrue();
        assertThat(root.get("pendingReviewRequests").isArray()).isTrue();
        assertThat(root.get("focusSuggestions").isArray()).isTrue();
    }

    @Test
    void shouldListAssignedIssuesWithTheirResourceDatesAndLabelsUpToTheBound() {
        Instant created = Instant.parse("2026-09-01T10:00:00Z");
        List<MentorWorkQuery.AssignedIssue> issues = new ArrayList<>();
        for (int i = 1; i <= WorkspaceContentSource.MAX_LISTED + 1; i++) {
            issues.add(new MentorWorkQuery.AssignedIssue(
                    100 + i,
                    i,
                    "Issue " + i,
                    "https://github.com/acme/app/issues/" + i,
                    "acme/app",
                    null,
                    null,
                    created,
                    created.plusSeconds(60),
                    List.of("bug", "ui")));
        }
        when(queryRepository.findAssignedOpenIssues(eq(1L), eq(2L))).thenReturn(issues);
        when(queryRepository.findPendingReviewRequestPrs(eq(1L), eq(2L)))
                .thenReturn(List.of(new MentorWorkQuery.RequestedReview(
                        7, 42, "Fix login", "https://github.com/acme/app/pull/42", "mona", "acme/app", created)));

        JsonNode root = provider.buildPayload(1L, 2L);

        assertThat(root.get("assignedIssues")).hasSize(WorkspaceContentSource.MAX_LISTED);
        assertThat(root.get("assignedIssuesTruncated").asBoolean()).isTrue();
        JsonNode first = root.get("assignedIssues").get(0);
        assertThat(first.get("sourceId").asLong()).isEqualTo(101L);
        assertThat(first.get("resource").asString()).isEqualTo("inputs/context/issue/101.json");
        assertThat(first.get("repository").asString()).isEqualTo("acme/app");
        assertThat(first.get("createdAt").asString()).isEqualTo("2026-09-01T10:00:00Z");
        assertThat(first.get("updatedAt").asString()).isEqualTo("2026-09-01T10:01:00Z");
        assertThat(first.get("labels").valueStream().map(JsonNode::asString)).containsExactly("bug", "ui");
        JsonNode review = root.get("pendingReviewRequests").get(0);
        assertThat(root.get("pendingReviewRequestsTruncated").asBoolean()).isFalse();
        assertThat(review.get("repository").asString()).isEqualTo("acme/app");
        assertThat(review.get("prCreatedAt").asString()).isEqualTo("2026-09-01T10:00:00Z");
        assertThat(review.has("prOpenDays")).isTrue();
        assertThat(review.has("waitingDays"))
                .as("when the review was requested is not stored")
                .isFalse();
    }

    @Test
    void shouldSayUnavailableAndReadNoListsWithoutAConnectedInstance() {
        when(actorSelector.connectedProviderId(1L)).thenReturn(Optional.empty());

        JsonNode root = provider.buildPayload(1L, 2L);

        assertThat(root.get("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(root.get("assignedIssues")).isEmpty();
        assertThat(root.get("pendingReviewRequests")).isEmpty();
        verifyNoInteractions(queryRepository);
    }

    @Test
    void staleReviewRequests() {
        var pr = new MentorWorkQuery.RequestedReview(
                1, 1, null, null, null, null, Instant.now().minus(Duration.ofDays(5)));
        List<String> suggestions = WorkspaceContentSource.computeFocusSuggestions(List.of(), List.of(pr));
        assertThat(suggestions).anyMatch(s -> s.contains("review request"));
    }

    @Test
    void freshReviewRequests() {
        var pr = new MentorWorkQuery.RequestedReview(
                1, 1, null, null, null, null, Instant.now().minus(Duration.ofHours(2)));
        List<String> suggestions = WorkspaceContentSource.computeFocusSuggestions(List.of(), List.of(pr));
        assertThat(suggestions).noneMatch(s -> s.contains("review request"));
    }
}

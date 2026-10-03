package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WorkspaceContentSourceTest extends BaseUnitTest {

    @Mock
    UserRepository userRepository;

    @Mock
    WorkspaceRepository workspaceRepository;

    @Mock
    MentorContextQueryRepository queryRepository;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    WorkspaceContentSource provider;

    @Test
    void writesWorkspaceJson() throws Exception {
        User user = new User();
        user.setLogin("octo");
        user.setName("Octo");
        when(userRepository.findById(eq(2L))).thenReturn(Optional.of(user));

        Workspace ws = new Workspace();
        ws.setWorkspaceSlug("acme");
        ws.setDisplayName("Acme");
        when(workspaceRepository.findById(eq(1L))).thenReturn(Optional.of(ws));

        when(queryRepository.findRecentChatThreads(eq(1L), eq(2L), any())).thenReturn(List.of());
        when(queryRepository.findAssignedOpenIssues(eq(1L), eq(2L))).thenReturn(List.of());
        when(queryRepository.findPendingReviewRequestPrs(eq(1L), eq(2L))).thenReturn(List.of());

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID()), files);

        byte[] bytes = files.get("inputs/context/workspace.json");
        assertThat(bytes).isNotNull();
        JsonNode root = objectMapper.readTree(bytes);
        assertThat(root.get("workspace").get("slug").asString()).isEqualTo("acme");
        assertThat(root.get("workspace").get("displayName").asString()).isEqualTo("Acme");
        assertThat(root.get("recentSessions").isArray()).isTrue();
        assertThat(root.get("assignedIssues").isArray()).isTrue();
        assertThat(root.get("pendingReviewRequests").isArray()).isTrue();
        assertThat(root.get("focusSuggestions").isArray()).isTrue();
    }

    @Test
    void staleReviewRequests() {
        PullRequest pr = new PullRequest();
        // 5 days in the past — beyond the 3-day urgency threshold.
        pr.setCreatedAt(Instant.now().minus(Duration.ofDays(5)));
        List<String> suggestions = WorkspaceContentSource.computeFocusSuggestions(List.of(), List.of(pr));
        assertThat(suggestions).anyMatch(s -> s.contains("review request"));
    }

    @Test
    void freshReviewRequests() {
        PullRequest pr = new PullRequest();
        pr.setCreatedAt(Instant.now().minus(Duration.ofHours(2)));
        List<String> suggestions = WorkspaceContentSource.computeFocusSuggestions(List.of(), List.of(pr));
        assertThat(suggestions).noneMatch(s -> s.contains("review request"));
    }
}

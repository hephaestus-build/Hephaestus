package de.tum.cit.aet.hephaestus.integration.scm.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountSummaryQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RecentAuthoredWorkContentSourceTest extends BaseUnitTest {

    @Mock
    AccountSummaryQuery accounts;

    @Mock
    ConversationContextQueryRepository queryRepository;

    @Mock
    ScmConversationWorkQuery work;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    RecentAuthoredWorkContentSource source;

    @BeforeEach
    void account() {
        when(accounts.findAllByIds(List.of(2L)))
                .thenReturn(Map.of(2L, new AccountSummaryQuery.AccountSummary(2, "Student", null)));
    }

    @Test
    void shouldPublishMergedPullRequestResourcesUsingItsSourceId() {
        when(work.actors(1L, 2L)).thenReturn(Optional.of(new ScmConversationWorkQuery.Actors(5L, List.of(200L))));
        PullRequest merged = new PullRequest();
        merged.setId(4009553323L);
        merged.setNumber(9);
        merged.setTitle("Add the simulator note");
        merged.setState(Issue.State.MERGED);
        when(queryRepository.findRecentAuthoredPullRequests(eq(1L), eq(5L), eq(List.of(200L)), any(Pageable.class)))
                .thenReturn(List.of(merged));
        Issue issue = new Issue();
        issue.setId(77L);
        issue.setNumber(14);
        issue.setTitle("Follow up on the runtime check");
        issue.setState(Issue.State.OPEN);
        when(queryRepository.findRecentAuthoredIssues(eq(1L), eq(5L), eq(List.of(200L)), any(Pageable.class)))
                .thenReturn(List.of(issue));

        JsonNode payload = source.buildPayload(1L, 2L);

        String resource = payload.get("pullRequests").get(0).get("resource").asString();
        assertThat(resource).isEqualTo("inputs/context/merge_readiness/4009553323.json");
        assertThat(MergeReadinessContentSource.pullRequestIdOf(resource)).contains(4009553323L);
        String issueResource = payload.get("issues").get(0).get("resource").asString();
        assertThat(issueResource).isEqualTo("inputs/context/issue/77.json");
        assertThat(IssueDetailSource.issueIdOf(issueResource)).contains(77L);
        assertThat(payload.get("pullRequests").get(0).get("reviewsResource").asString())
                .isEqualTo("inputs/context/review_attempts/pull_request/4009553323.json");
        assertThat(payload.get("issues").get(0).get("reviewsResource").asString())
                .isEqualTo("inputs/context/review_attempts/issue/77.json");
        assertThat(payload.has("status")).isFalse();
    }

    @Test
    void shouldSayTheListIsUnavailableWithoutAConnectedInstance() {
        when(work.actors(1L, 2L)).thenReturn(Optional.empty());

        JsonNode payload = source.buildPayload(1L, 2L);

        assertThat(payload.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(payload.path("pullRequests")).isEmpty();
        assertThat(payload.path("issues")).isEmpty();
        verify(queryRepository, never()).findRecentAuthoredPullRequests(anyLong(), anyLong(), any(), any());
    }
}

package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RecentAuthoredWorkContentSourceTest extends BaseUnitTest {

    @Mock
    UserRepository userRepository;

    @Mock
    MentorContextQueryRepository queryRepository;

    @Mock
    CacheManager cacheManager;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    RecentAuthoredWorkContentSource source;

    @Test
    void shouldPublishMergedPullRequestResourceUsingItsArtifactId() {
        User user = new User();
        user.setLogin("student");
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        PullRequest merged = new PullRequest();
        merged.setId(4009553323L);
        merged.setNumber(9);
        merged.setTitle("Add the simulator note");
        merged.setState(Issue.State.MERGED);
        when(queryRepository.findRecentAuthoredPullRequests(eq(1L), eq(2L), any(Pageable.class)))
                .thenReturn(List.of(merged));
        Issue issue = new Issue();
        issue.setId(77L);
        issue.setNumber(14);
        issue.setTitle("Follow up on the runtime check");
        issue.setState(Issue.State.OPEN);
        when(queryRepository.findRecentAuthoredIssues(eq(1L), eq(2L), any(Pageable.class)))
                .thenReturn(List.of(issue));

        JsonNode payload = source.buildPayload(1L, 2L);

        String resource = payload.get("pullRequests").get(0).get("resource").asString();
        assertThat(resource).isEqualTo("inputs/context/merge_readiness/4009553323.json");
        assertThat(MergeReadinessContentSource.artifactIdOf(resource)).contains(4009553323L);
        assertThat(payload.get("issues").get(0).has("resource")).isFalse();
    }
}

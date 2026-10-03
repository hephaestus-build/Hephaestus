package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.UserContentSource.ActivityInsights;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class UserContentSourceTest extends BaseUnitTest {

    @Mock
    UserRepository userRepository;

    @Mock
    MentorContextQueryRepository queryRepository;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    UserContentSource provider;

    @Test
    void writesUserJson() throws Exception {
        User user = new User();
        user.setLogin("octo");
        user.setName("Octo Cat");
        when(userRepository.findById(eq(2L))).thenReturn(Optional.of(user));
        when(queryRepository.fetchUserCounts(
                        eq(1L), eq(2L), any(Instant.class), any(Instant.class), any(Instant.class)))
                .thenReturn(new MentorUserCounts(
                        /* openPRs */ 2L,
                        /* mergedThisWeek */ 5L,
                        /* mergedLastWeek */ 3L,
                        /* openIssues */ 1L,
                        /* reviewsGivenThisWeek */ 8L,
                        /* reviewsGivenLastWeek */ 4L,
                        /* reviewsReceivedThisWeek */ 7L,
                        /* pendingReviewRequests */ 2L,
                        /* unresolvedThreads */ 1L));

        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID()), files);

        byte[] bytes = files.get("inputs/context/user.json");
        assertThat(bytes).isNotNull();
        JsonNode root = objectMapper.readTree(bytes);
        assertThat(root.get("user").get("login").asString()).isEqualTo("octo");
        assertThat(root.get("thisWeek").get("prsOpen").asLong()).isEqualTo(2L);
        assertThat(root.get("thisWeek").get("prsMerged").asLong()).isEqualTo(5L);
        // Distinct values per field so a positional mis-map in the 9-arg constructor projection is caught.
        assertThat(root.get("thisWeek").get("issuesOpen").asLong()).isEqualTo(1L);
        assertThat(root.get("thisWeek").get("reviewsGiven").asLong()).isEqualTo(8L);
        assertThat(root.get("thisWeek").get("reviewsReceived").asLong()).isEqualTo(7L);
        assertThat(root.get("thisWeek").get("pendingReviewRequests").asLong()).isEqualTo(2L);
        assertThat(root.get("thisWeek").get("unresolvedThreads").asLong()).isEqualTo(1L);
        assertThat(root.get("lastWeek").get("prsMerged").asLong()).isEqualTo(3L);
        assertThat(root.get("lastWeek").get("reviewsGiven").asLong()).isEqualTo(4L);
        assertThat(root.get("insights").isArray()).isTrue();
        assertThat(root.get("suggestedReflectionTopics").isArray()).isTrue();
    }

    @Test
    void velocityUp() {
        ActivityInsights insights = UserContentSource.generateInsights(
                /* openPRs */ 0,
                /* mergedThisWeek */ 5,
                /* mergedLastWeek */ 2,
                /* reviewsGivenThisWeek */ 3,
                /* reviewsGivenLastWeek */ 3,
                /* pendingReviewRequests */ 0,
                /* unresolvedThreads */ 0);
        assertThat(insights.insights()).anyMatch(s -> s.contains("velocity increased"));
        assertThat(insights.reflectionTopics()).isEmpty();
    }

    @Test
    void velocityDown() {
        ActivityInsights insights = UserContentSource.generateInsights(
                /* openPRs */ 0,
                /* mergedThisWeek */ 1,
                /* mergedLastWeek */ 5,
                /* reviewsGivenThisWeek */ 3,
                /* reviewsGivenLastWeek */ 3,
                /* pendingReviewRequests */ 0,
                /* unresolvedThreads */ 0);
        assertThat(insights.insights()).anyMatch(s -> s.contains("Shipping slowed"));
        assertThat(insights.reflectionTopics()).anyMatch(s -> s.contains("shipping pace"));
    }

    @Test
    @DisplayName("velocity direction is this-vs-last sensitive: more last week than this week never reads as increased")
    void velocityDirectionIsNotSymmetric() {
        // Distinguishes a mergedThisWeek<->mergedLastWeek swap: with this<last the message is "slowed",
        // and "velocity increased" must NOT appear (a swapped projection would invert this).
        ActivityInsights insights = UserContentSource.generateInsights(
                /* openPRs */ 0,
                /* mergedThisWeek */ 2,
                /* mergedLastWeek */ 5,
                /* reviewsGivenThisWeek */ 3,
                /* reviewsGivenLastWeek */ 3,
                /* pendingReviewRequests */ 0,
                /* unresolvedThreads */ 0);
        assertThat(insights.insights()).noneMatch(s -> s.contains("velocity increased"));
        assertThat(insights.insights()).anyMatch(s -> s.contains("Shipping slowed"));
    }

    @Test
    void manyOpenPrs() {
        ActivityInsights insights = UserContentSource.generateInsights(
                /* openPRs */ 10,
                /* mergedThisWeek */ 0,
                /* mergedLastWeek */ 0,
                /* reviewsGivenThisWeek */ 0,
                /* reviewsGivenLastWeek */ 0,
                /* pendingReviewRequests */ 0,
                /* unresolvedThreads */ 0);
        assertThat(insights.insights()).anyMatch(s -> s.contains("open PRs"));
        assertThat(insights.reflectionTopics()).anyMatch(s -> s.contains("merge-ready"));
    }

    @Test
    @DisplayName("insights: empty signal falls back to steady-week message")
    void steadyFallback() {
        ActivityInsights insights = UserContentSource.generateInsights(
                /* openPRs */ 0,
                /* mergedThisWeek */ 0,
                /* mergedLastWeek */ 0,
                /* reviewsGivenThisWeek */ 0,
                /* reviewsGivenLastWeek */ 0,
                /* pendingReviewRequests */ 0,
                /* unresolvedThreads */ 0);
        assertThat(insights.insights()).containsExactly("Steady week with consistent activity.");
        assertThat(insights.reflectionTopics()).isEmpty();
    }

    @Test
    @DisplayName("insights: no reviews this week when active last week surfaces nudge")
    void reviewDrop() {
        ActivityInsights insights = UserContentSource.generateInsights(
                /* openPRs */ 0,
                /* mergedThisWeek */ 0,
                /* mergedLastWeek */ 0,
                /* reviewsGivenThisWeek */ 0,
                /* reviewsGivenLastWeek */ 3,
                /* pendingReviewRequests */ 0,
                /* unresolvedThreads */ 0);
        assertThat(insights.insights()).anyMatch(s -> s.contains("No reviews given this week"));
    }
}

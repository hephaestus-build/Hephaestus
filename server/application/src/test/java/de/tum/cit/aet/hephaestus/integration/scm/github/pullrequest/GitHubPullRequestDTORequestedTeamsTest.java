package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlConfig;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHReviewRequestConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A pull request's review requests as the sync reads them ({@code RequestedReviewerFields}): a user, two teams, a
 * bot and a mannequin, decoded with the mapper the GitHub GraphQL client uses.
 */
class GitHubPullRequestDTORequestedTeamsTest extends BaseUnitTest {

    static final String REVIEW_REQUESTS = """
        {
          "totalCount": 5,
          "nodes": [
            { "requestedReviewer": { "__typename": "User", "id": "U_kgDOAFoAsQ", "databaseId": 5898705,
                "login": "FelixTJDietrich", "avatarUrl": "https://avatars.githubusercontent.com/u/5898705" } },
            { "requestedReviewer": { "__typename": "Team", "id": "T_kwDODNYmp84AzV7M", "databaseId": 13459148,
                "name": "Hephaetus", "slug": "hephaetus" } },
            { "requestedReviewer": { "__typename": "Team", "id": "T_kwDODNYmp84AzV7N", "databaseId": 13459149,
                "name": "Unsynced", "slug": "unsynced" } },
            { "requestedReviewer": { "__typename": "Bot", "id": "BOT_kgDOBvQaZg", "databaseId": 116398694,
                "login": "copilot-pull-request-reviewer", "avatarUrl": "https://avatars.githubusercontent.com/in/946600" } },
            { "requestedReviewer": { "__typename": "Mannequin", "id": "MDk6TWFubmVxdWluMQ==", "databaseId": 1,
                "login": "imported-reviewer", "avatarUrl": "https://avatars.githubusercontent.com/u/1" } }
          ]
        }
        """;

    /** Mirrors the {@code spring.jackson.deserialization.*} settings the GitHub codecs inherit. */
    static JsonMapper productionMapper() {
        JsonMapper base = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
        return GitHubGraphQlConfig.gitHubGraphQlObjectMapper(base);
    }

    /** An open pull request whose review requests are {@link #REVIEW_REQUESTS}. */
    static GHPullRequest pullRequestAskingForReviews(int number) {
        GHPullRequest pullRequest = new GHPullRequest();
        pullRequest.setNumber(number);
        pullRequest.setReviewRequests(productionMapper().readValue(REVIEW_REQUESTS, GHReviewRequestConnection.class));
        return pullRequest;
    }

    @Test
    void shouldReadTheTeamsAndTheUsersAmongTheReviewRequestsWhenBotsAndMannequinsAreAskedToo() {
        GitHubPullRequestDTO dto =
                Objects.requireNonNull(GitHubPullRequestDTO.fromPullRequest(pullRequestAskingForReviews(7)));

        assertThat(dto.requestedTeamIds()).containsExactly(13459148L, 13459149L);
        assertThat(dto.requestedReviewers()).extracting(GitHubUserDTO::login).containsExactly("FelixTJDietrich");
    }

    /** {@code reviewRequests(first: 100)} has no follow-up page: a longer list is not read whole. */
    @Test
    void shouldLeaveTheReviewRequestsUnreadWhenGitHubListsMoreThanWereFetched() {
        GHPullRequest pullRequest = pullRequestAskingForReviews(9);
        pullRequest.getReviewRequests().setTotalCount(101);

        GitHubPullRequestDTO dto = Objects.requireNonNull(GitHubPullRequestDTO.fromPullRequest(pullRequest));

        assertThat(dto.requestedTeamIds()).isNull();
        assertThat(dto.requestedReviewers()).isNull();
    }

    @Test
    void shouldLeaveTheTeamsUnreadWhenTheSyncDidNotReadTheReviewRequests() {
        GHPullRequest pullRequest = new GHPullRequest();
        pullRequest.setNumber(8);

        assertThat(Objects.requireNonNull(GitHubPullRequestDTO.fromPullRequest(pullRequest))
                        .requestedTeamIds())
                .isNull();
    }
}

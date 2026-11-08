package de.tum.cit.aet.hephaestus.integration.scm.github.issue;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class GitHubIssueCommentCountTest extends BaseUnitTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void shouldKeepAnOmittedWebhookCountUnknown() {
        assertThat(mapper.readValue("{\"id\":1,\"number\":1,\"locked\":false}", GitHubIssueDTO.class)
                        .commentsCount())
                .isNull();
    }

    @Test
    void shouldKeepAnUnselectedGraphQlCommentConnectionUnknown() {
        var issue = new GHIssue();
        issue.setNumber(1);
        assertThat(Objects.requireNonNull(GitHubIssueDTO.fromIssue(issue)).commentsCount())
                .isNull();
        assertThat(Objects.requireNonNull(GitHubIssueDTO.fromIssueWithRepository(issue))
                        .commentsCount())
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 200})
    void shouldKeepAnExplicitCountIncludingZero(int count) {
        var issue = new GHIssue();
        issue.setNumber(1);
        var comments = new GHIssueCommentConnection();
        comments.setTotalCount(count);
        issue.setComments(comments);

        assertThat(Objects.requireNonNull(GitHubIssueDTO.fromIssue(issue)).commentsCount())
                .isEqualTo(count);
        assertThat(Objects.requireNonNull(GitHubIssueDTO.fromIssueWithRepository(issue))
                        .commentsCount())
                .isEqualTo(count);
        assertThat(mapper.readValue("{\"number\":1,\"locked\":false,\"comments\":" + count + "}", GitHubIssueDTO.class)
                        .commentsCount())
                .isEqualTo(count);
    }
}

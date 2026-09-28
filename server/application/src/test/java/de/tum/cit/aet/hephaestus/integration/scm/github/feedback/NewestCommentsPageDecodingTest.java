package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlTestMapper;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueCommentConnection;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

/**
 * Every other test of {@code GithubSummaryChannel.findExistingSummary} stubs
 * {@code ClientResponseField.toEntity} and hands back an already-built connection, so no JSON is decoded:
 * were {@code hasPreviousPage}/{@code startCursor} dropped on the way in, the scan would report a confirmed
 * {@code ABSENT} after one page and license a duplicate summary on any thread longer than the page size.
 *
 * <p>The payload is a verbatim page from api.github.com (issue comments, {@code last: 1}), cursors included.
 */
class NewestCommentsPageDecodingTest extends BaseUnitTest {

    private static final String LIVE_PAGE = """
        {
          "pageInfo": {
            "hasPreviousPage": true,
            "startCursor": "Y3Vyc29yOnYyOpHPAAAAASmmmdk="
          },
          "nodes": [
            { "id": "IC_kwDOL-LvAM8AAAABKaaZ2Q", "body": "a comment body" }
          ]
        }
        """;

    @Test
    void backwardsPageInfoDecodesIntoTheGeneratedModel() {
        GHIssueCommentConnection connection =
                GitHubGraphQlTestMapper.create().readValue(LIVE_PAGE, GHIssueCommentConnection.class);

        assertThat(connection.getPageInfo()).isNotNull();
        assertThat(connection.getPageInfo().getHasPreviousPage()).isTrue();
        assertThat(connection.getPageInfo().getStartCursor()).isEqualTo("Y3Vyc29yOnYyOpHPAAAAASmmmdk=");
        assertThat(connection.getNodes()).singleElement().satisfies(node -> {
            assertThat(node.getId()).isEqualTo("IC_kwDOL-LvAM8AAAABKaaZ2Q");
            assertThat(node.getBody()).isEqualTo("a comment body");
        });
    }
}

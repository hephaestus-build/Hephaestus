package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.FeedbackReadbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("unit")
class GitHubFeedbackCopyReaderTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final UUID COPY = UUID.fromString("31000000-0000-4000-8000-000000000001");
    private static final String MARKER = "<!-- hephaestus-feedback-copy:" + COPY + " -->";

    @Test
    void shouldReadActualProviderWordsRenderingAndPositionInsteadOfTheExpectedAnchor() {
        var observed = read(thread(), true);
        assertThat(observed.status()).isEqualTo(CopyReadback.Status.FOUND);
        assertThat(observed.body()).isEqualTo("Actual provider words\n" + MARKER);
        assertThat(observed.renderedBody()).isEqualTo("<p>Actual rendered words</p>");
        assertThat(observed.anchor()).isEqualTo(new FeedbackAnchor("src/actual.ts", 12, 14, CitationSide.OLD));
    }

    @Test
    void shouldKeepHumanEditsAndRepliesOutOfRepairContent() {
        var node = thread();
        ((ObjectNode) node.path("comments").path("nodes").path(0))
                .set("editor", MAPPER.createObjectNode().put("login", "person"));
        assertThat(read(node, true)).isEqualTo(CopyReadback.refused(COPY, CopyReadback.Status.HUMAN_EDITED));
        node = thread();
        ((ObjectNode) node.path("comments")).put("totalCount", 2);
        assertThat(read(node, true)).isEqualTo(CopyReadback.refused(COPY, CopyReadback.Status.HUMAN_EDITED));
    }

    @Test
    void shouldRefuseForeignItemsAuthorsMarkersAndMissingOwnershipFacts() {
        var node = thread();
        ((ObjectNode) node.path("pullRequest")).put("id", "PR_other");
        assertThat(read(node, true).status()).isEqualTo(CopyReadback.Status.NOT_OWNED);
        node = thread();
        ((ObjectNode) node.path("comments").path("nodes").path(0)).put("viewerDidAuthor", false);
        assertThat(read(node, true).status()).isEqualTo(CopyReadback.Status.NOT_OWNED);
        node = thread();
        ((ObjectNode) node.path("comments").path("nodes").path(0)).put("body", "No retained marker");
        assertThat(read(node, true).status()).isEqualTo(CopyReadback.Status.NOT_OWNED);
        node = thread();
        ((ObjectNode) node.path("comments").path("nodes").path(0)).remove("editor");
        assertThat(read(node, true).status()).isEqualTo(CopyReadback.Status.UNAVAILABLE);
    }

    @Test
    void shouldRecordProviderResolutionAndDeletionWithoutReconstructingTheCopy() {
        var node = thread().put("isResolved", true).put("isOutdated", true);
        assertThat(read(node, true)).satisfies(actual -> {
            assertThat(actual.resolved()).isTrue();
            assertThat(actual.outdated()).isTrue();
        });
        var data = MAPPER.createObjectNode();
        data.putObject("viewer").put("login", "heph");
        data.putArray("nodes").addNull();
        assertThat(reader(data).read(target(), List.of(request(true))).getFirst())
                .isEqualTo(CopyReadback.refused(COPY, CopyReadback.Status.GONE));
    }

    @Test
    void shouldReadTheActualReviewSummary() {
        var node = MAPPER.createObjectNode()
                .put("__typename", "PullRequestReview")
                .put("id", "PRR_1")
                .put("body", "Provider summary\n" + MARKER)
                .put("bodyHTML", "<p>Provider summary</p>")
                .put("viewerDidAuthor", true)
                .put("state", "COMMENTED");
        node.putNull("editor");
        node.putObject("pullRequest").put("id", "PR_3");
        assertThat(read(node, false).body()).isEqualTo("Provider summary\n" + MARKER);
    }

    @Test
    void shouldReadAnIssueCommentOnlyOnTheExpectedIssue() {
        var data = MAPPER.createObjectNode();
        data.putObject("viewer").put("login", "heph");
        var node = data.putArray("nodes")
                .addObject()
                .put("__typename", "IssueComment")
                .put("id", "IC_1")
                .put("body", "Actual issue note\n" + MARKER)
                .put("bodyHTML", "<p>Actual issue note</p>")
                .put("viewerDidAuthor", true);
        node.putNull("editor");
        node.putNull("commentPullRequest");
        node.putObject("issue").put("id", "I_3");
        var target = ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITHUB, 1, null), "group/repo/issues/3", null);
        var request = new CopyReadRequest(COPY, "IC_1", null, MARKER, null);
        assertThat(reader(data).read(target, List.of(request)).getFirst().body())
                .isEqualTo("Actual issue note\n" + MARKER);
        ((ObjectNode) node.path("issue")).put("id", "I_other");
        assertThat(reader(data).read(target, List.of(request)).getFirst().status())
                .isEqualTo(CopyReadback.Status.NOT_OWNED);
    }

    private CopyReadback read(ObjectNode node, boolean thread) {
        var data = MAPPER.createObjectNode();
        data.putObject("viewer").put("login", "heph");
        data.putArray("nodes").add(node);
        return reader(data).read(target(), List.of(request(thread))).getFirst();
    }

    private static FeedbackTarget target() {
        return ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITHUB, 1, null), "group/repo#3", "b".repeat(40));
    }

    private static CopyReadRequest request(boolean thread) {
        return new CopyReadRequest(
                COPY,
                thread ? "PRRC_1" : "PRR_1",
                thread ? "PRRT_1" : null,
                MARKER,
                thread ? new FeedbackAnchor("src/expected.ts", 1, 1, CitationSide.NEW) : null);
    }

    private static GitHubFeedbackCopyReader reader(ObjectNode data) {
        var provider = mock(GitHubGraphQlClientProvider.class);
        when(provider.forScope(1L)).thenReturn(FeedbackReadbackFixtures.client("github", data));
        var subjects = mock(GitHubPrNodeIdResolver.class);
        when(subjects.resolve(1, "group", "repo", 3)).thenReturn("PR_3");
        when(subjects.resolveIssue(1, "group", "repo", 3)).thenReturn("I_3");
        return new GitHubFeedbackCopyReader(provider, subjects, ScmFeedbackFixtures.resolver());
    }

    private static ObjectNode thread() {
        var node = MAPPER.createObjectNode()
                .put("__typename", "PullRequestReviewThread")
                .put("id", "PRRT_1")
                .put("isResolved", false)
                .put("isOutdated", false)
                .put("subjectType", "LINE")
                .put("path", "src/actual.ts")
                .put("line", 14)
                .put("startLine", 12)
                .put("originalLine", 14)
                .put("originalStartLine", 12)
                .put("diffSide", "LEFT")
                .put("startDiffSide", "LEFT");
        node.putObject("pullRequest").put("id", "PR_3");
        var comments = node.putObject("comments").put("totalCount", 1);
        var first = comments.putArray("nodes")
                .addObject()
                .put("id", "PRRC_1")
                .put("body", "Actual provider words\n" + MARKER)
                .put("bodyHTML", "<p>Actual rendered words</p>")
                .put("viewerDidAuthor", true);
        first.putNull("editor");
        return node;
    }
}

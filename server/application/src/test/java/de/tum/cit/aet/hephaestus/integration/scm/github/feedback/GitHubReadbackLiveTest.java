package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlFragments;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Posts copies on a real pull request through {@link GitHubLineNoteWriter}, then reads them back as a later attempt does
 * after it lost the receipt. Readback must find a copy whatever footer it carries: the current one, one with another
 * workspace address, and the footer that v1.0.0-rc.1 posted without the disclosure marker. It must not find a copy that
 * was never posted. The test removes the line comments it wrote and empties the review bodies, which GitHub cannot
 * delete.
 *
 * <p>Needs {@code READBACK_GITHUB_TOKEN} with write access to {@code READBACK_GITHUB_PULL} ({@code owner/repo#number}),
 * and {@code READBACK_PATH} and {@code READBACK_LINE}: a line that the pull request adds.
 */
@Tag("live")
class GitHubReadbackLiveTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void shouldFindEveryPostedCopyWhateverFooterItCarriesWhenTheReceiptIsLost() {
        String token = System.getenv("READBACK_GITHUB_TOKEN");
        String pull = System.getenv("READBACK_GITHUB_PULL");
        assumeThat(token).as("READBACK_GITHUB_TOKEN").isNotBlank();
        assumeThat(pull).as("READBACK_GITHUB_PULL").isNotBlank();
        String path = Objects.requireNonNull(System.getenv("READBACK_PATH"), "READBACK_PATH");
        int line = Integer.parseInt(Objects.requireNonNull(System.getenv("READBACK_LINE"), "READBACK_LINE"));
        HttpGraphQlClient client = client(token);
        var pr = GitHubSummaryWriter.parseSubjectExternalId(pull);
        Map<String, Object> head = Objects.requireNonNull(client.document("""
                        query($owner: String!, $name: String!, $number: Int!) {
                          repository(owner: $owner, name: $name) {
                            pullRequest(number: $number) { id baseRefOid headRefOid }
                          }
                        }
                        """)
                .variable("owner", pr.owner())
                .variable("name", pr.name())
                .variable("number", pr.number())
                .retrieve("repository.pullRequest")
                .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(TIMEOUT));
        FeedbackTarget target = ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITHUB, 1L, null),
                pull,
                String.valueOf(head.get("baseRefOid")),
                String.valueOf(head.get("headRefOid")));
        var provider = mock(GitHubGraphQlClientProvider.class);
        when(provider.forScope(anyLong())).thenReturn(client);
        var nodeIds = mock(GitHubPrNodeIdResolver.class);
        when(nodeIds.resolve(1L, pr.owner(), pr.name(), pr.number())).thenReturn(String.valueOf(head.get("id")));
        var current = writer(provider, nodeIds, ScmFeedbackFixtures.formatter("readback"));
        var posted = new ArrayList<CopyReceipt>();
        try {
            var fresh = ScmFeedbackFixtures.readbackPackage("current", path, line);
            var written = current.postPackage(target, fresh, (attempting, completed) -> true)
                    .receipts();
            posted.addAll(written);
            assertThat(written).hasSize(3).allMatch(CopyReceipt::acknowledged);
            assertFound(current.findPosted(target, fresh), written);
            assertFound(
                    writer(provider, nodeIds, ScmFeedbackFixtures.formatter("renamed"))
                            .findPosted(target, fresh),
                    written);

            var old = ScmFeedbackFixtures.readbackPackage("rc.1", path, line);
            var oldWritten = writer(
                            provider, nodeIds, ScmFeedbackFixtures.formatterBeforeDisclosureMarker("readback"))
                    .postPackage(target, old, (attempting, completed) -> true)
                    .receipts();
            posted.addAll(oldWritten);
            assertThat(oldWritten).hasSize(3).allMatch(CopyReceipt::acknowledged);
            assertFound(current.findPosted(target, old), oldWritten);

            var never = ScmFeedbackFixtures.readbackPackage("never", path, line);
            assertThat(current.findPosted(target, never)).isEmpty();
        } finally {
            remove(client, posted);
        }
    }

    private static void assertFound(@Nullable List<CopyReceipt> found, List<CopyReceipt> written) {
        assertThat(found).as("readback").isNotNull();
        assertThat(Objects.requireNonNull(found))
                .allMatch(CopyReceipt::acknowledged)
                .extracting(CopyReceipt::copyId, CopyReceipt::externalRef)
                .containsExactlyInAnyOrderElementsOf(written.stream()
                        .map(receipt -> tuple(receipt.copyId(), receipt.externalRef()))
                        .toList());
    }

    private static GitHubLineNoteWriter writer(
            GitHubGraphQlClientProvider provider, GitHubPrNodeIdResolver nodeIds, ScmFeedbackFormatter formatter) {
        return new GitHubLineNoteWriter(
                provider, nodeIds, mock(OutboundEgressGuard.class), ScmFeedbackFixtures.resolver(), formatter);
    }

    private static HttpGraphQlClient client(String token) {
        var documents = new FragmentMergingDocumentSource(
                List.of(
                        new ClassPathResource("graphql/github/operations/"),
                        new ClassPathResource("graphql/github/fragments/")),
                List.of(".graphql", ".gql"),
                List.of(
                        new ClassPathResource(GitHubGraphQlFragments.ACTOR_FRAGMENTS_RESOURCE),
                        new ClassPathResource(GitHubGraphQlFragments.COMMIT_ENRICHMENT_FIELDS_RESOURCE)));
        var web = WebClient.builder()
                .baseUrl("https://api.github.com/graphql")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        return HttpGraphQlClient.builder(web).documentSource(documents).build();
    }

    // A line or file copy is a review comment, which GitHub deletes; the summary is the body of a submitted review,
    // which it does not, so the test empties it.
    private static void remove(HttpGraphQlClient client, List<CopyReceipt> posted) {
        for (var receipt : posted) {
            String ref = receipt.externalRef();
            if (ref == null) continue;
            if (ref.startsWith("PRR_"))
                client.document("""
                                mutation($id: ID!) {
                                  updatePullRequestReview(input: {pullRequestReviewId: $id, body: "Removed after a readback test."}) {
                                    clientMutationId
                                  }
                                }
                                """)
                        .variable("id", ref)
                        .execute()
                        .block(TIMEOUT);
            else
                client.document("""
                                mutation($id: ID!) {
                                  deletePullRequestReviewComment(input: {id: $id}) { clientMutationId }
                                }
                                """)
                        .variable("id", ref)
                        .execute()
                        .block(TIMEOUT);
        }
    }
}

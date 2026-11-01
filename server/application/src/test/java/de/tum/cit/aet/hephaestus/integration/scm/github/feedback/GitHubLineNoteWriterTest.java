package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitHubLineNoteWriterTest extends BaseUnitTest {
    private static final String COMMIT = "b".repeat(40);
    private static final String MARKER = "<!-- hephaestus-feedback-copy:summary -->";
    private static final FeedbackTarget TARGET =
            ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", COMMIT);
    private final GitHubGraphQlClientProvider provider = mock(GitHubGraphQlClientProvider.class);
    private final GitHubPrNodeIdResolver resolver = mock(GitHubPrNodeIdResolver.class);
    private final OutboundEgressGuard guard = mock(OutboundEgressGuard.class);
    private final List<String> writes = new ArrayList<>();
    private final List<Map<String, Object>> reviews = new ArrayList<>();
    private final List<Map<String, Object>> threads = new ArrayList<>();
    private boolean loseSubmit;
    private int failThread;
    private int createdThreads;
    private GitHubLineNoteWriter writer;

    @BeforeEach
    void setUp() {
        writer = new GitHubLineNoteWriter(
                provider, resolver, guard, ScmFeedbackFixtures.resolver(), ScmFeedbackFixtures.formatter());
        var client = mock(HttpGraphQlClient.class);
        lenient().when(provider.forScope(1L)).thenReturn(client);
        lenient().when(resolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_42");
        lenient().when(client.documentName(anyString())).thenAnswer(call -> {
            String document = call.getArgument(0);
            var variables = new HashMap<String, Object>();
            var request = mock(GraphQlClient.RequestSpec.class);
            when(request.variable(anyString(), any())).thenAnswer(set -> {
                variables.put(set.getArgument(0), set.getArgument(1));
                return request;
            });
            when(request.execute()).thenAnswer(execute -> {
                try {
                    return Mono.just(answer(document, variables));
                } catch (RuntimeException failure) {
                    return Mono.error(failure);
                }
            });
            return request;
        });
    }

    @Test
    void shouldSubmitSummaryLineAndFileCopiesInOneNotifyingReview() {
        var publication = publication();
        var result = writer.postPackage(TARGET, publication, (ids, done) -> true);
        assertThat(result.receipts()).hasSize(3).allMatch(CopyReceipt::acknowledged);
        assertThat(writes)
                .containsExactly(
                        "AddPullRequestReviewWithThreads",
                        "AddFeedbackReviewThread",
                        "AddFeedbackReviewThread",
                        "SubmitFeedbackReview");
        assertThat(reviews).singleElement().satisfies(review -> {
            assertThat(review.get("state")).isEqualTo("COMMENTED");
            assertThat(review.get("body"))
                    .isEqualTo(ScmFeedbackFixtures.formatter()
                            .footer(TARGET.item())
                            .summary(Objects.requireNonNull(publication.summary())));
        });
        assertThat(threads).extracting(thread -> thread.get("subjectType")).containsExactly("LINE", "FILE");
        assertThat(threads).allSatisfy(thread -> {
            var comment = comment(thread);
            assertThat(String.valueOf(comment.get("body")))
                    .contains(
                            "AI-generated",
                            "Answer or disagree",
                            "settings#practice-feedback",
                            "<!-- hephaestus-feedback-copy:")
                    .doesNotContain("Feedback for @everyone");
        });
    }

    @Test
    void shouldAdoptTheSamePendingReviewAfterPartialDraftFailure() {
        failThread = 2;
        var first = writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(first.receipts()).noneMatch(CopyReceipt::acknowledged);
        assertThat(writes).doesNotContain("SubmitFeedbackReview");
        failThread = 0;
        var retried = writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(retried.receipts()).allMatch(CopyReceipt::acknowledged);
        assertThat(reviews).hasSize(1);
        assertThat(threads).hasSize(2);
        assertThat(writes.stream().filter("SubmitFeedbackReview"::equals)).hasSize(1);
    }

    @Test
    void shouldReadBackAnAmbiguousSubmissionWithoutAnotherNotification() {
        loseSubmit = true;
        var first = writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(first.receipts()).noneMatch(CopyReceipt::acknowledged);
        assertThat(writer.findPosted(TARGET, publication())).hasSize(3).allMatch(CopyReceipt::acknowledged);
        assertThat(writer.postPackage(TARGET, publication(), (ids, done) -> true)
                        .receipts())
                .hasSize(3)
                .allMatch(CopyReceipt::acknowledged);
        assertThat(writes.stream().filter("SubmitFeedbackReview"::equals)).hasSize(1);
    }

    @Test
    void shouldRefuseAnotherPendingReviewWithoutChangingIt() {
        reviews.add(review("Unrelated draft"));
        assertThat(writer.postPackage(TARGET, publication(), (ids, done) -> true)
                        .receipts())
                .noneMatch(CopyReceipt::acknowledged);
        assertThat(writes).isEmpty();
        assertThat(reviews.getFirst().get("body")).isEqualTo("Unrelated draft");
    }

    @Test
    void shouldRefuseForeignTextAddedToItsPendingReview() {
        failThread = 2;
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        var foreign = new LinkedHashMap<String, Object>(threads.getFirst());
        var comment = new LinkedHashMap<String, Object>(comment(foreign));
        comment.put("body", "Someone else's draft text");
        foreign.put("comments", Map.of("nodes", List.of(comment)));
        threads.add(foreign);
        failThread = 0;
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(writes).doesNotContain("SubmitFeedbackReview");
    }

    @Test
    void shouldStopBeforeSubmissionWhenThePublicationFenceIsRevoked() {
        writer.postPackage(TARGET, publication(), (ids, done) -> threads.size() < 2);
        assertThat(writes).doesNotContain("SubmitFeedbackReview");
        assertThat(reviews.getFirst().get("state")).isEqualTo("PENDING");
    }

    @Test
    void shouldRecoverAfterTheRateLimitStopsPrivateDraftCreation() {
        failThread = 2;
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        when(provider.isRateLimitCritical(1L)).thenReturn(true);
        var before = List.copyOf(writes);
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(writes).isEqualTo(before);
        when(provider.isRateLimitCritical(1L)).thenReturn(false);
        failThread = 0;
        assertThat(writer.postPackage(TARGET, publication(), (ids, done) -> true)
                        .receipts())
                .allMatch(CopyReceipt::acknowledged);
        assertThat(writes.stream().filter("SubmitFeedbackReview"::equals)).hasSize(1);
    }

    @Test
    void shouldNeverPublishAgainAfterTheSubmittedBodyIsEdited() {
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        reviews.getFirst().put("body", "Edited\n" + MARKER);
        var before = List.copyOf(writes);
        assertThat(writer.findPosted(TARGET, publication())).isNull();
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(writes).isEqualTo(before);
    }

    @Test
    void shouldRefuseReadbackForAChangedSideOrCommit() {
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        threads.getFirst().put("diffSide", "LEFT");
        var found = writer.findPosted(TARGET, publication());
        assertThat(found).isNotNull().hasSize(3);
        assertThat(Objects.requireNonNull(found).get(1).acknowledged()).isFalse();
        reviews.getFirst().put("commit", Map.of("oid", "c".repeat(40)));
        assertThat(writer.findPosted(TARGET, publication())).isNull();
    }

    @Test
    void shouldCreateNothingWhenTheInitialFenceRefuses() {
        writer.postPackage(TARGET, publication(), (ids, done) -> false);
        assertThat(writes).isEmpty();
    }

    private OnWorkPackage publication() {
        return new OnWorkPackage(
                new SummaryContent(
                        UUID.nameUUIDFromBytes("summary".getBytes(StandardCharsets.UTF_8)),
                        "Review summary.",
                        "summary",
                        MARKER),
                List.of(note("line", 8), note("file", null)));
    }

    private static LineNote note(String key, @Nullable Integer line) {
        return new LineNote(
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                new FeedbackAnchor("src/App.java", line, line, CitationSide.NEW),
                "Feedback for @everyone.",
                key,
                "<!-- hephaestus-feedback-copy:" + key + " -->");
    }

    private Map<String, Object> review(String body) {
        var review = new LinkedHashMap<String, Object>();
        review.put("id", "PRR_1");
        review.put("body", body);
        review.put("state", "PENDING");
        review.put("viewerDidAuthor", true);
        review.put("url", "https://github.com/owner/repo/pull/42#pullrequestreview-1");
        review.put("commit", Map.of("oid", COMMIT));
        return review;
    }

    @SuppressWarnings("unchecked") // The fake holds only the GraphQL maps it created.
    private static Map<String, Object> comment(Map<String, Object> thread) {
        var comments = (Map<String, Object>) Objects.requireNonNull(thread.get("comments"));
        return ((List<Map<String, Object>>) Objects.requireNonNull(comments.get("nodes"))).getFirst();
    }

    private ClientGraphQlResponse answer(String document, Map<String, Object> variables) {
        var fields = new HashMap<String, Object>();
        switch (document) {
            case "GetFeedbackReviews" -> {
                for (var review : reviews)
                    review.put(
                            "comments",
                            Map.of(
                                    "nodes",
                                    threads.stream()
                                            .map(GitHubLineNoteWriterTest::comment)
                                            .toList(),
                                    "pageInfo",
                                    Map.of("hasNextPage", false)));
                fields.put(
                        "repository.pullRequest.reviews",
                        Map.of("nodes", reviews, "pageInfo", Map.of("hasNextPage", false)));
            }
            case "GetPullRequestReviewThreads" ->
                fields.put(
                        "repository.pullRequest.reviewThreads",
                        Map.of("nodes", threads, "pageInfo", Map.of("hasNextPage", false)));
            case "AddPullRequestReviewWithThreads" -> {
                writes.add(document);
                assertThat(variables).doesNotContainKey("event");
                reviews.add(review((String) Objects.requireNonNull(variables.get("body"))));
                fields.put("addPullRequestReview.pullRequestReview.id", "PRR_1");
                fields.put("addPullRequestReview.pullRequestReview.state", "PENDING");
            }
            case "AddFeedbackReviewThread" -> {
                writes.add(document);
                createdThreads++;
                if (createdThreads == failThread) throw new IllegalStateException("Draft creation failed");
                @SuppressWarnings("unchecked")
                var input = (Map<String, Object>) Objects.requireNonNull(variables.get("input"));
                var comment = new LinkedHashMap<String, Object>();
                comment.put("id", "PRRC_" + createdThreads);
                comment.put("body", input.get("body"));
                comment.put("viewerDidAuthor", true);
                comment.put("path", input.get("path"));
                comment.put("originalLine", input.get("line"));
                comment.put("originalStartLine", input.get("startLine"));
                comment.put("originalCommit", Map.of("oid", COMMIT));
                var thread = new LinkedHashMap<String, Object>();
                thread.put("id", "PRRT_" + createdThreads);
                thread.put("subjectType", input.get("subjectType"));
                thread.put("diffSide", input.get("side"));
                thread.put("startDiffSide", input.get("startSide"));
                thread.put("comments", Map.of("nodes", List.of(comment)));
                threads.add(thread);
                fields.put("addPullRequestReviewThread.thread.id", thread.get("id"));
            }
            case "SubmitFeedbackReview" -> {
                writes.add(document);
                reviews.getFirst().put("state", "COMMENTED");
                if (loseSubmit) throw new IllegalStateException("Response lost after submission");
                fields.put("submitPullRequestReview.pullRequestReview.id", "PRR_1");
            }
            default -> throw new IllegalArgumentException(document);
        }
        var response = mock(ClientGraphQlResponse.class);
        lenient().when(response.getErrors()).thenReturn(List.of());
        lenient().when(response.field(anyString())).thenAnswer(call -> {
            var field = mock(ClientResponseField.class);
            lenient().when(field.getValue()).thenReturn(fields.get(call.getArgument(0)));
            return field;
        });
        return response;
    }
}

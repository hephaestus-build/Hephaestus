package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.LiveHead;
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
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
    private @Nullable String providerHead = COMMIT;
    private boolean failDelete;
    private int failThread;
    private int createdThreads;
    private int createdReviews;
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
    void shouldPostCodeAndPunctuationAsWrittenWhenALineNoteHoldsMarkdown() {
        var publication = new OnWorkPackage(
                null,
                List.of(
                        note("mention", 8, "Ask @alice"),
                        note("code", 9, "`deactivateUser` returns `false` here; log it, @alice.")));

        writer.postPackage(TARGET, publication, (ids, done) -> true);

        assertThat(threads)
                .extracting(thread -> comment(thread).get("body"))
                .containsExactly(
                        delivered("Ask `@alice`", "mention"),
                        delivered("`deactivateUser` returns `false` here; log it, `@alice`.", "code"));
    }

    @Test
    void shouldAdoptTheSamePendingReviewWhenItCouldNotBeDeletedAfterPartialDraftFailure() {
        failThread = 2;
        failDelete = true;
        var first = writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(first.receipts()).noneMatch(CopyReceipt::acknowledged).allMatch(CopyReceipt::unconfirmed);
        assertThat(writes).doesNotContain("SubmitFeedbackReview");
        failThread = 0;
        failDelete = false;
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
    void shouldReadBackASubmissionPostedWithAnEarlierFooter() {
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        reviews.getFirst()
                .put("body", earlierFooter(String.valueOf(reviews.getFirst().get("body"))));
        for (var thread : threads) {
            var comment = new LinkedHashMap<String, Object>(comment(thread));
            comment.put("body", earlierFooter(String.valueOf(comment.get("body"))));
            thread.put("comments", Map.of("nodes", List.of(comment)));
        }

        assertThat(writer.findPosted(TARGET, publication())).hasSize(3).allMatch(CopyReceipt::acknowledged);
    }

    private static String earlierFooter(String body) {
        String earlier = body.replaceAll("<sub>[^\\n]*</sub>", "<sub>An earlier footer.</sub>");
        assertThat(earlier).isNotEqualTo(body);
        return earlier;
    }

    @Test
    void shouldRefuseAnotherPendingReviewWithoutChangingIt() {
        reviews.add(review("PRR_9", "Unrelated draft"));
        assertThat(writer.postPackage(TARGET, publication(), (ids, done) -> true)
                        .receipts())
                .noneMatch(CopyReceipt::acknowledged);
        assertThat(writes).isEmpty();
        assertThat(reviews.getFirst().get("body")).isEqualTo("Unrelated draft");
    }

    @Test
    void shouldRefuseForeignTextAddedToItsPendingReview() {
        failThread = 2;
        failDelete = true;
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
    void shouldDeleteItsPendingReviewSoTheNextPackageDeliversWhenTheFenceRefusesBeforeSubmission() {
        var refused = writer.postPackage(TARGET, publication(), (ids, done) -> threads.size() < 2);
        assertThat(writes).doesNotContain("SubmitFeedbackReview").contains("DeleteFeedbackReview");
        assertThat(reviews).isEmpty();
        assertThat(threads).isEmpty();
        assertThat(refused.receipts()).hasSize(3).noneMatch(CopyReceipt::unconfirmed);

        var next = writer.postPackage(TARGET, otherPublication(), (ids, done) -> true);
        assertThat(next.receipts()).hasSize(3).allMatch(CopyReceipt::acknowledged);
        assertThat(writes.stream().filter("SubmitFeedbackReview"::equals)).hasSize(1);
        assertThat(reviews)
                .singleElement()
                .satisfies(review -> assertThat(review.get("body")).asString().contains(OTHER_MARKER));
    }

    @Test
    void shouldStayUnconfirmedWhenItsPendingReviewCannotBeDeletedAfterARefusal() {
        failDelete = true;
        var refused = writer.postPackage(TARGET, publication(), (ids, done) -> threads.size() < 2);
        assertThat(refused.receipts()).hasSize(3).allMatch(CopyReceipt::unconfirmed);
        assertThat(reviews.getFirst().get("state")).isEqualTo("PENDING");
        assertThat(writes).doesNotContain("SubmitFeedbackReview");
    }

    @Test
    void shouldDeleteAStrandedHephaestusDraftOnlyOnceTheFenceAdmitsTheNextPackage() {
        failDelete = true;
        writer.postPackage(TARGET, publication(), (ids, done) -> threads.size() < 2);
        failDelete = false;
        var stranded = reviews.getFirst();

        writer.postPackage(TARGET, otherPublication(), (ids, done) -> false);
        assertThat(reviews).containsExactly(stranded);

        var next = writer.postPackage(TARGET, otherPublication(), (ids, done) -> true);
        assertThat(next.receipts()).hasSize(3).allMatch(CopyReceipt::acknowledged);
        assertThat(reviews).singleElement().satisfies(review -> {
            assertThat(review.get("state")).isEqualTo("COMMENTED");
            assertThat(review.get("body")).asString().contains(OTHER_MARKER);
        });
        assertThat(threads).hasSize(2);
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
    void shouldFindTheCopyAndNeverPublishAgainAfterTheSubmittedBodyIsEdited() {
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        reviews.getFirst().put("body", "Edited\n" + MARKER);
        var before = List.copyOf(writes);
        assertThat(writer.findPosted(TARGET, publication())).hasSize(3).allMatch(CopyReceipt::acknowledged);
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(writes).isEqualTo(before);
    }

    @Test
    void shouldFindACopyWhoseWordsAnEarlierReleaseEscapedOtherwise() {
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        var review = reviews.getFirst();
        review.put("body", "Review summary\\.\n" + review.get("body"));
        threads.forEach(thread -> comment(thread)
                .put("body", "Feedback for \\@everyone\\.\n" + comment(thread).get("body")));
        assertThat(writer.findPosted(TARGET, publication())).hasSize(3).allMatch(CopyReceipt::acknowledged);
    }

    @Test
    void shouldLeaveNotesUnknownNotAbsentWhenASubmittedReviewCarriesThemWithoutThePackageMarker() {
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        reviews.getFirst().put("body", "");
        assertThat(writer.findPosted(TARGET, publication()))
                .allMatch(receipt -> !receipt.acknowledged() && Boolean.TRUE.equals(receipt.writeMayHaveStarted()))
                .extracting(CopyReceipt::copyId)
                .containsExactlyInAnyOrderElementsOf(
                        publication().notes().stream().map(LineNote::copyId).toList());
    }

    @Test
    void shouldDeleteTheLeftoverPendingReviewBeforeProvingAbsence() {
        failThread = 2;
        failDelete = true;
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(reviews.getFirst().get("state")).isEqualTo("PENDING");
        assertThat(writer.findPosted(TARGET, publication())).isNull();

        failDelete = false;
        assertThat(writer.findPosted(TARGET, publication())).isEmpty();
        assertThat(reviews).isEmpty();
        assertThat(threads).isEmpty();
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
    void shouldReadThePullRequestHeadFromGitHubNotFromThisServer() {
        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.CURRENT);

        providerHead = "c".repeat(40);
        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.MOVED);

        providerHead = null;
        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.UNKNOWN);
        assertThat(writes).isEmpty();
    }

    @Test
    void shouldNotReadTheHeadWhileTheRateLimitIsCritical() {
        when(provider.isRateLimitCritical(1L)).thenReturn(true);
        providerHead = "c".repeat(40);
        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.UNKNOWN);
        verify(provider, never()).forScope(anyLong());
    }

    @Test
    void shouldLetTheSendWaitWhenTheHeadReadSpendsTheLastOfTheBudget() {
        when(provider.isRateLimitCritical(1L)).thenReturn(false, true);
        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.UNKNOWN);
    }

    @Test
    void shouldCreateNothingWhenTheInitialFenceRefuses() {
        writer.postPackage(TARGET, publication(), (ids, done) -> false);
        assertThat(writes).isEmpty();
    }

    private static final String OTHER_MARKER = "<!-- hephaestus-feedback-copy:other-summary -->";

    /** The package of a later review on the same pull request. */
    private OnWorkPackage otherPublication() {
        return new OnWorkPackage(
                new SummaryContent(
                        UUID.nameUUIDFromBytes("other-summary".getBytes(StandardCharsets.UTF_8)),
                        "A later review summary.",
                        "other-summary",
                        OTHER_MARKER),
                List.of(note("other-line", 9), note("other-file", null)));
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
        return note(key, line, "Feedback for @everyone.");
    }

    private static LineNote note(String key, @Nullable Integer line, String body) {
        return new LineNote(
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                new FeedbackAnchor("src/App.java", line, line, CitationSide.NEW),
                body,
                key,
                "<!-- hephaestus-feedback-copy:" + key + " -->");
    }

    private static String delivered(String text, String key) {
        return text + "\n\n" + ScmFeedbackFormatter.DISCLOSURE + "\n"
                + "<sub>AI-generated feedback. Answer or disagree in [Hephaestus](https://hephaestus.example/w/team/feedback/"
                + TARGET.item().itemId() + ").</sub>\n"
                + "<sub>[Why you see this and how to stop it](https://hephaestus.example/settings#practice-feedback)</sub>\n\n"
                + "<!-- hephaestus-feedback-copy:" + key + " -->\n<!-- hephaestus-diff-note-ck=" + key + " -->";
    }

    private Map<String, Object> review(String id, String body) {
        var review = new LinkedHashMap<String, Object>();
        review.put("id", id);
        review.put("body", body);
        review.put("state", "PENDING");
        review.put("viewerDidAuthor", true);
        review.put("url", "https://github.com/owner/repo/pull/42#pullrequestreview-1");
        review.put("commit", Map.of("oid", COMMIT));
        return review;
    }

    /** GitHub submits or deletes only a pending review. */
    private Map<String, Object> pending(@Nullable Object id) {
        return reviews.stream()
                .filter(review -> Objects.equals(review.get("id"), id) && "PENDING".equals(review.get("state")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No pending review " + id));
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
                                            .filter(thread -> Objects.equals(thread.get("review"), review.get("id")))
                                            .map(GitHubLineNoteWriterTest::comment)
                                            .toList(),
                                    "pageInfo",
                                    Map.of("hasNextPage", false)));
                fields.put(
                        "repository.pullRequest.reviews",
                        Map.of("nodes", reviews, "pageInfo", Map.of("hasNextPage", false)));
            }
            case "GetFeedbackPullRequestHead" -> {
                if (providerHead == null) throw new IllegalStateException("GitHub did not answer");
                fields.put("repository.pullRequest.headRefOid", providerHead);
            }
            case "GetPullRequestReviewThreads" ->
                fields.put(
                        "repository.pullRequest.reviewThreads",
                        Map.of("nodes", threads, "pageInfo", Map.of("hasNextPage", false)));
            case "AddPullRequestReviewWithThreads" -> {
                writes.add(document);
                assertThat(variables).doesNotContainKey("event");
                String id = "PRR_" + ++createdReviews;
                reviews.add(review(id, (String) Objects.requireNonNull(variables.get("body"))));
                fields.put("addPullRequestReview.pullRequestReview.id", id);
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
                thread.put("review", input.get("pullRequestReviewId"));
                thread.put("subjectType", input.get("subjectType"));
                thread.put("diffSide", input.get("side"));
                thread.put("startDiffSide", input.get("startSide"));
                thread.put("comments", Map.of("nodes", List.of(comment)));
                threads.add(thread);
                fields.put("addPullRequestReviewThread.thread.id", thread.get("id"));
            }
            case "SubmitFeedbackReview" -> {
                writes.add(document);
                var review = pending(variables.get("reviewId"));
                review.put("state", "COMMENTED");
                if (loseSubmit) throw new IllegalStateException("Response lost after submission");
                fields.put("submitPullRequestReview.pullRequestReview.id", review.get("id"));
            }
            case "DeleteFeedbackReview" -> {
                writes.add(document);
                if (failDelete) throw new IllegalStateException("Deletion was not confirmed");
                var review = pending(variables.get("reviewId"));
                reviews.remove(review);
                threads.removeIf(thread -> Objects.equals(thread.get("review"), review.get("id")));
                fields.put("deletePullRequestReview.pullRequestReview.id", review.get("id"));
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

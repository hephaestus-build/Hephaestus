package de.tum.cit.aet.hephaestus.integration.scm.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ActivityContentSourceTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    private final ConversationContextQueryRepository queries = mock(ConversationContextQueryRepository.class);
    private final ScmConversationWorkQuery work = mock(ScmConversationWorkQuery.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private ActivityContentSource source;

    @BeforeEach
    void setUp() {
        source = new ActivityContentSource(queries, work, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldDropWordsThenTheOldestRowsOfTheLongestListAndNameWhatItLeftOut() {
        when(work.actors(1L, 2L)).thenReturn(Optional.of(new ScmConversationWorkQuery.Actors(5L, List.of(200L))));
        Repository repository = new Repository();
        repository.setNameWithOwner("course/intro");
        List<PullRequestReview> reviews = IntStream.range(0, 30)
                .mapToObj(i -> review(pullRequest(repository, 100 + i), "R".repeat(400)))
                .toList();
        List<IssueComment> comments = IntStream.range(0, 30)
                .mapToObj(i -> comment(pullRequest(repository, 200 + i), "C".repeat(400)))
                .toList();
        when(queries.findReviewsGivenSince(eq(1L), eq(5L), eq(List.of(200L)), any(Instant.class), any(Pageable.class)))
                .thenReturn(reviews);
        when(queries.findCommentsWrittenSince(
                        eq(1L), eq(5L), eq(List.of(200L)), any(Instant.class), any(Pageable.class)))
                .thenReturn(comments);

        JsonNode payload = source.buildPayload(1L, 2L);

        assertThat(mapper.writeValueAsString(payload).length()).isLessThanOrEqualTo(ActivityContentSource.MAX_CHARS);
        assertThat(payload.path("reviewsGiven").valueStream()).allSatisfy(row -> {
            assertThat(row.has("words")).isFalse();
            assertThat(row.path("wordsNotLoaded").asBoolean()).isTrue();
        });
        JsonNode omitted = payload.path("omittedForSize");
        assertThat(payload.path("reviewsGiven").size()
                        + omitted.path("reviewsGiven").asInt())
                .isEqualTo(30);
        assertThat(payload.path("comments").size() + omitted.path("comments").asInt())
                .isEqualTo(30);
        assertThat(omitted.path("reviewsGiven").asInt()
                        + omitted.path("comments").asInt())
                .isPositive();
        assertThat(payload.path("reviewsGiven")
                        .get(0)
                        .path("pullRequest")
                        .path("number")
                        .asInt())
                .as("the newest rows stay")
                .isEqualTo(100);
    }

    @Test
    void shouldSayActivityIsUnavailableWithoutAConnectedInstance() {
        when(work.actors(1L, 2L)).thenReturn(Optional.empty());

        JsonNode payload = source.buildPayload(1L, 2L);

        assertThat(payload.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(payload.has("pullRequests")).isFalse();
        verify(queries, never()).findCommitsAuthoredSince(anyLong(), anyLong(), any(), any(), any());
    }

    private static PullRequest pullRequest(Repository repository, int number) {
        PullRequest pr = new PullRequest();
        pr.setId((long) number);
        pr.setNumber(number);
        pr.setTitle("T".repeat(1_000));
        pr.setState(Issue.State.OPEN);
        pr.setRepository(repository);
        pr.setHtmlUrl("https://gitlab.example/course/intro/-/merge_requests/" + number);
        return pr;
    }

    private static PullRequestReview review(PullRequest pr, String body) {
        PullRequestReview review = new PullRequestReview();
        review.setPullRequest(pr);
        review.setState(PullRequestReview.State.COMMENTED);
        review.setBody(body);
        review.setSubmittedAt(NOW.minusSeconds(60));
        review.setHtmlUrl(pr.getHtmlUrl() + "#review");
        return review;
    }

    private static IssueComment comment(Issue on, String body) {
        IssueComment comment = new IssueComment();
        comment.setIssue(on);
        comment.setBody(body);
        comment.setCreatedAt(NOW.minusSeconds(60));
        comment.setHtmlUrl(on.getHtmlUrl() + "#note_1");
        return comment;
    }
}

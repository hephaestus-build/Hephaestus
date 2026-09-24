package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

@Tag("unit")
class GitLabPullRequestReviewCommentProcessorTest extends BaseUnitTest {

    private static final long PROVIDER_ID = 2L;
    private static final long PR_ID = 100L;
    private static final long THREAD_ID = 200L;
    private static final long SCOPE_ID = 1L;
    private static final long NOTE_NATIVE_ID = 555666L;
    private static final String NOTE_GLOBAL_ID = "gid://gitlab/DiffNote/" + NOTE_NATIVE_ID;

    @Mock
    private PullRequestReviewCommentRepository commentRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GitLabPullRequestReviewCommentProcessor processor;
    private IdentityProvider provider;
    private PullRequest pr;
    private PullRequestReviewThread thread;

    @BeforeEach
    void setUp() {
        processor = new GitLabPullRequestReviewCommentProcessor(commentRepository, eventPublisher);

        provider = new IdentityProvider();
        provider.setId(PROVIDER_ID);
        provider.setType(IdentityProviderType.GITLAB);

        pr = new PullRequest();
        pr.setId(PR_ID);
        pr.setProvider(provider);
        Repository repository = new Repository();
        repository.setId(300L);
        repository.setNameWithOwner("group/project");
        pr.setRepository(repository);

        thread = new PullRequestReviewThread();
        thread.setId(THREAD_ID);
        thread.setProvider(provider);
    }

    // resolvePath (static helper)

    @Nested
    class ResolvePath {

        @Test
        void shouldPreferNewPathWhenAvailable() {
            var data = buildDiffNoteData("new/path.ts", "old/path.ts", "ignored.ts", 10, null, null, null, null);
            assertThat(GitLabPullRequestReviewCommentProcessor.resolvePath(data))
                    .isEqualTo("new/path.ts");
        }

        @Test
        void shouldFallBackToOldPathWhenNewPathIsBlank() {
            var data = buildDiffNoteData(null, "old/path.ts", "ignored.ts", null, 5, null, null, null);
            assertThat(GitLabPullRequestReviewCommentProcessor.resolvePath(data))
                    .isEqualTo("old/path.ts");
        }

        @Test
        void shouldFallBackToFilePathWhenNewAndOldPathAreNull() {
            var data = buildDiffNoteData(null, null, "resolved/path.ts", 10, null, null, null, null);
            assertThat(GitLabPullRequestReviewCommentProcessor.resolvePath(data))
                    .isEqualTo("resolved/path.ts");
        }

        @Test
        void shouldReturnEmptyStringWhenNoPathIsAvailable() {
            var data = buildDiffNoteData(null, null, null, 10, null, null, null, null);
            assertThat(GitLabPullRequestReviewCommentProcessor.resolvePath(data))
                    .isEmpty();
        }
    }

    // deriveSide (static helper)

    @Nested
    class DeriveSide {

        @Test
        void shouldReturnRightWhenNewLineIsPresent() {
            assertThat(GitLabPullRequestReviewCommentProcessor.deriveSide(42, null))
                    .isEqualTo(PullRequestReviewComment.Side.RIGHT);
        }

        @Test
        void shouldReturnLeftWhenOnlyOldLineIsPresent() {
            assertThat(GitLabPullRequestReviewCommentProcessor.deriveSide(null, 7))
                    .isEqualTo(PullRequestReviewComment.Side.LEFT);
        }

        @Test
        void shouldDefaultToRightWhenNeitherLineIsPresent() {
            assertThat(GitLabPullRequestReviewCommentProcessor.deriveSide(null, null))
                    .isEqualTo(PullRequestReviewComment.Side.RIGHT);
        }
    }

    // buildDiffHunkStub (static helper)

    @Nested
    class BuildDiffHunkStub {

        @Test
        void shouldBuildSingleLineStubWhenNewLineIsPresent() {
            assertThat(GitLabPullRequestReviewCommentProcessor.buildDiffHunkStub(null, 42))
                    .isEqualTo("@@ -0,1 +42,1 @@");
        }

        @Test
        void shouldBuildSingleLineStubWhenOldLineIsPresent() {
            assertThat(GitLabPullRequestReviewCommentProcessor.buildDiffHunkStub(7, null))
                    .isEqualTo("@@ -7,1 +0,1 @@");
        }

        @Test
        void shouldReturnNullWhenBothLinesAreNull() {
            assertThat(GitLabPullRequestReviewCommentProcessor.buildDiffHunkStub(null, null))
                    .isNull();
        }

        @Test
        void shouldReturnNullWhenBothLinesAreZero() {
            assertThat(GitLabPullRequestReviewCommentProcessor.buildDiffHunkStub(0, 0))
                    .isNull();
        }
    }

    // findOrCreateComment — new comment creation

    @Nested
    class CreateComment {

        @Test
        void shouldPopulateAllPositionMetadataWhenCreatingComment() {
            when(commentRepository.findByNativeIdAndProviderId(NOTE_NATIVE_ID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(commentRepository.save(any(PullRequestReviewComment.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewComment.class));

            var data = buildDiffNoteData(
                    "src/Foo.ts", "src/Foo.ts", "src/Foo.ts", 42, null, "head-sha", "base-sha", "start-sha");
            var context = new GitLabPullRequestReviewCommentProcessor.CommentContext(
                    thread, pr, null, provider, null, null, SCOPE_ID);

            PullRequestReviewComment saved = processor.findOrCreateComment(data, context);

            assertThat(saved).isNotNull();
            assertThat(saved.getPath()).isEqualTo("src/Foo.ts");
            assertThat(saved.getLine()).isEqualTo(42);
            assertThat(saved.getOriginalLine()).isZero();
            assertThat(saved.getSide()).isEqualTo(PullRequestReviewComment.Side.RIGHT);
            assertThat(saved.getStartSide()).isEqualTo(PullRequestReviewComment.Side.RIGHT);
            assertThat(saved.getCommitId()).isEqualTo("head-sha");
            assertThat(saved.getOriginalCommitId()).isEqualTo("base-sha");
            assertThat(saved.getDiffHunk()).isEqualTo("@@ -0,1 +42,1 @@");
        }

        @Test
        void shouldKeepTheOldLineAsTheLineWhenTheNoteSitsOnARemovedLine() {
            when(commentRepository.findByNativeIdAndProviderId(NOTE_NATIVE_ID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(commentRepository.save(any(PullRequestReviewComment.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewComment.class));

            // A deletion: GitLab gives old_line only, and the note is anchored on the base side.
            var data =
                    buildDiffNoteData("src/Foo.ts", "src/Foo.ts", "src/Foo.ts", null, 17, "head-sha", "base-sha", null);
            var context = new GitLabPullRequestReviewCommentProcessor.CommentContext(
                    thread, pr, null, provider, null, null, SCOPE_ID);

            PullRequestReviewComment saved = processor.findOrCreateComment(data, context);

            assertThat(saved).isNotNull();
            assertThat(saved.getLine()).isEqualTo(17);
            assertThat(saved.getOriginalLine()).isEqualTo(17);
            assertThat(saved.getSide()).isEqualTo(PullRequestReviewComment.Side.LEFT);
        }

        @Test
        void shouldFallBackToStartShaWhenBaseShaIsNull() {
            when(commentRepository.findByNativeIdAndProviderId(NOTE_NATIVE_ID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(commentRepository.save(any(PullRequestReviewComment.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewComment.class));

            var data = buildDiffNoteData("src/Foo.ts", "src/Foo.ts", null, 42, null, "head-sha", null, "start-sha");
            var context = new GitLabPullRequestReviewCommentProcessor.CommentContext(
                    thread, pr, null, provider, null, null, SCOPE_ID);

            PullRequestReviewComment saved = processor.findOrCreateComment(data, context);

            assertThat(saved).isNotNull();
            assertThat(saved.getOriginalCommitId()).isEqualTo("start-sha");
        }

        @Test
        void shouldAttachInReplyToWhenReplyContextIsProvided() {
            when(commentRepository.findByNativeIdAndProviderId(NOTE_NATIVE_ID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(commentRepository.save(any(PullRequestReviewComment.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewComment.class));

            PullRequestReviewComment parent = new PullRequestReviewComment();
            parent.setId(999L);

            var data = buildDiffNoteData("src/Foo.ts", null, null, 42, null, "head-sha", "base-sha", null);
            var context = new GitLabPullRequestReviewCommentProcessor.CommentContext(
                    thread, pr, null, provider, parent, null, SCOPE_ID);

            PullRequestReviewComment saved = processor.findOrCreateComment(data, context);

            assertThat(saved).isNotNull();
            assertThat(saved.getInReplyTo()).isSameAs(parent);
        }

        @Test
        void shouldLinkReviewWhenReviewContextIsProvided() {
            when(commentRepository.findByNativeIdAndProviderId(NOTE_NATIVE_ID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(commentRepository.save(any(PullRequestReviewComment.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewComment.class));

            PullRequestReview review = new PullRequestReview();
            review.setId(777L);
            review.setComments(new HashSet<>());

            var data = buildDiffNoteData("src/Foo.ts", null, null, 42, null, "head-sha", "base-sha", null);
            var context = new GitLabPullRequestReviewCommentProcessor.CommentContext(
                    thread, pr, null, provider, null, review, SCOPE_ID);

            PullRequestReviewComment saved = processor.findOrCreateComment(data, context);

            assertThat(saved).isNotNull();
            assertThat(saved.getReview()).isSameAs(review);
            assertThat(review.getComments()).contains(saved);
        }

        @Test
        void shouldPublishReviewCommentCreatedEventWhenNewCommentIsPersisted() {
            when(commentRepository.findByNativeIdAndProviderId(NOTE_NATIVE_ID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(commentRepository.save(any(PullRequestReviewComment.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewComment.class));

            var data = buildDiffNoteData("src/Foo.ts", null, null, 42, null, "head-sha", "base-sha", null);
            var context = new GitLabPullRequestReviewCommentProcessor.CommentContext(
                    thread, pr, null, provider, null, null, SCOPE_ID);
            processor.findOrCreateComment(data, context);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue()).isInstanceOf(ScmDomainEvent.ReviewCommentCreated.class);
        }

        @Test
        void shouldSkipWhenNoteGlobalIdCannotBeParsed() {
            var data = new GitLabPullRequestReviewCommentProcessor.DiffNoteData(
                    "not-a-gid", "body", "https://example", "p", 1, null, null, null, null, null, null, null, null);
            var context = new GitLabPullRequestReviewCommentProcessor.CommentContext(
                    thread, pr, null, provider, null, null, SCOPE_ID);

            PullRequestReviewComment saved = processor.findOrCreateComment(data, context);

            assertThat(saved).isNull();
            verify(commentRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // DiffNoteData backward-compat constructor

    @Nested
    class DiffNoteDataBackwardCompat {

        @Test
        void shouldDefaultStartShaToNullFor12ArgCallers() {
            var data = new GitLabPullRequestReviewCommentProcessor.DiffNoteData(
                    "gid://gitlab/DiffNote/1",
                    "body",
                    "https://example",
                    "src/Foo.ts",
                    10,
                    null,
                    "src/Foo.ts",
                    null,
                    "head-sha",
                    "base-sha",
                    Instant.EPOCH,
                    Instant.EPOCH);
            assertThat(data.startSha()).isNull();
            assertThat(data.baseSha()).isEqualTo("base-sha");
        }
    }

    // Helpers

    private static GitLabPullRequestReviewCommentProcessor.DiffNoteData buildDiffNoteData(
            @Nullable String newPath,
            @Nullable String oldPath,
            @Nullable String filePath,
            @Nullable Integer newLine,
            @Nullable Integer oldLine,
            @Nullable String headSha,
            @Nullable String baseSha,
            @Nullable String startSha) {
        return new GitLabPullRequestReviewCommentProcessor.DiffNoteData(
                NOTE_GLOBAL_ID,
                "body",
                "https://example.com/note",
                filePath,
                newLine,
                oldLine,
                newPath,
                oldPath,
                headSha,
                baseSha,
                startSha,
                Instant.parse("2024-01-15T10:00:00Z"),
                Instant.parse("2024-01-15T10:00:00Z"));
    }
}

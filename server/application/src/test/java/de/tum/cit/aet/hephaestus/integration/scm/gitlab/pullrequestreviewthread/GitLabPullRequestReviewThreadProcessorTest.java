package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

@Tag("unit")
class GitLabPullRequestReviewThreadProcessorTest extends BaseUnitTest {

    private static final long PROVIDER_ID = 2L;
    private static final long PR_ID = 100L;
    private static final long SCOPE_ID = 1L;
    private static final String DISCUSSION_GID = "gid://gitlab/Discussion/6a9c1750b37d4e";
    private static final Instant CREATED_AT = Instant.parse("2024-01-15T10:00:00Z");

    @Mock
    private PullRequestReviewThreadRepository threadRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GitLabPullRequestReviewThreadProcessor processor;
    private IdentityProvider provider;
    private PullRequest pr;

    @BeforeEach
    void setUp() {
        processor = new GitLabPullRequestReviewThreadProcessor(threadRepository, pullRequestRepository, eventPublisher);

        provider = new IdentityProvider();
        provider.setId(PROVIDER_ID);
        provider.setType(IdentityProviderType.GITLAB);

        pr = new PullRequest();
        pr.setId(PR_ID);
        pr.setProvider(provider);
        Repository repository = new Repository();
        repository.setId(200L);
        repository.setNameWithOwner("group/project");
        pr.setRepository(repository);
    }

    // deterministicNativeId

    @Nested
    class DeterministicNativeId {

        @Test
        void shouldBeDeterministicForIdenticalInput() {
            long a = GitLabPullRequestReviewThreadProcessor.deterministicNativeId(DISCUSSION_GID);
            long b = GitLabPullRequestReviewThreadProcessor.deterministicNativeId(DISCUSSION_GID);
            assertThat(a).isEqualTo(b);
        }

        @Test
        void shouldAlwaysReturnPositiveValue() {
            for (int i = 0; i < 50; i++) {
                String gid = "gid://gitlab/Discussion/" + Integer.toHexString(i * 997 + 13);
                assertThat(GitLabPullRequestReviewThreadProcessor.deterministicNativeId(gid))
                        .isNotNegative();
            }
        }

        @Test
        void shouldProduceDistinctValuesForDistinctDiscussionIds() {
            long a = GitLabPullRequestReviewThreadProcessor.deterministicNativeId("gid://gitlab/Discussion/aaaaaa");
            long b = GitLabPullRequestReviewThreadProcessor.deterministicNativeId("gid://gitlab/Discussion/bbbbbb");
            assertThat(a).isNotEqualTo(b);
        }
    }

    // findOrCreateThread — creation populates position metadata

    @Nested
    class CreateThread {

        @Test
        void shouldPopulateAllPositionMetadataWhenCreatingThread() {
            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(threadRepository.save(any(PullRequestReviewThread.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewThread.class));

            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID,
                    false,
                    null,
                    "src/Foo.ts",
                    42,
                    null,
                    PullRequestReviewComment.Side.RIGHT,
                    "head-sha",
                    "base-sha",
                    CREATED_AT);

            PullRequestReviewThread saved =
                    Objects.requireNonNull(processor.findOrCreateThread(data, pr, provider, SCOPE_ID));

            assertThat(saved).isNotNull();
            assertThat(saved.getPath()).isEqualTo("src/Foo.ts");
            assertThat(saved.getLine()).isEqualTo(42);
            assertThat(saved.getSide()).isEqualTo(PullRequestReviewComment.Side.RIGHT);
            // start_side mirrors side for single-line discussions (GraphQL has no line_range)
            assertThat(saved.getStartSide()).isEqualTo(PullRequestReviewComment.Side.RIGHT);
            assertThat(saved.getCommitSha()).isEqualTo("head-sha");
            assertThat(saved.getOriginalCommitSha()).isEqualTo("base-sha");
            assertThat(saved.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
        }

        @Test
        void shouldCreateTheThreadForItsCommentsUnresolvedWhateverTheDiscussionSays() {
            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(threadRepository.save(any(PullRequestReviewThread.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewThread.class));

            PullRequestReviewThread saved = Objects.requireNonNull(processor.findOrCreateThread(
                    resolved(Instant.parse("2024-01-16T09:30:00Z")), pr, provider, SCOPE_ID));

            assertThat(saved.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            assertThat(saved.getResolvedBy()).isNull();
            assertThat(saved.getResolvedAt()).isNull();
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        void shouldCreateAResolvedThreadFromAWholeReadAndReopenItFromALaterOne() {
            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            var saved = new AtomicReference<PullRequestReviewThread>();
            when(threadRepository.save(any(PullRequestReviewThread.class))).thenAnswer(inv -> {
                saved.set(inv.getArgument(0, PullRequestReviewThread.class));
                return saved.get();
            });
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(anyLong(), anyInt()))
                    .thenReturn(Optional.of(pr));
            Instant resolvedAt = Instant.parse("2024-01-16T09:30:00Z");
            Instant firstRead = Instant.parse("2024-01-16T10:00:00Z");

            processor.applyDiscussionRead(
                    Objects.requireNonNull(pr.getRepository()),
                    7,
                    firstRead,
                    List.of(resolved(resolvedAt)),
                    provider,
                    SCOPE_ID);

            PullRequestReviewThread created = Objects.requireNonNull(saved.get());
            assertThat(created.getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED);
            assertThat(created.getResolvedAt()).isEqualTo(resolvedAt);
            assertThat(created.getNodeId()).isEqualTo(DISCUSSION_GID);

            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.of(created));
            var reopened = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID, false, null, "src/Foo.ts", 42, null, null, null, null, null, CREATED_AT, null);

            processor.applyDiscussionRead(
                    Objects.requireNonNull(pr.getRepository()),
                    7,
                    firstRead.plusSeconds(60),
                    List.of(reopened),
                    provider,
                    SCOPE_ID);

            assertThat(created.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
            assertThat(created.getResolvedAt()).isNull();
        }

        private GitLabPullRequestReviewThreadProcessor.ThreadData resolved(Instant resolvedAt) {
            return new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID, true, null, "src/Foo.ts", 42, null, null, null, null, null, CREATED_AT, resolvedAt);
        }

        @Test
        void shouldKeepTheOldLineAsTheLineWhenTheDiscussionSitsOnARemovedLine() {
            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(threadRepository.save(any(PullRequestReviewThread.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewThread.class));

            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID,
                    false,
                    null,
                    "src/Foo.ts",
                    null,
                    17,
                    PullRequestReviewComment.Side.LEFT,
                    "head-sha",
                    "base-sha",
                    CREATED_AT);

            PullRequestReviewThread saved =
                    Objects.requireNonNull(processor.findOrCreateThread(data, pr, provider, SCOPE_ID));

            assertThat(saved.getLine()).isEqualTo(17);
            assertThat(saved.getSide()).isEqualTo(PullRequestReviewComment.Side.LEFT);
        }

        @Test
        void shouldLeavePositionNullWhenDataHasNoPositionInfo() {
            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(threadRepository.save(any(PullRequestReviewThread.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewThread.class));

            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID, false, null, null, null, CREATED_AT);

            PullRequestReviewThread saved =
                    Objects.requireNonNull(processor.findOrCreateThread(data, pr, provider, SCOPE_ID));

            assertThat(saved).isNotNull();
            assertThat(saved.getPath()).isNull();
            assertThat(saved.getLine()).isNull();
            assertThat(saved.getSide()).isNull();
            assertThat(saved.getCommitSha()).isNull();
            assertThat(saved.getOriginalCommitSha()).isNull();
            assertThat(saved.getOutdated()).isNull();
        }

        @Test
        void shouldMarkThreadOutdatedWhenPositionHasNullLines() {
            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(threadRepository.save(any(PullRequestReviewThread.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewThread.class));

            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID,
                    false,
                    null,
                    "src/Foo.ts",
                    null,
                    null,
                    PullRequestReviewComment.Side.RIGHT,
                    "head-sha",
                    "base-sha",
                    true,
                    CREATED_AT);

            PullRequestReviewThread saved =
                    Objects.requireNonNull(processor.findOrCreateThread(data, pr, provider, SCOPE_ID));

            assertThat(saved).isNotNull();
            assertThat(saved.getOutdated()).isTrue();
        }

        @Test
        void shouldMarkThreadOutdatedFalseWhenPositionIsActive() {
            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.empty());
            when(threadRepository.save(any(PullRequestReviewThread.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewThread.class));

            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID,
                    false,
                    null,
                    "src/Foo.ts",
                    42,
                    null,
                    PullRequestReviewComment.Side.RIGHT,
                    "head-sha",
                    "base-sha",
                    false,
                    CREATED_AT);

            PullRequestReviewThread saved =
                    Objects.requireNonNull(processor.findOrCreateThread(data, pr, provider, SCOPE_ID));

            assertThat(saved).isNotNull();
            assertThat(saved.getOutdated()).isFalse();
        }
    }

    // findOrCreateThread — update backfill

    @Nested
    class UpdateThreadBackfill {

        @Test
        void shouldBackfillAllPositionFieldsWhenExistingHasNull() {
            PullRequestReviewThread existing = new PullRequestReviewThread();
            existing.setId(500L);
            existing.setNodeId(DISCUSSION_GID);
            existing.setProvider(provider);
            existing.setPullRequest(pr);
            existing.setState(PullRequestReviewThread.State.UNRESOLVED);
            // all metadata fields null to simulate legacy row

            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.of(existing));
            when(threadRepository.save(any(PullRequestReviewThread.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewThread.class));

            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID,
                    false,
                    null,
                    "src/Foo.ts",
                    42,
                    null,
                    PullRequestReviewComment.Side.RIGHT,
                    "head-sha",
                    "base-sha",
                    CREATED_AT);

            PullRequestReviewThread result =
                    Objects.requireNonNull(processor.findOrCreateThread(data, pr, provider, SCOPE_ID));

            assertThat(result.getPath()).isEqualTo("src/Foo.ts");
            assertThat(result.getLine()).isEqualTo(42);
            assertThat(result.getSide()).isEqualTo(PullRequestReviewComment.Side.RIGHT);
            assertThat(result.getStartSide()).isEqualTo(PullRequestReviewComment.Side.RIGHT);
            assertThat(result.getCommitSha()).isEqualTo("head-sha");
            assertThat(result.getOriginalCommitSha()).isEqualTo("base-sha");
        }

        @Test
        void shouldNotClobberExistingValuesWhenBackfilling() {
            PullRequestReviewThread existing = new PullRequestReviewThread();
            existing.setId(500L);
            existing.setNodeId(DISCUSSION_GID);
            existing.setProvider(provider);
            existing.setPullRequest(pr);
            existing.setState(PullRequestReviewThread.State.UNRESOLVED);
            existing.setPath("existing/path.ts");
            existing.setLine(99);
            existing.setSide(PullRequestReviewComment.Side.LEFT);
            existing.setStartSide(PullRequestReviewComment.Side.LEFT);
            existing.setCommitSha("existing-head");
            existing.setOriginalCommitSha("existing-base");

            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.of(existing));

            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID,
                    false,
                    null,
                    "incoming/path.ts",
                    42,
                    null,
                    PullRequestReviewComment.Side.RIGHT,
                    "incoming-head",
                    "incoming-base",
                    CREATED_AT);

            PullRequestReviewThread result =
                    Objects.requireNonNull(processor.findOrCreateThread(data, pr, provider, SCOPE_ID));

            assertThat(result.getPath()).isEqualTo("existing/path.ts");
            assertThat(result.getLine()).isEqualTo(99);
            assertThat(result.getSide()).isEqualTo(PullRequestReviewComment.Side.LEFT);
            assertThat(result.getStartSide()).isEqualTo(PullRequestReviewComment.Side.LEFT);
            assertThat(result.getCommitSha()).isEqualTo("existing-head");
            assertThat(result.getOriginalCommitSha()).isEqualTo("existing-base");
            // Nothing changed, so no save should happen
            verify(threadRepository, never()).save(any());
        }

        @Test
        void shouldBackfillOutdatedWhenExistingHasNull() {
            PullRequestReviewThread existing = new PullRequestReviewThread();
            existing.setId(500L);
            existing.setNodeId(DISCUSSION_GID);
            existing.setProvider(provider);
            existing.setPullRequest(pr);
            existing.setState(PullRequestReviewThread.State.UNRESOLVED);
            existing.setPath("src/Foo.ts");
            existing.setLine(42);
            existing.setOutdated(null);

            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.of(existing));
            when(threadRepository.save(any(PullRequestReviewThread.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewThread.class));

            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID,
                    false,
                    null,
                    "src/Foo.ts",
                    42,
                    null,
                    PullRequestReviewComment.Side.RIGHT,
                    "head-sha",
                    "base-sha",
                    true,
                    CREATED_AT);

            PullRequestReviewThread result =
                    Objects.requireNonNull(processor.findOrCreateThread(data, pr, provider, SCOPE_ID));

            assertThat(result.getOutdated()).isTrue();
        }

        @Test
        void shouldNotOverwriteOutdatedWhenExistingHasValue() {
            PullRequestReviewThread existing = new PullRequestReviewThread();
            existing.setId(500L);
            existing.setNodeId(DISCUSSION_GID);
            existing.setProvider(provider);
            existing.setPullRequest(pr);
            existing.setState(PullRequestReviewThread.State.UNRESOLVED);
            existing.setPath("src/Foo.ts");
            existing.setLine(42);
            existing.setSide(PullRequestReviewComment.Side.RIGHT);
            existing.setStartSide(PullRequestReviewComment.Side.RIGHT);
            existing.setCommitSha("head-sha");
            existing.setOriginalCommitSha("base-sha");
            existing.setOutdated(false);

            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.of(existing));

            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID,
                    false,
                    null,
                    "src/Foo.ts",
                    42,
                    null,
                    PullRequestReviewComment.Side.RIGHT,
                    "head-sha",
                    "base-sha",
                    true,
                    CREATED_AT);

            PullRequestReviewThread result =
                    Objects.requireNonNull(processor.findOrCreateThread(data, pr, provider, SCOPE_ID));

            assertThat(result.getOutdated()).isFalse();
            // Nothing changed so no save should happen
            verify(threadRepository, never()).save(any());
        }

        @Test
        void shouldTransitionStateAndPublishResolvedEventWhenDiscussionResolvesExistingThread() {
            PullRequestReviewThread existing = new PullRequestReviewThread();
            existing.setId(500L);
            existing.setNodeId(DISCUSSION_GID);
            existing.setProvider(provider);
            existing.setPullRequest(pr);
            existing.setState(PullRequestReviewThread.State.UNRESOLVED);

            when(threadRepository.findByNodeIdAndProviderId(DISCUSSION_GID, PROVIDER_ID))
                    .thenReturn(Optional.of(existing));
            when(threadRepository.save(any(PullRequestReviewThread.class)))
                    .thenAnswer(inv -> inv.getArgument(0, PullRequestReviewThread.class));

            User resolver = new User();
            resolver.setLogin("resolver");

            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID, true, resolver, null, null, null, null, null, null, CREATED_AT);
            when(pullRequestRepository.findForUpdateByRepositoryIdAndNumber(anyLong(), anyInt()))
                    .thenReturn(Optional.of(pr));

            processor.applyDiscussionRead(
                    Objects.requireNonNull(pr.getRepository()), 7, Instant.now(), List.of(data), provider, SCOPE_ID);

            assertThat(existing.getState()).isEqualTo(PullRequestReviewThread.State.RESOLVED);
            assertThat(existing.getResolvedBy()).isSameAs(resolver);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getClass().getSimpleName()).isEqualTo("ReviewThreadResolved");
        }
    }

    // ThreadData backward-compat 6-arg constructor

    @Nested
    class ThreadDataBackwardCompat {

        @Test
        void shouldDefaultOptionalFieldsToNullFor6ArgCallers() {
            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID, false, null, "src/Foo.ts", 42, CREATED_AT);

            assertThat(data.side()).isNull();
            assertThat(data.oldLine()).isNull();
            assertThat(data.commitSha()).isNull();
            assertThat(data.originalCommitSha()).isNull();
            assertThat(data.outdated()).isNull();
            assertThat(data.filePath()).isEqualTo("src/Foo.ts");
            assertThat(data.newLine()).isEqualTo(42);
        }

        @Test
        void shouldDefaultOutdatedToNullFor10ArgCallers() {
            var data = new GitLabPullRequestReviewThreadProcessor.ThreadData(
                    DISCUSSION_GID,
                    false,
                    null,
                    "src/Foo.ts",
                    42,
                    null,
                    PullRequestReviewComment.Side.RIGHT,
                    "head-sha",
                    "base-sha",
                    CREATED_AT);

            assertThat(data.outdated()).isNull();
            assertThat(data.commitSha()).isEqualTo("head-sha");
        }
    }
}

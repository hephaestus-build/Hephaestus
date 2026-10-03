package de.tum.cit.aet.hephaestus.integration.scm.github.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestConnection;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

/**
 * The centre of gravity here is {@link FailsClosed}. A phantom row is a visible, self-correcting
 * annoyance; a wrongly-deleted issue is invisible and takes its feedback with it. So the tests that
 * matter most are the ones asserting that the sweep deletes <em>nothing</em> when it cannot prove it
 * saw the whole upstream set — one per way a listing can come up short.
 */
class GitHubDeletionSweepServiceTest extends BaseUnitTest {

    private static final Long SCOPE_ID = 100L;
    private static final Long REPO_ID = 7L;
    private static final String NAME_WITH_OWNER = "acme/widgets";

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private GitHubGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitHubGraphQlSyncCoordinator graphQlSyncHelper;

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private HttpGraphQlClient.RequestSpec requestSpec;

    @Mock
    private SyncExecutionHandle handle;

    private GitHubDeletionSweepService service;

    /** Responses handed to the client in order, so a multi-page listing can be scripted. */
    private final Deque<Mono<ClientGraphQlResponse>> scriptedResponses = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        GitHubSyncProperties syncProperties = new GitHubSyncProperties(
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofSeconds(120),
                Duration.ZERO,
                true,
                Duration.ofMinutes(5),
                10);

        service = new GitHubDeletionSweepService(
                issueRepository, repositoryRepository, graphQlClientProvider, graphQlSyncHelper, syncProperties);

        lenient().when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(client);
        lenient().when(client.documentName(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
        lenient()
                .when(requestSpec.execute())
                .thenAnswer(invocation -> scriptedResponses.isEmpty() ? Mono.empty() : scriptedResponses.poll());
    }

    private static Repository repository() {
        Repository repository = new Repository();
        repository.setId(REPO_ID);
        repository.setNameWithOwner(NAME_WITH_OWNER);
        return repository;
    }

    /** A valid response whose issues connection carries the given numbers. */
    private static ClientGraphQlResponse issuePage(List<Integer> numbers, boolean hasNextPage, int totalCount) {
        return issuePage(numbers, pageInfo(hasNextPage), totalCount);
    }

    /** A valid issues response with an explicitly-built {@code pageInfo}, for malformed-paging cases. */
    private static ClientGraphQlResponse issuePage(List<Integer> numbers, GHPageInfo pageInfo, int totalCount) {
        GHIssueConnection connection = new GHIssueConnection();
        connection.setNodes(numbers.stream()
                .map(number -> {
                    GHIssue issue = new GHIssue();
                    issue.setNumber(number);
                    return issue;
                })
                .toList());
        connection.setPageInfo(pageInfo);
        connection.setTotalCount(totalCount);
        return validResponse("repository.issues", connection);
    }

    private static ClientGraphQlResponse pullRequestPage(List<Integer> numbers, boolean hasNextPage, int totalCount) {
        GHPullRequestConnection connection = new GHPullRequestConnection();
        connection.setNodes(numbers.stream()
                .map(number -> {
                    GHPullRequest pullRequest = new GHPullRequest();
                    pullRequest.setNumber(number);
                    return pullRequest;
                })
                .toList());
        connection.setPageInfo(pageInfo(hasNextPage));
        connection.setTotalCount(totalCount);
        return validResponse("repository.pullRequests", connection);
    }

    private static ClientGraphQlResponse validResponse(String fieldPath, Object entity) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField field = mock(ClientResponseField.class);
        lenient().when(response.isValid()).thenReturn(true);
        lenient().when(response.field(fieldPath)).thenReturn(field);
        lenient().when(field.toEntity(ArgumentMatchers.<Class<Object>>any())).thenReturn(entity);
        return response;
    }

    /**
     * Stubs a repository-level feature flag onto an already-built page mock.
     *
     * <p>Left unstubbed by {@link #validResponse}, the flag reads as {@code null} — "unknown" — which is
     * what an unselected or restricted field looks like and which the sweep deliberately does not treat
     * as evidence. Tests that care about the flag say so explicitly.
     */
    private static ClientGraphQlResponse withFlag(ClientGraphQlResponse response, String path, Boolean value) {
        ClientResponseField field = mock(ClientResponseField.class);
        lenient().when(field.getValue()).thenReturn(value);
        lenient().when(response.field(path)).thenReturn(field);
        return response;
    }

    private static ClientGraphQlResponse issuePage(
            List<Integer> numbers, boolean hasNextPage, int totalCount, Boolean hasIssuesEnabled) {
        return withFlag(issuePage(numbers, hasNextPage, totalCount), "repository.hasIssuesEnabled", hasIssuesEnabled);
    }

    /** Both entity classes report an empty, provably-complete upstream — the inert default. */
    private void scriptEmptyUpstream() {
        scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0)));
        scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
    }

    private void stubLocalNumbers(List<Integer> issues, List<Integer> pullRequests) {
        lenient()
                .when(issueRepository.findLiveIssueNumbersByRepositoryId(REPO_ID))
                .thenReturn(issues);
        lenient()
                .when(issueRepository.findLivePullRequestNumbersByRepositoryId(REPO_ID))
                .thenReturn(pullRequests);
    }

    /**
     * Asserts the sweep wrote no tombstone at all. The write is type-discriminated — issues and pull
     * requests are tombstoned through separate repository methods — so "deleted nothing" means neither
     * was called.
     */
    private void verifyNothingTombstoned() {
        verify(issueRepository, never()).tombstoneIssuesByRepositoryIdAndNumbers(anyLong(), anyCollection(), any());
        verify(issueRepository, never())
                .tombstonePullRequestsByRepositoryIdAndNumbers(anyLong(), anyCollection(), any());
    }

    @Nested
    class RemovesWhatUpstreamNoLongerHas {

        @Test
        void shouldTombstoneIssuePresentLocallyButAbsentUpstream() {
            // Upstream has #1 and #3; the mirror also thinks it has #2, which was deleted upstream and
            // — absent a redelivered webhook — would otherwise live forever.
            scriptedResponses.add(Mono.just(issuePage(List.of(1, 3), false, 2)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());
            when(issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(anyLong(), anyCollection(), any()))
                    .thenReturn(1);

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            ArgumentCaptor<Collection<Integer>> captor = ArgumentCaptor.captor();
            verify(issueRepository)
                    .tombstoneIssuesByRepositoryIdAndNumbers(eq(REPO_ID), captor.capture(), any(Instant.class));
            assertThat(captor.getValue()).containsExactly(2);
            assertThat(outcome.issuesTombstoned()).isEqualTo(1);
            assertThat(outcome.skipped()).isFalse();
        }

        @Test
        void shouldTombstonePullRequestPresentLocallyButAbsentUpstream() {
            // GitHub emits no pull_request.deleted event at all, so this sweep is the only thing that
            // will ever notice a vanished pull request.
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(10), false, 1)));
            stubLocalNumbers(List.of(), List.of(10, 11));
            when(issueRepository.tombstonePullRequestsByRepositoryIdAndNumbers(anyLong(), anyCollection(), any()))
                    .thenReturn(1);

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            ArgumentCaptor<Collection<Integer>> captor = ArgumentCaptor.captor();
            verify(issueRepository)
                    .tombstonePullRequestsByRepositoryIdAndNumbers(eq(REPO_ID), captor.capture(), any(Instant.class));
            assertThat(captor.getValue()).containsExactly(11);
            assertThat(outcome.pullRequestsTombstoned()).isEqualTo(1);
        }

        @Test
        void shouldTombstoneNothingWhenMirrorAgreesWithUpstream() {
            scriptedResponses.add(Mono.just(issuePage(List.of(1, 2), false, 2)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.total()).isZero();
            assertThat(outcome.skipped()).isFalse();
        }

        @Test
        void shouldAssembleTheUpstreamSetAcrossPagesBeforeDiffing() {
            // Pagination is where a naive sweep goes wrong: page one alone says #3 does not exist.
            scriptedResponses.add(Mono.just(issuePage(List.of(1, 2), true, 3)));
            scriptedResponses.add(Mono.just(issuePage(List.of(3), false, 3)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());

            service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
        }
    }

    /**
     * The listing is not instant, and the mirror does not hold still while it runs. An item created
     * upstream mid-listing is legitimately absent from the pages already fetched but present locally
     * moments later via webhook — the one shape of local-but-not-upstream that is NOT a deletion.
     */
    @Nested
    class ToleratesConcurrentWrites {

        @Test
        void shouldNotTombstoneAnIssueInsertedByWebhookWhileTheListingWasRunning() {
            // The mirror starts at {1, 3}. Issue #4 is opened upstream after the listing began, so it
            // is not in the (complete, correct) upstream page — and its webhook lands locally before
            // the diff runs. Diffing a post-listing read of the mirror would make #4 look deleted and
            // tombstone a brand-new issue; it would then stay invisible until the next daily sync
            // resurrected it.
            List<Integer> mirror = new ArrayList<>(List.of(1, 3));
            lenient()
                    .when(issueRepository.findLiveIssueNumbersByRepositoryId(REPO_ID))
                    .thenAnswer(invocation -> List.copyOf(mirror));
            lenient()
                    .when(issueRepository.findLivePullRequestNumbersByRepositoryId(REPO_ID))
                    .thenReturn(List.of());
            scriptedResponses.add(Mono.fromSupplier(() -> {
                mirror.add(4); // the webhook insert, landing mid-listing
                return issuePage(List.of(1, 3), false, 2);
            }));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.issuesTombstoned()).isZero();
            assertThat(outcome.skipped()).isFalse();
        }

        @Test
        void shouldStillTombstoneItemsThatPredatedTheListing() {
            // The guard must not become a blanket amnesty: #2 was already in the mirror before the
            // listing started and upstream does not have it, so it is a genuine deletion.
            List<Integer> mirror = new ArrayList<>(List.of(1, 2, 3));
            lenient()
                    .when(issueRepository.findLiveIssueNumbersByRepositoryId(REPO_ID))
                    .thenAnswer(invocation -> List.copyOf(mirror));
            lenient()
                    .when(issueRepository.findLivePullRequestNumbersByRepositoryId(REPO_ID))
                    .thenReturn(List.of());
            scriptedResponses.add(Mono.fromSupplier(() -> {
                mirror.add(4);
                return issuePage(List.of(1, 3), false, 2);
            }));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            when(issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(anyLong(), anyCollection(), any()))
                    .thenReturn(1);

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            ArgumentCaptor<Collection<Integer>> captor = ArgumentCaptor.captor();
            verify(issueRepository)
                    .tombstoneIssuesByRepositoryIdAndNumbers(eq(REPO_ID), captor.capture(), any(Instant.class));
            // #2 only — never the concurrently-created #4.
            assertThat(captor.getValue()).containsExactly(2);
            assertThat(outcome.issuesTombstoned()).isEqualTo(1);
        }
    }

    /**
     * The guarantee: an upstream listing that is short for ANY reason authorizes NO deletion. Each
     * test below is one way the listing can come up short, and every one must end in zero deletes.
     */
    @Nested
    class FailsClosed {

        @Test
        void shouldDeleteNothingWhenPaginationAbortsMidListing() {
            // Page one arrives and says there is more; page two never does. The accumulator holds a
            // real-looking {1,2} — acting on it would tombstone every issue from #3 up.
            scriptedResponses.add(Mono.just(issuePage(List.of(1, 2), true, 4000)));
            scriptedResponses.add(Mono.error(new IllegalStateException("connection reset")));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3, 4), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.issuesTombstoned()).isZero();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenUpstreamTotalCountDisagreesWithWhatWasReceived() {
            // The single most dangerous shape: pagination claims it finished (hasNextPage=false) but
            // handed over fewer nodes than the server's own totalCount. Indistinguishable from a small
            // repository except for this cross-check.
            scriptedResponses.add(Mono.just(issuePage(List.of(1), false, 900)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenTheResponseCarriesGraphQlErrors() {
            ClientGraphQlResponse invalid = mock(ClientGraphQlResponse.class);
            when(invalid.isValid()).thenReturn(false);
            scriptedResponses.add(Mono.just(invalid));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());
            // No classification and no retry advice — the coordinator says abort.
            when(graphQlSyncHelper.classifyGraphQlErrors(any())).thenReturn(null);

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenRateLimitedAndTheCoordinatorRefusesToRetry() {
            ClientGraphQlResponse invalid = mock(ClientGraphQlResponse.class);
            when(invalid.isValid()).thenReturn(false);
            scriptedResponses.add(Mono.just(invalid));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());
            when(graphQlSyncHelper.classifyGraphQlErrors(any()))
                    .thenReturn(ClassificationResult.rateLimited(Duration.ofMinutes(30), "rate limited"));
            when(graphQlSyncHelper.handleGraphQlClassification(any())).thenReturn(false);

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenUpstreamClaimsMorePagesButHandsBackNoCursor() {
            // hasNextPage=true with a null endCursor: there is more and we have no way to ask for it.
            // The page itself is perfectly valid, so only the cursor check catches this.
            scriptedResponses.add(Mono.just(issuePage(List.of(1), pageInfo(null, true), 50)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldStillSweepPullRequestsWhenOnlyTheIssueListingIsIncomplete() {
            // The two connections fail independently. One being unverifiable must not indefinitely
            // suppress the other, or a single flaky listing freezes drift repair for the repository.
            scriptedResponses.add(Mono.just(issuePage(List.of(1), false, 900)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(10), false, 1)));
            stubLocalNumbers(List.of(1, 2), List.of(10, 11));
            when(issueRepository.tombstonePullRequestsByRepositoryIdAndNumbers(anyLong(), anyCollection(), any()))
                    .thenReturn(1);

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            assertThat(outcome.issuesTombstoned()).isZero();
            assertThat(outcome.pullRequestsTombstoned()).isEqualTo(1);
            assertThat(outcome.skipped()).isTrue();
            verify(issueRepository, never()).tombstoneIssuesByRepositoryIdAndNumbers(anyLong(), anyCollection(), any());
            verify(issueRepository, times(1))
                    .tombstonePullRequestsByRepositoryIdAndNumbers(anyLong(), anyCollection(), any());
        }

        @Test
        void shouldDeleteNothingWhenTheRepositoryNameCannotBeParsed() {
            Repository malformed = new Repository();
            malformed.setId(REPO_ID);
            malformed.setNameWithOwner("not-a-valid-name");

            var outcome = service.sweepRepository(SCOPE_ID, malformed, handle);

            verifyNothingTombstoned();
            assertThat(outcome.total()).isZero();
        }
    }

    /**
     * The blind spot the completeness proof cannot cover. Every check in {@link FailsClosed} is a check
     * about pagination, and pagination is trivially complete for a listing that returned nothing:
     * {@code totalCount: 0} reconciles with zero received nodes perfectly. These tests pin the two guards
     * that stop that degenerate agreement from authorizing the deletion of an entire repository.
     */
    @Nested
    class RefusesUnprovableEmptiness {

        @Test
        void shouldRefuseTheIssueListingWhenIssuesAreDisabledUpstream() {
            // The isolating case for the flag guard: the mirror is empty, so the emptiness guard cannot
            // fire and the ONLY thing that can mark this degraded is reading hasIssuesEnabled. Without the
            // flag, a repository with Issues switched off reads as a clean, complete, empty no-op.
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0, false)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldTombstoneNothingWhenIssuesAreDisabledUpstreamButTheMirrorHasLiveIssues() {
            // The consequential case: switching Issues off hides them. It does not delete them, and
            // switching the feature back on returns every one.
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0, false)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.issuesTombstoned()).isZero();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenUpstreamIssuesAreEmptyButTheMirrorStillHasLiveOnes() {
            // The flag says Issues are ON, so the flag guard is deliberately inert and only the emptiness
            // guard can save these three issues. "Every issue in the repository was deleted since the last
            // sync" is a far worse bet than "this listing is wrong".
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0, true)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.issuesTombstoned()).isZero();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenUpstreamPullRequestsAreEmptyButTheMirrorStillHasLiveOnes() {
            // Pull requests have no toggle to interrogate, so the emptiness guard is the only cover this
            // entity class gets — and it is the class GitHub emits no deletion event for at all.
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0, true)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(), List.of(10, 11));

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.pullRequestsTombstoned()).isZero();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldStayACleanNoOpWhenUpstreamAndMirrorAreBothEmpty() {
            // The guard must not turn every genuinely empty repository into a permanent warning: with
            // nothing local at risk there is nothing to protect, so this stays a non-degraded no-op.
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0, true)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.total()).isZero();
            assertThat(outcome.skipped()).isFalse();
        }

        @Test
        void shouldStillTombstoneAGenuinelyAbsentIssueWhenUpstreamIsNotEmpty() {
            // The guards are scoped to the empty listing and nothing else — an ordinary sweep with a
            // non-empty, feature-enabled upstream must still retire the row that upstream really lost.
            scriptedResponses.add(Mono.just(issuePage(List.of(1, 3), false, 2, true)));
            scriptedResponses.add(Mono.just(pullRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());
            when(issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(anyLong(), anyCollection(), any()))
                    .thenReturn(1);

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            ArgumentCaptor<Collection<Integer>> captor = ArgumentCaptor.captor();
            verify(issueRepository)
                    .tombstoneIssuesByRepositoryIdAndNumbers(eq(REPO_ID), captor.capture(), any(Instant.class));
            assertThat(captor.getValue()).containsExactly(2);
            assertThat(outcome.issuesTombstoned()).isEqualTo(1);
            assertThat(outcome.skipped()).isFalse();
        }
    }

    @Nested
    class HonorsCancellation {

        @Test
        void shouldDeleteNothingWhenCancelledBeforeTheListingStarts() {
            when(handle.isCancellationRequested()).thenReturn(true);
            stubLocalNumbers(List.of(1, 2, 3), List.of());

            service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            verify(client, never()).documentName(anyString());
        }

        @Test
        void shouldDeleteNothingWhenCancelledMidListing() {
            // Cancel AFTER page one of a two-page listing, so the accumulator genuinely holds a partial
            // set {1,2} — the dangerous case. The cancellation flag is polled at: sweepRepository entry
            // (false), the entity loop for ISSUE (false), the listing's while-loop before page one
            // (false → page one IS fetched), then the while-loop before page two (true → abort with a
            // populated accumulator). A cancel here must tombstone nothing rather than act on {1,2}.
            when(handle.isCancellationRequested()).thenReturn(false, false, false, true);
            scriptedResponses.add(Mono.just(issuePage(List.of(1, 2), true, 4)));
            scriptedResponses.add(Mono.just(issuePage(List.of(3, 4), false, 4)));
            stubLocalNumbers(List.of(1, 2, 3, 4, 5), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            // Exactly one page was fetched before the cancel — proof the accumulator was mid-listing,
            // not empty as it would be for a cancel-before-page-one.
            verify(requestSpec, times(1)).execute();
            verifyNothingTombstoned();
            assertThat(outcome.total()).isZero();
        }

        @Test
        void shouldStopSweepingRemainingRepositoriesWhenCancelled() {
            Repository first = repository();
            Repository second = new Repository();
            second.setId(8L);
            second.setNameWithOwner("acme/gadgets");
            when(repositoryRepository.findAllByWorkspaceMonitors(SCOPE_ID)).thenReturn(List.of(first, second));
            when(handle.isCancellationRequested()).thenReturn(true);

            var outcome = service.sweepScope(SCOPE_ID, handle);

            assertThat(outcome.total()).isZero();
            verifyNothingTombstoned();
        }
    }

    @Nested
    class SweepScope {

        @Test
        void shouldReportSweepPhaseProgressPerRepository() {
            when(repositoryRepository.findAllByWorkspaceMonitors(SCOPE_ID)).thenReturn(List.of(repository()));
            scriptEmptyUpstream();
            stubLocalNumbers(List.of(), List.of());

            service.sweepScope(SCOPE_ID, handle);

            verify(handle, atLeastOnce())
                    .progress(any(), any(), argThat(progress -> progress.phase() == SyncPhase.SWEEP));
        }

        @Test
        void shouldRunWithoutAHandleOutsideARecordedJob() {
            when(repositoryRepository.findAllByWorkspaceMonitors(SCOPE_ID)).thenReturn(List.of(repository()));
            scriptEmptyUpstream();
            stubLocalNumbers(List.of(), List.of());

            var outcome = service.sweepScope(SCOPE_ID, null);

            assertThat(outcome.total()).isZero();
        }

        @Test
        void shouldSweepNothingWhenScopeMonitorsNoRepositories() {
            when(repositoryRepository.findAllByWorkspaceMonitors(SCOPE_ID)).thenReturn(List.of());

            var outcome = service.sweepScope(SCOPE_ID, handle);

            assertThat(outcome.total()).isZero();
            assertThat(outcome.skipped()).isFalse();
        }
    }

    private static GHPageInfo pageInfo(@Nullable String endCursor, boolean hasNextPage) {
        GHPageInfo pageInfo = new GHPageInfo();
        pageInfo.setHasNextPage(hasNextPage);
        if (endCursor != null) pageInfo.setEndCursor(endCursor);
        return pageInfo;
    }

    private static GHPageInfo pageInfo(boolean hasNextPage) {
        GHPageInfo pageInfo = new GHPageInfo();
        pageInfo.setHasNextPage(hasNextPage);
        if (hasNextPage) pageInfo.setEndCursor("cursor");
        return pageInfo;
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler.HandleResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link GitLabDeletionSweepService}.
 *
 * <p>The sweep fails closed: it deletes <em>nothing</em> unless it can prove it saw the whole upstream
 * set. A phantom row is a visible, self-correcting annoyance; a wrongly-deleted issue is invisible and
 * takes its feedback with it. {@link FailsClosed} covers one case per way a GitLab listing can come up
 * short.
 */
@Tag("unit")
class GitLabDeletionSweepServiceTest extends BaseUnitTest {

    private static final Long SCOPE_ID = 100L;
    private static final Long REPO_ID = 7L;
    private static final String FULL_PATH = "acme/widgets";
    private static final Long PROVIDER_ID = 77L;

    /** The committed operation document each connection prefix is listed by. */
    private static final Map<String, String> LISTING_DOCUMENTS = Map.of(
            "project.issues", "GetProjectIssueNumbers", "project.mergeRequests", "GetProjectMergeRequestNumbers");

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabGraphQlResponseHandler responseHandler;

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private HttpGraphQlClient.RequestSpec requestSpec;

    @Mock
    private SyncExecutionHandle handle;

    @Mock
    private WorkspaceActorSelector actorSelector;

    @Mock
    private GitLabWorkspaceLinkService workspaceLinkService;

    private GitLabDeletionSweepService service;

    /** Responses handed to the client in order, so a multi-page listing can be scripted. */
    private final Deque<Mono<ClientGraphQlResponse>> scriptedResponses = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        GitLabProperties properties = new GitLabProperties(
                "https://gitlab.com",
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ZERO,
                Duration.ofMinutes(5));

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new GitLabDeletionSweepService(
                issueRepository,
                repositoryRepository,
                graphQlClientProvider,
                responseHandler,
                properties,
                actorSelector,
                workspaceLinkService,
                new TransactionTemplate(transactionManager));
        // Default: the scope is connected to the repository's instance and may still write it.
        lenient().when(actorSelector.connectedProviderId(SCOPE_ID)).thenReturn(Optional.of(PROVIDER_ID));
        lenient()
                .when(workspaceLinkService.mayWriteRepository(eq(SCOPE_ID), any()))
                .thenReturn(true);

        lenient().when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(client);
        lenient().when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);
        lenient().when(client.documentName(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
        lenient()
                .when(requestSpec.execute())
                .thenAnswer(invocation -> scriptedResponses.isEmpty() ? Mono.empty() : scriptedResponses.poll());
        // Default: every valid response is CONTINUE. Individual tests override for the invalid ones.
        lenient()
                .when(responseHandler.handle(any(), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.CONTINUE, null));
    }

    private static Repository repository() {
        Repository repository = new Repository();
        repository.setId(REPO_ID);
        repository.setNameWithOwner(FULL_PATH);
        return repository;
    }

    private ClientGraphQlResponse issuePage(List<Integer> iids, boolean hasNextPage, int count) {
        return page("project.issues", iids, new GitLabPageInfo(hasNextPage, hasNextPage ? "cursor" : null), count);
    }

    private ClientGraphQlResponse mergeRequestPage(List<Integer> iids, boolean hasNextPage, int count) {
        return page(
                "project.mergeRequests", iids, new GitLabPageInfo(hasNextPage, hasNextPage ? "cursor" : null), count);
    }

    /** A valid response whose connection carries the given IIDs, count and pageInfo, at {@code prefix}. */
    private ClientGraphQlResponse page(String prefix, List<Integer> iids, GitLabPageInfo pageInfo, int count) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        lenient().when(response.isValid()).thenReturn(true);

        ClientResponseField countField = mock(ClientResponseField.class);
        lenient().when(countField.getValue()).thenReturn(count);
        lenient().when(response.field(prefix + ".count")).thenReturn(countField);

        ClientResponseField nodesField = mock(ClientResponseField.class);
        List<Map<String, Object>> nodes = iids.stream()
                .map(iid -> Map.<String, Object>of("iid", String.valueOf(iid)))
                .toList();
        String document = LISTING_DOCUMENTS.get(prefix);
        assertNotNull(document);
        assertVendorCouldReturn(GITLAB, document, prefix + ".nodes", nodes);
        // doReturn to sidestep the generic signature of toEntityList(Class<T>).
        lenient().doReturn(nodes).when(nodesField).toEntityList(Map.class);
        lenient().when(response.field(prefix + ".nodes")).thenReturn(nodesField);

        ClientResponseField pageInfoField = mock(ClientResponseField.class);
        lenient().when(pageInfoField.toEntity(GitLabPageInfo.class)).thenReturn(pageInfo);
        lenient().when(response.field(prefix + ".pageInfo")).thenReturn(pageInfoField);

        return response;
    }

    /**
     * Stubs a project-level feature flag onto an already-built page mock.
     *
     * <p>Left unstubbed by {@link #page}, the flag reads as {@code null} — "unknown" — which is what an
     * older GitLab or a restricted field looks like and which the sweep deliberately does not treat as
     * evidence. Tests that care about the flag say so explicitly.
     */
    private static ClientGraphQlResponse withFlag(ClientGraphQlResponse response, String path, Boolean value) {
        ClientResponseField field = mock(ClientResponseField.class);
        lenient().when(field.getValue()).thenReturn(value);
        lenient().when(response.field(path)).thenReturn(field);
        return response;
    }

    private ClientGraphQlResponse issuePage(List<Integer> iids, boolean hasNextPage, int count, Boolean issuesEnabled) {
        return withFlag(issuePage(iids, hasNextPage, count), "project.issuesEnabled", issuesEnabled);
    }

    private ClientGraphQlResponse mergeRequestPage(
            List<Integer> iids, boolean hasNextPage, int count, Boolean mergeRequestsEnabled) {
        return withFlag(
                mergeRequestPage(iids, hasNextPage, count), "project.mergeRequestsEnabled", mergeRequestsEnabled);
    }

    /** Both entity classes report an empty, provably-complete upstream — the inert default. */
    private void scriptEmptyUpstream() {
        scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0)));
        scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));
    }

    private void stubLocalNumbers(List<Integer> issues, List<Integer> mergeRequests) {
        lenient()
                .when(issueRepository.findLiveIssueNumbersByRepositoryId(REPO_ID))
                .thenReturn(issues);
        lenient()
                .when(issueRepository.findLivePullRequestNumbersByRepositoryId(REPO_ID))
                .thenReturn(mergeRequests);
    }

    /**
     * Asserts the sweep wrote no tombstone at all. The write is type-discriminated — issues and merge
     * requests are tombstoned through separate repository methods, since GitLab keeps their IIDs in
     * separate namespaces — so "deleted nothing" means neither was called.
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
            // Upstream has #1 and #3; the mirror also thinks it has #2, which was deleted upstream and —
            // GitLab having no issue-deletion webhook — would otherwise live in the mirror forever.
            scriptedResponses.add(Mono.just(issuePage(List.of(1, 3), false, 2)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));
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
        void shouldTombstoneMergeRequestPresentLocallyButAbsentUpstream() {
            // GitLab emits no merge-request-deletion event at all, so this sweep is the only thing that
            // will ever notice a vanished MR.
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(10), false, 1)));
            stubLocalNumbers(List.of(), List.of(10, 11));
            when(issueRepository.tombstonePullRequestsByRepositoryIdAndNumbers(anyLong(), anyCollection(), any()))
                    .thenReturn(1);

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            ArgumentCaptor<Collection<Integer>> captor = ArgumentCaptor.captor();
            verify(issueRepository)
                    .tombstonePullRequestsByRepositoryIdAndNumbers(eq(REPO_ID), captor.capture(), any(Instant.class));
            assertThat(captor.getValue()).containsExactly(11);
            assertThat(outcome.mergeRequestsTombstoned()).isEqualTo(1);
        }

        @Test
        void shouldTombstoneNothingWhenMirrorAgreesWithUpstream() {
            scriptedResponses.add(Mono.just(issuePage(List.of(1, 2), false, 2)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));
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
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));
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
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.issuesTombstoned()).isZero();
            assertThat(outcome.skipped()).isFalse();
        }

        @Test
        void shouldStillTombstoneItemsThatPredatedTheListing() {
            // The guard must not become a blanket amnesty: #2 was already in the mirror before the listing
            // started and upstream does not have it, so it is a genuine deletion.
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
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));
            when(issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(anyLong(), anyCollection(), any()))
                    .thenReturn(1);

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            ArgumentCaptor<Collection<Integer>> captor = ArgumentCaptor.captor();
            verify(issueRepository)
                    .tombstoneIssuesByRepositoryIdAndNumbers(eq(REPO_ID), captor.capture(), any(Instant.class));
            assertThat(captor.getValue()).containsExactly(2);
            assertThat(outcome.issuesTombstoned()).isEqualTo(1);
        }
    }

    /**
     * The guarantee: an upstream listing that is short for ANY reason authorizes NO deletion. Each test
     * below is one way the listing can come up short, and every one must end in zero deletes.
     */
    @Nested
    class FailsClosed {

        @Test
        void shouldDeleteNothingWhenPaginationAbortsMidListing() {
            // Page one arrives and says there is more; page two is a transport failure. The accumulator
            // holds a real-looking {1,2} — acting on it would tombstone every issue from #3 up.
            scriptedResponses.add(Mono.just(issuePage(List.of(1, 2), true, 4000)));
            scriptedResponses.add(Mono.error(new IllegalStateException("connection reset")));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3, 4), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.issuesTombstoned()).isZero();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenUpstreamCountDisagreesWithWhatWasReceived() {
            // The single most dangerous shape: pagination claims it finished (hasNextPage=false) but handed
            // over fewer IIDs than the connection's own count. Indistinguishable from a small project
            // except for this cross-check.
            scriptedResponses.add(Mono.just(issuePage(List.of(1), false, 900)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenTheResponseHandlerAborts() {
            // A GraphQL error / auth failure / complexity rejection — the handler says ABORT.
            ClientGraphQlResponse invalid = mock(ClientGraphQlResponse.class);
            lenient().when(invalid.isValid()).thenReturn(false);
            when(responseHandler.handle(eq(invalid), anyString(), any()))
                    .thenReturn(new HandleResult(HandleResult.Action.ABORT, null));
            scriptedResponses.add(Mono.just(invalid));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenUpstreamClaimsMorePagesButHandsBackNoCursor() {
            // hasNextPage=true with a null endCursor: there is more and we have no way to ask for it. The
            // page itself is perfectly valid, so only the cursor check catches this.
            scriptedResponses.add(Mono.just(page("project.issues", List.of(1), new GitLabPageInfo(true, null), 50)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenAnIidCannotBeParsed() {
            // A node whose iid does not parse would be silently absent from the upstream set and could
            // make a live local row look deleted. The whole listing is refused.
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            lenient().when(response.isValid()).thenReturn(true);
            ClientResponseField countField = mock(ClientResponseField.class);
            lenient().when(countField.getValue()).thenReturn(1);
            lenient().when(response.field("project.issues.count")).thenReturn(countField);
            ClientResponseField nodesField = mock(ClientResponseField.class);
            lenient()
                    .doReturn(List.of(Map.of("iid", "not-a-number")))
                    .when(nodesField)
                    .toEntityList(Map.class);
            lenient().when(response.field("project.issues.nodes")).thenReturn(nodesField);
            ClientResponseField pageInfoField = mock(ClientResponseField.class);
            lenient().when(pageInfoField.toEntity(GitLabPageInfo.class)).thenReturn(new GitLabPageInfo(false, null));
            lenient().when(response.field("project.issues.pageInfo")).thenReturn(pageInfoField);

            scriptedResponses.add(Mono.just(response));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldStillSweepMergeRequestsWhenOnlyTheIssueListingIsIncomplete() {
            // The two connections fail independently. One being unverifiable must not indefinitely
            // suppress the other, or a single flaky listing freezes drift repair for the project.
            scriptedResponses.add(Mono.just(issuePage(List.of(1), false, 900))); // count mismatch → skip
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(10), false, 1)));
            stubLocalNumbers(List.of(1, 2), List.of(10, 11));
            when(issueRepository.tombstonePullRequestsByRepositoryIdAndNumbers(anyLong(), anyCollection(), any()))
                    .thenReturn(1);

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            assertThat(outcome.issuesTombstoned()).isZero();
            assertThat(outcome.mergeRequestsTombstoned()).isEqualTo(1);
            assertThat(outcome.skipped()).isTrue();
            verify(issueRepository, never()).tombstoneIssuesByRepositoryIdAndNumbers(anyLong(), anyCollection(), any());
            verify(issueRepository, times(1))
                    .tombstonePullRequestsByRepositoryIdAndNumbers(anyLong(), anyCollection(), any());
        }

        @Test
        void shouldDeleteNothingWhenTheProjectPathIsBlank() {
            Repository blank = new Repository();
            blank.setId(REPO_ID);
            blank.setNameWithOwner("");

            var outcome = service.sweepRepository(SCOPE_ID, blank, handle);

            verifyNothingTombstoned();
            assertThat(outcome.total()).isZero();
        }
    }

    /**
     * The blind spot the completeness proof cannot cover. Every check in {@link FailsClosed} is a check
     * about pagination, and pagination is trivially complete for a listing that returned nothing:
     * {@code count: 0} reconciles with zero received IIDs perfectly. These tests pin the two guards that
     * stop that degenerate agreement from authorizing the deletion of an entire project.
     */
    @Nested
    class RefusesUnprovableEmptiness {

        @Test
        void shouldRefuseTheIssueListingWhenIssuesAreDisabledUpstream() {
            // The isolating case for the flag guard: the mirror is empty, so the emptiness guard cannot
            // fire and the ONLY thing that can mark this degraded is reading issuesEnabled. Without the
            // flag, GitLab's finder scopes the project out and this reads as a clean, complete no-op.
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0, false)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0, true)));
            stubLocalNumbers(List.of(), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldTombstoneNothingWhenIssuesAreDisabledUpstreamButTheMirrorHasLiveIssues() {
            // The consequential case: switching Issues off hides the project's issues from the finder. It
            // does not delete them, and switching the feature back on returns every one.
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0, false)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0, true)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.issuesTombstoned()).isZero();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldRefuseTheMergeRequestListingWhenMergeRequestsAreDisabledUpstream() {
            // The merge-request half has its own project-level toggle and its own finder scope. The mirror
            // is empty so the emptiness guard cannot fire — reading mergeRequestsEnabled is the only thing
            // that can mark this degraded.
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0, true)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0, false)));
            stubLocalNumbers(List.of(), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.mergeRequestsTombstoned()).isZero();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenUpstreamIssuesAreEmptyButTheMirrorStillHasLiveOnes() {
            // Both flags say the features are ON, so the flag guard is deliberately inert and only the
            // emptiness guard can save these three issues. "Every issue in the project was deleted since
            // the last sync" is a far worse bet than "this listing is wrong".
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0, true)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0, true)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.issuesTombstoned()).isZero();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldDeleteNothingWhenUpstreamMergeRequestsAreEmptyButTheMirrorStillHasLiveOnes() {
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0, true)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0, true)));
            stubLocalNumbers(List.of(), List.of(10, 11));

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.mergeRequestsTombstoned()).isZero();
            assertThat(outcome.skipped()).isTrue();
        }

        @Test
        void shouldStayACleanNoOpWhenUpstreamAndMirrorAreBothEmpty() {
            // The guard must not turn every genuinely empty project into a permanent warning: with nothing
            // local at risk there is nothing to protect, so this stays a non-degraded no-op.
            scriptedResponses.add(Mono.just(issuePage(List.of(), false, 0, true)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0, true)));
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
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0, true)));
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
            // set {1,2} — the dangerous case that a bare cancel-before test never exercises. The
            // cancellation flag is polled at: sweepRepository entry (false), the entity loop for ISSUE
            // (false), the listing's while-loop before page one (false → page one IS fetched), then the
            // while-loop before page two (true → abort with a populated accumulator). Nothing may be
            // tombstoned.
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
        void shouldStopSweepingRemainingProjectsWhenCancelled() {
            Repository first = repository();
            Repository second = new Repository();
            second.setId(8L);
            second.setNameWithOwner("acme/gadgets");
            when(repositoryRepository.findAllByWorkspaceMonitorsOnProvider(SCOPE_ID, PROVIDER_ID))
                    .thenReturn(List.of(first, second));
            when(handle.isCancellationRequested()).thenReturn(true);

            var outcome = service.sweepScope(SCOPE_ID, handle);

            assertThat(outcome.total()).isZero();
            verifyNothingTombstoned();
        }
    }

    @Nested
    class WritesOnlyWithAuthority {

        @Test
        void shouldTombstoneNothingWhenTheRepositoryStoppedBeingThisScopesDuringTheListing() {
            // A complete listing that proves #2 gone, but by the time it ends the monitor, the connection or
            // the group link it was started under has changed.
            scriptedResponses.add(Mono.just(issuePage(List.of(1, 3), false, 2)));
            scriptedResponses.add(Mono.just(mergeRequestPage(List.of(), false, 0)));
            stubLocalNumbers(List.of(1, 2, 3), List.of());
            when(workspaceLinkService.mayWriteRepository(eq(SCOPE_ID), any())).thenReturn(false);

            var outcome = service.sweepRepository(SCOPE_ID, repository(), handle);

            verifyNothingTombstoned();
            assertThat(outcome.skipped()).isTrue();
            InOrder listingThenCheck = inOrder(requestSpec, workspaceLinkService);
            listingThenCheck.verify(requestSpec).execute();
            listingThenCheck.verify(workspaceLinkService).mayWriteRepository(eq(SCOPE_ID), any());
        }

        @Test
        void shouldSweepNothingWithoutAnActiveConnection() {
            when(actorSelector.connectedProviderId(SCOPE_ID)).thenReturn(Optional.empty());

            var outcome = service.sweepScope(SCOPE_ID, handle);

            assertThat(outcome.total()).isZero();
            verifyNoInteractions(repositoryRepository, graphQlClientProvider);
        }
    }

    @Nested
    class SweepScope {

        @Test
        void shouldReportSweepPhaseProgressPerProject() {
            when(repositoryRepository.findAllByWorkspaceMonitorsOnProvider(SCOPE_ID, PROVIDER_ID))
                    .thenReturn(List.of(repository()));
            scriptEmptyUpstream();
            stubLocalNumbers(List.of(), List.of());

            service.sweepScope(SCOPE_ID, handle);

            verify(handle, atLeastOnce())
                    .progress(any(), any(), ArgumentMatchers.argThat(progress -> progress.phase() == SyncPhase.SWEEP));
        }

        @Test
        void shouldRunWithoutAHandleOutsideARecordedJob() {
            when(repositoryRepository.findAllByWorkspaceMonitorsOnProvider(SCOPE_ID, PROVIDER_ID))
                    .thenReturn(List.of(repository()));
            scriptEmptyUpstream();
            stubLocalNumbers(List.of(), List.of());

            var outcome = service.sweepScope(SCOPE_ID, null);

            assertThat(outcome.total()).isZero();
        }

        @Test
        void shouldSweepNothingWhenScopeMonitorsNoProjects() {
            when(repositoryRepository.findAllByWorkspaceMonitorsOnProvider(SCOPE_ID, PROVIDER_ID))
                    .thenReturn(List.of());

            var outcome = service.sweepScope(SCOPE_ID, handle);

            assertThat(outcome.total()).isZero();
            assertThat(outcome.skipped()).isFalse();
        }
    }
}

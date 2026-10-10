package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ReviewSourceNotReadyException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmReviewRangeSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewRepositoryPreparerTest extends BaseUnitTest {
    @Mock
    GitRepositoryManager git;

    @Mock
    PullRequestRepository pullRequests;

    @Mock
    RepositoryToMonitorRepository monitors;

    @Mock
    ConnectionService connections;

    @Mock
    ScmTokenSource tokens;

    @Mock
    ScmReviewRangeSource ranges;

    @Mock
    PlatformTransactionManager transactions;

    private ReviewRepositoryPreparer preparer;
    private AgentJob job;
    private Repository repository;
    private PullRequest pullRequest;
    private static final RepositoryKey KEY = new RepositoryKey(1, 2);
    private static final String HEAD = "a".repeat(40);

    @BeforeEach
    void setUp() {
        // The target is its own review base unless a test pins a merge base explicitly.
        lenient()
                .when(git.reviewBase(any(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        preparer = new ReviewRepositoryPreparer(
                git,
                pullRequests,
                monitors,
                connections,
                List.of(tokens),
                mock(RepositoryRepository.class),
                List.of(ranges),
                transactions);
        var workspace = new Workspace();
        workspace.setId(1L);
        job = new AgentJob();
        job.setWorkspace(workspace);
        job.setMetadata(JsonMapper.builder()
                .build()
                .createObjectNode()
                .put("pull_request_id", 3)
                .put("repository_id", 2)
                .put("commit_sha", HEAD)
                .put("base_ref_oid", "b".repeat(40))
                .put("repository_full_name", "untrusted/wrong")
                .put("pr_number", 999));
        repository = new Repository();
        repository.setId(2L);
        repository.setNameWithOwner("owner/repo");
        var provider = new IdentityProvider();
        provider.setType(IdentityProviderType.GITLAB);
        provider.setServerUrl("https://scm.example");
        repository.setProvider(provider);
        pullRequest = new PullRequest();
        pullRequest.setId(3L);
        pullRequest.setNativeId(30L);
        pullRequest.setUpdatedAt(Instant.parse("2026-10-01T12:00:00.123Z"));
        repository.setNativeId(20L);
        pullRequest.setRepository(repository);
        pullRequest.setNumber(42);
        pullRequest.setHeadRefOid(HEAD);
        when(pullRequests.findByIdWithAuthorAndRepository(3L)).thenReturn(Optional.of(pullRequest));
    }

    private void pinNoBase(String targetBranch) {
        var metadata = (ObjectNode) Objects.requireNonNull(job.getMetadata());
        metadata.remove("base_ref_oid");
        metadata.put("target_branch", targetBranch);
    }

    private void authorize() {
        when(monitors.existsByWorkspaceIdAndNameWithOwner(1L, "owner/repo")).thenReturn(true);
        when(connections.findActiveProviderKind(1)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(tokens.kind()).thenReturn(IntegrationKind.GITLAB);
        when(tokens.serverUrl(1)).thenReturn(Optional.of("https://scm.example"));
    }

    @Test
    void shouldFetchSqlRepositoryAndReviewRefWhenMetadataNamesAnotherRepository() {
        authorize();
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(tokens.reviewHeadRef(42)).thenReturn(Optional.of("refs/merge-requests/42/head"));
        // The branch fetch did not carry the head; the review ref does.
        when(git.commitExists(KEY, HEAD)).thenReturn(false, true);
        when(git.commitExists(KEY, "b".repeat(40))).thenReturn(true);
        assertThat(preparer.prepare(job))
                .isEqualTo(new ReviewRepositoryPreparer.PreparedReview(KEY, HEAD, "b".repeat(40)));
        verify(git).ensureRepository(KEY, "https://scm.example/owner/repo.git", "private-token");
        verify(git)
                .fetchRemoteCommit(
                        KEY,
                        "https://scm.example/owner/repo.git",
                        "refs/merge-requests/42/head",
                        HEAD,
                        "private-token");
    }

    @Test
    void shouldNotFetchTheReviewRefWhenTheBranchFetchCarriedTheHead() {
        authorize();
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(true);
        when(git.commitExists(KEY, "b".repeat(40))).thenReturn(true);
        assertThat(preparer.prepare(job))
                .isEqualTo(new ReviewRepositoryPreparer.PreparedReview(KEY, HEAD, "b".repeat(40)));
        verify(git, never()).fetchRemoteCommit(any(), any(), any(), any(), any());
    }

    @Test
    void shouldRejectUnmonitoredRepositoryBeforeResolvingCredentials() {
        assertThatThrownBy(() -> preparer.prepare(job)).isInstanceOf(JobPreparationException.class);
        verifyNoInteractions(git, connections, tokens);
    }

    @Test
    void shouldRejectDifferentProviderBeforeResolvingCredentials() {
        authorize();
        repository.getProvider().setServerUrl("https://other.example");
        assertThatThrownBy(() -> preparer.prepare(job)).isInstanceOf(JobPreparationException.class);
        verifyNoInteractions(git);
    }

    @Test
    void shouldFailWhenPinnedHeadIsNotAvailableAfterFetch() {
        authorize();
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(tokens.reviewHeadRef(42)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> preparer.prepare(job))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining("Pinned review commit");
    }

    @Test
    void shouldResolveTheTargetBranchOnceWhenTheProviderPinnedNoBase() {
        pinNoBase("main");
        authorize();
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(true);
        when(git.resolveBranchHead(KEY, "main")).thenReturn("c".repeat(40));
        when(git.commitExists(KEY, "c".repeat(40))).thenReturn(true);
        assertThat(preparer.prepare(job))
                .isEqualTo(new ReviewRepositoryPreparer.PreparedReview(KEY, HEAD, "c".repeat(40)));
    }

    @Test
    void shouldFailWhenNeitherPinnedBaseNorTargetBranchIsAvailable() {
        pinNoBase("gone");
        authorize();
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(true);
        assertThatThrownBy(() -> preparer.prepare(job))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining("base commit");
    }

    @Test
    void shouldAuthorizeCapturedDatabaseContentWithoutGitOrCredentials() {
        authorize();
        assertThat(preparer.authorize(job)).isEqualTo(KEY);
        verifyNoInteractions(git);
        verify(tokens, never()).accessToken(1);
    }

    @Test
    void shouldRejectMetadataRepositoryMismatchBeforeProviderAccess() {
        repository.setId(999L);
        assertThatThrownBy(() -> preparer.authorize(job)).isInstanceOf(JobPreparationException.class);
        verifyNoInteractions(git, connections, tokens);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldUseTheRecordedGitlabDiffBaseIncludingAnEmptyRange(boolean empty) {
        authorize();
        String base = empty ? HEAD : "c".repeat(40);
        pullRequest.setBaseRefOid(base);
        when(tokens.recordsReviewDiffBase()).thenReturn(true);
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(true);
        when(git.commitExists(KEY, base)).thenReturn(true);
        assertThat(preparer.prepare(job).target()).isEqualTo(base);
        verifyNoInteractions(ranges, transactions);
        verify(git, never()).reviewBase(any(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRefuseAStaleRecordedRevisionOrMissingBase(boolean stale) {
        authorize();
        pullRequest.setBaseRefOid("c".repeat(40));
        pullRequest.setHeadRefOid(stale ? "d".repeat(40) : HEAD);
        when(tokens.recordsReviewDiffBase()).thenReturn(true);
        if (!stale) {
            when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
            when(git.commitExists(KEY, HEAD)).thenReturn(true);
        }
        assertThatThrownBy(() -> preparer.prepare(job))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining(stale ? "does not match" : "base commit is unavailable");
        verify(git, never()).reviewBase(any(), anyString(), anyString());
        if (stale) verifyNoInteractions(git);
    }

    private void authorizeHydration() {
        authorize();
        when(tokens.recordsReviewDiffBase()).thenReturn(true);
        when(ranges.kind()).thenReturn(IntegrationKind.GITLAB);
        var connection = mock(Connection.class);
        when(connection.getId()).thenReturn(10L);
        when(connections.findActive(1L, IntegrationKind.GITLAB)).thenReturn(Optional.of(connection));
        lenient().when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient()
                .when(pullRequests.findForUpdateByRepositoryIdAndNumber(2L, 42))
                .thenReturn(Optional.of(pullRequest));
    }

    @Test
    void shouldNotSubstituteAQueuedBaseWhenTheProviderDiffBaseIsMissing() {
        authorizeHydration();
        when(ranges.read(1L, "owner/repo", 42)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> preparer.prepare(job))
                .isInstanceOf(ReviewSourceNotReadyException.class)
                .hasMessageContaining("not prepared");

        verify(tokens, never()).accessToken(1);
        verifyNoInteractions(git);
    }

    @Test
    void shouldPrepareTheOriginalJobWhenGitLabProvidesItsPairOnALaterAttempt() {
        authorizeHydration();
        var metadata = Objects.requireNonNull(job.getMetadata()).deepCopy();
        var pair = new ScmReviewRangeSource.ReviewRange(20L, 30L, HEAD, "c".repeat(40));
        when(ranges.read(1L, "owner/repo", 42))
                .thenAnswer(invocation -> {
                    return Optional.empty();
                })
                .thenReturn(Optional.of(pair));
        assertThatThrownBy(() -> preparer.prepare(job)).isInstanceOf(ReviewSourceNotReadyException.class);
        verifyNoInteractions(git);
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(true);
        when(git.commitExists(KEY, pair.base())).thenReturn(true);

        assertThat(preparer.prepare(job))
                .isEqualTo(new ReviewRepositoryPreparer.PreparedReview(KEY, HEAD, pair.base()));
        assertThat(pullRequest.getBaseRefOid()).isEqualTo(pair.base());
        assertThat(job.getMetadata()).isEqualTo(metadata);
        verify(git, never()).reviewBase(any(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"head", "version", "connection", "repository"})
    void shouldRefuseHydrationWhenTheCapturedIdentityChangesDuringTheRead(String changed) {
        authorizeHydration();
        when(ranges.read(1L, "owner/repo", 42)).thenAnswer(invocation -> {
            switch (changed) {
                case "head" -> pullRequest.setHeadRefOid("d".repeat(40));
                case "version" -> pullRequest.setUpdatedAt(Instant.parse("2026-10-01T12:00:01Z"));
                case "repository" -> repository.setNameWithOwner("other/repo");
                case "connection" -> {
                    var connection = mock(Connection.class);
                    when(connection.getId()).thenReturn(11L);
                    when(connections.findActive(1L, IntegrationKind.GITLAB)).thenReturn(Optional.of(connection));
                }
                default -> throw new IllegalArgumentException(changed);
            }
            return Optional.of(new ScmReviewRangeSource.ReviewRange(20L, 30L, HEAD, "c".repeat(40)));
        });
        assertThatThrownBy(() -> preparer.prepare(job)).isInstanceOf(JobPreparationException.class);
        assertThat(pullRequest.getBaseRefOid()).isNull();
        verifyNoInteractions(git);
        verify(pullRequests, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRejectAProviderPairForAnotherProjectOrMergeRequest(boolean project) {
        authorizeHydration();
        when(ranges.read(1L, "owner/repo", 42))
                .thenReturn(Optional.of(new ScmReviewRangeSource.ReviewRange(
                        project ? 21L : 20L, project ? 30L : 31L, HEAD, "c".repeat(40))));
        assertThatThrownBy(() -> preparer.prepare(job)).isInstanceOf(JobPreparationException.class);
        assertThat(pullRequest.getBaseRefOid()).isNull();
        verifyNoInteractions(git);
    }

    /** Admits the job with its base retained with its head, as admission now records it, for the given occasion. */
    private void admitRetained(@Nullable String occasion) {
        var metadata = (ObjectNode) Objects.requireNonNull(job.getMetadata());
        metadata.put(PullRequestReviewHandler.RETAINED_RANGE_METADATA_KEY, true);
        if (occasion != null) metadata.put("signal", occasion);
    }

    /** A later push moved the merge request; the mirror now holds the newer head and its own base. */
    private void moveTheWork() {
        pullRequest.setHeadRefOid("d".repeat(40));
        pullRequest.setBaseRefOid("e".repeat(40));
    }

    private static @Nullable String occasion(String name) {
        return switch (name) {
            case "opened" -> ScmSignals.PULL_REQUEST_OPENED.value();
            case "ready" -> ScmSignals.PULL_REQUEST_READY.value();
            case "reviewed" -> ScmSignals.PULL_REQUEST_REVIEWED.value();
            case "merged" -> ScmSignals.PULL_REQUEST_MERGED.value();
            case "manual" -> null;
            case "synchronized" -> ScmSignals.PULL_REQUEST_SYNCHRONIZED.value();
            case "edited" -> ScmSignals.PULL_REQUEST_EDITED.value();
            case "linked" -> ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED.value();
            default -> throw new IllegalArgumentException(name);
        };
    }

    // Every occasion, a replaceable push or edit included: whether a newer review replaces a queued one is the push
    // coalescer's decision, and a job that reaches capture is still owed its own range.
    @ParameterizedTest
    @ValueSource(strings = {"opened", "ready", "reviewed", "merged", "manual", "synchronized", "edited", "linked"})
    void shouldPrepareADelayedOccasionAtTheRangeItWasAdmittedWith(String name) {
        authorize();
        admitRetained(occasion(name));
        moveTheWork();
        when(tokens.recordsReviewDiffBase()).thenReturn(true);
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(true);
        when(git.commitExists(KEY, "b".repeat(40))).thenReturn(true);

        assertThat(preparer.prepare(job))
                .isEqualTo(new ReviewRepositoryPreparer.PreparedReview(KEY, HEAD, "b".repeat(40)));

        // Neither the newer head's base nor the provider's current range stands in for the admitted pair.
        verifyNoInteractions(ranges, transactions);
        verify(git, never()).reviewBase(any(), anyString(), anyString());
        verify(pullRequests, never()).save(any());
        assertThat(pullRequest.getBaseRefOid()).isEqualTo("e".repeat(40));
    }

    @Test
    void shouldKeepTheAdmittedBaseWhenTheSameHeadLaterHasAnotherBase() {
        authorize();
        admitRetained(ScmSignals.PULL_REQUEST_READY.value());
        // The target moved after admission: the provider recorded another base for the same head.
        pullRequest.setBaseRefOid("e".repeat(40));
        when(tokens.recordsReviewDiffBase()).thenReturn(true);
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(true);
        when(git.commitExists(KEY, "b".repeat(40))).thenReturn(true);

        assertThat(preparer.prepare(job).target()).isEqualTo("b".repeat(40));

        verifyNoInteractions(ranges, transactions);
        verify(pullRequests, never()).save(any());
    }

    @Test
    void shouldNotBorrowTheCurrentBaseWhenTheAdmittedBaseCommitIsUnavailable() {
        authorize();
        admitRetained(ScmSignals.PULL_REQUEST_READY.value());
        pullRequest.setBaseRefOid("e".repeat(40));
        when(tokens.recordsReviewDiffBase()).thenReturn(true);
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(true);
        when(git.commitExists(KEY, "b".repeat(40))).thenReturn(false);

        assertThatThrownBy(() -> preparer.prepare(job))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining("Review base commit is unavailable");

        verify(git, never()).commitExists(KEY, "e".repeat(40));
        verify(git, never()).resolveBranchHead(any(), anyString());
        verifyNoInteractions(ranges, transactions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "false", "string", "no-base", "numeric-base", "blank-base"})
    void shouldRefuseAMovedHeadWithoutItsRetainedRange(String admission) {
        authorize();
        var metadata = (ObjectNode) Objects.requireNonNull(job.getMetadata());
        switch (admission) {
            // Admitted before original retention was recorded: its base is not covered by that contract.
            case "legacy" -> {}
            case "false" -> metadata.put(PullRequestReviewHandler.RETAINED_RANGE_METADATA_KEY, false);
            case "string" -> metadata.put(PullRequestReviewHandler.RETAINED_RANGE_METADATA_KEY, "true");
            case "no-base" -> {
                metadata.remove("base_ref_oid");
                admitRetained(null);
            }
            case "numeric-base" -> {
                metadata.put("base_ref_oid", 12345);
                admitRetained(null);
            }
            case "blank-base" -> {
                metadata.put("base_ref_oid", "   ");
                admitRetained(null);
            }
            default -> throw new IllegalArgumentException(admission);
        }
        moveTheWork();
        when(tokens.recordsReviewDiffBase()).thenReturn(true);

        assertThatThrownBy(() -> preparer.prepare(job))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining("does not match the queued head");

        verifyNoInteractions(git, ranges, transactions);
        verify(tokens, never()).accessToken(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRefuseADelayedOccasionWhoseAdmittedCommitsAreUnavailable(boolean headMissing) {
        authorize();
        admitRetained(ScmSignals.PULL_REQUEST_READY.value());
        moveTheWork();
        when(tokens.recordsReviewDiffBase()).thenReturn(true);
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(!headMissing);
        if (headMissing) {
            when(tokens.reviewHeadRef(42)).thenReturn(Optional.of("refs/merge-requests/42/head"));
        } else {
            when(git.commitExists(KEY, "b".repeat(40))).thenReturn(false);
        }

        assertThatThrownBy(() -> preparer.prepare(job))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining(headMissing ? "Pinned review commit" : "Review base commit is unavailable");

        verifyNoInteractions(ranges, transactions);
        verify(git, never()).reviewBase(any(), anyString(), anyString());
        verify(git, never()).resolveBranchHead(any(), anyString());
    }

    @Test
    void shouldRefuseADelayedOccasionOnAnUnmonitoredRepositoryBeforeAnyProviderAccess() {
        admitRetained(ScmSignals.PULL_REQUEST_MERGED.value());
        moveTheWork();
        when(monitors.existsByWorkspaceIdAndNameWithOwner(1L, "owner/repo")).thenReturn(false);

        assertThatThrownBy(() -> preparer.prepare(job)).isInstanceOf(JobPreparationException.class);

        verifyNoInteractions(git, connections, tokens, ranges);
    }

    @Test
    void shouldKeepTheMergeBaseForAProviderThatRecordsTheTargetTipWhenTheWorkMoved() {
        authorize();
        admitRetained(ScmSignals.PULL_REQUEST_READY.value());
        moveTheWork();
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(true);
        when(git.commitExists(KEY, "b".repeat(40))).thenReturn(true);
        when(git.reviewBase(KEY, "b".repeat(40), HEAD)).thenReturn("c".repeat(40));

        assertThat(preparer.prepare(job).target()).isEqualTo("c".repeat(40));

        verifyNoInteractions(ranges, transactions);
    }

    @Test
    void shouldResolveTheMergeBaseForAProviderThatRecordsTheTargetTip() {
        authorize();
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(true);
        when(git.commitExists(KEY, "b".repeat(40))).thenReturn(true);
        when(git.reviewBase(KEY, "b".repeat(40), HEAD)).thenReturn("c".repeat(40));
        assertThat(preparer.prepare(job).target()).isEqualTo("c".repeat(40));
    }

    @Test
    void shouldFailWhenTheTargetAndHeadShareNoReviewBase() {
        authorize();
        when(tokens.accessToken(1)).thenReturn(Optional.of("private-token"));
        when(git.commitExists(KEY, HEAD)).thenReturn(true);
        when(git.commitExists(KEY, "b".repeat(40))).thenReturn(true);
        when(git.reviewBase(KEY, "b".repeat(40), HEAD)).thenReturn(null);
        assertThatThrownBy(() -> preparer.prepare(job))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining("The pinned review diff range is unavailable");
    }
}

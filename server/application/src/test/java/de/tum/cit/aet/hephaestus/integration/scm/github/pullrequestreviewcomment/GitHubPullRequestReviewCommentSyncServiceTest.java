package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubReviewThreadDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class GitHubPullRequestReviewCommentSyncServiceTest extends BaseUnitTest {

    private static final long ROOT_COMMENT_ID = 2494208170L;
    private static final long PROVIDER_ID = 7L;

    private final PullRequestReviewThreadRepository threads = mock(PullRequestReviewThreadRepository.class);
    private final GitHubPullRequestReviewCommentSyncService service = new GitHubPullRequestReviewCommentSyncService(
            threads,
            mock(GitHubGraphQlClientProvider.class),
            mock(GitHubPullRequestReviewCommentProcessor.class),
            mock(GitHubUserProcessor.class),
            mock(GitHubSyncProperties.class),
            mock(GitHubExceptionClassifier.class),
            mock(GitHubGraphQlSyncCoordinator.class),
            mock(TransactionTemplate.class));

    @Test
    void shouldRecordTheNodeIdWhenTheWebhookStoredTheThreadWithoutOne() {
        // The review-comment webhook stores a thread under its root comment's id, with no node id and unresolved.
        var stored = storedThread(null);
        when(threads.save(any())).thenAnswer(call -> call.getArgument(0));

        service.processThread(read("PRRT_kwDOQNWqEc5hJ2Ab", true), pullRequest(), 1L, SyncResult.Status.COMPLETED);

        assertThat(stored.getNodeId()).isEqualTo("PRRT_kwDOQNWqEc5hJ2Ab");
        assertThat(stored.getState()).isEqualTo(PullRequestReviewThread.State.UNRESOLVED);
        verify(threads).save(stored);
    }

    @Test
    void shouldKeepTheStoredNodeIdWhenTheReadNamesAnother() {
        var stored = storedThread("PRRT_stored");

        service.processThread(read("PRRT_other", false), pullRequest(), 1L, SyncResult.Status.COMPLETED);

        assertThat(stored.getNodeId()).isEqualTo("PRRT_stored");
        verify(threads, never()).save(any());
    }

    private PullRequestReviewThread storedThread(@Nullable String nodeId) {
        var thread = new PullRequestReviewThread();
        thread.setNativeId(ROOT_COMMENT_ID);
        if (nodeId != null) thread.setNodeId(nodeId);
        thread.setState(PullRequestReviewThread.State.UNRESOLVED);
        when(threads.findByNativeIdAndProviderId(ROOT_COMMENT_ID, PROVIDER_ID)).thenReturn(Optional.of(thread));
        return thread;
    }

    private static PullRequest pullRequest() {
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        provider.setId(PROVIDER_ID);
        var repository = new Repository();
        repository.setId(3L);
        repository.setProvider(provider);
        var pullRequest = new PullRequest();
        pullRequest.setRepository(repository);
        pullRequest.setNumber(1);
        return pullRequest;
    }

    private static GitHubReviewThreadDTO read(String nodeId, boolean resolved) {
        var comment = new GHPullRequestReviewComment();
        comment.setId("PRRC_root");
        comment.setFullDatabaseId(BigInteger.valueOf(ROOT_COMMENT_ID));
        var comments = new GHPullRequestReviewCommentConnection();
        comments.setNodes(List.of(comment));
        return new GitHubReviewThreadDTO(
                nodeId, "README.md", 1, null, "RIGHT", null, resolved, false, false, null, comments);
    }
}

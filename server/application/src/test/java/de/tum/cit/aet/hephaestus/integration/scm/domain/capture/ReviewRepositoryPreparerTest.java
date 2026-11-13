package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReviewRepositoryPreparerTest extends BaseUnitTest {
    private final GitRepositoryManager git = mock(GitRepositoryManager.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final RepositoryRepository repositories = mock(RepositoryRepository.class);
    private final ConnectionService connections = mock(ConnectionService.class);
    private final ScmTokenSource tokens = mock(ScmTokenSource.class);
    private ScmCaptureContext context;
    private ReviewRepositoryPreparer preparer;

    @BeforeEach
    void setupPinnedWork() {
        context = ScmCaptureFixtures.context(
                new ObjectMapper()
                        .createObjectNode()
                        .put("pull_request_id", 3)
                        .put("repository_id", 2)
                        .put("repository_full_name", "owner/repo")
                        .put("commit_sha", "a".repeat(40))
                        .put("base_ref_oid", "b".repeat(40)),
                1L,
                false);
        var provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://scm.example");
        provider.setId(4L);
        var repository = new Repository();
        repository.setId(2L);
        repository.setProvider(provider);
        repository.setNameWithOwner("owner/repo");
        var pr = new PullRequest();
        pr.setId(3L);
        pr.setRepository(repository);
        when(pullRequests.findByIdWithAuthorAndRepository(3L)).thenReturn(Optional.of(pr));
        when(repositories.isMonitored(1L, 2L)).thenReturn(true);
        when(connections.findActive(1L, IntegrationKind.GITHUB)).thenReturn(Optional.of(mock(Connection.class)));
        when(tokens.kind()).thenReturn(IntegrationKind.GITHUB);
        when(tokens.serverUrl(1L)).thenReturn(Optional.of("https://scm.example"));
        when(tokens.accessToken(1L)).thenReturn(Optional.of("test-token"));
        preparer = new ReviewRepositoryPreparer(git, pullRequests, connections, List.of(tokens), repositories);
    }

    @Test
    void shouldKeepThePinnedBaseAndHead() {
        when(git.commitExists(any(), anyString())).thenReturn(true);
        var prepared = preparer.prepare(context);
        assertThat(prepared.head()).isEqualTo("a".repeat(40));
        assertThat(prepared.target()).isEqualTo("b".repeat(40));
    }

    @Test
    void shouldFetchTheReviewRefOfAHeadThatNoBranchReaches() {
        when(tokens.reviewHeadRef(anyLong())).thenReturn(Optional.of("refs/pull/7/head"));
        var remote = preparer.remote(context.pinned(), context.work());

        preparer.fetch(remote);

        verify(git).ensureRepository(remote.key(), "https://scm.example/owner/repo.git", "test-token");
        verify(git)
                .fetchRemoteCommit(
                        remote.key(),
                        "https://scm.example/owner/repo.git",
                        "refs/pull/7/head",
                        "a".repeat(40),
                        "test-token");
    }

    @Test
    void shouldRefuseWorkOutsideWorkspaceMonitors() {
        when(repositories.isMonitored(1L, 2L)).thenReturn(false);
        assertThatThrownBy(() -> preparer.authorize(context))
                .isInstanceOf(ItemCaptureException.class)
                .hasMessageContaining("does not monitor");
    }

    @Test
    void shouldRefuseUnavailablePinnedBaseWithoutMovingIt() {
        when(git.commitExists(any(), anyString())).thenReturn(true);
        when(git.commitExists(any(), eq("b".repeat(40)))).thenReturn(false);
        assertThatThrownBy(() -> preparer.prepare(context))
                .isInstanceOf(ItemCaptureException.class)
                .hasMessageContaining("Pinned review diff range");
    }
}

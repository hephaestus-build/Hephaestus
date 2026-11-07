package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitTransportFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class ReviewRepositoryPreparerIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private ConnectionRepository connectionRows;

    @Autowired
    private ConnectionService connections;

    @Autowired
    private PullRequestReviewCommentRepository comments;

    @Autowired
    private DeliveredPullRequestCommentLookup delivered;

    @Autowired
    private PersonDataCopyRecorder personCopies;

    private static final String HEAD = "a".repeat(40);
    private PullRequest pullRequest;
    private ScmCaptureContext context;
    private ReviewRepositoryPreparer preparer;
    private ScmTokenSource tokens;
    private MockWebServer upstream;
    private String serverUrl;

    @TempDir
    private Path temporary;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new MockWebServer();
        upstream.start();
        serverUrl = upstream.url("/").toString().replaceAll("/$", "");
        databaseTestUtils.cleanDatabase();
        var provider = providers.save(new IdentityProvider(IdentityProviderType.GITLAB, serverUrl));
        var repository = new Repository();
        repository.setNativeId(200L);
        repository.setProvider(provider);
        repository.setName("repo");
        repository.setNameWithOwner("owner/repo");
        repository.setHtmlUrl(serverUrl + "/owner/repo");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository = repositories.save(repository);
        var pr = new PullRequest();
        pr.setNativeId(300L);
        pr.setProvider(provider);
        pr.setRepository(repository);
        pr.setNumber(42);
        pr.setTitle("Reviewed change");
        pr.setHtmlUrl(serverUrl + "/owner/repo/-/merge_requests/42");
        pr.setState(PullRequest.State.OPEN);
        pr.setCreatedAt(Instant.now());
        pr.setUpdatedAt(Instant.parse("2026-10-01T12:00:00.123Z"));
        pr.setHeadRefOid(HEAD);
        pullRequest = pullRequests.save(pr);
        var workspace = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaces, connectionRows, WorkspaceTestFixtures.gitLabPatWorkspace("owner"), serverUrl);
        var monitor = WorkspaceTestFixtures.repositoryMonitor(workspace, "owner/repo", repository.getNativeId());
        monitors.save(monitor);
        long workspaceId = workspace.getId();
        context = ScmCaptureFixtures.context(
                JsonMapper.builder()
                        .build()
                        .createObjectNode()
                        .put("pull_request_id", pullRequest.getId())
                        .put("repository_id", repository.getId())
                        .put("repository_full_name", "owner/repo")
                        .put("body", "Retained description")
                        .put("commit_sha", HEAD)
                        .put("base_ref_oid", "b".repeat(40)),
                workspaceId,
                false);
        var previous = context.pinned();
        context = new ScmCaptureContext(
                ItemFixtures.snapshot(previous.item(), previous.state())
                        .version(previous.version())
                        .integration(new IntegrationRef(IntegrationKind.GITLAB, workspaceId, null))
                        .roles(previous.roles())
                        .label(previous.label())
                        .build(),
                context.request(),
                context.work(),
                context.preparation(),
                context.capturedAt());
        tokens = mock(ScmTokenSource.class);
        when(tokens.kind()).thenReturn(IntegrationKind.GITLAB);
        when(tokens.serverUrl(workspaceId)).thenReturn(Optional.of(serverUrl));
        when(tokens.recordsReviewDiffBase()).thenReturn(true);
        when(tokens.accessToken(workspaceId)).thenReturn(Optional.of("private-token"));
        var git = mock(GitRepositoryManager.class);
        when(git.commitExists(any(), anyString())).thenReturn(true);
        preparer = new ReviewRepositoryPreparer(git, pullRequests, connections, List.of(tokens), repositories);
    }

    @AfterEach
    void stopUpstream() throws Exception {
        upstream.close();
    }

    @Test
    void shouldExposeTheRealTransportCauseWhenGitLabHasNoGitEndpoint() {
        upstream.enqueue(new MockResponse.Builder().code(404).build());
        var git = new GitRepositoryManager(
                new GitRepositoryProperties(true, 1, 1024 * 1024, 1L << 40),
                new GitRepositoryLockManager(),
                new FabricLayout(temporary.resolve("fabric").toString()),
                GitTransportFixtures.guarded());
        var realPreparer = new ReviewRepositoryPreparer(git, pullRequests, connections, List.of(tokens), repositories);

        assertThatThrownBy(() -> realPreparer.prepare(context))
                .isInstanceOf(GitRepositoryManager.GitOperationException.class)
                .hasCauseInstanceOf(InvalidRemoteException.class)
                .hasRootCauseInstanceOf(NoRemoteRepositoryException.class);
    }

    @Test
    void shouldKeepDescriptionAndDiscussionWhenGitLabCannotSupplyGitObjects() throws Exception {
        upstream.enqueue(new MockResponse.Builder().code(404).build());
        var git = new GitRepositoryManager(
                new GitRepositoryProperties(true, 1, 1024 * 1024, 1L << 40),
                new GitRepositoryLockManager(),
                new FabricLayout(temporary.resolve("fabric").toString()),
                GitTransportFixtures.guarded());
        var realPreparer = new ReviewRepositoryPreparer(git, pullRequests, connections, List.of(tokens), repositories);
        var content =
                new PullRequestContentSource(new JsonMapper(), git, pullRequests, comments, realPreparer, delivered);
        var tree = new RepositoryTreeContentSource(git, realPreparer, personCopies);
        var captured = content.capture(context, content.sourceKinds());
        var repository = tree.capture(context, Set.of(RepositoryTreeContentSource.KIND));
        try {
            assertThat(new String(
                            Objects.requireNonNull(captured.files().get(PullRequestContentSource.DESCRIPTION_FILE)),
                            StandardCharsets.UTF_8))
                    .isEqualTo("Retained description");
            assertThat(captured.files())
                    .containsKey("context/comments.json")
                    .doesNotContainKeys(PullRequestContentSource.CHANGE_FILE, PullRequestContentSource.COMMITS_FILE);
            assertThat(captured.completeness())
                    .containsEntry(PullRequestContentSource.CORE, SourceCompleteness.COMPLETE)
                    .containsEntry(PullRequestContentSource.COMMENTS, SourceCompleteness.COMPLETE)
                    .doesNotContainKey(PullRequestContentSource.DIFF);
            assertThat(captured.stateOverrides())
                    .containsEntry(
                            PullRequestContentSource.DIFF,
                            new SourceCaptureState.Unavailable(SourceAbsenceReason.REPOSITORY_FETCH_FAILED));
            assertThat(repository.stateOverrides())
                    .containsEntry(
                            RepositoryTreeContentSource.KIND,
                            new SourceCaptureState.Unavailable(SourceAbsenceReason.REPOSITORY_FETCH_FAILED));
            assertThat(repository.files()).isEmpty();
            assertThat(repository.filesOnDisk()).isEmpty();
            assertThat(repository.directories()).isEmpty();
            assertThat(upstream.getRequestCount()).isEqualTo(1);
        } finally {
            if (captured.cleanup() != null) captured.cleanup().close();
            if (repository.cleanup() != null) repository.cleanup().close();
        }
    }

    @Test
    void shouldPrepareTheExactPinnedRangeFromTheRealMirror() {
        var prepared = preparer.prepare(context);
        assertThat(prepared.head()).isEqualTo(HEAD);
        assertThat(prepared.target()).isEqualTo("b".repeat(40));
    }
}

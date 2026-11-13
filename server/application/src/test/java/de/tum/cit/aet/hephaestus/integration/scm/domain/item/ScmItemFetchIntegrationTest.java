package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.GeneralReviewCommentContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.IssueContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewRepositoryPreparer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The item source runs against the real database and its transactions. Only the provider is replaced: the credential
 * mint and the fetch record whether a transaction was active when they were called.
 */
class ScmItemFetchIntegrationTest extends BaseIntegrationTest {
    private static final String URL = "https://gitlab.example.test";
    private static final String COMMIT = "c".repeat(40);

    @Autowired
    private ItemIndexService writer;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private PullRequestRepository pulls;

    @Autowired
    private PullRequestReviewRepository reviews;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScmItemAccess items;

    @Autowired
    private ScmWorkLookup workLookup;

    @Autowired
    private IssueEvidenceVersion issueVersions;

    @Autowired
    private ScmFileVersions fileVersions;

    @Autowired
    private ScmCaptureContributors contributors;

    @Autowired
    private PullRequestContentSource pullContent;

    @Autowired
    private IssueContentSource issueContent;

    @Autowired
    private GeneralReviewCommentContentSource generalComments;

    @Autowired
    private ReviewThreadContentSource threads;

    @Autowired
    private LinkedWorkItemContentSource linked;

    @Autowired
    private RepositoryTreeContentSource repositoryTree;

    @Autowired
    private ItemEventService events;

    @Autowired
    private PersonDataWriteFence fence;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ScmItemTransactions itemTransactions;

    @Autowired
    private PlatformTransactionManager transactions;

    private final GitRepositoryManager git = mock(GitRepositoryManager.class);
    private final ScmTokenSource tokens = mock(ScmTokenSource.class);
    private final ScmGitTree tree = mock(ScmGitTree.class);

    /** Whether a transaction was active at each credential mint and each fetch, in order. */
    private final List<Boolean> transactionAtProvider = new CopyOnWriteArrayList<>();

    private ScmItemService sources;

    @BeforeEach
    void setUp() {
        when(git.isEnabled()).thenReturn(true);
        when(tokens.kind()).thenReturn(IntegrationKind.GITLAB);
        when(tokens.serverUrl(anyLong())).thenReturn(Optional.of(URL));
        when(tokens.accessToken(anyLong())).thenAnswer(call -> {
            transactionAtProvider.add(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of("token");
        });
        doAnswer(call -> {
                    transactionAtProvider.add(TransactionSynchronizationManager.isActualTransactionActive());
                    return null;
                })
                .when(git)
                .ensureRepository(any(), anyString(), any());
        when(tree.resolve(any(), eq("refs/heads/main"))).thenReturn(COMMIT);
        when(tree.inventory(any(), eq(COMMIT)))
                .thenReturn(List.of(new ScmFileVersions.Entry("src/a.ts", "100644", "d".repeat(40))));
        var access = new ScmRepositoryAccess(connectionService, items, repositories, List.of(tokens), git);
        var preparer = new ReviewRepositoryPreparer(git, pulls, connectionService, List.of(tokens), repositories);
        var capture = new ScmWorkCapture(
                pullContent,
                issueContent,
                generalComments,
                threads,
                linked,
                repositoryTree,
                contributors,
                git,
                preparer,
                workLookup,
                reviews,
                events,
                fence,
                mapper,
                Clock.systemUTC());
        sources = new ScmItemService(
                items,
                access,
                repositories,
                workLookup,
                reviews,
                issueVersions,
                tree,
                fileVersions,
                mapper,
                capture,
                itemTransactions,
                Clock.systemUTC());
    }

    @Test
    void shouldSyncTheMirrorWithNoTransactionWhenARepositoryOrFilePinOrCaptureFetches() {
        var root = repositoryItem(fixture());
        var file = writer.upsert(
                new ItemRegistration(root.workspaceId(), ScmItemEvents.FILE, null, root.itemId(), "src/a.ts"));

        var repository = sources.pin(root, null);
        var pinnedFile = sources.pin(file, null);
        sources.capture(repository, new CaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(), "3.0.0", 3));

        assertThat(repository.version().value()).isEqualTo(COMMIT);
        assertThat(pinnedFile.version().baseVersion()).isEqualTo(COMMIT);
        assertThat(transactionAtProvider).isNotEmpty().containsOnly(false);
    }

    @Test
    void shouldRefuseToSyncTheMirrorWhenTheCallerHoldsATransaction() {
        var root = repositoryItem(fixture());

        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> sources.pin(root, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(transactionAtProvider).isEmpty();
    }

    @Test
    void shouldFetchTheReviewedRepositoryWithNoTransactionWhenAPullRequestCaptureReadsTheClone() {
        doAnswer(call -> {
                    transactionAtProvider.add(TransactionSynchronizationManager.isActualTransactionActive());
                    throw new GitRepositoryManager.RepositoryFetchException(new InvalidRemoteException("unreachable"));
                })
                .when(git)
                .ensureRepository(any(), anyString(), any());
        var fixture = fixture();
        var repository = fixture.repository();
        var root = repositoryItem(fixture);
        var author = users.saveAndFlush(TestUserFactory.createUser(
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                "author-" + UUID.randomUUID(),
                repository.getProvider()));
        var pull = new PullRequest();
        pull.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        pull.setProvider(repository.getProvider());
        pull.setRepository(repository);
        pull.setNumber(7);
        pull.setTitle("A pull request");
        pull.setBody("Description");
        pull.setState(Issue.State.OPEN);
        pull.setAuthor(author);
        pull.setHtmlUrl(repository.getHtmlUrl() + "/-/merge_requests/7");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pull.setHeadRefOid("a".repeat(40));
        pull.setBaseRefOid("b".repeat(40));
        pull.setHeadRefName("topic");
        pull.setBaseRefName("main");
        pull = pulls.saveAndFlush(pull);
        var pullRef = writer.upsert(new ItemRegistration(
                root.workspaceId(), ScmItemEvents.PULL_REQUEST, pull.getId(), root.itemId(), null));
        var pinned = sources.pin(pullRef, null);

        var captured = sources.capture(
                pinned,
                new CaptureRequest(
                        UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(LinkedWorkItemContentSource.KIND), "3.0.0", 3));

        assertThat(captured.completeness()).containsKey(LinkedWorkItemContentSource.KIND);
        assertThat(transactionAtProvider).containsExactly(false, false);
    }

    private record Fixture(long workspaceId, Repository repository) {}

    private Fixture fixture() {
        String slug = "scm-fetch-" + UUID.randomUUID();
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, URL)
                .orElseGet(() -> providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, URL)));
        var workspace = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.gitLabPatWorkspace(slug).withSlug(slug),
                URL);
        var repository = new Repository();
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setProvider(provider);
        repository.setName("source");
        repository.setNameWithOwner(slug + "/source");
        repository.setHtmlUrl(URL + "/" + slug + "/source");
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setPushedAt(Instant.now());
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository = repositories.saveAndFlush(repository);
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(
                workspace, repository.getNameWithOwner(), repository.getNativeId()));
        return new Fixture(workspace.getId(), repository);
    }

    private ItemRef repositoryItem(Fixture fixture) {
        return writer.upsert(new ItemRegistration(
                fixture.workspaceId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
    }
}

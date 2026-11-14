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
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceFingerprint;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.GeneralReviewCommentContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.IssueContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewRepositoryPreparer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitTransportFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.practices.result.ResultFreshness;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    private IssueRepository issues;

    @Autowired
    private ScmCaptureConsent consent;

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
    private RepositoryRefState refs;

    @Autowired
    private PlatformTransactionManager transactions;

    private final GitRepositoryManager git = mock(GitRepositoryManager.class);
    private final ScmTokenSource tokens = mock(ScmTokenSource.class);
    private final ScmGitTree tree = mock(ScmGitTree.class);

    /** Whether a transaction was active at each credential mint and each fetch, in order. */
    private final List<Boolean> transactionAtProvider = new CopyOnWriteArrayList<>();

    private ScmItemService sources;
    private ScmSourceFingerprints reader;

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
        var built = pipeline(git);
        sources = built.sources();
        reader = built.reader();
    }

    private record Pipeline(ScmItemService sources, ScmSourceFingerprints reader) {}

    /** The item source and its fingerprint reader on {@code git}, with this test's provider double. */
    private Pipeline pipeline(GitRepositoryManager git) {
        var access = new ScmRepositoryAccess(connectionService, items, repositories, List.of(tokens), git);
        var preparer = new ReviewRepositoryPreparer(git, pulls, connectionService, List.of(tokens), repositories);
        // Built on the provider double, so that linked work reads this test's mirror rather than a disabled one.
        var linked = new LinkedWorkItemContentSource(mapper, pulls, issues, git, preparer, consent);
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
        var service = new ScmItemService(
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
        return new Pipeline(service, new ScmSourceFingerprints(service, items, refs, capture, itemTransactions));
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
        var pullRef = pullRequestItem(fixture());
        var pinned = sources.pin(pullRef, null);

        var captured = sources.capture(
                pinned,
                new CaptureRequest(
                        UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(LinkedWorkItemContentSource.KIND), "3.0.0", 3));

        assertThat(captured.stateOverrides().get(LinkedWorkItemContentSource.KIND))
                .isEqualTo(new SourceCaptureState.Unavailable(SourceAbsenceReason.REPOSITORY_FETCH_FAILED));
        assertThat(transactionAtProvider).containsExactly(false, false);
    }

    @Test
    void shouldReadLinkedWorkFingerprintsFromTheMirrorWithoutFetchingUntilASyncFillsIt() {
        var mirrored = new AtomicBoolean(false);
        when(git.isRepositoryCloned(any())).thenAnswer(call -> mirrored.get());
        when(git.commitExists(any(), anyString())).thenAnswer(call -> mirrored.get());
        doAnswer(call -> {
                    transactionAtProvider.add(TransactionSynchronizationManager.isActualTransactionActive());
                    mirrored.set(true);
                    return null;
                })
                .when(git)
                .ensureRepository(any(), anyString(), any());
        var pullRef = pullRequestItem(fixture());
        var kinds = Set.of(LinkedWorkItemContentSource.KIND);
        var locked = new TransactionTemplate(transactions);

        var beforeSync = locked.execute(status -> reader.fingerprints(pullRef, kinds, null, mapper));

        assertThat(beforeSync).doesNotContainKey(LinkedWorkItemContentSource.KIND);
        assertThat(transactionAtProvider).isEmpty();

        reader.sync(pullRef, kinds, null);
        var afterSync = locked.execute(status -> reader.fingerprints(pullRef, kinds, null, mapper));

        assertThat(transactionAtProvider).containsExactly(false, false);
        var captured = sources.capture(
                sources.pin(pullRef, null),
                new CaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 1, kinds, "3.0.0", 3));
        assertThat(afterSync)
                .containsEntry(
                        LinkedWorkItemContentSource.KIND,
                        SourceFingerprint.of(mapper, LinkedWorkItemContentSource.KIND, captured));
    }

    @Test
    void shouldRefuseToSyncTheReviewedRepositoryWhenTheCallerHoldsATransaction() {
        var pullRef = pullRequestItem(fixture());
        var kinds = Set.of(LinkedWorkItemContentSource.KIND);

        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
                    reader.sync(pullRef, kinds, null);
                    return null;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a database transaction");
        assertThat(transactionAtProvider).isEmpty();
    }

    @Test
    void shouldStoreNoLinkedWorkFingerprintWhenTheCaptureFetchFailsSoAFullReadCannotFindAChange() {
        doAnswer(call -> {
                    throw new GitRepositoryManager.RepositoryFetchException(new InvalidRemoteException("unreachable"));
                })
                .when(git)
                .ensureRepository(any(), anyString(), any());
        doAnswer(call -> {
                    call.<Consumer<String>>getArgument(3).accept("Fixes #12");
                    return null;
                })
                .when(git)
                .forEachCommitMessage(any(), anyString(), anyString(), any());
        var pullRef = pullRequestItem(fixture());
        var kinds = Set.of(LinkedWorkItemContentSource.KIND);
        var captured = sources.capture(
                sources.pin(pullRef, null),
                new CaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 1, kinds, "3.0.0", 3));
        var recorded = new HashMap<SourceKind, String>();
        var fingerprint = SourceFingerprint.of(mapper, LinkedWorkItemContentSource.KIND, captured);
        if (fingerprint != null) recorded.put(LinkedWorkItemContentSource.KIND, fingerprint);

        // Later this server's mirror holds the range, and its commit names an issue that the title does not.
        when(git.isRepositoryCloned(any())).thenReturn(true);
        when(git.commitExists(any(), anyString())).thenReturn(true);
        var full = new TransactionTemplate(transactions)
                .execute(status -> reader.fingerprints(pullRef, kinds, null, mapper));

        assertThat(recorded).isEmpty();
        assertThat(full).containsKey(LinkedWorkItemContentSource.KIND);
        assertThat(ResultFreshness.compare(recorded, Objects.requireNonNull(full)))
                .isNotEqualTo(Currentness.STALE);
    }

    @Test
    void shouldKeepTheDiffFingerprintWhenThisServerHasNoMirror(@TempDir Path root) {
        var noMirror = new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, 64 * 1024 * 1024, 1L << 40),
                new GitRepositoryLockManager(),
                new FabricLayout(root.resolve("fabric").toString()),
                GitTransportFixtures.guarded(),
                mock(RepositoryRefState.class),
                GitTransportFixtures.copyFence());
        var cold = pipeline(noMirror).reader();
        var pullRef = pullRequestItem(fixture());
        var kinds = Set.of(PullRequestContentSource.DIFF, LinkedWorkItemContentSource.KIND);

        var read = new TransactionTemplate(transactions)
                .execute(status -> cold.fingerprints(pullRef, kinds, null, mapper));

        assertThat(read).containsKey(PullRequestContentSource.DIFF).doesNotContainKey(LinkedWorkItemContentSource.KIND);
        assertThat(transactionAtProvider).isEmpty();
    }

    @Test
    void shouldRefuseToFetchTheReviewedRepositoryWhenTheCallerHoldsATransaction() {
        var pullRef = pullRequestItem(fixture());
        var pinned = sources.pin(pullRef, null);
        var request = new CaptureRequest(
                UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(LinkedWorkItemContentSource.KIND), "3.0.0", 3);

        assertThatThrownBy(
                        () -> new TransactionTemplate(transactions).execute(status -> sources.capture(pinned, request)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a database transaction");
        assertThat(transactionAtProvider).isEmpty();
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

    private ItemRef pullRequestItem(Fixture fixture) {
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
        return writer.upsert(new ItemRegistration(
                root.workspaceId(), ScmItemEvents.PULL_REQUEST, pull.getId(), root.itemId(), null));
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

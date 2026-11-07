package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoadException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckoutException;
import de.tum.cit.aet.hephaestus.integration.scm.context.MentorContextQueryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.context.ScmMentorWorkQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitTransportFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.settings.WorkspaceTeamRepositorySettings;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.settings.WorkspaceTeamRepositorySettingsRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;

class ScmRepositoryCheckoutsIntegrationTest extends BaseIntegrationTest {
    private static final String SERVER_URL = "https://gitlab.checkouts.example";
    private static final String TOKEN = "private-token";

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
    private PullRequestRepository pullRequests;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private ScmMentorWorkQuery work;

    @Autowired
    private MentorContextQueryRepository authoredWork;

    @Autowired
    private WorkspaceTeamRepositorySettingsRepository teamRepositorySettings;

    @TempDir
    private Path temporary;

    private IdentityProvider provider;
    private Workspace workspace;
    private Repository monitored;
    private Git upstream;
    private Path source;
    private GitRepositoryManager git;
    private ScmRepositoryCheckouts checkouts;
    private ScmContextLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        databaseTestUtils.cleanDatabase();
        provider = providers.save(new IdentityProvider(IdentityProviderType.GITLAB, SERVER_URL));
        workspace = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaces, connectionRows, WorkspaceTestFixtures.gitLabPatWorkspace("owner"), SERVER_URL);
        var other = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaces, connectionRows, WorkspaceTestFixtures.gitLabPatWorkspace("other"), SERVER_URL);
        monitored = repository(201L, "owner/repo");
        monitor(workspace, monitored);
        repository(202L, "owner/unmonitored");
        var hidden = repository(203L, "owner/hidden");
        monitor(workspace, hidden);
        hide(hidden);
        monitor(other, repository(204L, "other/repo"));

        source = Files.createDirectory(temporary.resolve("source"));
        Files.createDirectory(source.resolve("src"));
        upstream = Git.init()
                .setInitialBranch("main")
                .setDirectory(source.toFile())
                .call();
        GitTestFixtures.disableSigning(upstream.getRepository());

        var tokens = mock(ScmTokenSource.class);
        when(tokens.kind()).thenReturn(IntegrationKind.GITLAB);
        when(tokens.serverUrl(anyLong())).thenReturn(Optional.of(SERVER_URL));
        when(tokens.accessToken(anyLong())).thenReturn(Optional.of(TOKEN));
        var mirrors = new GitRepositoryManager(
                new GitRepositoryProperties(true, 1, 1024 * 1024, 1L << 40),
                new GitRepositoryLockManager(),
                new FabricLayout(temporary.resolve("fabric").toString()),
                GitTransportFixtures.guarded());
        // The provider URL has no Git server behind it: the mirror fetches from the local upstream instead.
        git = mock(GitRepositoryManager.class, AdditionalAnswers.delegatesTo(mirrors));
        doAnswer(invocation -> {
                    mirrors.ensureRepository(
                            invocation.getArgument(0), source.toUri().toString(), null, invocation.getArgument(3));
                    return null;
                })
                .when(git)
                .ensureRepository(any(), anyString(), any(), any());
        var preparer = new ReviewRepositoryPreparer(git, pullRequests, connections, List.of(tokens), repositories);
        checkouts = new ScmRepositoryCheckouts(git, preparer, pullRequests, teamRepositorySettings);
        loader = new ScmContextLoader(checkouts, preparer, teamRepositorySettings, pullRequests, work, authoredWork);
    }

    @AfterEach
    void closeUpstream() {
        upstream.close();
    }

    @Test
    void shouldGiveOneAnswerWhenRepositoryIsNotVisibleToTheWorkspace() {
        long workspaceId = workspace.getId();
        for (String name : List.of("other/repo", "owner/unmonitored", "owner/hidden", "owner/unknown")) {
            assertThatThrownBy(() -> checkouts.checkout(workspaceId, name, "main", null))
                    .as(name)
                    .isInstanceOfSatisfying(
                            RepositoryCheckoutException.class,
                            refusal -> assertThat(refusal.code())
                                    .isEqualTo(RepositoryCheckoutException.Code.REPOSITORY_NOT_VISIBLE))
                    .hasMessage("Repository is not visible to this workspace");
        }
        verify(git, never()).ensureRepository(any(), anyString(), any(), any());
    }

    @Test
    void shouldGiveOneAnswerWhenALoadNamesARepositoryTheWorkspaceCannotSee() {
        long workspaceId = workspace.getId();
        for (String path : List.of(
                "repos/gitlab.checkouts.example/other/repo",
                "repos/gitlab.checkouts.example/owner/unmonitored",
                "repos/gitlab.checkouts.example/owner/hidden@main",
                "repos/gitlab.checkouts.example/owner/unknown",
                "repos/github.com/owner/repo",
                "repos/owner/repo")) {
            assertThatThrownBy(() -> loader.load(workspaceId, 1L, path, () -> false))
                    .as(path)
                    .isInstanceOfSatisfying(
                            ContextLoadException.class,
                            refusal -> assertThat(refusal.code()).isEqualTo(ContextLoadException.Code.NOT_VISIBLE))
                    .hasMessage("No repository at " + path
                            + " that this workspace can load. INDEX.md lists the repositories you can load.");
        }
        verify(git, never()).ensureRepository(any(), anyString(), any(), any());
    }

    @Test
    void shouldLoadTheDefaultBranchWithItsHistoryAndListOnlyVisibleRepositoriesWhenTheModelLoadsARepository()
            throws Exception {
        commit("README.md", "Repository\n");
        String head = commit("src/Main.java", "class Main {}\n");
        monitored.setDefaultBranch("main");
        repositories.save(monitored);
        pullRequest(7, head);

        String index = loader.index(workspace.getId(), 1L);
        assertThat(index)
                .contains("`repos/gitlab.checkouts.example/owner/repo`, default branch `main`, open: #7")
                .contains("@merge-request/<number>")
                .doesNotContain("owner/hidden", "other/repo", "owner/unmonitored");

        Path directory;
        try (var loaded =
                loader.load(workspace.getId(), 1L, "repos/gitlab.checkouts.example/owner/repo", () -> false)) {
            directory = loaded.directory();
            assertThat(loaded.path()).isEqualTo("repos/gitlab.checkouts.example/owner/repo");
            assertThat(loaded.version()).isEqualTo(new ContextLoader.Version("main", head, "@" + head.substring(0, 7)));
            assertThat(directory.resolve(".git")).isDirectory();
            assertThat(directory.resolve("src/Main.java")).hasContent("class Main {}");
            assertThat(loaded.note()).contains("@" + head.substring(0, 7));
        }
        assertThat(directory).doesNotExist();
    }

    @Test
    void shouldStageOnlyTheLoadedRefAndTheDefaultBranchWithTheirHistoryAsLocalBranchesWhenALoadNamesABranch()
            throws Exception {
        String main = commit("README.md", "Repository\n");
        upstream.tag().setName("v1").setSigned(false).call();
        upstream.checkout().setCreateBranch(true).setName("unrelated").call();
        String unrelated = commit("secret.txt", "not on main or feature\n");
        upstream.checkout().setName("main").call();
        upstream.checkout().setCreateBranch(true).setName("feature").call();
        String feature = commit("src/Main.java", "class Main {}\n");
        upstream.checkout().setName("main").call();
        monitored.setDefaultBranch("main");
        repositories.save(monitored);

        try (var loaded = loader.load(
                        workspace.getId(), 1L, "repos/gitlab.checkouts.example/owner/repo@feature", () -> false);
                var staged = new FileRepositoryBuilder()
                        .setGitDir(loaded.directory().resolve(".git").toFile())
                        .setMustExist(true)
                        .build()) {
            assertThat(staged.getRefDatabase().getRefs())
                    .extracting(Ref::getName)
                    .containsExactlyInAnyOrder("HEAD", "refs/heads/feature", "refs/heads/main");
            assertThat(Objects.requireNonNull(staged.resolve("main")).getName()).isEqualTo(main);
            assertThat(Objects.requireNonNull(staged.resolve("feature")).getName())
                    .isEqualTo(feature);
            assertThat(staged.getObjectDatabase().has(ObjectId.fromString(unrelated)))
                    .as("a commit only another branch reaches is not staged")
                    .isFalse();
            assertThat(loaded.note())
                    .contains("`feature` at " + feature.substring(0, 12), "`main` at " + main.substring(0, 12));
        }
    }

    @Test
    void shouldFetchNothingWhenTheReaderStoppedWaitingBeforeItGotTheMirrorLock() throws Exception {
        commit("README.md", "Repository\n");

        assertThatThrownBy(() -> checkouts.checkout(workspace.getId(), "owner/repo", "main", null, () -> true))
                .isInstanceOf(CancellationException.class);

        assertThat(git.isRepositoryCloned(new RepositoryKey(workspace.getId(), monitored.getId())))
                .as("the abandoned reader took the lock and fetched nothing")
                .isFalse();
    }

    @Test
    void shouldRefuseWhenBranchDoesNotExist() throws Exception {
        commit("README.md", "Repository\n");

        assertThatThrownBy(() -> checkouts.checkout(workspace.getId(), "owner/repo", "missing", null))
                .isInstanceOfSatisfying(
                        RepositoryCheckoutException.class,
                        refusal ->
                                assertThat(refusal.code()).isEqualTo(RepositoryCheckoutException.Code.REF_NOT_FOUND));
    }

    @Test
    void shouldCheckOutBranchWithItsOwnGitDirectoryWhenRepositoryIsMonitored() throws Exception {
        String base = commit("README.md", "Repository\n");
        upstream.tag().setName("v1").setSigned(false).call();
        String head = commit("src/Main.java", "class Main {}\n");

        Path directory;
        try (var checkout = checkouts.checkout(workspace.getId(), "owner/repo", "main", "v1")) {
            directory = checkout.directory();
            assertThat(checkout.repository()).isEqualTo("owner/repo");
            assertThat(checkout.head()).isEqualTo(head);
            assertThat(checkout.base()).isEqualTo(base);
            assertThat(directory.resolve(".git")).isDirectory();
            assertThat(directory.resolve("src/Main.java")).hasContent("class Main {}");
            assertThat(Files.readString(directory.resolve(".git/HEAD")).strip()).isEqualTo(head);
            // The names the program asked for resolve in the checkout, as in the developer's clone.
            try (var named = new FileRepositoryBuilder()
                    .setGitDir(directory.resolve(".git").toFile())
                    .setMustExist(true)
                    .build()) {
                assertThat(Objects.requireNonNull(named.resolve("main")).getName())
                        .isEqualTo(head);
                assertThat(Objects.requireNonNull(named.resolve("v1^{commit}")).getName())
                        .isEqualTo(base);
            }
        }
        assertThat(directory).doesNotExist();
        verify(git)
                .ensureRepository(
                        eq(new RepositoryKey(workspace.getId(), monitored.getId())),
                        eq(SERVER_URL + "/owner/repo.git"),
                        eq(TOKEN),
                        any());
    }

    @Test
    void shouldCheckOutStoredHeadWhenRefNamesAMergeRequest() throws Exception {
        String head = commit("README.md", "Reviewed\n");
        commit("README.md", "Later\n");
        pullRequest(7, head);

        try (var checkout = checkouts.checkout(workspace.getId(), "owner/repo", "merge-request/7", null)) {
            assertThat(checkout.head()).isEqualTo(head);
            assertThat(checkout.base()).isNull();
            assertThat(checkout.directory().resolve("README.md")).hasContent("Reviewed");
        }
    }

    private String commit(String path, String content) throws Exception {
        Files.writeString(source.resolve(path), content);
        upstream.add().addFilepattern(".").call();
        return upstream.commit()
                .setSign(false)
                .setMessage("Change " + path)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call()
                .name();
    }

    private Repository repository(long nativeId, String nameWithOwner) {
        var repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setProvider(provider);
        repository.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl(SERVER_URL + "/" + nameWithOwner);
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        return repositories.save(repository);
    }

    private void monitor(Workspace owner, Repository repository) {
        var monitor =
                WorkspaceTestFixtures.repositoryMonitor(owner, repository.getNameWithOwner(), repository.getNativeId());
        monitors.save(monitor);
    }

    /** A team setting that hides the repository from contributions. */
    private void hide(Repository repository) {
        var team = new Team();
        team.setNativeId(301L);
        team.setProvider(provider);
        team.setName("Hidden work");
        team.setSlug("hidden-work");
        team.setOrganization("owner");
        team.setHtmlUrl(SERVER_URL + "/groups/owner/hidden-work");
        team.setPrivacy(Team.Privacy.VISIBLE);
        var settings = new WorkspaceTeamRepositorySettings(workspace, teams.save(team), repository);
        settings.setHiddenFromContributions(true);
        teamRepositorySettings.save(settings);
    }

    private void pullRequest(int number, String head) {
        var pr = new PullRequest();
        pr.setNativeId(400L + number);
        pr.setProvider(provider);
        pr.setRepository(monitored);
        pr.setNumber(number);
        pr.setTitle("Reviewed change");
        pr.setHtmlUrl(SERVER_URL + "/owner/repo/-/merge_requests/" + number);
        pr.setState(PullRequest.State.OPEN);
        pr.setCreatedAt(Instant.now());
        pr.setUpdatedAt(Instant.now());
        pr.setHeadRefOid(head);
        pullRequests.save(pr);
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRepositoryMonitors;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.GitLabStoppedProjectService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class ScmRepositoryItemLifecycleIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private SyncTargetProvider syncTargets;

    @Autowired
    private WorkspaceRepositoryMonitorService monitorService;

    @Autowired
    private GitLabRepositoryMonitors gitLabMonitors;

    @Autowired
    private MonitoredRepositoryQuery scope;

    @Autowired
    private ScmRepositoryAccess access;

    @Autowired
    private GitLabStoppedProjectService stoppedProjects;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private ItemRepository items;

    @Autowired
    private PullRequestRepository pulls;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldRegisterAnEmptyRepositoryAfterSyncAndReuseItsItemForLaterPullRequests(IdentityProviderType type) {
        var fixture = fixture(type);
        var monitor = monitor(fixture.workspace(), fixture.repository());
        assertThat(repositoryItemIds(fixture)).isEmpty();

        reconcile(monitor, fixture.repository());
        long itemId = repositoryItemId(fixture);
        reconcile(monitor, fixture.repository());
        assertThat(repositoryItemIds(fixture)).containsExactly(itemId);
        assertActive(fixture.workspace(), itemId, true);

        var pull = new PullRequest();
        pull.setNativeId(randomId());
        pull.setProvider(fixture.repository().getProvider());
        pull.setRepository(fixture.repository());
        pull.setNumber(1);
        pull.setTitle("First pull request");
        pull.setState(Issue.State.OPEN);
        pull.setHtmlUrl(fixture.repository().getHtmlUrl() + "/pull/1");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pull.setHeadRefOid("a".repeat(40));
        pull.setBaseRefOid("b".repeat(40));
        pull.setHeadRefName("topic");
        pull.setBaseRefName("main");
        long pullId = pulls.saveAndFlush(pull).getId();
        transactions.executeWithoutResult(status -> publisher.publishEvent(new ScmDomainEvent.PullRequestCreated(
                ScmEventPayload.PullRequestData.from(pulls.findById(pullId).orElseThrow()),
                EventContext.forSync(fixture.workspace().getId(), null, type, Instant.now()))));

        assertThat(repositoryItemIds(fixture)).containsExactly(itemId);
        assertThat(items.findSource(fixture.workspace().getId(), ScmItemEvents.PULL_REQUEST.value(), pullId))
                .hasValueSatisfying(item -> assertThat(item.getParentId()).isEqualTo(itemId));
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldIndexAlreadyMirroredWorkWithoutAnEventWhenAnotherWorkspaceMonitorsTheRepository(
            IdentityProviderType type) {
        var fixture = fixture(type);
        var monitor = monitor(fixture.workspace(), fixture.repository());
        reconcile(monitor, fixture.repository());
        long pullId = mirroredPull(fixture.repository(), 1);
        long issueId = mirroredIssue(fixture.repository(), 2, false);
        long deletedId = mirroredIssue(fixture.repository(), 3, true);
        var other = connectedWorkspace(type, fixture.repository().getProvider().getServerUrl());
        var otherMonitor = monitor(other, fixture.repository());

        reconcile(otherMonitor, fixture.repository());

        long holder = items.findSource(
                        other.getId(),
                        ScmItemEvents.REPOSITORY.value(),
                        fixture.repository().getId())
                .orElseThrow()
                .getId();
        long pullItem = memberId(other, ScmItemEvents.PULL_REQUEST, pullId, holder);
        long issueItem = memberId(other, ScmItemEvents.ISSUE, issueId, holder);
        assertThat(items.findSource(other.getId(), ScmItemEvents.ISSUE.value(), deletedId))
                .isEmpty();
        assertThat(items.findSource(fixture.workspace().getId(), ScmItemEvents.PULL_REQUEST.value(), pullId))
                .isEmpty();

        reconcile(monitor, fixture.repository());
        reconcile(otherMonitor, fixture.repository());

        memberId(fixture.workspace(), ScmItemEvents.PULL_REQUEST, pullId, repositoryItemId(fixture));
        assertThat(memberId(other, ScmItemEvents.PULL_REQUEST, pullId, holder)).isEqualTo(pullItem);
        assertThat(memberId(other, ScmItemEvents.ISSUE, issueId, holder)).isEqualTo(issueItem);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM item_event WHERE workspace_id IN (?, ?)",
                        Long.class,
                        fixture.workspace().getId(),
                        other.getId()))
                .isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldNotIndexMirroredWorkWhenTheWorkspaceHasNoConnectionToItsProvider(IdentityProviderType type) {
        var fixture = fixture(type);
        fixture.repository().setVisibility(Repository.Visibility.PRIVATE);
        repositories.saveAndFlush(fixture.repository());
        mirroredPull(fixture.repository(), 1);
        mirroredIssue(fixture.repository(), 2, false);
        var unrelated = type == IdentityProviderType.GITHUB ? IdentityProviderType.GITLAB : IdentityProviderType.GITHUB;
        var stranger = connectedWorkspace(
                unrelated,
                unrelated == IdentityProviderType.GITHUB ? "https://github.com" : "https://gitlab.example.test");

        reconcile(monitor(stranger, fixture.repository()), fixture.repository());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM item WHERE workspace_id=?", Long.class, stranger.getId()))
                .isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepMemberIdentitiesAndIndexWorkMirroredMeanwhileWhenMonitoringResumes(IdentityProviderType type) {
        var fixture = fixture(type);
        var monitor = monitor(fixture.workspace(), fixture.repository());
        long pullId = mirroredPull(fixture.repository(), 1);
        reconcile(monitor, fixture.repository());
        long holder = repositoryItemId(fixture);
        long pullItem = memberId(fixture.workspace(), ScmItemEvents.PULL_REQUEST, pullId, holder);

        syncTargets.removeSyncTarget(monitor.getId());
        assertActive(fixture.workspace(), holder, false);
        long issueId = mirroredIssue(fixture.repository(), 2, false);
        assertThat(items.findSource(fixture.workspace().getId(), ScmItemEvents.ISSUE.value(), issueId))
                .isEmpty();
        reconcile(monitor(fixture.workspace(), fixture.repository()), fixture.repository());

        assertThat(repositoryItemIds(fixture)).containsExactly(holder);
        assertActive(fixture.workspace(), holder, true);
        assertThat(memberId(fixture.workspace(), ScmItemEvents.PULL_REQUEST, pullId, holder))
                .isEqualTo(pullItem);
        memberId(fixture.workspace(), ScmItemEvents.ISSUE, issueId, holder);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldRemoveOnlyTheWorkspaceItemAndRestoreItsIdentityWhenMonitoringResumes(IdentityProviderType type) {
        var fixture = fixture(type);
        var monitor = monitor(fixture.workspace(), fixture.repository());
        reconcile(monitor, fixture.repository());
        long itemId = repositoryItemId(fixture);
        var other = connectedWorkspace(type, fixture.repository().getProvider().getServerUrl());
        reconcile(monitor(other, fixture.repository()), fixture.repository());
        long otherItemId = items.findSource(
                        other.getId(),
                        ScmItemEvents.REPOSITORY.value(),
                        fixture.repository().getId())
                .orElseThrow()
                .getId();

        syncTargets.removeSyncTarget(monitor.getId());

        assertActive(fixture.workspace(), itemId, false);
        assertActive(other, otherItemId, true);
        assertThat(repositories.findById(fixture.repository().getId())).isPresent();
        reconcile(monitor(fixture.workspace(), fixture.repository()), fixture.repository());
        assertThat(repositoryItemIds(fixture)).containsExactly(itemId);
        assertActive(fixture.workspace(), itemId, true);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldUnindexAnInstallationRemovalAndKeepAGitLabStopThroughTheWorkspaceMonitorService(
            IdentityProviderType type) {
        var fixture = fixture(type);
        reconcile(monitor(fixture.workspace(), fixture.repository()), fixture.repository());
        long itemId = repositoryItemId(fixture);
        var other = connectedWorkspace(type, fixture.repository().getProvider().getServerUrl());
        reconcile(monitor(other, fixture.repository()), fixture.repository());

        if (type == IdentityProviderType.GITHUB) {
            monitorService.removeRepositoryMonitorForInstallation(
                    installationId(fixture.workspace()),
                    fixture.repository().getNameWithOwner(),
                    fixture.repository().getNativeId());
        } else {
            monitorService.removeRepositoryFromMonitor(
                    fixture.workspace().getWorkspaceSlug(), fixture.repository().getNameWithOwner());
        }

        assertActive(fixture.workspace(), itemId, type == IdentityProviderType.GITLAB);
        assertThat(repositories.findById(fixture.repository().getId())).isPresent();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldRemoveTheItemBeforeDeletingTheLastMonitoredRepository(IdentityProviderType type) {
        var fixture = fixture(type);
        reconcile(monitor(fixture.workspace(), fixture.repository()), fixture.repository());
        long itemId = repositoryItemId(fixture);

        if (type == IdentityProviderType.GITHUB) {
            monitorService.removeRepositoryMonitorForInstallation(
                    installationId(fixture.workspace()),
                    fixture.repository().getNameWithOwner(),
                    fixture.repository().getNativeId());
        } else {
            monitorService.removeRepositoryFromMonitor(
                    fixture.workspace().getWorkspaceSlug(), fixture.repository().getNameWithOwner());
            assertThat(repositories.findById(fixture.repository().getId())).isPresent();
            var stopped = stoppedProjects.getStoppedProjects(fixture.workspace().getId());
            assertThat(stopped)
                    .singleElement()
                    .satisfies(project -> assertThat(project.repositoryItemId()).isEqualTo(String.valueOf(itemId)));
            stoppedProjects.removeStoppedProject(
                    fixture.workspace().getId(), itemId, stopped.getFirst().stopReceipt(), actor());
        }

        assertActive(fixture.workspace(), itemId, false);
        assertThat(repositories.findById(fixture.repository().getId())).isEmpty();
    }

    @Test
    void shouldRetainTheProjectAndItsIndexedWorkWhenGitLabStopsTheLastMonitor() {
        var fixture = fixture(IdentityProviderType.GITLAB);
        var monitor = monitor(fixture.workspace(), fixture.repository());
        long pullId = mirroredPull(fixture.repository(), 1);
        long issueId = mirroredIssue(fixture.repository(), 2, false);
        reconcile(monitor, fixture.repository());
        long holder = repositoryItemId(fixture);
        long pullItem = memberId(fixture.workspace(), ScmItemEvents.PULL_REQUEST, pullId, holder);
        long issueItem = memberId(fixture.workspace(), ScmItemEvents.ISSUE, issueId, holder);

        monitorService.removeRepositoryFromMonitor(
                fixture.workspace().getWorkspaceSlug(), fixture.repository().getNameWithOwner());

        assertThat(monitors.findById(monitor.getId())).isEmpty();
        assertThat(gitLabConfig(fixture.workspace()).projectExclusions().nativeIds())
                .contains(fixture.repository().getNativeId());
        assertThat(repositories.findById(fixture.repository().getId())).isPresent();
        assertThat(pulls.findById(pullId)).isPresent();
        assertThat(issues.findById(issueId)).isPresent();
        assertThat(repositoryItemIds(fixture)).containsExactly(holder);
        assertActive(fixture.workspace(), holder, true);
        assertThat(memberId(fixture.workspace(), ScmItemEvents.PULL_REQUEST, pullId, holder))
                .isEqualTo(pullItem);
        assertThat(memberId(fixture.workspace(), ScmItemEvents.ISSUE, issueId, holder))
                .isEqualTo(issueItem);
    }

    @Test
    void shouldDenyLiveScopeAndSourceAccessWhenGitLabStopsAProjectWhoseWorkIsRetained() {
        var fixture = fixture(IdentityProviderType.GITLAB);
        var stopped = fixture.repository();
        long pullId = mirroredPull(stopped, 1);
        reconcile(monitor(fixture.workspace(), stopped), stopped);
        long holder = repositoryItemId(fixture);
        var sibling = repository(stopped.getProvider(), fixture.workspace().getAccountLogin() + "/sibling");
        reconcile(monitor(fixture.workspace(), sibling), sibling);
        long siblingItem = items.findSource(
                        fixture.workspace().getId(), ScmItemEvents.REPOSITORY.value(), sibling.getId())
                .orElseThrow()
                .getId();
        assertThat(scope.repositories(fixture.workspace().getId())).containsKeys(holder, siblingItem);
        access.authorize(fixture.workspace().getId(), stopped);

        monitorService.removeRepositoryFromMonitor(fixture.workspace().getWorkspaceSlug(), stopped.getNameWithOwner());

        assertThat(scope.repositories(fixture.workspace().getId()))
                .doesNotContainKey(holder)
                .containsKey(siblingItem);
        assertThatThrownBy(() -> access.authorize(fixture.workspace().getId(), stopped))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        denied -> assertThat(denied.code()).isEqualTo(ItemCaptureCode.ITEM_NOT_VISIBLE));
        access.authorize(fixture.workspace().getId(), sibling);
        assertThat(repositories.findById(stopped.getId())).isPresent();
        assertActive(fixture.workspace(), holder, true);
        memberId(fixture.workspace(), ScmItemEvents.PULL_REQUEST, pullId, holder);
    }

    @Test
    void shouldRollBackTheRepositoryItemWithTheMonitorTransaction() {
        var fixture = fixture(IdentityProviderType.GITHUB);
        var monitor = monitor(fixture.workspace(), fixture.repository());

        transactions.executeWithoutResult(status -> {
            reconcile(monitor, fixture.repository());
            assertThat(repositoryItemIds(fixture)).hasSize(1);
            status.setRollbackOnly();
        });

        assertThat(repositoryItemIds(fixture)).isEmpty();
        assertThat(monitors.findById(monitor.getId()))
                .hasValueSatisfying(row -> assertThat(row.getNativeId())
                        .isEqualTo(fixture.repository().getNativeId()));
    }

    @Test
    void shouldRegisterAndRestoreAnEmptyGitHubRepositoryWhenItsInstallationBindsIt() {
        var fixture = fixture(IdentityProviderType.GITHUB);
        var repository = fixture.repository();
        var snapshot = new ProvisioningListener.RepositorySnapshot(
                repository.getNativeId(), repository.getNameWithOwner(), repository.getName(), false);

        monitorService.ensureRepositoryAndMonitorFromSnapshot(installationId(fixture.workspace()), snapshot);
        long itemId = repositoryItemId(fixture);
        assertActive(fixture.workspace(), itemId, true);
        monitorService.ensureRepositoryAndMonitorFromSnapshot(installationId(fixture.workspace()), snapshot);
        assertThat(repositoryItemIds(fixture)).containsExactly(itemId);
        monitorService.removeAllRepositoriesFromMonitor(installationId(fixture.workspace()), false);
        assertActive(fixture.workspace(), itemId, false);
        monitorService.ensureRepositoryAndMonitorFromSnapshot(installationId(fixture.workspace()), snapshot);
        assertThat(repositoryItemIds(fixture)).containsExactly(itemId);
        assertActive(fixture.workspace(), itemId, true);
    }

    @Test
    void shouldRegisterAndRestoreAnEmptyGitLabRepositoryWhenDiscoveryBindsIt() {
        var fixture = fixture(IdentityProviderType.GITLAB);

        assertThat(gitLabMonitors.monitorAll(fixture.workspace(), List.of(fixture.repository())))
                .isEqualTo(1);
        long itemId = repositoryItemId(fixture);
        assertActive(fixture.workspace(), itemId, true);
        var monitor = monitors.findByWorkspaceIdAndNameWithOwner(
                        fixture.workspace().getId(), fixture.repository().getNameWithOwner())
                .orElseThrow();
        syncTargets.removeSyncTarget(monitor.getId());
        assertActive(fixture.workspace(), itemId, false);
        assertThat(gitLabMonitors.monitorAll(fixture.workspace(), List.of(fixture.repository())))
                .isEqualTo(1);
        assertThat(repositoryItemIds(fixture)).containsExactly(itemId);
        assertActive(fixture.workspace(), itemId, true);
    }

    @Test
    void shouldIgnoreARepositoryFromAnotherGitLabInstanceWhenThePathMatches() {
        var fixture = fixture(IdentityProviderType.GITLAB);
        var foreignProvider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://" + UUID.randomUUID() + ".test"));
        var foreign = repository(foreignProvider, fixture.repository().getNameWithOwner());
        var monitor = monitor(fixture.workspace(), fixture.repository());

        reconcile(monitor, fixture.repository());

        assertThat(repositoryItemIds(fixture)).hasSize(1);
        assertThat(items.findSource(fixture.workspace().getId(), ScmItemEvents.REPOSITORY.value(), foreign.getId()))
                .isEmpty();
        monitor.setNativeId(foreign.getNativeId());
        monitors.saveAndFlush(monitor);
        reconcile(monitor, foreign);
        assertThat(items.findSource(fixture.workspace().getId(), ScmItemEvents.REPOSITORY.value(), foreign.getId()))
                .isEmpty();
    }

    private Fixture fixture(IdentityProviderType type) {
        String url = type == IdentityProviderType.GITHUB ? "https://github.com" : "https://gitlab.example.test";
        var provider = providers
                .findByTypeAndServerUrl(type, url)
                .orElseGet(() -> providers.saveAndFlush(new IdentityProvider(type, url)));
        var workspace = connectedWorkspace(type, url);
        return new Fixture(workspace, repository(provider, workspace.getAccountLogin() + "/empty"));
    }

    private Workspace connectedWorkspace(IdentityProviderType type, String url) {
        String slug = "repo-item-" + UUID.randomUUID();
        if (type == IdentityProviderType.GITHUB) {
            long id = randomId();
            return WorkspaceTestFixtures.persistInstallationWorkspace(
                    workspaces,
                    connections,
                    WorkspaceTestFixtures.installationWorkspace(id, slug).withSlug(slug),
                    id);
        }
        return WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.gitLabPatWorkspace(slug).withSlug(slug),
                url);
    }

    private Repository repository(IdentityProvider provider, String nameWithOwner) {
        var repository = new Repository();
        repository.setNativeId(randomId());
        repository.setProvider(provider);
        repository.setName("empty");
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl(provider.getServerUrl() + "/" + nameWithOwner);
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        return repositories.saveAndFlush(repository);
    }

    private RepositoryToMonitor monitor(Workspace workspace, Repository repository) {
        var monitor = WorkspaceTestFixtures.repositoryMonitor(
                workspace, repository.getNameWithOwner(), repository.getNativeId());
        return monitors.saveAndFlush(monitor);
    }

    private void reconcile(RepositoryToMonitor monitor, Repository repository) {
        syncTargets.reconcileSyncTargetIdentity(
                monitor.getId(), repository.getNativeId(), repository.getNameWithOwner());
    }

    /** A pull request the mirror holds without head or base: indexing must not need a version. */
    private long mirroredPull(Repository repository, int number) {
        var pull = new PullRequest();
        pull.setNativeId(randomId());
        pull.setProvider(repository.getProvider());
        pull.setRepository(repository);
        pull.setNumber(number);
        pull.setTitle("Mirrored pull request");
        pull.setState(Issue.State.OPEN);
        pull.setHtmlUrl(repository.getHtmlUrl() + "/pull/" + number);
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        return pulls.saveAndFlush(pull).getId();
    }

    private long mirroredIssue(Repository repository, int number, boolean deleted) {
        var issue = new Issue();
        issue.setNativeId(randomId());
        issue.setProvider(repository.getProvider());
        issue.setRepository(repository);
        issue.setNumber(number);
        issue.setTitle("Mirrored issue");
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/issues/" + number);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        if (deleted) issue.setDeletedAt(Instant.now());
        return issues.saveAndFlush(issue).getId();
    }

    private long memberId(Workspace workspace, ItemKind kind, long sourceId, long parentId) {
        var rows = jdbc.queryForList(
                "SELECT id FROM item WHERE workspace_id=? AND kind=? AND source_id=? AND parent_id=?"
                        + " AND removed_at IS NULL",
                Long.class,
                workspace.getId(),
                kind.value(),
                sourceId,
                parentId);
        assertThat(rows).hasSize(1);
        return Objects.requireNonNull(rows.getFirst());
    }

    private List<Long> repositoryItemIds(Fixture fixture) {
        return jdbc
                .queryForList(
                        "SELECT id FROM item WHERE workspace_id=? AND kind='scm.repository' AND source_id=?",
                        Long.class,
                        fixture.workspace().getId(),
                        fixture.repository().getId())
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    private long repositoryItemId(Fixture fixture) {
        assertThat(repositoryItemIds(fixture)).hasSize(1);
        return repositoryItemIds(fixture).getFirst();
    }

    private void assertActive(Workspace workspace, long itemId, boolean active) {
        assertThat(jdbc.queryForObject(
                        "SELECT removed_at IS NULL FROM item WHERE workspace_id=? AND id=?",
                        Boolean.class,
                        workspace.getId(),
                        itemId))
                .isEqualTo(active);
    }

    private long installationId(Workspace workspace) {
        var config = connections
                .findActive(workspace.getId(), IntegrationKind.GITHUB)
                .orElseThrow()
                .getConfig();
        if (config instanceof ConnectionConfig.GitHubAppConfig app) return Objects.requireNonNull(app.installationId());
        throw new IllegalStateException("Test workspace has no GitHub App connection");
    }

    private long actor() {
        return Objects.requireNonNull(accounts.save(new Account("stopped-project-admin-" + UUID.randomUUID()))
                .getId());
    }

    private ConnectionConfig.GitLabConfig gitLabConfig(Workspace workspace) {
        var config = connections
                .findActive(workspace.getId(), IntegrationKind.GITLAB)
                .orElseThrow()
                .getConfig();
        if (config instanceof ConnectionConfig.GitLabConfig gitLab) return gitLab;
        throw new IllegalStateException("Test workspace has no GitLab connection");
    }

    private static long randomId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    private record Fixture(Workspace workspace, Repository repository) {}
}

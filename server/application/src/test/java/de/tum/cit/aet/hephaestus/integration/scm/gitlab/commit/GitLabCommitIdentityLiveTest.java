package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.core.database.GitLabCommitIdentityResolutionSeedChange;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolutionJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolutionProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolutionRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LiveGitLabTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real GitLab contract and database proof, with disposable provider resources and non-admin connection tokens. */
@LiveGitLabTest
class GitLabCommitIdentityLiveTest extends BaseIntegrationTest {
    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private GitLabProperties gitlab;

    @Autowired
    private CommitIdentityResolutionProperties properties;

    @Autowired
    private CommitIdentityResolutionJob job;

    @Autowired
    private GitLabProjectSyncService projects;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private CredentialBundleConverter converter;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private BackfillStateProvider targets;

    @Autowired
    private UserRepository users;

    @Autowired
    private CommitRepository commits;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource database;

    @Autowired
    private GitLabUserService gitlabUsers;

    @Autowired
    private CommitIdentityResolutionRepository resolutions;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    private final List<Long> workspaceIds = new ArrayList<>();
    private final List<Long> repositoryIds = new ArrayList<>();

    record Scope(long workspaceId, long repositoryId, long providerId, long wrongUserId, long monitorId) {}

    record Captured(long commitId, UUID activityId) {}

    @Test
    void shouldResolveARenamedGitLabUserToItsStableProviderIdentity() throws Exception {
        try (var fixture = new GitLabIdentityFixture(mapper)) {
            fixture.create();
            Scope scope = scope(fixture);
            Captured captured = capture(scope, fixture.commit("rename.txt"));
            long mirrored = Objects.requireNonNull(gitlabUsers.findOrCreateUser(
                            new GitLabUserLookup(
                                    "gid://gitlab/User/" + fixture.userId(),
                                    fixture.username(),
                                    "Identity proof",
                                    null,
                                    null,
                                    fixture.email(),
                                    false),
                            scope.providerId()))
                    .getId();
            fixture.rename();
            seedAndRun(scope);
            long account = author(captured);
            User resolved = users.findById(account).orElseThrow();
            assertThat(resolved.getNativeId()).isEqualTo(fixture.userId());
            assertThat(resolved.getLogin()).isEqualTo(fixture.username());
            assertThat(resolved.getId()).isEqualTo(mirrored);
            assertCorrection(scope, captured, account, "RESOLVED", "resolved");
            assertThat(fixture.version()).isNotBlank();
        }
    }

    @Test
    void shouldClearTheRemovedEmailAndResolveTheNewEmailToTheSameGitLabAccount() throws Exception {
        try (var fixture = new GitLabIdentityFixture(mapper)) {
            fixture.create();
            Scope scope = scope(fixture);
            Captured old = capture(scope, fixture.commit("old-email.txt"));
            fixture.changeEmail();
            Captured current = capture(scope, fixture.commit("new-email.txt"));
            seedAndRun(scope);
            assertThat(jdbc.queryForObject("SELECT author_id FROM git_commit WHERE id=?", Long.class, old.commitId()))
                    .isNull();
            assertCorrection(scope, old, null, "NO_ACCOUNT", "no_account");
            long account = author(current);
            User resolved = users.findById(account).orElseThrow();
            assertThat(resolved.getNativeId()).isEqualTo(fixture.userId());
            assertThat(resolved.getEmail()).isEqualTo(fixture.email());
            assertCorrection(scope, current, account, "RESOLVED", "resolved");
        }
    }

    @Test
    void shouldResolveAfterNormalProjectSyncFollowsATransferByStableProjectId() throws Exception {
        try (var fixture = new GitLabIdentityFixture(mapper)) {
            fixture.create();
            Scope scope = scope(fixture);
            Captured captured = capture(scope, fixture.commit("transfer.txt"));
            fixture.transfer();
            var discovered = projects.fetchProjectById(scope.workspaceId(), fixture.projectId())
                    .orElseThrow();
            Repository moved = projects.persistProject(discovered).orElseThrow();
            assertThat(moved.getId()).isEqualTo(scope.repositoryId());
            assertThat(moved.getNativeId()).isEqualTo(fixture.projectId());
            targets.reconcileSyncTargetIdentity(scope.monitorId(), moved.getNativeId(), moved.getNameWithOwner());
            assertThat(monitors.findById(scope.monitorId()).orElseThrow().getNameWithOwner())
                    .isEqualTo(fixture.projectPath());
            seedAndRun(scope);
            long account = author(captured);
            assertThat(users.findById(account).orElseThrow().getNativeId()).isEqualTo(fixture.userId());
            assertCorrection(scope, captured, account, "RESOLVED", "resolved");
        }
    }

    private Scope scope(GitLabIdentityFixture fixture) {
        assertThat(properties.enabled()).isTrue();
        assertThat(gitlab.defaultServerUrl()).isEqualTo(fixture.server());
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, fixture.server())
                .orElseGet(() ->
                        providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, fixture.server())));
        var workspace = WorkspaceTestFixtures.activeWorkspace(fixture.groupPath());
        workspace.setAccountLogin(fixture.groupPath());
        workspace = workspaces.saveAndFlush(workspace);
        workspaceIds.add(workspace.getId());
        var config = new ConnectionConfig.GitLabConfig(fixture.server(), fixture.groupId(), null, Set.of(), null);
        var connection = new Connection(workspace, IntegrationKind.GITLAB, fixture.server(), config);
        connection.setCredentials(new BearerToken(fixture.token(), null), converter);
        connection = connections.saveAndFlush(connection);
        connectionService.transition(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "CONNECT",
                        "SYSTEM",
                        "live-proof",
                        UUID.randomUUID().toString(),
                        "Identity proof"));
        Repository repository =
                projects.syncProject(workspace.getId(), fixture.projectPath()).orElseThrow();
        repositoryIds.add(repository.getId());
        var monitor = monitors.saveAndFlush(
                WorkspaceTestFixtures.repositoryMonitor(workspace, repository.getNameWithOwner(), fixture.projectId()));
        var wrong = new User();
        wrong.setProvider(provider);
        wrong.setNativeId(1_000_000_000L + fixture.userId());
        wrong.setLogin("wrong-" + fixture.groupPath());
        wrong.setType(User.Type.USER);
        wrong = users.saveAndFlush(wrong);
        return new Scope(
                workspace.getId(),
                repository.getId(),
                Objects.requireNonNull(provider.getId()),
                Objects.requireNonNull(wrong.getId()),
                Objects.requireNonNull(monitor.getId()));
    }

    private Captured capture(Scope scope, GitLabIdentityFixture.Commit commit) {
        String sha = commit.sha();
        Instant now = Instant.now();
        commits.upsertCommit(
                sha,
                "Identity proof",
                null,
                "https://example.invalid/commit/" + sha,
                now,
                now,
                0,
                0,
                0,
                now,
                scope.repositoryId(),
                scope.wrongUserId(),
                null,
                commit.authorEmail(),
                commit.committerEmail(),
                null,
                false);
        long commitId = Objects.requireNonNull(commits.findByShaAndRepositoryId(sha, scope.repositoryId())
                .orElseThrow()
                .getId());
        transactions.executeWithoutResult(status -> events.publishEvent(new ScmDomainEvent.CommitCreated(
                new ScmEventPayload.CommitData(
                        commitId,
                        sha,
                        "Identity proof",
                        now,
                        0,
                        0,
                        0,
                        null,
                        scope.wrongUserId(),
                        null,
                        scope.repositoryId()),
                EventContext.forSync(scope.workspaceId(), null, IdentityProviderType.GITLAB, now))));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM activity_event WHERE workspace_id=? AND target_type='commit' AND target_id=?",
                                Long.class,
                                scope.workspaceId(),
                                commitId))
                        .isEqualTo(1L));
        UUID activityId = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM activity_event WHERE workspace_id=? AND target_type='commit' AND target_id=?",
                UUID.class,
                scope.workspaceId(),
                commitId));
        return new Captured(commitId, activityId);
    }

    private void seedAndRun(Scope scope) throws Exception {
        var seed = new GitLabCommitIdentityResolutionSeedChange();
        try (var connection = database.getConnection()) {
            var postgres = new PostgresDatabase();
            postgres.setConnection(new JdbcConnection(connection));
            seed.execute(postgres);
            connection.commit();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM commit_identity_resolution WHERE repository_id=? AND state='PENDING'",
                        Long.class,
                        scope.repositoryId()))
                .isPositive();
        assertThat(resolutions.findNextRepository().orElseThrow().getRepositoryId())
                .isEqualTo(scope.repositoryId());
        // ShedLock stores UTC without a zone; this test session uses Europe/Berlin.
        await().atMost(Duration.ofSeconds(70))
                .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT coalesce(max(lock_until),timezone('utc',now())) <= timezone('utc',now()) FROM shedlock WHERE name='commit-identity-resolution'",
                        Boolean.class)));
        // As on the scheduled path: no ambient transaction or tenant is held by this caller.
        job.run();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM commit_identity_resolution WHERE repository_id=? AND state='PENDING'",
                        Long.class,
                        scope.repositoryId()))
                .isZero();
    }

    private long author(Captured captured) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT author_id FROM git_commit WHERE id=?", Long.class, captured.commitId()));
    }

    private void assertCorrection(
            Scope scope, Captured captured, @Nullable Long account, String state, String outcome) {
        assertThat(jdbc.queryForObject(
                        "SELECT actor_id FROM activity_event WHERE id=? AND workspace_id=?",
                        Long.class,
                        captured.activityId(),
                        scope.workspaceId()))
                .isEqualTo(account);
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM commit_identity_resolution WHERE repository_id=? AND commit_id=?",
                        String.class,
                        scope.repositoryId(),
                        captured.commitId()))
                .isEqualTo(state);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM commit_identity_change WHERE target='git_commit.author_id'"
                                + " AND row_id=? AND old_user_id=? AND new_user_id IS NOT DISTINCT FROM ? AND outcome=?",
                        Long.class,
                        captured.commitId(),
                        scope.wrongUserId(),
                        account,
                        outcome))
                .isEqualTo(1L);
    }

    @AfterEach
    void removeOnlyThisTestsLocalMirror() {
        for (long id : workspaceIds) {
            lifecycle.purgeWorkspace(workspaces.findById(id).orElseThrow().getWorkspaceSlug());
        }
        for (long id : repositoryIds) repositories.deleteById(id);
    }
}

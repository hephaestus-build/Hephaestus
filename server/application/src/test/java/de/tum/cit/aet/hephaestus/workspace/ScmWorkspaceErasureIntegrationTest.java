package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.connect.GithubConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.scm.github.workspace.GitHubWorkspaceProvisioningAdapter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect.GitlabConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.adapter.ScmWorkspacePurgeAdapter;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-Postgres proof of SCM erase-on-disconnect/purge semantics, and above all of its
 * <b>cross-tenant safety</b>.
 *
 * <p>The SCM mirror is instance-global: {@code repository}, {@code issue}, {@code pull_request} and
 * their children carry no {@code workspace_id} and are genuinely shared between workspaces that
 * monitor the same source repository. An erase that simply deleted "the workspace's repositories"
 * would destroy another tenant's data. A repository two workspaces monitor must survive one of them
 * disconnecting, together with its issues, while a repository only the erased workspace monitored is
 * cascade-deleted with everything hanging off it.
 *
 * <p>Also pins idempotency (the erase is re-runnable) and the retention decisions: another
 * integration's rows and the {@code sync_job} history are NOT erased.
 */
class ScmWorkspaceErasureIntegrationTest extends BaseIntegrationTest {

    private static final String SHARED_REPO = "acme/shared";
    private static final String EXCLUSIVE_REPO = "acme/exclusive";

    @Autowired
    private ScmWorkspaceContentEraser eraser;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private SlackThreadRepository slackThreadRepository;

    @Autowired
    private SyncJobRepository syncJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ScmWorkspacePurgeAdapter scmWorkspacePurgeAdapter;

    @Autowired
    private GithubConnectionStrategy githubConnectionStrategy;

    @Autowired
    private GitlabConnectionStrategy gitlabConnectionStrategy;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private OrganizationMembershipRepository organizationMembershipRepository;

    @Autowired
    private GitHubWorkspaceProvisioningAdapter provisioningAdapter;

    private IdentityProvider gitProvider;
    private Workspace tenantA;
    private Workspace tenantB;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();

        gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        tenantA = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaceRepository,
                connectionRepository,
                WorkspaceTestFixtures.installationWorkspace(5001L, "acme").withSlug("tenant-a"),
                5001L);
        tenantB = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaceRepository,
                connectionRepository,
                WorkspaceTestFixtures.installationWorkspace(5002L, "acme").withSlug("tenant-b"),
                5002L);

        Repository shared = persistRepository(9001L, SHARED_REPO);
        Repository exclusive = persistRepository(9002L, EXCLUSIVE_REPO);
        persistIssue(shared, 1, "shared issue");
        persistIssue(exclusive, 1, "exclusive issue");

        // Both tenants monitor the shared repo; only tenant A monitors the exclusive one.
        persistMonitor(tenantA, SHARED_REPO);
        persistMonitor(tenantB, SHARED_REPO);
        persistMonitor(tenantA, EXCLUSIVE_REPO);
    }

    @Test
    @DisplayName("erasing one tenant leaves a co-monitored repository and its issues fully intact")
    void erase_isCrossTenantSafe() {
        eraser.eraseWorkspaceScmMirror(tenantA.getId());

        assertThat(repositoryRepository.findByNameWithOwner(SHARED_REPO)).isPresent();
        assertThat(issueRepository.findAll())
                .singleElement()
                .extracting(Issue::getTitle)
                .isEqualTo("shared issue");
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(tenantB.getId()))
                .singleElement()
                .extracting(RepositoryToMonitor::getNameWithOwner)
                .isEqualTo(SHARED_REPO);

        assertThat(repositoryToMonitorRepository.findByWorkspaceId(tenantA.getId()))
                .isEmpty();
        assertThat(repositoryRepository.findByNameWithOwner(EXCLUSIVE_REPO)).isEmpty();
    }

    @Test
    @DisplayName("erasing the last tenant drops the previously shared repository and its cascade")
    void erase_ofLastMonitoringTenant_dropsTheSharedRepositoryToo() {
        eraser.eraseWorkspaceScmMirror(tenantA.getId());
        eraser.eraseWorkspaceScmMirror(tenantB.getId());

        assertThat(repositoryRepository.count()).isZero();
        assertThat(issueRepository.count()).isZero();
        assertThat(repositoryToMonitorRepository.count()).isZero();
    }

    @Test
    @DisplayName("erasure is idempotent: a second run deletes nothing and throws nothing")
    void erase_isIdempotent() {
        eraser.eraseWorkspaceScmMirror(tenantA.getId());
        long repositoriesAfterFirst = repositoryRepository.count();

        eraser.eraseWorkspaceScmMirror(tenantA.getId());

        assertThat(repositoryRepository.count()).isEqualTo(repositoriesAfterFirst);
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(tenantA.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("erasure is surgical: another integration's rows and the retained sync history both survive")
    void erase_leavesOtherIntegrationsAndRetainedHistoryAlone() {
        SlackThread slackThread = new SlackThread();
        slackThread.setWorkspaceId(tenantA.getId());
        slackThread.setSlackChannelId("C123");
        slackThread.setSlackThreadTs("1700000000.000100");
        slackThread.setCreatedAt(Instant.now());
        slackThreadRepository.save(slackThread);

        SyncJob syncJob = new SyncJob();
        syncJob.setWorkspace(tenantA);
        syncJob.setConnection(
                connectionRepository.findByWorkspaceId(tenantA.getId()).getFirst());
        syncJob.setKind(IntegrationKind.GITHUB);
        syncJob.setType(SyncJobType.INITIAL);
        syncJob.setTrigger(SyncJobTrigger.MANUAL);
        syncJob.setStatus(SyncJobStatus.SUCCEEDED);
        syncJob.setCreatedAt(Instant.now());
        syncJobRepository.save(syncJob);

        eraser.eraseWorkspaceScmMirror(tenantA.getId());

        // Slack is erased by its own eraser on its own disconnect — an SCM disconnect must not reach it.
        // Counted through JDBC: slack_thread is workspace-scoped, so an unscoped JPA count() would be
        // rejected by the tenancy statement inspector.
        assertThat(countRows("slack_thread")).isEqualTo(1);
        // sync_job is retained: operational audit carries no mirrored third-party content.
        assertThat(countRows("sync_job")).isEqualTo(1);
    }

    @Test
    @DisplayName("purge and GitHub disconnect erase the same rows even when vendor uninstall is unavailable")
    void bothTriggers_eraseTheSameRowSetDespiteUnavailableVendorUninstall() {
        // Trigger B — workspace purge via the adapter.
        assertThat(scmWorkspacePurgeAdapter.getOrder()).isEqualTo(-200);
        scmWorkspacePurgeAdapter.deleteWorkspaceData(tenantA.getId());

        assertThat(repositoryToMonitorRepository.findByWorkspaceId(tenantA.getId()))
                .isEmpty();
        assertThat(repositoryRepository.findByNameWithOwner(EXCLUSIVE_REPO)).isEmpty();
        assertThat(repositoryRepository.findByNameWithOwner(SHARED_REPO)).isPresent();

        // No GitHub credentials are configured: vendor failure must not prevent local erasure.
        var ref = new IntegrationRef(IntegrationKind.GITHUB, tenantB.getId(), "5002");
        var logger = (Logger) LoggerFactory.getLogger(GithubConnectionStrategy.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            githubConnectionStrategy.revoke(ref);
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("GitHub uninstall failed during disconnect: ref=" + ref
                                + ", error=java.lang.IllegalStateException: GitHub App credentials not configured.");
            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }

        assertThat(repositoryToMonitorRepository.count()).isZero();
        assertThat(repositoryRepository.count()).isZero();
        assertThat(issueRepository.count()).isZero();
    }

    @Test
    @DisplayName("GitLab disconnect erases the mirror too — its only erase trigger")
    void gitlabRevoke_erasesTheMirror() {
        gitlabConnectionStrategy.revoke(new IntegrationRef(IntegrationKind.GITLAB, tenantA.getId(), "group-1"));

        assertThat(repositoryToMonitorRepository.findByWorkspaceId(tenantA.getId()))
                .isEmpty();
        assertThat(repositoryRepository.findByNameWithOwner(EXCLUSIVE_REPO)).isEmpty();
        // Still cross-tenant safe on the GitLab path.
        assertThat(repositoryRepository.findByNameWithOwner(SHARED_REPO)).isPresent();
    }

    /**
     * The {@code Workspace.organization} binding is <b>exclusive</b>, not shared: it is a
     * {@code @OneToOne} on a {@code unique = true} {@code organization_id} column ({@code
     * workspace_organization_id_key}), so an organization backs at most ONE workspace. If a disconnect
     * erased the org-tier mirror but kept the FK, the workspace would squat the organization forever —
     * no other workspace could ever install it without violating the unique constraint, and a disconnect
     * does not purge the workspace. The erase must therefore release the link.
     */
    @Test
    @DisplayName("disconnect erases the org-tier mirror and releases the organization for re-binding")
    void erase_releasesTheOrganizationAndErasesItsOrgTierMirror() {
        Organization organization = persistOrganization(4242L, "acme");
        bindOrganization(tenantA, organization);
        persistTeam(organization, 7101L, "backend");
        organizationMembershipRepository.upsertMembership(organization.getId(), 8101L, OrganizationMemberRole.MEMBER);

        eraser.eraseWorkspaceScmMirror(tenantA.getId());

        assertThat(teamsFor(organization)).isEmpty();
        assertThat(organizationMembershipRepository.findByOrganizationId(organization.getId()))
                .isEmpty();

        // Read the FK column directly: Workspace.organization is LAZY, so asserting on the entity
        // getter outside a session reports a proxy-initialization error instead of the real diff.
        assertThat(organizationIdOf(tenantA)).isNull();

        // The Organization identity row itself is global and survives — never deleted.
        assertThat(organizationRepository.findById(organization.getId())).isPresent();

        // The released organization's single binding slot (workspace_organization_id_key) is now free
        // to be bound again.
        bindOrganization(tenantB, organization);
        assertThat(organizationIdOf(tenantB)).isEqualTo(organization.getId());

        assertThat(repositoryToMonitorRepository.findByWorkspaceId(tenantB.getId()))
                .singleElement()
                .extracting(RepositoryToMonitor::getNameWithOwner)
                .isEqualTo(SHARED_REPO);
        assertThat(repositoryRepository.findByNameWithOwner(SHARED_REPO)).isPresent();
    }

    /**
     * A vendor-side {@code installation.deleted} must run the same purge chain an admin deletion runs,
     * not just write {@code status=PURGED}: writing the label directly skips every {@code
     * WorkspacePurgeContributor} bean, leaving Slack/Outline content, org-tier rows and an ACTIVE
     * Connection alive behind a "purged" label.
     */
    @Test
    @DisplayName("a vendor-side GitHub uninstall runs the full purge chain, not just a status write")
    void onInstallationDeleted_runsEveryPurgeContributorNotJustAStatusWrite() {
        Organization organization = persistOrganization(4243L, "acme");
        bindOrganization(tenantA, organization);
        persistTeam(organization, 7102L, "platform");

        SlackThread slackThread = new SlackThread();
        slackThread.setWorkspaceId(tenantA.getId());
        slackThread.setSlackChannelId("C999");
        slackThread.setSlackThreadTs("1700000000.000900");
        slackThread.setCreatedAt(Instant.now());
        slackThreadRepository.save(slackThread);

        provisioningAdapter.onInstallationDeleted(5001L);

        Workspace purged = workspaceRepository.findById(tenantA.getId()).orElseThrow();
        assertThat(purged.getStatus()).isEqualTo(Workspace.WorkspaceStatus.PURGED);

        // Contributor evidence: Slack content (SlackWorkspacePurgeAdapter) is gone, the SCM mirror
        // (ScmWorkspacePurgeAdapter) is gone, and the Connection (ConnectionPurgeContributor) is
        // torn down rather than left ACTIVE behind a "purged" label.
        assertThat(countRows("slack_thread")).isZero();
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(tenantA.getId()))
                .isEmpty();
        assertThat(repositoryRepository.findByNameWithOwner(EXCLUSIVE_REPO)).isEmpty();
        assertThat(connectionRepository.findByWorkspaceId(tenantA.getId()))
                .allSatisfy(connection -> assertThat(connection.getState()).isEqualTo(IntegrationState.UNINSTALLED));

        // tenantB co-monitors the shared repo and is untouched — a vendor uninstall purges exactly
        // the one workspace that installation backs, never a sibling tenant.
        assertThat(repositoryRepository.findByNameWithOwner(SHARED_REPO)).isPresent();
        assertThat(workspaceRepository.findById(tenantB.getId()).orElseThrow().getStatus())
                .isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
        assertThat(teamsFor(organization)).isEmpty();
    }

    @Test
    @DisplayName("a redelivered installation.deleted is a no-op on the already-purged workspace")
    void onInstallationDeleted_isIdempotentAcrossWebhookRedelivery() {
        provisioningAdapter.onInstallationDeleted(5001L);
        long repositoriesAfterFirst = repositoryRepository.count();

        provisioningAdapter.onInstallationDeleted(5001L);

        assertThat(repositoryRepository.count()).isEqualTo(repositoriesAfterFirst);
        assertThat(workspaceRepository.findById(tenantA.getId()).orElseThrow().getStatus())
                .isEqualTo(Workspace.WorkspaceStatus.PURGED);
    }

    private List<Team> teamsFor(Organization organization) {
        return teamRepository.findByOrganizationIgnoreCaseAndProviderId(organization.getLogin(), gitProviderId());
    }

    private Organization persistOrganization(long nativeId, String login) {
        Organization organization = new Organization();
        organization.setNativeId(nativeId);
        organization.setLogin(login);
        organization.setName("Org " + login);
        organization.setProvider(gitProvider);
        organization.setCreatedAt(Instant.now());
        organization.setUpdatedAt(Instant.now());
        return organizationRepository.save(organization);
    }

    private void bindOrganization(Workspace workspace, Organization organization) {
        Workspace managed = workspaceRepository.findById(workspace.getId()).orElseThrow();
        managed.setOrganization(organization);
        workspaceRepository.save(managed);
    }

    private void persistTeam(Organization organization, long nativeId, String slug) {
        Team team = new Team();
        team.setNativeId(nativeId);
        team.setProvider(gitProvider);
        team.setName(slug);
        team.setSlug(slug);
        team.setOrganization(organization.getLogin());
        team.setHtmlUrl("https://github.com/orgs/" + organization.getLogin() + "/teams/" + slug);
        team.setCreatedAt(Instant.now());
        team.setUpdatedAt(Instant.now());
        teamRepository.save(team);
    }

    /** The workspace's {@code organization_id} FK, read as a column so no lazy proxy is involved. */
    private @Nullable Long organizationIdOf(Workspace workspace) {
        return jdbcTemplate.queryForObject(
                "SELECT organization_id FROM workspace WHERE id = ?", Long.class, workspace.getId());
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        assertNotNull(count);
        return count;
    }

    private Repository persistRepository(long nativeId, String nameWithOwner) {
        Repository repo = new Repository();
        repo.setNativeId(nativeId);
        repo.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        repo.setNameWithOwner(nameWithOwner);
        repo.setHtmlUrl("https://github.com/" + nameWithOwner);
        repo.setVisibility(Repository.Visibility.PRIVATE);
        repo.setDefaultBranch("main");
        repo.setPushedAt(Instant.now());
        repo.setCreatedAt(Instant.now());
        repo.setUpdatedAt(Instant.now());
        repo.setProvider(gitProvider);
        return repositoryRepository.save(repo);
    }

    private void persistIssue(Repository repo, int number, String title) {
        Issue issue = new Issue();
        issue.setNativeId(repo.getNativeId() * 100 + number);
        issue.setNumber(number);
        issue.setTitle(title);
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl(repo.getHtmlUrl() + "/issues/" + number);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        issue.setProvider(gitProvider);
        issue.setRepository(repo);
        issueRepository.save(issue);
    }

    private void persistMonitor(Workspace workspace, String nameWithOwner) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setNameWithOwner(nameWithOwner);
        monitor.setWorkspace(workspace);
        repositoryToMonitorRepository.save(monitor);
    }

    private Long gitProviderId() {
        Long id = gitProvider.getId();
        assertNotNull(id);
        return id;
    }
}

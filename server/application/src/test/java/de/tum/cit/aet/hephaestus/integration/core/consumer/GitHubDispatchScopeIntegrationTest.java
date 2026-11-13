package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class GitHubDispatchScopeIntegrationTest extends BaseIntegrationTest {

    private static final String LOGIN = "HephaestusTest";
    private static final String REPOSITORY = LOGIN + "/TestRepository";
    private static final long REPOSITORY_NATIVE_ID = 998279771L;

    @Autowired
    private ProviderEventConsumer consumer;

    @Autowired
    private ProviderEventStore store;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private CommitRepository commits;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingScmEventListener events;

    private Repository repository;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        events.clear();
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        var organization = new Organization();
        organization.setNativeId(215361191L);
        organization.setLogin(LOGIN);
        organization.setName(LOGIN);
        organization.setCreatedAt(Instant.now());
        organization.setUpdatedAt(Instant.now());
        organization.setAvatarUrl("https://example.invalid/avatar");
        organization.setHtmlUrl("https://example.invalid/org");
        organization.setProvider(provider);
        organization = organizations.save(organization);
        var row = new Repository();
        row.setNativeId(REPOSITORY_NATIVE_ID);
        row.setName("TestRepository");
        row.setNameWithOwner(REPOSITORY);
        row.setHtmlUrl("https://github.com/" + REPOSITORY);
        row.setVisibility(Repository.Visibility.PUBLIC);
        row.setDefaultBranch("main");
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row.setPushedAt(Instant.now());
        row.setProvider(provider);
        row.setOrganization(organization);
        repository = repositories.save(row);
    }

    @Test
    void shouldUseTheDispatchWorkspaceWhenTwoWorkspacesShareAnAccountLogin() throws Exception {
        var other = workspace("dispatch-a", LOGIN);
        var target = workspace("dispatch-b", LOGIN);

        dispatchIssue(target);
        dispatchIssue(other);

        assertIssueAndScope(target);
    }

    @Test
    void shouldUseTheDispatchWorkspaceWhenTwoWorkspacesMonitorTheSamePersonalRepository() throws Exception {
        repository.setOrganization(null);
        repository = repositories.save(repository);
        var other = workspace("dispatch-a", "personal-a");
        var target = workspace("dispatch-b", "personal-b");
        monitor(target);
        monitor(other);

        dispatchIssue(target);
        dispatchIssue(other);

        assertIssueAndScope(target);
    }

    @Test
    void shouldKeepTeamEventsInTheDispatchWorkspaceWhenAnAccountLoginIsShared() throws Exception {
        var target = workspace("dispatch-a", LOGIN);
        workspace("dispatch-b", LOGIN);

        assertThat(StoredEventHandling.handle(
                        store,
                        consumer,
                        jdbc,
                        target.getId(),
                        "github.HephaestusTest.?.team",
                        new ClassPathResource("github/team.org.created.json").getContentAsByteArray()))
                .isEqualTo("DONE");

        var team = teams.findByNativeIdAndProviderId(
                        14170707L,
                        Objects.requireNonNull(repository.getProvider().getId()))
                .orElseThrow();
        assertThat(events.ofType(ScmDomainEvent.TeamCreated.class))
                .filteredOn(event -> event.teamId().equals(team.getId()))
                .singleElement()
                .satisfies(event -> assertThat(event.context().scopeId()).isEqualTo(target.getId()));
    }

    @Test
    void shouldKeepPushEventsInTheDispatchWorkspaceWhenAPersonalRepositoryIsShared() throws Exception {
        repository.setOrganization(null);
        repository = repositories.save(repository);
        var target = workspace("dispatch-a", "personal-a");
        var other = workspace("dispatch-b", "personal-b");
        monitor(target);
        monitor(other);

        var payload = (ObjectNode) mapper.readTree(new ClassPathResource("github/push.json").getContentAsByteArray());
        payload.put("ref", "refs/heads/main");
        assertThat(StoredEventHandling.handle(
                        store,
                        consumer,
                        jdbc,
                        target.getId(),
                        "github.HephaestusTest.TestRepository.push",
                        mapper.writeValueAsBytes(payload)))
                .isEqualTo("DONE");

        assertThat(commits.findByShaAndRepositoryId("5e25fac12d5c56488a01b1e4375828fe7d30d64e", repository.getId()))
                .isPresent();
        assertThat(events.ofType(ScmDomainEvent.CommitCreated.class))
                .filteredOn(event -> event.commit().repositoryId().equals(repository.getId()))
                .singleElement()
                .satisfies(event -> assertThat(event.context().scopeId()).isEqualTo(target.getId()));
    }

    @Test
    void shouldNotInferAWorkspaceForAnUnscopedDispatch() throws Exception {
        workspace("dispatch-a", LOGIN);
        workspace("dispatch-b", LOGIN);
        assertThat(StoredEventHandling.handle(
                        store,
                        consumer,
                        jdbc,
                        null,
                        "github.HephaestusTest.TestRepository.issues",
                        new ClassPathResource("github/issues.opened.json").getContentAsByteArray()))
                .isEqualTo("DONE");

        var issue = issues.findByRepositoryIdAndNumber(repository.getId(), 20).orElseThrow();
        assertThat(events.ofType(ScmDomainEvent.IssueCreated.class))
                .filteredOn(event -> event.issue().id().equals(issue.getId()))
                .singleElement()
                .satisfies(event -> assertThat(event.context().scopeId()).isNull());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldScopeARelatedIssueOnlyWhenTheDeliveringWorkspaceMonitorsItsRepository(boolean monitoredHere)
            throws Exception {
        var target = workspace("dispatch-a", LOGIN);
        var other = workspace("dispatch-b", LOGIN);
        var related = new Repository();
        related.setNativeId(REPOSITORY_NATIVE_ID + 1);
        related.setName("Related");
        related.setNameWithOwner(LOGIN + "/Related");
        related.setHtmlUrl("https://github.com/" + LOGIN + "/Related");
        related.setVisibility(Repository.Visibility.PUBLIC);
        related.setCreatedAt(Instant.now());
        related.setUpdatedAt(Instant.now());
        related.setProvider(repository.getProvider());
        related = repositories.save(related);
        monitor(target);
        monitors.save(
                WorkspaceTestFixtures.repositoryMonitor(other, related.getNameWithOwner(), related.getNativeId()));
        if (monitoredHere) {
            monitors.save(
                    WorkspaceTestFixtures.repositoryMonitor(target, related.getNameWithOwner(), related.getNativeId()));
        }
        var payload = (ObjectNode) mapper.readTree(
                new ClassPathResource("github/sub_issues.sub_issue_added.json").getContentAsByteArray());
        var relatedRef = (ObjectNode) payload.path("sub_issue_repo");
        relatedRef.put("id", related.getNativeId());
        relatedRef.put("full_name", related.getNameWithOwner());

        assertThat(StoredEventHandling.handle(
                        store,
                        consumer,
                        jdbc,
                        target.getId(),
                        "github.HephaestusTest.TestRepository.sub_issues",
                        mapper.writeValueAsBytes(payload)))
                .isEqualTo("DONE");

        var issue = issues.findByRepositoryIdAndNumber(related.getId(), 21).orElseThrow();
        assertThat(events.ofType(ScmDomainEvent.IssueCreated.class))
                .filteredOn(event -> event.issue().id().equals(issue.getId()))
                .singleElement()
                .satisfies(event ->
                        assertThat(event.context().scopeId()).isEqualTo(monitoredHere ? target.getId() : null));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM activity_event WHERE workspace_id = ? AND repository_id = ? AND target_type = 'issue' AND target_id = ?",
                        Long.class,
                        target.getId(),
                        related.getId(),
                        issue.getId()))
                .isEqualTo(monitoredHere ? 1L : 0L);
    }

    private Workspace workspace(String slug, String login) {
        long installation = slug.equals("dispatch-a") ? 10001L : 10002L;
        return WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.installationWorkspace(installation, login).withSlug(slug),
                installation);
    }

    private void monitor(Workspace workspace) {
        var row = new RepositoryToMonitor();
        row.setWorkspace(workspace);
        row.setNativeId(REPOSITORY_NATIVE_ID);
        row.setNameWithOwner(REPOSITORY);
        monitors.save(row);
    }

    private void dispatchIssue(Workspace workspace) throws Exception {
        assertThat(StoredEventHandling.handle(
                        store,
                        consumer,
                        jdbc,
                        workspace.getId(),
                        "github.HephaestusTest.TestRepository.issues",
                        new ClassPathResource("github/issues.opened.json").getContentAsByteArray()))
                .isEqualTo("DONE");
    }

    private void assertIssueAndScope(Workspace workspace) {
        var issue = issues.findByRepositoryIdAndNumber(repository.getId(), 20).orElseThrow();
        assertThat(events.ofType(ScmDomainEvent.IssueCreated.class))
                .filteredOn(event -> event.issue().id().equals(issue.getId()))
                .singleElement()
                .satisfies(event -> assertThat(event.context().scopeId()).isEqualTo(workspace.getId()));
    }
}

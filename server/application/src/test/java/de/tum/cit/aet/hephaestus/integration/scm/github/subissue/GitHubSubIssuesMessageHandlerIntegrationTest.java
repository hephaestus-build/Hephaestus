package de.tum.cit.aet.hephaestus.integration.scm.github.subissue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.dto.GitHubSubIssuesEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class GitHubSubIssuesMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitHubSubIssuesMessageHandler handler;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private JdbcTemplate jdbc;

    private IdentityProvider gitProvider;
    private Repository testRepository;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setupTestData();
    }

    private void setupTestData() {
        // Create git provider
        gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create organization
        Organization org = new Organization();
        org.setNativeId(215361191L);
        org.setProvider(gitProvider);
        org.setLogin("HephaestusTest");
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
        org = organizationRepository.save(org);

        // Create repository
        testRepository = new Repository();
        testRepository.setNativeId(998279771L);
        testRepository.setProvider(gitProvider);
        testRepository.setName("TestRepository");
        testRepository.setNameWithOwner("HephaestusTest/TestRepository");
        testRepository.setHtmlUrl("https://github.com/HephaestusTest/TestRepository");
        testRepository.setVisibility(Repository.Visibility.PUBLIC);
        testRepository.setDefaultBranch("main");
        testRepository.setCreatedAt(Instant.now());
        testRepository.setUpdatedAt(Instant.now());
        testRepository.setPushedAt(Instant.now());
        testRepository.setOrganization(org);
        testRepository = repositoryRepository.save(testRepository);

        // Create workspace
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test");
        workspace.setDisplayName("Hephaestus Test");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setPublicActivityEnabled(true);
        workspace.setOrganizationId(org.getId());
        workspace.setAccountLogin("HephaestusTest");
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    private Issue createTestIssue(Long issueId, int number, String title) {
        Issue issue = new Issue();
        issue.setNativeId(issueId);
        issue.setProvider(gitProvider);
        issue.setNumber(number);
        issue.setTitle(title);
        issue.setState(Issue.State.OPEN);
        issue.setRepository(testRepository);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        return issueRepository.save(issue);
    }

    @Test
    void shouldReturnCorrectEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("repository.sub_issues");
    }

    @Test
    void shouldHandleSubIssueAddedEvent() throws Exception {
        GitHubSubIssuesEventDTO event = loadPayload("sub_issues.sub_issue_added");

        // Create parent issue
        createTestIssue(
                required(event.parentIssue().getDatabaseId()),
                event.parentIssue().number(),
                "Parent Issue");

        handler.handleEvent(event);

        Issue parent = issueRepository
                .findByRepositoryIdAndNumber(
                        testRepository.getId(), event.parentIssue().number())
                .orElseThrow();
        Issue child = issueRepository
                .findByRepositoryIdAndNumber(
                        testRepository.getId(), event.subIssue().number())
                .orElseThrow();
        assertThat(child.getParentIssue()).isNotNull();
        assertThat(child.getParentIssue().getId()).isEqualTo(parent.getId());
        assertThat(parent.getSubIssuesTotal()).isEqualTo(1);
        assertThat(parent.getSubIssuesCompleted()).isEqualTo(0);
        assertThat(parent.getSubIssuesPercentCompleted()).isEqualTo(0);
        var summary = required(event.parentIssue().subIssuesSummary());
        var workspace = monitoredWorkspace();
        assertThat(parentOccasions(workspace, parent)).isEmpty();
        ObjectNode withoutSummary = (ObjectNode) objectMapper.readTree(json("sub_issues.sub_issue_added"));
        ((ObjectNode) withoutSummary.required("parent_issue")).remove("sub_issues_summary");
        var noSummary = objectMapper.treeToValue(withoutSummary, GitHubSubIssuesEventDTO.class);
        assertThat(noSummary.parentIssue().subIssuesSummary()).isNull();

        handler.handleEvent(noSummary);

        var retained = issueRepository.findById(parent.getId()).orElseThrow();
        assertThat(retained.getSubIssuesTotal()).isEqualTo(summary.total());
        assertThat(retained.getSubIssuesCompleted()).isEqualTo(summary.completed());
        assertThat(retained.getSubIssuesPercentCompleted()).isEqualTo(summary.percentCompleted());
        assertThat(required(issueRepository
                                .findById(child.getId())
                                .orElseThrow()
                                .getParentIssue())
                        .getId())
                .isEqualTo(parent.getId());
        assertThat(parentOccasions(workspace, retained)).isEmpty();

        // A genuine relationship change without a summary cannot certify the old provider counts.
        var unlinked = issueRepository.findById(child.getId()).orElseThrow();
        unlinked.setParentIssue(null);
        issueRepository.saveAndFlush(unlinked);
        assertThat(issueRepository.findById(child.getId()).orElseThrow().getParentIssue())
                .isNull();
        handler.handleEvent(noSummary);

        var changed = issueRepository.findById(parent.getId()).orElseThrow();
        assertThat(changed.getSubIssuesTotal()).isNull();
        assertThat(changed.getSubIssuesCompleted()).isNull();
        assertThat(changed.getSubIssuesPercentCompleted()).isNull();
        assertThat(required(issueRepository
                                .findById(child.getId())
                                .orElseThrow()
                                .getParentIssue())
                        .getId())
                .isEqualTo(parent.getId());
        assertThat(parentOccasions(workspace, changed)).hasSize(1);
    }

    @Test
    void shouldHandleSubIssueRemovedEvent() throws Exception {
        GitHubSubIssuesEventDTO event = loadPayload("sub_issues.sub_issue_removed");
        var summary = required(event.parentIssue().subIssuesSummary());
        // Hydrate native text before monitoring, so a metadata edit cannot disguise the summary occasion.
        handler.handleEvent(event);
        var parent = issueRepository
                .findByRepositoryIdAndNumber(
                        testRepository.getId(), event.parentIssue().number())
                .orElseThrow();
        var child = issueRepository
                .findByRepositoryIdAndNumber(
                        testRepository.getId(), event.subIssue().number())
                .orElseThrow();
        assertThat(child.getParentIssue()).isNull();
        parent.setSubIssuesTotal(summary.total() + 3);
        parent.setSubIssuesCompleted(summary.completed() + 1);
        parent.setSubIssuesPercentCompleted(33);
        issueRepository.saveAndFlush(parent);
        var workspace = monitoredWorkspace();
        assertThat(parentOccasions(workspace, parent)).isEmpty();

        handler.handleEvent(event);

        parent = issueRepository.findById(parent.getId()).orElseThrow();
        assertThat(parent.getSubIssuesTotal()).isEqualTo(summary.total());
        assertThat(parent.getSubIssuesCompleted()).isEqualTo(summary.completed());
        assertThat(parent.getSubIssuesPercentCompleted()).isEqualTo(summary.percentCompleted());
        var occasions = parentOccasions(workspace, parent);
        assertThat(occasions).hasSize(1);
        assertThat(issueRepository.findById(child.getId()).orElseThrow().getParentIssue())
                .isNull();

        var retainedParent = createTestIssue(
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                event.parentIssue().number() + 10_000,
                "A different stored parent");
        child.setParentIssue(retainedParent);
        issueRepository.saveAndFlush(child);
        handler.handleEvent(event);
        assertThat(required(issueRepository
                                .findById(child.getId())
                                .orElseThrow()
                                .getParentIssue())
                        .getId())
                .isEqualTo(retainedParent.getId());

        // No tracked relationship changes and no provider summary means no new progress fact.
        ObjectNode withoutSummary = (ObjectNode) objectMapper.readTree(json("sub_issues.sub_issue_removed"));
        ((ObjectNode) withoutSummary.required("parent_issue")).putNull("sub_issues_summary");
        handler.handleEvent(objectMapper.treeToValue(withoutSummary, GitHubSubIssuesEventDTO.class));
        var unchanged = issueRepository.findById(parent.getId()).orElseThrow();
        assertThat(unchanged.getSubIssuesTotal()).isEqualTo(summary.total());
        assertThat(unchanged.getSubIssuesCompleted()).isEqualTo(summary.completed());
        assertThat(required(issueRepository
                                .findById(child.getId())
                                .orElseThrow()
                                .getParentIssue())
                        .getId())
                .isEqualTo(retainedParent.getId());
        assertThat(parentOccasions(workspace, unchanged)).containsExactlyInAnyOrderElementsOf(occasions);
    }

    private Workspace monitoredWorkspace() {
        long installation = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        var workspace =
                workspaceRepository.findByWorkspaceSlug("hephaestus-test").orElseThrow();
        var config = new ConnectionConfig.GitHubAppConfig(installation, workspace.getAccountLogin(), null, Set.of());
        var connection = new Connection(workspace, IntegrationKind.GITHUB, Long.toString(installation), config);
        connection.setDisplayName(workspace.getAccountLogin());
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        var monitor = WorkspaceTestFixtures.repositoryMonitor(workspace, testRepository.getNameWithOwner());
        monitor.setNativeId(testRepository.getNativeId());
        monitors.saveAndFlush(monitor);
        return workspace;
    }

    private List<UUID> parentOccasions(Workspace workspace, Issue parent) {
        return jdbc
                .queryForList(
                        "SELECT e.id FROM item_event e JOIN item i"
                                + " ON i.id=e.item_id AND i.workspace_id=e.workspace_id"
                                + " WHERE e.workspace_id=? AND i.kind=? AND i.source_id=? AND e.name=?",
                        UUID.class,
                        workspace.getId(),
                        ScmItemEvents.ISSUE.value(),
                        parent.getId(),
                        ScmItemEvents.ISSUE_UPDATED.value())
                .stream()
                .map(GitHubSubIssuesMessageHandlerIntegrationTest::required)
                .toList();
    }

    @Test
    void shouldNotStoreASubIssueFromAnotherRepositoryUnderTheDeliveringOne() throws Exception {
        ObjectNode payload = (ObjectNode) objectMapper.readTree(json("sub_issues.sub_issue_added"));
        ((ObjectNode) payload.required("sub_issue_repo")).put("full_name", "HephaestusTest/Unsynchronized");
        GitHubSubIssuesEventDTO event = objectMapper.treeToValue(payload, GitHubSubIssuesEventDTO.class);
        createTestIssue(
                required(event.parentIssue().getDatabaseId()),
                event.parentIssue().number(),
                "Parent Issue");

        handler.handleEvent(event);

        assertThat(issueRepository.findByRepositoryIdAndNumber(
                        testRepository.getId(), event.subIssue().number()))
                .isEmpty();
    }

    private GitHubSubIssuesEventDTO loadPayload(String filename) throws IOException {
        return objectMapper.readValue(json(filename), GitHubSubIssuesEventDTO.class);
    }

    private static String json(String filename) throws IOException {
        return new ClassPathResource("github/" + filename + ".json").getContentAsString(StandardCharsets.UTF_8);
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.github.subissue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.dto.GitHubSubIssuesEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for GitHubSubIssuesMessageHandler.
 * <p>
 * Tests use JSON fixtures parsed directly into DTOs using JSON fixtures for complete isolation.
 */
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
        testRepository.setNativeId(1000663383L);
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
        workspace.setIsPubliclyViewable(true);
        workspace.setOrganization(org);
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
    }

    @Test
    void shouldHandleSubIssueRemovedEvent() throws Exception {
        GitHubSubIssuesEventDTO event = loadPayload("sub_issues.sub_issue_removed");

        // Create parent issue and sub issue
        createTestIssue(
                required(event.parentIssue().getDatabaseId()),
                event.parentIssue().number(),
                "Parent Issue");
        createTestIssue(
                required(event.subIssue().getDatabaseId()), event.subIssue().number(), "Sub Issue");

        handler.handleEvent(event);

        // Then - handler processes without error
        assertThat(event.action()).isEqualTo("sub_issue_removed");
    }

    private GitHubSubIssuesEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubSubIssuesEventDTO.class);
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}

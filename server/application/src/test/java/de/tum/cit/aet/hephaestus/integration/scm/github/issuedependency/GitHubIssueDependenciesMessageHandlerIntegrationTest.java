package de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency.dto.GitHubIssueDependenciesEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drives {@link GitHubIssueDependenciesMessageHandler} with the recorded {@code issue_dependencies} deliveries:
 * issue #1 of {@code HephaestusTest/NewRepository} marked as blocked by #2 and then unmarked, each change
 * reported once from each side.
 */
class GitHubIssueDependenciesMessageHandlerIntegrationTest extends BaseIntegrationTest {

    private static final int BLOCKED_NUMBER = 1;
    private static final int BLOCKING_NUMBER = 2;

    @Autowired
    private GitHubIssueDependenciesMessageHandler handler;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Repository repository;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();

        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        Organization organization = new Organization();
        organization.setNativeId(215361191L);
        organization.setProvider(provider);
        organization.setLogin("HephaestusTest");
        organization.setName("Hephaestus Test");
        organization.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
        organization.setCreatedAt(Instant.now());
        organization.setUpdatedAt(Instant.now());
        organization = organizationRepository.save(organization);

        repository = new Repository();
        repository.setNativeId(1134507394L);
        repository.setProvider(provider);
        repository.setName("NewRepository");
        repository.setNameWithOwner("HephaestusTest/NewRepository");
        repository.setHtmlUrl("https://github.com/HephaestusTest/NewRepository");
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository.setOrganization(organization);
        repository = repositoryRepository.save(repository);

        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test");
        workspace.setDisplayName("Hephaestus Test");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setIsPubliclyViewable(true);
        workspace.setOrganization(organization);
        workspace.setAccountLogin("HephaestusTest");
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    @Test
    void shouldRecordTheBlockerWhenTheBlockedIssueReportsIt() throws IOException {
        handler.handleEvent(load("blocked_by_added"));

        assertThat(blockerNumbers()).containsExactly(BLOCKING_NUMBER);
    }

    @Test
    void shouldRecordTheBlockerWhenOnlyTheBlockingIssueReportsIt() throws IOException {
        handler.handleEvent(load("blocking_added"));

        assertThat(blockerNumbers()).containsExactly(BLOCKING_NUMBER);
    }

    @Test
    void shouldRecordTheBlockerOnceWhenBothSidesReportIt() throws IOException {
        handler.handleEvent(load("blocked_by_added"));
        handler.handleEvent(load("blocking_added"));

        assertThat(blockerNumbers()).containsExactly(BLOCKING_NUMBER);
    }

    @Test
    void shouldRemoveTheBlockerWhenEitherSideReportsItsRemoval() throws IOException {
        handler.handleEvent(load("blocked_by_added"));
        handler.handleEvent(load("blocked_by_removed"));
        assertThat(blockerNumbers()).isEmpty();

        handler.handleEvent(load("blocking_added"));
        handler.handleEvent(load("blocking_removed"));
        assertThat(blockerNumbers()).isEmpty();
    }

    @Test
    void shouldNotStoreABlockerFromAnotherRepositoryUnderTheDeliveringOne() throws IOException {
        ObjectNode payload = (ObjectNode) objectMapper.readTree(fixture("blocked_by_added"));
        ((ObjectNode) payload.required("blocking_issue_repo")).put("full_name", "HephaestusTest/Unsynchronized");

        handler.handleEvent(objectMapper.treeToValue(payload, GitHubIssueDependenciesEventDTO.class));

        assertThat(issueRepository.findByRepositoryIdAndNumber(repository.getId(), BLOCKING_NUMBER))
                .isEmpty();
        assertThat(blockerNumbers()).isEmpty();
    }

    private List<Integer> blockerNumbers() {
        Issue blocked = issueRepository
                .findByRepositoryIdAndNumber(repository.getId(), BLOCKED_NUMBER)
                .flatMap(issue -> issueRepository.findByIdWithBlockedBy(issue.getId()))
                .orElseThrow();
        return blocked.getBlockedBy().stream().map(Issue::getNumber).toList();
    }

    private GitHubIssueDependenciesEventDTO load(String action) throws IOException {
        return objectMapper.readValue(fixture(action), GitHubIssueDependenciesEventDTO.class);
    }

    private static String fixture(String action) throws IOException {
        return new ClassPathResource("github/issue_dependencies." + action + ".json")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}

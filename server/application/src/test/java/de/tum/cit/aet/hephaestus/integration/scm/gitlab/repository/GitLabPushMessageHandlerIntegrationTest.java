package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class GitLabPushMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitLabPushMessageHandler handler;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private CommitRepository commits;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private GitLabProjectAdmission admission;

    @Test
    void shouldProcessPushWhenExistingOrganizationIsLazyOutsidePreparationTransaction() throws Exception {
        databaseTestUtils.cleanDatabase();
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")
                .orElseGet(() ->
                        providers.save(new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")));
        var organization = new Organization();
        organization.setNativeId(1L);
        organization.setLogin("hephaestustest");
        organization.setName("HephaestusTest");
        organization.setAvatarUrl("");
        organization.setHtmlUrl("https://gitlab.lrz.de/hephaestustest");
        organization.setCreatedAt(Instant.now());
        organization.setUpdatedAt(Instant.now());
        organization.setProvider(provider);
        organization = organizations.save(organization);

        var repository = new Repository();
        repository.setNativeId(246765L);
        repository.setName("demo-repository");
        repository.setNameWithOwner("hephaestustest/demo-repository");
        repository.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository");
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository.setOrganization(organization);
        repository.setProvider(provider);
        Repository saved = repositories.save(repository);
        Long repositoryId = saved.getId();
        var workspace = new Workspace();
        workspace.setWorkspaceSlug("push-fixture");
        workspace.setDisplayName("Push fixture");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setAccountLogin("hephaestustest");
        workspace.setAccountType(AccountType.ORG);
        workspace = workspaces.saveAndFlush(workspace);
        var connection = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                "push-fixture",
                new ConnectionConfig.GitLabConfig(provider.getServerUrl(), null, null, Set.of(), null));
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        var monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        monitor.setNativeId(repository.getNativeId());
        monitors.saveAndFlush(monitor);

        GitLabPushEventDTO event;
        try (var input = new ClassPathResource("gitlab/push.json").getInputStream()) {
            event = objectMapper.readValue(input, GitLabPushEventDTO.class);
        }
        GitLabPushEventDTO push = event;
        new GitLabRouteTestFixtures.Deliveries(
                        connections, connectionService, admission, objectMapper, transactions, provider.getServerUrl())
                .handle(workspace, saved, push, () -> handler.handleEvent(push));

        transactions.executeWithoutResult(status -> {
            var initial = commits.findByShaAndRepositoryId("9c5dedd52046bb5213189afc25f75e608a98d462", repositoryId)
                    .orElseThrow();
            assertThat(initial.getMessage()).isEqualTo("Initial commit");
            assertThat(initial.getChangedFiles()).isEqualTo(1);
            assertThat(initial.getFileChanges()).singleElement().satisfies(change -> {
                assertThat(change.getFilename()).isEqualTo("README.md");
                assertThat(change.getChangeType()).isEqualTo(CommitFileChange.ChangeType.ADDED);
            });
        });
        assertThat(commits.findByShaAndRepositoryId("a4bf10d93a2d136f1db911b6f1c03d26d835a44f", repositoryId))
                .isPresent();
    }
}

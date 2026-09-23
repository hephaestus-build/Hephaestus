package de.tum.cit.aet.hephaestus.integration.scm.github.check;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.check.dto.GitHubCheckSuiteEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.check.dto.GitHubStatusEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks GitHub check-suite and commit-status payloads through persisted head-check state.
 */
class GitHubCheckEventsIntegrationTest extends BaseIntegrationTest {

    private static final String CHECKED_SHA = "67700adac0c0f02df77fdf1bf037273e7b056aa9";
    private static final String OTHER_SHA = "1".repeat(40);

    @Autowired
    private GitHubCheckSuiteMessageHandler checkSuiteHandler;

    @Autowired
    private GitHubStatusMessageHandler statusHandler;

    @Autowired
    private PullRequestRepository pullRequestRepository;

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
    private Repository repository;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Organization org = new Organization();
        org.setNativeId(215361191L);
        org.setLogin("HephaestusTest");
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/215361191?v=4");
        org.setProvider(gitProvider);
        org = organizationRepository.save(org);
        repository = new Repository();
        repository.setNativeId(1087937297L);
        repository.setName("payload-fixture-repo-renamed");
        repository.setNameWithOwner("HephaestusTest/payload-fixture-repo-renamed");
        repository.setHtmlUrl("https://github.com/HephaestusTest/payload-fixture-repo-renamed");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository.setOrganization(org);
        repository.setProvider(gitProvider);
        repository = repositoryRepository.save(repository);
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

    @Test
    void shouldReturnTheEventKeys() {
        assertThat(checkSuiteHandler.key().eventType()).isEqualTo("repository.check_suite");
        assertThat(statusHandler.key().eventType()).isEqualTo("repository.status");
    }

    @Test
    void shouldFailTheHeadOfEveryPullRequestOnTheCommitWhenASuiteFails() throws Exception {
        PullRequest onHead = persistPullRequest(9, CHECKED_SHA);
        PullRequest elsewhere = persistPullRequest(10, OTHER_SHA);

        checkSuiteHandler.handleEvent(load("check_suite.completed", GitHubCheckSuiteEventDTO.class));

        PullRequest checked = pullRequestRepository.findById(onHead.getId()).orElseThrow();
        assertThat(checked.getHeadCheckState()).isEqualTo(CheckState.FAILURE);
        assertThat(checked.getHeadCheckSha()).isEqualTo(CHECKED_SHA);
        PullRequest untouched =
                pullRequestRepository.findById(elsewhere.getId()).orElseThrow();
        assertThat(untouched.getHeadCheckState()).isNull();
    }

    @Test
    void shouldRecordACommitStatusWithoutUndoingAFailedSuite() throws Exception {
        PullRequest pr = persistPullRequest(9, CHECKED_SHA);

        statusHandler.handleEvent(load("status", GitHubStatusEventDTO.class));
        assertThat(pullRequestRepository.findById(pr.getId()).orElseThrow().getHeadCheckState())
                .isEqualTo(CheckState.SUCCESS);

        checkSuiteHandler.handleEvent(load("check_suite.completed", GitHubCheckSuiteEventDTO.class));
        statusHandler.handleEvent(load("status", GitHubStatusEventDTO.class));

        assertThat(pullRequestRepository.findById(pr.getId()).orElseThrow().getHeadCheckState())
                .isEqualTo(CheckState.FAILURE);
    }

    private PullRequest persistPullRequest(int number, String headSha) {
        PullRequest pr = new PullRequest();
        pr.setNativeId(2_871_001_000L + number);
        pr.setNumber(number);
        pr.setTitle("PR #" + number);
        pr.setState(PullRequest.State.OPEN);
        pr.setRepository(repository);
        pr.setHeadRefOid(headSha);
        pr.setCreatedAt(Instant.now());
        pr.setUpdatedAt(Instant.now());
        pr.setProvider(gitProvider);
        return pullRequestRepository.save(pr);
    }

    private <T> T load(String fixture, Class<T> type) throws IOException {
        String json = new ClassPathResource("github/" + fixture + ".json").getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, type);
    }
}

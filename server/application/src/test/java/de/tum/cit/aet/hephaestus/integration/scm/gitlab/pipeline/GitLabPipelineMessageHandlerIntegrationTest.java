package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pipeline;

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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pipeline.dto.GitLabPipelineEventDTO;
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
 * Checks GitLab pipeline payloads through persisted merge-request head-check state.
 */
class GitLabPipelineMessageHandlerIntegrationTest extends BaseIntegrationTest {

    private static final String PIPELINE_SHA = "bcbb5ec396a2c0f828686f14fac9b80b780504f2";

    @Autowired
    private GitLabPipelineMessageHandler handler;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private IdentityProvider provider;
    private Repository repository;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")));
        Organization org = new Organization();
        org.setNativeId(1L);
        org.setLogin("hephaestustest");
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("HephaestusTest");
        org.setAvatarUrl("");
        org.setHtmlUrl("https://gitlab.lrz.de/hephaestustest");
        org.setProvider(provider);
        org = organizationRepository.save(org);
        repository = new Repository();
        repository.setNativeId(246765L);
        repository.setName("demo-repository");
        repository.setNameWithOwner("hephaestustest/demo-repository");
        repository.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository");
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository.setOrganization(org);
        repository.setProvider(provider);
        repository = repositoryRepository.save(repository);
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test-gitlab");
        workspace.setDisplayName("HephaestusTest GitLab");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setIsPubliclyViewable(true);
        workspace.setOrganization(org);
        workspace.setAccountLogin("hephaestustest");
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    @Test
    void shouldReturnTheEventKey() {
        assertThat(handler.key().eventType()).isEqualTo("pipeline");
    }

    @Test
    void shouldRecordTheFailedPipelineOnTheMergeRequestItNames() throws Exception {
        PullRequest mr = persistMergeRequest(11);

        handler.handleEvent(load("pipeline"));

        PullRequest checked = pullRequestRepository.findById(mr.getId()).orElseThrow();
        assertThat(checked.getHeadCheckState()).isEqualTo(CheckState.FAILURE);
        assertThat(checked.getHeadCheckSha()).isEqualTo(PIPELINE_SHA);
    }

    private PullRequest persistMergeRequest(int iid) {
        PullRequest pr = new PullRequest();
        pr.setNativeId(343218L);
        pr.setNumber(iid);
        pr.setTitle("Add fixture feature");
        pr.setState(PullRequest.State.OPEN);
        pr.setRepository(repository);
        pr.setHeadRefOid(PIPELINE_SHA);
        pr.setCreatedAt(Instant.now());
        pr.setUpdatedAt(Instant.now());
        pr.setProvider(provider);
        return pullRequestRepository.save(pr);
    }

    private GitLabPipelineEventDTO load(String fixture) throws IOException {
        String json = new ClassPathResource("gitlab/" + fixture + ".json").getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitLabPipelineEventDTO.class);
    }
}

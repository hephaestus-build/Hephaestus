package de.tum.cit.aet.hephaestus.integration.scm.gitlab.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.label.dto.GitLabLabelDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GitLabLabelProcessorIntegrationTest extends BaseIntegrationTest {

    private static final String FIXTURE_ORG_LOGIN = "hephaestustest";
    private static final String FIXTURE_REPO_FULL_NAME = "hephaestustest/demo-repository";

    @Autowired
    private GitLabLabelProcessor labelProcessor;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private RecordingScmEventListener eventListener;

    private Repository testRepository;
    private Workspace testWorkspace;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    private ProcessingContext testContext() {
        return ProcessingContext.forSync(testWorkspace.getId(), testRepository);
    }

    @Nested
    class ProcessMethod {

        @Test
        void shouldCreateNewLabel() {
            GitLabLabelDTO dto = new GitLabLabelDTO(
                    "gid://gitlab/ProjectLabel/123",
                    "bug",
                    "#FF0000",
                    "Something isn't working",
                    "2026-01-15T10:00:00Z",
                    "2026-01-15T10:00:00Z");

            Label result = labelProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("bug");
            assertThat(result.getColor()).isEqualTo("#FF0000");
            assertThat(result.getDescription()).isEqualTo("Something isn't working");
            assertThat(result.getCreatedAt()).isNotNull();
            assertThat(result.getUpdatedAt()).isNotNull();
            assertThat(result.getLastSyncAt()).isNotNull();
            assertThat(result.getNativeId()).isNegative(); // deterministic ID

            assertThat(eventListener.ofType(ScmDomainEvent.LabelCreated.class)).hasSize(1);
        }

        @Test
        void shouldMapTitleToName() {
            GitLabLabelDTO dto = new GitLabLabelDTO(null, "enhancement", "#428BCA", null, null, null);

            Label result = labelProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("enhancement");
        }

        @Test
        void shouldUpdateExistingLabel() {
            // Create
            GitLabLabelDTO createDto = new GitLabLabelDTO(null, "bug", "#FF0000", "Old description", null, null);
            labelProcessor.process(createDto, testRepository, testContext());
            eventListener.clear();

            // Update
            GitLabLabelDTO updateDto = new GitLabLabelDTO(null, "bug", "#00FF00", "New description", null, null);
            Label result = labelProcessor.process(updateDto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getColor()).isEqualTo("#00FF00");
            assertThat(result.getDescription()).isEqualTo("New description");

            assertThat(eventListener.ofType(ScmDomainEvent.LabelUpdated.class)).hasSize(1);
            assertThat(labelRepository.findAllByRepository_Id(testRepository.getId()))
                    .hasSize(1);
        }

        @Test
        @DisplayName("is idempotent — processing same DTO twice produces one label")
        void shouldBeIdempotent() {
            GitLabLabelDTO dto = new GitLabLabelDTO(null, "bug", "#FF0000", null, null, null);

            labelProcessor.process(dto, testRepository, testContext());
            labelProcessor.process(dto, testRepository, testContext());

            assertThat(labelRepository.findAllByRepository_Id(testRepository.getId()))
                    .hasSize(1);
        }

        @Test
        void shouldHandleNullDescription() {
            GitLabLabelDTO dto = new GitLabLabelDTO(null, "no-desc", "#CCCCCC", null, null, null);

            Label result = labelProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getDescription()).isNull();
        }

        @Test
        void shouldReturnNullForNullDto() {
            Label result = labelProcessor.process(null, testRepository, testContext());
            assertThat(result).isNull();
        }

        @Test
        void shouldReturnNullForBlankTitle() {
            GitLabLabelDTO dto = new GitLabLabelDTO(null, "  ", "#FF0000", null, null, null);
            Label result = labelProcessor.process(dto, testRepository, testContext());
            assertThat(result).isNull();
        }

        @Test
        void shouldDeduplicateGroupLabels() {
            // Same label name from different sources (project vs group)
            GitLabLabelDTO projectLabel =
                    new GitLabLabelDTO("gid://gitlab/ProjectLabel/1", "shared", "#FF0000", null, null, null);
            GitLabLabelDTO groupLabel =
                    new GitLabLabelDTO("gid://gitlab/GroupLabel/999", "shared", "#FF0000", null, null, null);

            labelProcessor.process(projectLabel, testRepository, testContext());
            labelProcessor.process(groupLabel, testRepository, testContext());

            assertThat(labelRepository.findAllByRepository_Id(testRepository.getId()))
                    .hasSize(1);
        }
    }

    @Nested
    class DeleteMethod {

        @Test
        @DisplayName("deletes label and publishes event")
        void shouldDeleteLabel() {
            GitLabLabelDTO dto = new GitLabLabelDTO(null, "to-delete", "#FF0000", null, null, null);
            Label label = labelProcessor.process(dto, testRepository, testContext());
            eventListener.clear();

            assertNotNull(label);
            labelProcessor.delete(label.getId(), testContext());

            assertThat(labelRepository.findById(label.getId())).isEmpty();
            assertThat(eventListener.ofType(ScmDomainEvent.LabelDeleted.class)).hasSize(1);
        }

        @Test
        void shouldHandleNullId() {
            labelProcessor.delete(null, testContext());
            // No exception thrown
        }

        @Test
        void shouldHandleNonExistentLabel() {
            labelProcessor.delete(-999L, testContext());
            assertThat(eventListener.ofType(ScmDomainEvent.LabelDeleted.class)).isEmpty();
        }
    }

    // Test Data Setup

    private void setupTestData() {
        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")));

        Organization org = new Organization();
        org.setNativeId(1L);
        org.setLogin(FIXTURE_ORG_LOGIN);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("HephaestusTest");
        org.setAvatarUrl("");
        org.setHtmlUrl("https://gitlab.lrz.de/hephaestustest");
        org.setProvider(provider);
        org = organizationRepository.save(org);

        testRepository = new Repository();
        testRepository.setNativeId(246765L);
        testRepository.setName("demo-repository");
        testRepository.setNameWithOwner(FIXTURE_REPO_FULL_NAME);
        testRepository.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository");
        testRepository.setVisibility(Repository.Visibility.PRIVATE);
        testRepository.setDefaultBranch("main");
        testRepository.setCreatedAt(Instant.now());
        testRepository.setUpdatedAt(Instant.now());
        testRepository.setPushedAt(Instant.now());
        testRepository.setOrganization(org);
        testRepository.setProvider(provider);
        testRepository = repositoryRepository.save(testRepository);

        testWorkspace = new Workspace();
        testWorkspace.setWorkspaceSlug("hephaestus-test-gitlab");
        testWorkspace.setDisplayName("HephaestusTest GitLab");
        testWorkspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        testWorkspace.setPublicActivityEnabled(true);
        testWorkspace.setOrganization(org);
        testWorkspace.setAccountLogin(FIXTURE_ORG_LOGIN);
        testWorkspace.setAccountType(AccountType.ORG);
        testWorkspace = workspaceRepository.save(testWorkspace);
    }
}

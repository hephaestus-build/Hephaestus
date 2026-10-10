package de.tum.cit.aet.hephaestus.integration.scm.github.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
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

class GitHubLabelProcessorIntegrationTest extends BaseIntegrationTest {

    private static final Long TEST_ORG_ID = 215361191L;
    private static final Long TEST_REPO_ID = 998279771L;
    private static final String TEST_ORG_LOGIN = "HephaestusTest";
    private static final String TEST_REPO_FULL_NAME = "HephaestusTest/TestRepository";

    @Autowired
    private GitHubLabelProcessor processor;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private RecordingScmEventListener eventListener;

    private Repository testRepository;
    private Workspace testWorkspace;
    private IdentityProvider gitProvider;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    private void setupTestData() {
        // Create GitHub provider
        gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create organization
        Organization org = new Organization();
        org.setNativeId(TEST_ORG_ID);
        org.setLogin(TEST_ORG_LOGIN);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/" + TEST_ORG_ID);
        org.setHtmlUrl("https://github.com/" + TEST_ORG_LOGIN);
        org.setProvider(gitProvider);
        org = organizationRepository.save(org);

        // Create repository
        testRepository = new Repository();
        testRepository.setNativeId(TEST_REPO_ID);
        testRepository.setName("TestRepository");
        testRepository.setNameWithOwner(TEST_REPO_FULL_NAME);
        testRepository.setHtmlUrl("https://github.com/" + TEST_REPO_FULL_NAME);
        testRepository.setVisibility(Repository.Visibility.PUBLIC);
        testRepository.setDefaultBranch("main");
        testRepository.setCreatedAt(Instant.now());
        testRepository.setUpdatedAt(Instant.now());
        testRepository.setPushedAt(Instant.now());
        testRepository.setOrganization(org);
        testRepository.setProvider(gitProvider);
        testRepository = repositoryRepository.save(testRepository);

        // Create workspace
        testWorkspace = new Workspace();
        testWorkspace.setWorkspaceSlug("hephaestus-test");
        testWorkspace.setDisplayName("Hephaestus Test");
        testWorkspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        testWorkspace.setPublicActivityEnabled(true);
        testWorkspace.setOrganization(org);
        testWorkspace.setAccountLogin(TEST_ORG_LOGIN);
        testWorkspace.setAccountType(AccountType.ORG);
        testWorkspace = workspaceRepository.save(testWorkspace);
    }

    private ProcessingContext createContext() {
        return ProcessingContext.forSync(testWorkspace.getId(), testRepository);
    }

    // Process (Create/Update) Tests

    @Nested
    class ProcessMethod {

        @Test
        void shouldCreateNewLabelAndPublishEvent() {
            Long labelId = 111222333L;
            GitHubLabelDTO dto = new GitHubLabelDTO(
                    labelId, "LA_node123", "new-feature", "A new feature label", "00ff00", null, null);

            Label result = processor.process(dto, testRepository, createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(labelId);
            assertThat(result.getName()).isEqualTo("new-feature");
            assertThat(result.getColor()).isEqualTo("00ff00");
            assertThat(result.getDescription()).isEqualTo("A new feature label");
            assertThat(result.getRepository().getId()).isEqualTo(testRepository.getId());

            // Verify persisted
            assertThat(labelRepository.findByNativeIdAndProviderId(labelId, gitProviderId()))
                    .isPresent();

            // Verify LabelCreated event published
            assertThat(eventListener.ofType(ScmDomainEvent.LabelCreated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.label().id()).isEqualTo(result.getId());
                        assertThat(event.context().scopeId()).isEqualTo(testWorkspace.getId());
                        assertNotNull(event.context().repository());
                        assertThat(event.context().repository().id()).isEqualTo(testRepository.getId());
                    });
            assertThat(eventListener.ofType(ScmDomainEvent.LabelUpdated.class)).isEmpty();
        }

        @Test
        void shouldUpdateExistingLabelAndPublishEvent() {
            Long labelId = 444555666L;
            Label existing = new Label();
            existing.setNativeId(labelId);
            existing.setProvider(gitProvider);
            existing.setName("old-name");
            existing.setColor("ffffff");
            existing.setDescription("Old description");
            existing.setRepository(testRepository);
            labelRepository.save(existing);

            eventListener.clear();

            GitHubLabelDTO dto = new GitHubLabelDTO(
                    labelId, "LA_node456", "updated-name", "Updated description", "ff0000", null, null);

            Label result = processor.process(dto, testRepository, createContext());
            assertNotNull(result);

            assertThat(result.getName()).isEqualTo("updated-name");
            assertThat(result.getColor()).isEqualTo("ff0000");
            assertThat(result.getDescription()).isEqualTo("Updated description");

            // Verify LabelUpdated event published
            assertThat(eventListener.ofType(ScmDomainEvent.LabelUpdated.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.label().name()).isEqualTo("updated-name");
                    });
            assertThat(eventListener.ofType(ScmDomainEvent.LabelCreated.class)).isEmpty();
        }

        @Test
        void shouldReturnNullForNullDto() {
            Label result = processor.process(null, testRepository, createContext());

            assertThat(result).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.LabelCreated.class)).isEmpty();
            assertThat(eventListener.ofType(ScmDomainEvent.LabelUpdated.class)).isEmpty();
        }

        @Test
        void shouldCreateLabelWithGeneratedIdWhenDtoHasNullId() {
            // A GraphQL sync DTO carries no ID.
            GitHubLabelDTO dto = new GitHubLabelDTO(
                    null, // null ID - simulates GraphQL response
                    "LA_node",
                    "graphql-synced-label",
                    "desc",
                    "000000",
                    null,
                    null);

            Label result = processor.process(dto, testRepository, createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isNotNull();
            assertThat(result.getNativeId()).isNegative(); // Generated IDs are negative to avoid collision
            assertThat(result.getName()).isEqualTo("graphql-synced-label");
            assertThat(eventListener.ofType(ScmDomainEvent.LabelCreated.class)).hasSize(1);
        }

        @Test
        void shouldUpdateExistingLabelByNameWhenDtoHasNullId() {
            Long existingId = 999888777L;
            Label existingLabel = new Label();
            existingLabel.setNativeId(existingId);
            existingLabel.setProvider(gitProvider);
            existingLabel.setName("existing-label");
            existingLabel.setColor("111111");
            existingLabel.setDescription("old description");
            existingLabel.setRepository(testRepository);
            labelRepository.save(existingLabel);

            // DTO without ID (like GraphQL sync) but matching name
            GitHubLabelDTO dto = new GitHubLabelDTO(
                    null, // null ID
                    "LA_node",
                    "existing-label", // same name
                    "new description",
                    "222222",
                    null,
                    null);

            Label result = processor.process(dto, testRepository, createContext());

            assertNotNull(result);
            assertThat(result.getNativeId()).isEqualTo(existingId); // keeps original native ID
            assertThat(result.getDescription()).isEqualTo("new description");
            assertThat(result.getColor()).isEqualTo("222222");
            assertThat(labelRepository.count()).isEqualTo(1);
        }

        @Test
        void shouldHandleLabelWithNullDescription() {
            Long labelId = 777888999L;
            GitHubLabelDTO dto = new GitHubLabelDTO(
                    labelId,
                    "LA_node789",
                    "no-desc-label",
                    null, // null description
                    "abcdef",
                    null,
                    null);

            Label result = processor.process(dto, testRepository, createContext());
            assertNotNull(result);

            assertThat(result.getDescription()).isNull();
            assertThat(labelRepository
                            .findByNativeIdAndProviderId(labelId, gitProviderId())
                            .get()
                            .getDescription())
                    .isNull();
        }

        @Test
        @DisplayName("Should be idempotent - processing same DTO twice")
        void shouldBeIdempotent() {
            Long labelId = 123123123L;
            GitHubLabelDTO dto =
                    new GitHubLabelDTO(labelId, "LA_idem", "idempotent-label", "Same every time", "112233", null, null);

            processor.process(dto, testRepository, createContext());
            eventListener.clear();
            processor.process(dto, testRepository, createContext());

            assertThat(labelRepository.count()).isEqualTo(1);
            assertThat(eventListener.ofType(ScmDomainEvent.LabelUpdated.class)).hasSize(1);
            assertThat(eventListener.ofType(ScmDomainEvent.LabelCreated.class)).isEmpty();
        }
    }

    // Delete Tests

    @Nested
    class DeleteMethod {

        @Test
        void shouldDeleteLabelAndPublishEvent() {
            Long labelId = 555666777L;
            Label label = new Label();
            label.setNativeId(labelId);
            label.setProvider(gitProvider);
            label.setName("to-delete");
            label.setColor("ff0000");
            label.setRepository(testRepository);
            label = labelRepository.save(label);

            assertThat(labelRepository.findByNativeIdAndProviderId(labelId, gitProviderId()))
                    .isPresent();
            Long savedLabelId = label.getId();

            processor.delete(savedLabelId, createContext());

            assertThat(labelRepository.findByNativeIdAndProviderId(labelId, gitProviderId()))
                    .isEmpty();

            // Verify event published
            assertThat(eventListener.ofType(ScmDomainEvent.LabelDeleted.class))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.labelId()).isEqualTo(savedLabelId);
                        assertThat(event.labelName()).isEqualTo("to-delete");
                        assertThat(event.context().scopeId()).isEqualTo(testWorkspace.getId());
                        assertNotNull(event.context().repository());
                        assertThat(event.context().repository().id()).isEqualTo(testRepository.getId());
                    });
        }

        @Test
        void shouldHandleDeletionOfNonExistentLabel() {
            Long nonExistentId = 999999999L;
            assertThat(labelRepository.findByNativeIdAndProviderId(nonExistentId, gitProviderId()))
                    .isEmpty();

            assertThatCode(() -> processor.delete(nonExistentId, createContext()))
                    .doesNotThrowAnyException();

            // No event published
            assertThat(eventListener.ofType(ScmDomainEvent.LabelDeleted.class)).isEmpty();
        }

        @Test
        void shouldHandleNullLabelId() {
            assertThatCode(() -> processor.delete(null, createContext())).doesNotThrowAnyException();

            // No event published
            assertThat(eventListener.ofType(ScmDomainEvent.LabelDeleted.class)).isEmpty();
        }
    }

    private Long gitProviderId() {
        Long id = gitProvider.getId();
        assertNotNull(id);
        return id;
    }
}

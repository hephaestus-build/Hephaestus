package de.tum.cit.aet.hephaestus.integration.scm.github.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.tenancy.WorkspaceScopedTables;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * <b>Fixture Values (label.created.json):</b>
 * <ul>
 *   <li>Label ID: 8747399111</li>
 *   <li>Label name: "documentation"</li>
 *   <li>Label color: "0075ca"</li>
 *   <li>Label description: "Improvements or additions to documentation"</li>
 * </ul>
 * <p>
 * Note: This test class does NOT use @Transactional because the GitHubLabelProcessor
 * uses REQUIRES_NEW propagation. Having @Transactional here would cause deadlocks
 * as the test transaction would hold locks needed by the processor's new transaction.
 */
class GitHubLabelMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // IDs from the actual GitHub webhook fixtures
    private static final Long FIXTURE_ORG_ID = 215361191L;
    private static final Long FIXTURE_REPO_ID = 998279771L;
    private static final String FIXTURE_ORG_LOGIN = "HephaestusTest";
    private static final String FIXTURE_REPO_NAME = "TestRepository";
    private static final String FIXTURE_REPO_FULL_NAME = "HephaestusTest/TestRepository";

    // Exact fixture values from label.created.json for correctness verification
    private static final Long FIXTURE_LABEL_ID = 8747399111L;
    private static final String FIXTURE_LABEL_NAME = "documentation";
    private static final String FIXTURE_LABEL_COLOR = "0075ca";
    private static final String FIXTURE_LABEL_DESCRIPTION = "Improvements or additions to documentation";

    @Autowired
    private GitHubLabelMessageHandler handler;

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
    private IssueRepository issueRepository;

    @Autowired
    private WorkspaceScopedTables workspaceScopedTables;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Repository testRepository;
    private IdentityProvider gitProvider;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setupTestData();
    }

    private void setupTestData() {
        // Create GitHub provider
        gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        // Create organization matching fixture data
        Organization org = new Organization();
        org.setNativeId(FIXTURE_ORG_ID);
        org.setLogin(FIXTURE_ORG_LOGIN);
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setName("Hephaestus Test");
        org.setAvatarUrl("https://avatars.githubusercontent.com/u/" + FIXTURE_ORG_ID + "?v=4");
        org.setHtmlUrl("https://github.com/" + FIXTURE_ORG_LOGIN);
        org.setProvider(gitProvider);
        org = organizationRepository.save(org);

        // Create repository matching fixture data
        testRepository = new Repository();
        testRepository.setNativeId(FIXTURE_REPO_ID);
        testRepository.setName(FIXTURE_REPO_NAME);
        testRepository.setNameWithOwner(FIXTURE_REPO_FULL_NAME);
        testRepository.setHtmlUrl("https://github.com/" + FIXTURE_REPO_FULL_NAME);
        testRepository.setVisibility(Repository.Visibility.PUBLIC);
        testRepository.setDefaultBranch("main");
        testRepository.setCreatedAt(Instant.now());
        testRepository.setUpdatedAt(Instant.now());
        testRepository.setPushedAt(Instant.now());
        testRepository.setOrganization(org);
        testRepository.setProvider(gitProvider);
        testRepository = repositoryRepository.save(testRepository);

        // Create workspace
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test");
        workspace.setDisplayName("Hephaestus Test");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setPublicActivityEnabled(true);
        workspace.setOrganization(org);
        workspace.setAccountLogin(FIXTURE_ORG_LOGIN);
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }

    @Test
    void shouldReturnCorrectEventType() {
        assertThat(handler.key().eventType()).isEqualTo("repository.label");
    }

    @Test
    void shouldProcessCreatedLabelEvents() throws Exception {
        GitHubLabelEventDTO event = loadPayload("label.created");
        assertThat(labelRepository.findByNativeIdAndProviderId(FIXTURE_LABEL_ID, providerId()))
                .isEmpty();

        handler.handleEvent(event);

        Label label = labelRepository
                .findByNativeIdAndProviderId(FIXTURE_LABEL_ID, providerId())
                .orElseThrow();

        // Core schema fields (mapped to DB columns)
        assertThat(label.getNativeId()).isEqualTo(FIXTURE_LABEL_ID);
        assertThat(label.getName()).isEqualTo(FIXTURE_LABEL_NAME);
        assertThat(label.getColor()).isEqualTo(FIXTURE_LABEL_COLOR);
        assertThat(label.getDescription()).isEqualTo(FIXTURE_LABEL_DESCRIPTION);

        // Repository association (foreign key)
        assertThat(label.getRepository()).isNotNull();
        assertThat(label.getRepository().getId()).isEqualTo(testRepository.getId());

        // Note: createdAt/updatedAt are not provided in webhook payloads (only in GraphQL)
        // Note: lastSyncAt is ETL infrastructure, not set by webhook handler
    }

    @Test
    void shouldProcessEditedLabelEvents() throws Exception {
        GitHubLabelEventDTO event = loadPayload("label.edited");
        GitHubLabelDTO eventLabel = event.label();
        assertNotNull(eventLabel);
        Long labelId = Objects.requireNonNull(eventLabel.id());

        Label existingLabel = new Label();
        existingLabel.setNativeId(labelId);
        existingLabel.setProvider(gitProvider);
        existingLabel.setName("stale-name");
        existingLabel.setColor("ffffff");
        existingLabel.setDescription("stale description");
        existingLabel.setRepository(testRepository);
        labelRepository.save(existingLabel);

        handler.handleEvent(event);

        Label label = labelRepository
                .findByNativeIdAndProviderId(labelId, providerId())
                .orElseThrow();
        assertThat(label.getName()).isEqualTo(eventLabel.name());
        assertThat(label.getColor()).isEqualTo(eventLabel.color());
        assertThat(label.getDescription()).isEqualTo(eventLabel.description());

        // Verify repository association preserved (not overwritten)
        assertThat(label.getRepository().getId()).isEqualTo(testRepository.getId());
    }

    @Test
    void shouldProcessDeletedLabelEvents() throws Exception {
        GitHubLabelEventDTO event = loadPayload("label.deleted");
        GitHubLabelDTO eventLabel = event.label();
        assertNotNull(eventLabel);
        Long labelId = Objects.requireNonNull(eventLabel.id());

        // Create existing label
        Label existingLabel = new Label();
        existingLabel.setNativeId(labelId);
        existingLabel.setProvider(gitProvider);
        existingLabel.setName(eventLabel.name());
        existingLabel.setColor(eventLabel.color());
        existingLabel.setRepository(testRepository);
        labelRepository.save(existingLabel);

        // Verify it exists
        assertThat(labelRepository.findByNativeIdAndProviderId(labelId, providerId()))
                .isPresent();

        handler.handleEvent(event);

        assertThat(labelRepository.findByNativeIdAndProviderId(labelId, providerId()))
                .isEmpty();
    }

    private GitHubLabelEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("github/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitHubLabelEventDTO.class);
    }

    /**
     * Creates a test repository reference DTO matching the fixture data.
     */
    private GitHubRepositoryRefDTO createTestRepoRef() {
        return new GitHubRepositoryRefDTO(
                FIXTURE_REPO_ID,
                "R_test",
                FIXTURE_REPO_NAME,
                FIXTURE_REPO_FULL_NAME,
                false,
                "https://github.com/" + FIXTURE_REPO_FULL_NAME,
                null);
    }

    // Edge Case Tests

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleNullLabelGracefully() {
            GitHubLabelEventDTO event = new GitHubLabelEventDTO("created", null, createTestRepoRef(), null);

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();

            // No labels should be created
            assertThat(labelRepository.count()).isZero();
        }

        @Test
        void shouldHandleMissingRepositoryContext() {
            GitHubLabelDTO labelDto =
                    new GitHubLabelDTO(999999L, "LA_test", "test-label", "Test description", "ff0000", null, null);
            GitHubLabelEventDTO event = new GitHubLabelEventDTO("created", labelDto, null, null);

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
        }

        @Test
        void shouldHandleLabelWithNullDescription() throws Exception {
            Long labelId = 123456789L;
            GitHubLabelDTO labelDto = new GitHubLabelDTO(
                    labelId,
                    "LA_nodeId",
                    "no-description-label",
                    null, // null description
                    "abcdef",
                    null,
                    null);
            GitHubLabelEventDTO event = new GitHubLabelEventDTO("created", labelDto, createTestRepoRef(), null);

            handler.handleEvent(event);

            assertThat(labelRepository.findByNativeIdAndProviderId(labelId, providerId()))
                    .isPresent()
                    .get()
                    .satisfies(label -> {
                        assertThat(label.getName()).isEqualTo("no-description-label");
                        assertThat(label.getDescription()).isNull();
                    });
        }

        @Test
        void shouldUpdateDescriptionToNull() throws Exception {
            Long labelId = 987654321L;
            Label existingLabel = new Label();
            existingLabel.setNativeId(labelId);
            existingLabel.setProvider(gitProvider);
            existingLabel.setName("has-description");
            existingLabel.setColor("123456");
            existingLabel.setDescription("original description");
            existingLabel.setRepository(testRepository);
            labelRepository.save(existingLabel);

            GitHubLabelDTO labelDto = new GitHubLabelDTO(
                    labelId,
                    "LA_nodeId",
                    "has-description",
                    null, // setting to null
                    "123456",
                    null,
                    null);
            GitHubLabelEventDTO event = new GitHubLabelEventDTO("edited", labelDto, createTestRepoRef(), null);
            handler.handleEvent(event);

            assertThat(labelRepository.findByNativeIdAndProviderId(labelId, providerId()))
                    .isPresent()
                    .get()
                    .extracting(Label::getDescription)
                    .isNull();
        }

        @Test
        void shouldHandleIdempotentCreation() throws Exception {
            GitHubLabelEventDTO event = loadPayload("label.created");

            handler.handleEvent(event);
            handler.handleEvent(event);

            assertThat(labelRepository.findByNativeIdAndProviderId(FIXTURE_LABEL_ID, providerId()))
                    .isPresent();
            assertThat(labelRepository.count()).isEqualTo(1);
        }

        @Test
        void shouldHandleDeletionOfNonExistentLabel() throws Exception {
            GitHubLabelEventDTO event = loadPayload("label.deleted");
            assertThat(labelRepository.findByNativeIdAndProviderId(FIXTURE_LABEL_ID, providerId()))
                    .isEmpty();

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
        }

        @Test
        void shouldHandleUnknownAction() {
            GitHubLabelDTO labelDto =
                    new GitHubLabelDTO(111222333L, "LA_unknown", "unknown-action-label", "desc", "000000", null, null);
            GitHubLabelEventDTO event = new GitHubLabelEventDTO(
                    "unknown_action", // not created/edited/deleted
                    labelDto,
                    createTestRepoRef(),
                    null);

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
        }
    }

    // Repository Association Tests

    @Nested
    class RepositoryAssociation {

        @Test
        void shouldAssociateLabelWithRepository() throws Exception {
            GitHubLabelEventDTO event = loadPayload("label.created");

            handler.handleEvent(event);

            transactionTemplate.executeWithoutResult(status -> {
                assertThat(labelRepository.findByNativeIdAndProviderId(FIXTURE_LABEL_ID, providerId()))
                        .isPresent()
                        .get()
                        .satisfies(label -> {
                            assertThat(label.getRepository()).isNotNull();
                            assertThat(label.getRepository().getId()).isEqualTo(testRepository.getId());
                            assertThat(label.getRepository().getNameWithOwner()).isEqualTo(FIXTURE_REPO_FULL_NAME);
                        });
            });
        }

        @Test
        void shouldFindLabelByRepositoryAndName() throws Exception {
            GitHubLabelEventDTO event = loadPayload("label.created");
            handler.handleEvent(event);

            assertNotNull(event.label());
            var foundLabel = labelRepository.findByRepositoryIdAndName(
                    testRepository.getId(), event.label().name());

            assertThat(foundLabel).isPresent().get().satisfies(label -> {
                assertThat(label.getNativeId()).isEqualTo(FIXTURE_LABEL_ID);
                assertNotNull(event.label());
                assertThat(label.getName()).isEqualTo(event.label().name());
            });
        }
    }

    // Label-Issue Association Tests

    /**
     * Tests that verify label-issue relationships.
     * <p>
     * Note: This class does NOT use @Transactional because the handler uses REQUIRES_NEW.
     * We use TransactionTemplate for lazy loading assertions to avoid connection leaks.
     */
    @Nested
    class LabelIssueRelationship {

        @Test
        void shouldPreserveLabelIssueRelationshipsAfterEdit() throws Exception {
            GitHubLabelEventDTO createEvent = loadPayload("label.created");
            handler.handleEvent(createEvent);

            Label label = labelRepository
                    .findByNativeIdAndProviderId(FIXTURE_LABEL_ID, providerId())
                    .orElseThrow();

            Issue issue = new Issue();
            issue.setNativeId(12345L);
            issue.setNumber(1);
            issue.setTitle("Test Issue");
            issue.setState(Issue.State.OPEN);
            issue.setRepository(testRepository);
            issue.setCreatedAt(Instant.now());
            issue.setUpdatedAt(Instant.now());
            issue.setProvider(gitProvider);
            issue.getLabels().add(label);
            Issue savedIssue = issueRepository.save(issue);
            Long savedIssueId = savedIssue.getId();

            assertNotNull(createEvent.label());
            GitHubLabelDTO editedDto = new GitHubLabelDTO(
                    FIXTURE_LABEL_ID,
                    createEvent.label().nodeId(),
                    "renamed-documentation",
                    "Updated description",
                    "ff0000",
                    null,
                    null);
            GitHubLabelEventDTO editEvent = new GitHubLabelEventDTO("edited", editedDto, createTestRepoRef(), null);
            handler.handleEvent(editEvent);

            // Use TransactionTemplate for lazy loading assertions
            transactionTemplate.executeWithoutResult(status -> {
                Issue updatedIssue = issueRepository.findById(savedIssueId).orElseThrow();
                assertThat(updatedIssue.getLabels()).hasSize(1).first().satisfies(l -> {
                    assertThat(l.getName()).isEqualTo("renamed-documentation");
                    assertThat(l.getColor()).isEqualTo("ff0000");
                });
            });
        }
    }

    @Test
    void shouldRemoveALabelFromAnIssueUnderTenancyEnforcement() throws Exception {
        // Hibernate unlinks the label with "delete from issue_label where issue_id=? and
        // label_id=?", the one statement the inspector allows on a declared join table. This runs
        // against the real mapping metamodel and the enforcement mode the tests boot with.
        assertThat(workspaceScopedTables.isManyToManyJoinTable("issue_label")).isTrue();
        assertThat(workspaceScopedTables.isManyToManyJoinTable("issue")).isFalse();

        handler.handleEvent(loadPayload("label.created"));
        Label label = labelRepository
                .findByNativeIdAndProviderId(FIXTURE_LABEL_ID, providerId())
                .orElseThrow();
        // A second label stays on the issue: with the collection still non-empty after the
        // removal, Hibernate deletes the one row by both keys rather than clearing the issue's
        // rows by owner key, which the single-key path allowed all along.
        Label keptLabel = new Label();
        keptLabel.setNativeId(FIXTURE_LABEL_ID + 1);
        keptLabel.setProvider(gitProvider);
        keptLabel.setRepository(testRepository);
        keptLabel.setName("kept");
        keptLabel.setColor("00ff00");
        keptLabel.setCreatedAt(Instant.now());
        keptLabel.setUpdatedAt(Instant.now());
        keptLabel = labelRepository.save(keptLabel);

        Issue issue = new Issue();
        issue.setNativeId(67890L);
        issue.setNumber(2);
        issue.setTitle("Labelled, then less so");
        issue.setState(Issue.State.OPEN);
        issue.setRepository(testRepository);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        issue.setProvider(gitProvider);
        issue.getLabels().add(label);
        issue.getLabels().add(keptLabel);
        Long issueId = issueRepository.save(issue).getId();

        Long removedLabelId = label.getId();
        transactionTemplate.executeWithoutResult(status -> {
            Issue labelled = issueRepository.findById(issueId).orElseThrow();
            labelled.getLabels().removeIf(l -> removedLabelId.equals(l.getId()));
        });

        Long keptLabelId = keptLabel.getId();
        transactionTemplate.executeWithoutResult(status -> assertThat(
                        issueRepository.findById(issueId).orElseThrow().getLabels())
                .singleElement()
                .satisfies(l -> assertThat(l.getId()).isEqualTo(keptLabelId)));
    }

    private Long providerId() {
        Long id = gitProvider.getId();
        assertNotNull(id);
        return id;
    }
}

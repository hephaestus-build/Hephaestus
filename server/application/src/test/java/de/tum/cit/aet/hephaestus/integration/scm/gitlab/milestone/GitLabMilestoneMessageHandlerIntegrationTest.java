package de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone.dto.GitLabMilestoneEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for GitLabMilestoneMessageHandler.
 * <p>
 * Tests the full webhook handling flow: JSON fixtures &rarr; DTO &rarr; handler &rarr; processor &rarr; DB.
 * <p>
 * <b>Fixture values (milestone.create.json — Milestone IID #2):</b>
 * <ul>
 *   <li>Native ID: 18066</li>
 *   <li>IID: 2</li>
 *   <li>Title: "v2.0.0"</li>
 *   <li>Description: "Second major release"</li>
 *   <li>State: active &rarr; OPEN</li>
 *   <li>Due date: 2026-06-01</li>
 *   <li>Project ID: 246765</li>
 * </ul>
 */
@Tag("integration")
@DisplayName("GitLab Milestone Message Handler")
class GitLabMilestoneMessageHandlerIntegrationTest extends BaseIntegrationTest {

    // Fixture values from milestone.create.json
    private static final long NATIVE_MILESTONE_ID = 18066L;
    private static final int MILESTONE_IID = 2;
    private static final String FIXTURE_MILESTONE_TITLE = "v2.0.0";
    private static final String FIXTURE_MILESTONE_DESC = "Second major release";
    private static final String FIXTURE_DUE_DATE = "2026-06-01";
    private static final String FIXTURE_MILESTONE_HTML_URL =
            "https://gitlab.lrz.de/hephaestustest/demo-repository/-/milestones/2";

    // Repository/org setup
    private static final String FIXTURE_ORG_LOGIN = "hephaestustest";
    private static final String FIXTURE_REPO_FULL_NAME = "hephaestustest/demo-repository";

    @Autowired
    private GitLabMilestoneMessageHandler handler;

    @Autowired
    private MilestoneRepository milestoneRepository;

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

    @Autowired
    private RecordingScmEventListener eventListener;

    private Repository savedRepo;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        eventListener.clear();
        setupTestData();
    }

    // Event Type

    @Nested
    class EventTypeTest {

        @Test
        void returnsCorrectEventType() {
            assertThat(handler.key().eventType()).isEqualTo("milestone");
        }
    }

    // Basic Lifecycle

    @Nested
    class BasicLifecycleEvents {

        @Test
        void shouldPersistMilestoneOnCreateEvent() throws Exception {
            GitLabMilestoneEventDTO event = loadPayload("milestone.create");

            handler.handleEvent(event);

            Milestone milestone = milestoneRepository
                    .findByNumberAndRepositoryId(MILESTONE_IID, savedRepo.getId())
                    .orElseThrow();

            assertThat(milestone.getNativeId()).isEqualTo(NATIVE_MILESTONE_ID);
            assertThat(milestone.getNumber()).isEqualTo(MILESTONE_IID);
            assertThat(milestone.getTitle()).isEqualTo(FIXTURE_MILESTONE_TITLE);
            assertThat(milestone.getDescription()).isEqualTo(FIXTURE_MILESTONE_DESC);
            assertThat(milestone.getState()).isEqualTo(Milestone.State.OPEN);
            assertThat(milestone.getDueOn()).isEqualTo(Instant.parse(FIXTURE_DUE_DATE + "T00:00:00Z"));
            assertThat(milestone.getHtmlUrl()).isEqualTo(FIXTURE_MILESTONE_HTML_URL);
            assertThat(milestone.getRepository().getId()).isEqualTo(savedRepo.getId());

            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneCreated.class))
                    .hasSize(1);
        }

        @Test
        void shouldCloseMilestoneOnCloseEvent() throws Exception {
            // Create first
            handler.handleEvent(loadPayload("milestone.create"));
            eventListener.clear();

            // Close
            handler.handleEvent(loadPayload("milestone.close"));

            Milestone milestone = milestoneRepository
                    .findByNumberAndRepositoryId(MILESTONE_IID, savedRepo.getId())
                    .orElse(null);
            assertThat(milestone).isNotNull();
            assertThat(milestone.getState()).isEqualTo(Milestone.State.CLOSED);
            assertThat(milestone.getClosedAt()).isNotNull();

            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneUpdated.class))
                    .hasSize(1);
        }

        @Test
        void shouldReopenMilestoneOnReopenEvent() throws Exception {
            // Create and close
            handler.handleEvent(loadPayload("milestone.create"));
            handler.handleEvent(loadPayload("milestone.close"));
            eventListener.clear();

            // Reopen
            handler.handleEvent(loadPayload("milestone.reopen"));

            Milestone milestone = milestoneRepository
                    .findByNumberAndRepositoryId(MILESTONE_IID, savedRepo.getId())
                    .orElse(null);
            assertThat(milestone).isNotNull();
            assertThat(milestone.getState()).isEqualTo(Milestone.State.OPEN);
            assertThat(milestone.getClosedAt()).isNull();

            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneUpdated.class))
                    .hasSize(1);
        }
    }

    // Edge Cases

    @Nested
    class EdgeCases {

        @Test
        void shouldHandleMissingRepositoryGracefully() throws Exception {
            repositoryRepository.deleteAll();

            GitLabMilestoneEventDTO event = loadPayload("milestone.create");

            assertThatCode(() -> handler.handleEvent(event)).doesNotThrowAnyException();
            assertThat(milestoneRepository.count()).isZero();
        }

        @Test
        @DisplayName("is idempotent — processing same event twice")
        void shouldBeIdempotent() throws Exception {
            GitLabMilestoneEventDTO event = loadPayload("milestone.create");

            handler.handleEvent(event);
            long countAfterFirst = milestoneRepository.count();

            handler.handleEvent(event);

            assertThat(milestoneRepository.count()).isEqualTo(countAfterFirst);
        }

        @Test
        void shouldHandleFullLifecycle() throws Exception {
            handler.handleEvent(loadPayload("milestone.create"));
            assertThat(milestoneRepository
                            .findByNumberAndRepositoryId(MILESTONE_IID, savedRepo.getId())
                            .orElseThrow()
                            .getState())
                    .isEqualTo(Milestone.State.OPEN);

            handler.handleEvent(loadPayload("milestone.close"));
            assertThat(milestoneRepository
                            .findByNumberAndRepositoryId(MILESTONE_IID, savedRepo.getId())
                            .orElseThrow()
                            .getState())
                    .isEqualTo(Milestone.State.CLOSED);

            handler.handleEvent(loadPayload("milestone.reopen"));
            assertThat(milestoneRepository
                            .findByNumberAndRepositoryId(MILESTONE_IID, savedRepo.getId())
                            .orElseThrow()
                            .getState())
                    .isEqualTo(Milestone.State.OPEN);

            assertThat(milestoneRepository.count()).isEqualTo(1);
        }
    }

    // Helpers

    private GitLabMilestoneEventDTO loadPayload(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource("gitlab/" + filename + ".json");
        String json = resource.getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, GitLabMilestoneEventDTO.class);
    }

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

        Repository repo = new Repository();
        repo.setNativeId(246765L);
        repo.setName("demo-repository");
        repo.setNameWithOwner(FIXTURE_REPO_FULL_NAME);
        repo.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository");
        repo.setVisibility(Repository.Visibility.PRIVATE);
        repo.setDefaultBranch("main");
        repo.setCreatedAt(Instant.now());
        repo.setUpdatedAt(Instant.now());
        repo.setPushedAt(Instant.now());
        repo.setOrganization(org);
        repo.setProvider(provider);
        savedRepo = repositoryRepository.save(repo);

        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("hephaestus-test-gitlab");
        workspace.setDisplayName("HephaestusTest GitLab");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.setPublicActivityEnabled(true);
        workspace.setOrganization(org);
        workspace.setAccountLogin(FIXTURE_ORG_LOGIN);
        workspace.setAccountType(AccountType.ORG);
        workspaceRepository.save(workspace);
    }
}

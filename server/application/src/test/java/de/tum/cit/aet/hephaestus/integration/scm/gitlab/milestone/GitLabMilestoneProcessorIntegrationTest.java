package de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone.dto.GitLabMilestoneDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for GitLabMilestoneProcessor.
 * <p>
 * Tests the processor independently from the webhook handler to verify:
 * <ul>
 *   <li>Milestone upsert logic (create vs update)</li>
 *   <li>State mapping: GitLab {@code "active"} &rarr; OPEN, {@code "closed"} &rarr; CLOSED</li>
 *   <li>Domain event publishing (MilestoneCreated, MilestoneUpdated, MilestoneDeleted)</li>
 *   <li>Due date parsing (date-only string &rarr; Instant at midnight UTC)</li>
 *   <li>Group milestone deterministic nativeId</li>
 *   <li>htmlUrl construction (GraphQL webPath vs webhook projectWebUrl)</li>
 * </ul>
 */
@DisplayName("GitLab Milestone Processor")
class GitLabMilestoneProcessorIntegrationTest extends BaseIntegrationTest {

    private static final String FIXTURE_ORG_LOGIN = "hephaestustest";
    private static final String FIXTURE_REPO_FULL_NAME = "hephaestustest/demo-repository";

    @Autowired
    private GitLabMilestoneProcessor milestoneProcessor;

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
    private RecordingScmEventListener eventListener;

    private Repository testRepository;
    private Workspace testWorkspace;
    private IdentityProvider gitlabProvider;

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
        void shouldCreateNewMilestone() {
            GitLabMilestoneDTO dto = new GitLabMilestoneDTO(
                    18066L,
                    2,
                    "v2.0.0",
                    "Second major release",
                    "active",
                    "2026-06-01",
                    "/hephaestustest/demo-repository/-/milestones/2",
                    null,
                    false,
                    3,
                    10,
                    "2026-01-31T18:27:14Z",
                    "2026-01-31T18:27:14Z");

            Milestone result = milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getNativeId()).isEqualTo(18066L);
            assertThat(result.getNumber()).isEqualTo(2);
            assertThat(result.getTitle()).isEqualTo("v2.0.0");
            assertThat(result.getDescription()).isEqualTo("Second major release");
            assertThat(result.getState()).isEqualTo(Milestone.State.OPEN);
            assertThat(result.getDueOn())
                    .isEqualTo(LocalDate.of(2026, 6, 1)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant());
            assertThat(result.getHtmlUrl())
                    .isEqualTo("https://gitlab.lrz.de/hephaestustest/demo-repository/-/milestones/2");
            assertThat(result.getOpenIssuesCount()).isEqualTo(7);
            assertThat(result.getClosedIssuesCount()).isEqualTo(3);
            assertThat(result.getCreatedAt()).isNotNull();
            assertThat(result.getUpdatedAt()).isNotNull();
            assertThat(result.getLastSyncAt()).isNotNull();

            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneCreated.class))
                    .hasSize(1);
        }

        @Test
        void shouldMapActiveStateToOpen() {
            GitLabMilestoneDTO dto = createDto(1, "active");

            Milestone result = milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getState()).isEqualTo(Milestone.State.OPEN);
        }

        @Test
        void shouldMapClosedStateToClosed() {
            GitLabMilestoneDTO dto = new GitLabMilestoneDTO(
                    18066L,
                    2,
                    "v2.0.0",
                    null,
                    "closed",
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    "2026-01-31T18:27:14Z",
                    "2026-01-31T18:27:16Z");

            Milestone result = milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getState()).isEqualTo(Milestone.State.CLOSED);
            assertThat(result.getClosedAt()).isNotNull();
        }

        @Test
        @DisplayName("clears closedAt when milestone is reopened")
        void shouldClearClosedAtOnReopen() {
            // Create as closed
            GitLabMilestoneDTO closedDto = new GitLabMilestoneDTO(
                    18066L,
                    2,
                    "v2.0.0",
                    null,
                    "closed",
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    "2026-01-31T18:27:14Z",
                    "2026-01-31T18:27:16Z");
            milestoneProcessor.process(closedDto, testRepository, testContext());

            // Reopen
            GitLabMilestoneDTO reopenDto = new GitLabMilestoneDTO(
                    18066L,
                    2,
                    "v2.0.0",
                    null,
                    "active",
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    "2026-01-31T18:27:14Z",
                    "2026-01-31T18:27:19Z");
            Milestone result = milestoneProcessor.process(reopenDto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getState()).isEqualTo(Milestone.State.OPEN);
            assertThat(result.getClosedAt()).isNull();
        }

        @Test
        void shouldUpdateExistingMilestone() {
            // Create
            GitLabMilestoneDTO createDto = createDto(2, "active");
            milestoneProcessor.process(createDto, testRepository, testContext());
            eventListener.clear();

            // Update
            GitLabMilestoneDTO updateDto = new GitLabMilestoneDTO(
                    18066L,
                    2,
                    "v2.0.0 - Updated",
                    "Updated description",
                    "active",
                    "2026-12-31",
                    null,
                    null,
                    false,
                    null,
                    null,
                    "2026-01-31T18:27:14Z",
                    "2026-02-15T10:00:00Z");
            Milestone result = milestoneProcessor.process(updateDto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("v2.0.0 - Updated");
            assertThat(result.getDescription()).isEqualTo("Updated description");
            assertThat(result.getDueOn())
                    .isEqualTo(LocalDate.of(2026, 12, 31)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant());

            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneUpdated.class))
                    .hasSize(1);
            assertThat(milestoneRepository.findAllByRepository_Id(testRepository.getId()))
                    .hasSize(1);
        }

        @Test
        @DisplayName("is idempotent — processing same DTO twice produces one milestone")
        void shouldBeIdempotent() {
            GitLabMilestoneDTO dto = createDto(2, "active");

            milestoneProcessor.process(dto, testRepository, testContext());
            milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(milestoneRepository.findAllByRepository_Id(testRepository.getId()))
                    .hasSize(1);
        }

        @Test
        void shouldUseDeterministicIdForGroupMilestone() {
            GitLabMilestoneDTO dto = new GitLabMilestoneDTO(
                    99999L,
                    5,
                    "Group Milestone",
                    "Inherited",
                    "active",
                    null,
                    null,
                    null,
                    true,
                    null,
                    null,
                    null,
                    null);

            Milestone result = milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getNativeId()).isNegative();
            assertThat(result.getNumber()).isEqualTo(5);
        }

        @Test
        void shouldConstructHtmlUrlFromWebPath() {
            GitLabMilestoneDTO dto = new GitLabMilestoneDTO(
                    18066L,
                    2,
                    "v2.0.0",
                    null,
                    "active",
                    null,
                    "/hephaestustest/demo-repository/-/milestones/2",
                    null,
                    false,
                    null,
                    null,
                    null,
                    null);

            Milestone result = milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getHtmlUrl())
                    .isEqualTo("https://gitlab.lrz.de/hephaestustest/demo-repository/-/milestones/2");
        }

        @Test
        void shouldConstructHtmlUrlFromProjectWebUrl() {
            GitLabMilestoneDTO dto = new GitLabMilestoneDTO(
                    18066L,
                    2,
                    "v2.0.0",
                    null,
                    "active",
                    null,
                    null,
                    "https://gitlab.lrz.de/hephaestustest/demo-repository",
                    false,
                    null,
                    null,
                    null,
                    null);

            Milestone result = milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getHtmlUrl())
                    .isEqualTo("https://gitlab.lrz.de/hephaestustest/demo-repository/-/milestones/2");
        }

        @Test
        void shouldParseDueDate() {
            GitLabMilestoneDTO dto = new GitLabMilestoneDTO(
                    18066L, 2, "v2.0.0", null, "active", "2026-06-01", null, null, false, null, null, null, null);

            Milestone result = milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getDueOn())
                    .isEqualTo(LocalDate.of(2026, 6, 1)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant());
        }

        @Test
        void shouldHandleNullDescription() {
            GitLabMilestoneDTO dto = createDto(2, "active");

            Milestone result = milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getDescription()).isNull();
        }

        @Test
        void shouldHandleNullDueDate() {
            GitLabMilestoneDTO dto = new GitLabMilestoneDTO(
                    18066L, 2, "v2.0.0", null, "active", null, null, null, false, null, null, null, null);

            Milestone result = milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getDueOn()).isNull();
        }

        @Test
        void shouldReturnNullForNullDto() {
            Milestone result = milestoneProcessor.process(null, testRepository, testContext());

            assertThat(result).isNull();
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneCreated.class))
                    .isEmpty();
        }

        @Test
        void shouldReturnNullForInvalidIid() {
            GitLabMilestoneDTO dto = new GitLabMilestoneDTO(
                    18066L, 0, "Invalid", null, "active", null, null, null, false, null, null, null, null);

            Milestone result = milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNull();
        }

        @Test
        void shouldDefaultToOpenForNullState() {
            GitLabMilestoneDTO dto = new GitLabMilestoneDTO(
                    18066L, 2, "v2.0.0", null, null, null, null, null, false, null, null, null, null);

            Milestone result = milestoneProcessor.process(dto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getState()).isEqualTo(Milestone.State.OPEN);
        }
    }

    @Nested
    class GroupMilestoneFanOut {

        @Test
        void shouldFanOutGroupMilestoneToEveryRepoWhenProcessingOncePerRepo() {
            Repository secondRepo =
                    createRepository(246766L, "other-repository", FIXTURE_ORG_LOGIN + "/other-repository");

            GitLabMilestoneDTO groupDto = new GitLabMilestoneDTO(
                    42_000L,
                    7,
                    "Sprint 7",
                    "Group-wide sprint",
                    "active",
                    "2026-06-30",
                    "/hephaestustest/-/milestones/7",
                    null,
                    true,
                    0,
                    0,
                    "2026-01-10T10:00:00Z",
                    "2026-01-10T10:00:00Z");

            Milestone first = milestoneProcessor.process(groupDto, testRepository, testContext());
            Milestone second = milestoneProcessor.process(
                    groupDto, secondRepo, ProcessingContext.forSync(testWorkspace.getId(), secondRepo));

            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(first.getId()).isNotEqualTo(second.getId());
            assertThat(milestoneRepository.findAllByRepository_Id(testRepository.getId()))
                    .hasSize(1);
            assertThat(milestoneRepository.findAllByRepository_Id(secondRepo.getId()))
                    .hasSize(1);
        }

        @Test
        void shouldUseDistinctNegativeNativeIdsWhenFanningOutSameGroupMilestone() {
            Repository secondRepo =
                    createRepository(246767L, "third-repository", FIXTURE_ORG_LOGIN + "/third-repository");

            GitLabMilestoneDTO groupDto = new GitLabMilestoneDTO(
                    42_001L,
                    8,
                    "Sprint 8",
                    null,
                    "active",
                    null,
                    null,
                    null,
                    true,
                    null,
                    null,
                    "2026-02-01T10:00:00Z",
                    "2026-02-01T10:00:00Z");

            Milestone first = milestoneProcessor.process(groupDto, testRepository, testContext());
            Milestone second = milestoneProcessor.process(
                    groupDto, secondRepo, ProcessingContext.forSync(testWorkspace.getId(), secondRepo));

            // Both rows must carry the shared iid …
            assertNotNull(first);
            assertThat(first.getNumber()).isEqualTo(8);
            assertNotNull(second);
            assertThat(second.getNumber()).isEqualTo(8);
            // … but different deterministic negative nativeIds so (provider_id, native_id) stays unique.
            assertThat(first.getNativeId()).isNegative();
            assertThat(second.getNativeId()).isNegative();
            assertThat(first.getNativeId()).isNotEqualTo(second.getNativeId());
        }

        @Test
        void shouldPersistDescriptionAndDueOnWhenGroupMilestoneIsFannedOut() {
            // Gap 4 notes that description, due_on, and closed_at were NULL on most rows.
            // Verify all three survive the fan-out path.
            GitLabMilestoneDTO groupDto = new GitLabMilestoneDTO(
                    42_002L,
                    9,
                    "Sprint 9",
                    "End-of-quarter milestone",
                    "closed",
                    "2026-03-31",
                    "/hephaestustest/-/milestones/9",
                    null,
                    true,
                    5,
                    5,
                    "2026-03-01T10:00:00Z",
                    "2026-03-30T23:59:59Z");

            Milestone result = milestoneProcessor.process(groupDto, testRepository, testContext());

            assertThat(result).isNotNull();
            assertThat(result.getDescription()).isEqualTo("End-of-quarter milestone");
            assertThat(result.getDueOn())
                    .isEqualTo(LocalDate.of(2026, 3, 31)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant());
            assertThat(result.getState()).isEqualTo(Milestone.State.CLOSED);
            // closed_at is approximated from updatedAt on first transition to CLOSED — must not be null.
            assertThat(result.getClosedAt()).isNotNull();
        }

        private Repository createRepository(long nativeId, String name, String fullName) {
            Organization org = organizationRepository
                    .findByLoginIgnoreCaseAndProvider_Type(FIXTURE_ORG_LOGIN, IdentityProviderType.GITLAB)
                    .orElseThrow(() -> new IllegalStateException("Test org not found"));

            Repository repo = new Repository();
            repo.setNativeId(nativeId);
            repo.setName(name);
            repo.setNameWithOwner(fullName);
            repo.setHtmlUrl("https://gitlab.lrz.de/" + fullName);
            repo.setVisibility(Repository.Visibility.PRIVATE);
            repo.setDefaultBranch("main");
            repo.setCreatedAt(testRepository.getCreatedAt());
            repo.setUpdatedAt(testRepository.getUpdatedAt());
            repo.setPushedAt(testRepository.getPushedAt());
            repo.setOrganization(org);
            repo.setProvider(gitlabProvider);
            return repositoryRepository.save(repo);
        }
    }

    @Nested
    class DeleteMethod {

        @Test
        @DisplayName("deletes milestone and publishes event")
        void shouldDeleteMilestone() {
            GitLabMilestoneDTO dto = createDto(2, "active");
            Milestone milestone = milestoneProcessor.process(dto, testRepository, testContext());
            eventListener.clear();

            assertNotNull(milestone);
            milestoneProcessor.delete(milestone.getId(), testContext());

            assertThat(milestoneRepository.findById(milestone.getId())).isEmpty();
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneDeleted.class))
                    .hasSize(1);
        }

        @Test
        void shouldHandleNullId() {
            assertThatCode(() -> milestoneProcessor.delete(null, testContext())).doesNotThrowAnyException();
        }

        @Test
        void shouldHandleNonExistentMilestone() {
            milestoneProcessor.delete(-999L, testContext());
            assertThat(eventListener.ofType(ScmDomainEvent.MilestoneDeleted.class))
                    .isEmpty();
        }
    }

    // Helpers

    private GitLabMilestoneDTO createDto(int iid, String state) {
        return new GitLabMilestoneDTO(
                18066L, iid, "v2.0.0", null, state, null, null, null, false, null, null, null, null);
    }

    // Test Data Setup

    private void setupTestData() {
        gitlabProvider = gitProviderRepository
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
        org.setProvider(gitlabProvider);
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
        testRepository.setProvider(gitlabProvider);
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

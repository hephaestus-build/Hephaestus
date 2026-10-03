package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.events.GitHubProjectEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectItemDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link GitHubProjectItemProcessor}.
 * <p>
 * Covers the core processing pipeline ({@code process()}, {@code processArchived()},
 * {@code processRestored()}), stale item removal logic ({@code removeStaleIssuePrItems()},
 * {@code removeStaleDraftIssues()}), and edge cases around null inputs and unknown content types.
 */
class GitHubProjectItemProcessorTest extends BaseUnitTest {

    @Mock
    private ProjectItemRepository projectItemRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LabelRepository labelRepository;

    @Mock
    private MilestoneRepository milestoneRepository;

    @Mock
    private GitHubUserProcessor gitHubUserProcessor;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GitHubProjectItemProcessor processor;

    private static final Long SCOPE_ID = 100L;
    private static final Long PROJECT_ID = 42L;
    private static final String NODE_ID = "PVTI_abc123";
    private static final Long ITEM_DB_ID = 9001L;
    private static final Long PROVIDER_ID = 1L;

    private Project project;
    private ProcessingContext context;

    @BeforeEach
    void setUp() {
        processor = new GitHubProjectItemProcessor(
                projectItemRepository,
                issueRepository,
                userRepository,
                labelRepository,
                milestoneRepository,
                gitHubUserProcessor,
                eventPublisher);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setTitle("Test Project");
        project.setOwnerType(Project.OwnerType.ORGANIZATION);
        project.setOwnerId(1L);
        project.setNumber(1);
        project.setUrl("https://github.com/orgs/test/projects/1");

        IdentityProvider provider = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
        provider.setId(PROVIDER_ID);
        context = new ProcessingContext(
                SCOPE_ID, null, provider, Instant.now(), UUID.randomUUID().toString(), null, DataSource.GRAPHQL_SYNC);
    }

    private GitHubProjectItemDTO createDraftIssueDTO() {
        return new GitHubProjectItemDTO(
                null,
                ITEM_DB_ID,
                NODE_ID,
                null,
                "DRAFT_ISSUE",
                null,
                null,
                "Draft Title",
                "Draft Body",
                false,
                null,
                Collections.emptyList(),
                false,
                0,
                null,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-02T00:00:00Z"));
    }

    private GitHubProjectItemDTO createIssueDTO(@Nullable Long issueId) {
        return new GitHubProjectItemDTO(
                null,
                ITEM_DB_ID,
                NODE_ID,
                null,
                "ISSUE",
                issueId,
                42,
                null,
                null,
                false,
                null,
                Collections.emptyList(),
                false,
                0,
                null,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-02T00:00:00Z"));
    }

    private GitHubProjectItemDTO createPullRequestDTO(Long issueId) {
        return new GitHubProjectItemDTO(
                null,
                ITEM_DB_ID,
                NODE_ID,
                null,
                "PULL_REQUEST",
                issueId,
                99,
                null,
                null,
                false,
                null,
                Collections.emptyList(),
                false,
                0,
                null,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-02T00:00:00Z"));
    }

    private ProjectItem createProjectItemEntity(ProjectItem.ContentType contentType) {
        ProjectItem item = new ProjectItem();
        item.setId(ITEM_DB_ID);
        item.setNodeId(NODE_ID);
        item.setProject(project);
        item.setContentType(contentType);
        item.setArchived(false);
        item.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        item.setUpdatedAt(Instant.parse("2025-01-02T00:00:00Z"));
        return item;
    }

    /**
     * Stubs repository calls for a successful process() invocation.
     *
     * @param isNew whether the item should be treated as new (not existing)
     * @param entity the entity to return from findByProjectIdAndNodeId
     */
    private void stubSuccessfulProcess(boolean isNew, ProjectItem entity) {
        when(projectItemRepository.existsByProjectIdAndNodeId(PROJECT_ID, NODE_ID))
                .thenReturn(!isNew);
        when(projectItemRepository.findByProjectIdAndNodeId(PROJECT_ID, NODE_ID))
                .thenReturn(Optional.of(entity));
    }

    @Nested
    class Process {

        @Test
        void shouldReturnNullWhenDtoIsNull() {
            ProjectItem result = processor.process(null, project, context);

            assertThat(result).isNull();
            verify(projectItemRepository, never())
                    .upsertCore(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(boolean.class),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldReturnNullWhenNodeIdIsNull() {
            GitHubProjectItemDTO dto = new GitHubProjectItemDTO(
                    null,
                    ITEM_DB_ID,
                    null,
                    null,
                    "DRAFT_ISSUE",
                    null,
                    null,
                    "Title",
                    "Body",
                    false,
                    null,
                    Collections.emptyList(),
                    false,
                    0,
                    null,
                    Instant.now(),
                    Instant.now());

            ProjectItem result = processor.process(dto, project, context);

            assertThat(result).isNull();
        }

        @Test
        void shouldReturnNullWhenNodeIdIsBlank() {
            GitHubProjectItemDTO dto = new GitHubProjectItemDTO(
                    null,
                    ITEM_DB_ID,
                    "  ",
                    null,
                    "DRAFT_ISSUE",
                    null,
                    null,
                    "Title",
                    "Body",
                    false,
                    null,
                    Collections.emptyList(),
                    false,
                    0,
                    null,
                    Instant.now(),
                    Instant.now());

            ProjectItem result = processor.process(dto, project, context);

            assertThat(result).isNull();
        }

        @Test
        void shouldReturnNullWhenDatabaseIdIsNull() {
            // getDatabaseId() falls back to `id`, so both are null here.
            GitHubProjectItemDTO dto = new GitHubProjectItemDTO(
                    null,
                    null,
                    NODE_ID,
                    null,
                    "DRAFT_ISSUE",
                    null,
                    null,
                    "Title",
                    "Body",
                    false,
                    null,
                    Collections.emptyList(),
                    false,
                    0,
                    null,
                    Instant.now(),
                    Instant.now());

            ProjectItem result = processor.process(dto, project, context);

            assertThat(result).isNull();
        }

        @Test
        void shouldReturnNullWhenContentTypeIsUnknown() {
            GitHubProjectItemDTO dto = new GitHubProjectItemDTO(
                    null,
                    ITEM_DB_ID,
                    NODE_ID,
                    null,
                    "UNKNOWN_TYPE",
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    Collections.emptyList(),
                    false,
                    0,
                    null,
                    Instant.now(),
                    Instant.now());

            ProjectItem result = processor.process(dto, project, context);

            assertThat(result).isNull();
            verify(projectItemRepository, never())
                    .upsertCore(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(boolean.class),
                            any(),
                            any(),
                            any());
        }

        @Test
        void shouldProcessDraftIssueWithoutIssueLookup() {
            GitHubProjectItemDTO dto = createDraftIssueDTO();
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            entity.setDraftTitle("Draft Title");
            entity.setDraftBody("Draft Body");
            stubSuccessfulProcess(true, entity);

            ProjectItem result = processor.process(dto, project, context);

            assertNotNull(result);
            assertThat(result.getId()).isEqualTo(ITEM_DB_ID);
            assertThat(result.getContentType()).isEqualTo(ProjectItem.ContentType.DRAFT_ISSUE);

            // Verify upsert was called with correct params
            verify(projectItemRepository)
                    .upsertCore(
                            eq(ITEM_DB_ID),
                            eq(PROVIDER_ID),
                            eq(NODE_ID),
                            eq(PROJECT_ID),
                            eq("DRAFT_ISSUE"),
                            isNull(), // issueId — not resolved for DRAFT_ISSUE
                            isNull(), // contentDatabaseId — not set for DRAFT_ISSUE
                            eq("Draft Title"),
                            eq("Draft Body"),
                            eq(false),
                            isNull(), // creatorId — no creator in DTO
                            eq(Instant.parse("2025-01-01T00:00:00Z")),
                            eq(Instant.parse("2025-01-02T00:00:00Z")));

            // Verify no issue lookup was attempted
            verify(issueRepository, never()).existsById(any());

            // Verify created event was published
            ArgumentCaptor<GitHubProjectEvent.ProjectItemCreated> eventCaptor =
                    ArgumentCaptor.forClass(GitHubProjectEvent.ProjectItemCreated.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().projectId()).isEqualTo(PROJECT_ID);
        }

        @Test
        void shouldProcessIssueItemWithCorrectContentType() {
            Long issueId = 555L;
            GitHubProjectItemDTO dto = createIssueDTO(issueId);
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.ISSUE);
            stubSuccessfulProcess(true, entity);
            when(issueRepository.existsById(issueId)).thenReturn(true);

            ProjectItem result = processor.process(dto, project, context);

            assertNotNull(result);

            // Verify upsert was called with ISSUE content type and resolved issueId
            verify(projectItemRepository)
                    .upsertCore(
                            eq(ITEM_DB_ID),
                            eq(PROVIDER_ID),
                            eq(NODE_ID),
                            eq(PROJECT_ID),
                            eq("ISSUE"),
                            eq(issueId), // issueId resolved because issue exists locally
                            eq(issueId), // contentDatabaseId set from dto.issueId()
                            isNull(), // no draftTitle for ISSUE
                            isNull(), // no draftBody for ISSUE
                            eq(false),
                            isNull(),
                            any(Instant.class),
                            any(Instant.class));
        }

        @Test
        void shouldProcessPullRequestItemWithCorrectContentType() {
            Long prIssueId = 777L;
            GitHubProjectItemDTO dto = createPullRequestDTO(prIssueId);
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.PULL_REQUEST);
            stubSuccessfulProcess(true, entity);
            when(issueRepository.existsById(prIssueId)).thenReturn(true);

            ProjectItem result = processor.process(dto, project, context);

            assertNotNull(result);
            verify(projectItemRepository)
                    .upsertCore(
                            eq(ITEM_DB_ID),
                            eq(PROVIDER_ID),
                            eq(NODE_ID),
                            eq(PROJECT_ID),
                            eq("PULL_REQUEST"),
                            eq(prIssueId),
                            eq(prIssueId),
                            isNull(),
                            isNull(),
                            eq(false),
                            isNull(),
                            any(Instant.class),
                            any(Instant.class));
        }

        @Test
        void shouldSetIssueIdToNullWhenIssueNotLocal() {
            Long nonExistentIssueId = 999L;
            GitHubProjectItemDTO dto = createIssueDTO(nonExistentIssueId);
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.ISSUE);
            stubSuccessfulProcess(true, entity);
            when(issueRepository.existsById(nonExistentIssueId)).thenReturn(false);

            ProjectItem result = processor.process(dto, project, context);

            assertNotNull(result);
            // issueId should be null because issue doesn't exist locally
            // contentDatabaseId should still be set for orphan relinking
            verify(projectItemRepository)
                    .upsertCore(
                            eq(ITEM_DB_ID),
                            eq(PROVIDER_ID),
                            eq(NODE_ID),
                            eq(PROJECT_ID),
                            eq("ISSUE"),
                            isNull(), // issueId null — issue not in local DB
                            eq(nonExistentIssueId), // contentDatabaseId still set for relinking
                            isNull(),
                            isNull(),
                            eq(false),
                            isNull(),
                            any(Instant.class),
                            any(Instant.class));
        }

        @Test
        void shouldHandleNullContentDatabaseIdForIssueItem() {
            GitHubProjectItemDTO dto = createIssueDTO(null);
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.ISSUE);
            stubSuccessfulProcess(true, entity);

            ProjectItem result = processor.process(dto, project, context);

            assertNotNull(result);
            verify(projectItemRepository)
                    .upsertCore(
                            eq(ITEM_DB_ID),
                            eq(PROVIDER_ID),
                            eq(NODE_ID),
                            eq(PROJECT_ID),
                            eq("ISSUE"),
                            isNull(), // issueId null — contentDatabaseId was null
                            isNull(), // contentDatabaseId null
                            isNull(),
                            isNull(),
                            eq(false),
                            isNull(),
                            any(Instant.class),
                            any(Instant.class));
            // Should not attempt issue lookup when contentDatabaseId is null
            verify(issueRepository, never()).existsById(any());
        }

        @Test
        void shouldResolveCreatorWhenCreatorExistsLocally() {
            Long creatorId = 200L;
            GitHubUserDTO creatorDTO = new GitHubUserDTO(creatorId, null, "testuser", null, null, null, null);
            GitHubProjectItemDTO dto = new GitHubProjectItemDTO(
                    null,
                    ITEM_DB_ID,
                    NODE_ID,
                    null,
                    "DRAFT_ISSUE",
                    null,
                    null,
                    "Title",
                    "Body",
                    false,
                    creatorDTO,
                    Collections.emptyList(),
                    false,
                    0,
                    null,
                    Instant.now(),
                    Instant.now());
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            stubSuccessfulProcess(true, entity);
            when(userRepository.existsById(creatorId)).thenReturn(true);

            ProjectItem result = processor.process(dto, project, context);

            assertNotNull(result);
            verify(projectItemRepository)
                    .upsertCore(
                            eq(ITEM_DB_ID),
                            eq(PROVIDER_ID),
                            eq(NODE_ID),
                            eq(PROJECT_ID),
                            eq("DRAFT_ISSUE"),
                            isNull(),
                            isNull(),
                            eq("Title"),
                            eq("Body"),
                            eq(false),
                            eq(creatorId), // creatorId resolved
                            any(Instant.class),
                            any(Instant.class));
        }

        @Test
        void shouldSetCreatorIdToNullWhenCreatorNotLocal() {
            Long creatorId = 300L;
            GitHubUserDTO creatorDTO = new GitHubUserDTO(creatorId, null, "unknown", null, null, null, null);
            GitHubProjectItemDTO dto = new GitHubProjectItemDTO(
                    null,
                    ITEM_DB_ID,
                    NODE_ID,
                    null,
                    "DRAFT_ISSUE",
                    null,
                    null,
                    "Title",
                    null,
                    false,
                    creatorDTO,
                    Collections.emptyList(),
                    false,
                    0,
                    null,
                    Instant.now(),
                    Instant.now());
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            stubSuccessfulProcess(true, entity);
            when(userRepository.existsById(creatorId)).thenReturn(false);

            processor.process(dto, project, context);

            verify(projectItemRepository)
                    .upsertCore(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(boolean.class),
                            isNull(), // creatorId null because user doesn't exist locally
                            any(),
                            any());
        }

        @Test
        void shouldSetCreatorIdToNullWhenCreatorDtoIsNull() {
            GitHubProjectItemDTO dto = createDraftIssueDTO(); // creator is null
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            stubSuccessfulProcess(true, entity);

            processor.process(dto, project, context);

            verify(projectItemRepository)
                    .upsertCore(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(boolean.class),
                            isNull(), // creatorId null because creator DTO is null
                            any(),
                            any());
        }

        @Test
        void shouldPassArchivedFlagThroughToUpsert() {
            GitHubProjectItemDTO dto = new GitHubProjectItemDTO(
                    null,
                    ITEM_DB_ID,
                    NODE_ID,
                    null,
                    "DRAFT_ISSUE",
                    null,
                    null,
                    "Title",
                    null,
                    true,
                    null,
                    Collections.emptyList(),
                    false,
                    0,
                    null,
                    Instant.now(),
                    Instant.now());
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            entity.setArchived(true);
            stubSuccessfulProcess(false, entity);

            processor.process(dto, project, context);

            verify(projectItemRepository)
                    .upsertCore(
                            eq(ITEM_DB_ID),
                            eq(PROVIDER_ID),
                            eq(NODE_ID),
                            eq(PROJECT_ID),
                            eq("DRAFT_ISSUE"),
                            isNull(),
                            isNull(),
                            eq("Title"),
                            isNull(),
                            eq(true), // archived = true
                            isNull(),
                            any(Instant.class),
                            any(Instant.class));
        }

        @Test
        void shouldReturnEntityFromRepositoryAfterUpsert() {
            GitHubProjectItemDTO dto = createDraftIssueDTO();
            ProjectItem expectedEntity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            expectedEntity.setDraftTitle("Draft Title");
            stubSuccessfulProcess(true, expectedEntity);

            ProjectItem result = processor.process(dto, project, context);

            assertThat(result).isSameAs(expectedEntity);
        }

        @Test
        void shouldPublishCreatedEventWhenItemIsNew() {
            GitHubProjectItemDTO dto = createDraftIssueDTO();
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            stubSuccessfulProcess(true, entity); // isNew = true

            processor.process(dto, project, context);

            ArgumentCaptor<GitHubProjectEvent.ProjectItemCreated> captor =
                    ArgumentCaptor.forClass(GitHubProjectEvent.ProjectItemCreated.class);
            verify(eventPublisher).publishEvent(captor.capture());
            GitHubProjectEvent.ProjectItemCreated event = captor.getValue();
            assertThat(event.projectId()).isEqualTo(PROJECT_ID);
            assertThat(event.item().id()).isEqualTo(ITEM_DB_ID);
            assertThat(event.item().contentType()).isEqualTo(ProjectItem.ContentType.DRAFT_ISSUE);
        }

        @Test
        void shouldPublishUpdatedEventWhenItemExists() {
            GitHubProjectItemDTO dto = createDraftIssueDTO();
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            stubSuccessfulProcess(false, entity); // isNew = false

            processor.process(dto, project, context);

            ArgumentCaptor<GitHubProjectEvent.ProjectItemUpdated> captor =
                    ArgumentCaptor.forClass(GitHubProjectEvent.ProjectItemUpdated.class);
            verify(eventPublisher).publishEvent(captor.capture());
            GitHubProjectEvent.ProjectItemUpdated event = captor.getValue();
            assertThat(event.projectId()).isEqualTo(PROJECT_ID);
            assertThat(event.item().id()).isEqualTo(ITEM_DB_ID);
        }

        @Test
        void shouldUseFallbackIdWhenDatabaseIdIsNull() {
            Long fallbackId = 5555L;
            GitHubProjectItemDTO dto = new GitHubProjectItemDTO(
                    fallbackId,
                    null,
                    NODE_ID,
                    null,
                    "DRAFT_ISSUE",
                    null,
                    null,
                    "Title",
                    null,
                    false,
                    null,
                    Collections.emptyList(),
                    false,
                    0,
                    null,
                    Instant.now(),
                    Instant.now());
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            entity.setId(fallbackId);
            stubSuccessfulProcess(true, entity);

            ProjectItem result = processor.process(dto, project, context);

            assertNotNull(result);
            verify(projectItemRepository)
                    .upsertCore(
                            eq(fallbackId), // getDatabaseId() falls back to id
                            eq(PROVIDER_ID),
                            eq(NODE_ID),
                            eq(PROJECT_ID),
                            eq("DRAFT_ISSUE"),
                            isNull(),
                            isNull(),
                            eq("Title"),
                            isNull(),
                            eq(false),
                            isNull(),
                            any(Instant.class),
                            any(Instant.class));
        }
    }

    @Nested
    class RemoveStaleDraftIssues {

        @Test
        void shouldReturnZeroWhenProjectIdIsNull() {
            int result = processor.removeStaleDraftIssues(null, List.of("PVTI_1"), context);

            assertThat(result).isZero();
            verify(projectItemRepository, never()).deleteByProjectIdAndContentTypeAndNodeIdNotIn(any(), any(), any());
            verify(projectItemRepository, never()).deleteByProjectIdAndContentType(any(), any());
        }

        @Test
        void shouldDeleteStaleDraftIssuesNotInSyncedList() {
            List<String> syncedNodeIds = List.of("PVTI_1", "PVTI_2");
            when(projectItemRepository.deleteByProjectIdAndContentTypeAndNodeIdNotIn(
                            eq(PROJECT_ID), eq(ProjectItem.ContentType.DRAFT_ISSUE), eq(syncedNodeIds)))
                    .thenReturn(2);

            int result = processor.removeStaleDraftIssues(PROJECT_ID, syncedNodeIds, context);

            assertThat(result).isEqualTo(2);
            verify(projectItemRepository)
                    .deleteByProjectIdAndContentTypeAndNodeIdNotIn(
                            eq(PROJECT_ID), eq(ProjectItem.ContentType.DRAFT_ISSUE), eq(syncedNodeIds));
        }

        @Test
        void shouldDeleteAllDraftIssuesWhenSyncedListIsEmpty() {
            when(projectItemRepository.deleteByProjectIdAndContentType(
                            eq(PROJECT_ID), eq(ProjectItem.ContentType.DRAFT_ISSUE)))
                    .thenReturn(3);

            int result = processor.removeStaleDraftIssues(PROJECT_ID, List.of(), context);

            assertThat(result).isEqualTo(3);
            verify(projectItemRepository)
                    .deleteByProjectIdAndContentType(eq(PROJECT_ID), eq(ProjectItem.ContentType.DRAFT_ISSUE));
            verify(projectItemRepository, never()).deleteByProjectIdAndContentTypeAndNodeIdNotIn(any(), any(), any());
        }

        @Test
        void shouldDeleteAllDraftIssuesWhenSyncedListIsNull() {
            when(projectItemRepository.deleteByProjectIdAndContentType(
                            eq(PROJECT_ID), eq(ProjectItem.ContentType.DRAFT_ISSUE)))
                    .thenReturn(1);

            int result = processor.removeStaleDraftIssues(PROJECT_ID, null, context);

            assertThat(result).isEqualTo(1);
            verify(projectItemRepository)
                    .deleteByProjectIdAndContentType(eq(PROJECT_ID), eq(ProjectItem.ContentType.DRAFT_ISSUE));
        }
    }

    @Nested
    class RemoveStaleIssuePrItems {

        @Test
        void shouldReturnZeroWhenProjectIdIsNull() {
            int result = processor.removeStaleIssuePrItems(null, List.of("PVTI_1"), context);

            assertThat(result).isZero();
            verify(projectItemRepository, never()).deleteByProjectIdAndContentTypeInAndNodeIdNotIn(any(), any(), any());
            verify(projectItemRepository, never()).deleteByProjectIdAndContentTypeIn(any(), any());
        }

        @Test
        void shouldDeleteNonArchivedItemsNotInSyncedList() {
            List<String> syncedNodeIds = List.of("PVTI_1", "PVTI_2");
            List<ProjectItem.ContentType> expectedTypes =
                    List.of(ProjectItem.ContentType.ISSUE, ProjectItem.ContentType.PULL_REQUEST);
            when(projectItemRepository.deleteByProjectIdAndContentTypeInAndNodeIdNotIn(
                            eq(PROJECT_ID), eq(expectedTypes), eq(syncedNodeIds)))
                    .thenReturn(3);

            int result = processor.removeStaleIssuePrItems(PROJECT_ID, syncedNodeIds, context);

            assertThat(result).isEqualTo(3);
            verify(projectItemRepository)
                    .deleteByProjectIdAndContentTypeInAndNodeIdNotIn(
                            eq(PROJECT_ID), eq(expectedTypes), eq(syncedNodeIds));
        }

        @Test
        void shouldCallDeleteByContentTypeInWhenSyncedListIsNull() {
            List<ProjectItem.ContentType> expectedTypes =
                    List.of(ProjectItem.ContentType.ISSUE, ProjectItem.ContentType.PULL_REQUEST);
            when(projectItemRepository.deleteByProjectIdAndContentTypeIn(eq(PROJECT_ID), eq(expectedTypes)))
                    .thenReturn(5);

            int result = processor.removeStaleIssuePrItems(PROJECT_ID, null, context);

            assertThat(result).isEqualTo(5);
            verify(projectItemRepository).deleteByProjectIdAndContentTypeIn(eq(PROJECT_ID), eq(expectedTypes));
            verify(projectItemRepository, never()).deleteByProjectIdAndContentTypeInAndNodeIdNotIn(any(), any(), any());
        }

        @Test
        void shouldCallDeleteByContentTypeInWhenSyncedListIsEmpty() {
            List<ProjectItem.ContentType> expectedTypes =
                    List.of(ProjectItem.ContentType.ISSUE, ProjectItem.ContentType.PULL_REQUEST);
            when(projectItemRepository.deleteByProjectIdAndContentTypeIn(eq(PROJECT_ID), eq(expectedTypes)))
                    .thenReturn(2);

            int result = processor.removeStaleIssuePrItems(PROJECT_ID, List.of(), context);

            assertThat(result).isEqualTo(2);
            verify(projectItemRepository).deleteByProjectIdAndContentTypeIn(eq(PROJECT_ID), eq(expectedTypes));
            verify(projectItemRepository, never()).deleteByProjectIdAndContentTypeInAndNodeIdNotIn(any(), any(), any());
        }

        @Test
        void shouldReturnZeroWhenNoStaleItemsExist() {
            List<String> syncedNodeIds = List.of("PVTI_1", "PVTI_2", "PVTI_3");
            List<ProjectItem.ContentType> expectedTypes =
                    List.of(ProjectItem.ContentType.ISSUE, ProjectItem.ContentType.PULL_REQUEST);
            when(projectItemRepository.deleteByProjectIdAndContentTypeInAndNodeIdNotIn(
                            eq(PROJECT_ID), eq(expectedTypes), eq(syncedNodeIds)))
                    .thenReturn(0);

            int result = processor.removeStaleIssuePrItems(PROJECT_ID, syncedNodeIds, context);

            assertThat(result).isZero();
        }
    }

    @Nested
    class ProcessArchived {

        @Test
        void shouldForceArchivedTrueAndPublishArchivedEvent() {
            GitHubProjectItemDTO dto = createDraftIssueDTO(); // archived=false, as a webhook deserializes it
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            entity.setArchived(true);
            stubSuccessfulProcess(false, entity);

            ProjectItem result = processor.processArchived(dto, project, context);

            assertNotNull(result);

            // Verify that archived=true was forced via withArchived(true)
            verify(projectItemRepository)
                    .upsertCore(
                            eq(ITEM_DB_ID),
                            eq(PROVIDER_ID),
                            eq(NODE_ID),
                            eq(PROJECT_ID),
                            eq("DRAFT_ISSUE"),
                            isNull(),
                            isNull(),
                            eq("Draft Title"),
                            eq("Draft Body"),
                            eq(true), // archived forced to true
                            isNull(),
                            any(Instant.class),
                            any(Instant.class));

            // Verify both the base event (Updated) and the Archived event are published
            // process() publishes Updated (since !isNew), then processArchived publishes Archived
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());

            List<Object> events = eventCaptor.getAllValues();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(GitHubProjectEvent.ProjectItemUpdated.class);
            assertThat(events.get(1)).isInstanceOf(GitHubProjectEvent.ProjectItemArchived.class);

            GitHubProjectEvent.ProjectItemArchived archivedEvent =
                    (GitHubProjectEvent.ProjectItemArchived) events.get(1);
            assertThat(archivedEvent.projectId()).isEqualTo(PROJECT_ID);
            assertThat(archivedEvent.item().id()).isEqualTo(ITEM_DB_ID);
        }

        @Test
        void shouldNotPublishArchivedEventWhenProcessReturnsNull() {
            ProjectItem result = processor.processArchived(null, project, context);

            assertThat(result).isNull();
            verify(eventPublisher, never()).publishEvent(any(GitHubProjectEvent.ProjectItemArchived.class));
        }

        @Test
        void shouldPassActorIdToProcessAndArchivedEvent() {
            Long actorId = 400L;
            GitHubProjectItemDTO dto = createDraftIssueDTO();
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            entity.setArchived(true);
            stubSuccessfulProcess(false, entity);

            ProjectItem result = processor.processArchived(dto, project, context, actorId);

            assertNotNull(result);
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());

            GitHubProjectEvent.ProjectItemArchived archivedEvent = (GitHubProjectEvent.ProjectItemArchived)
                    eventCaptor.getAllValues().get(1);
            assertThat(archivedEvent.item().actorId()).isEqualTo(actorId);
        }
    }

    @Nested
    class ProcessRestored {

        @Test
        void shouldForceArchivedFalseAndPublishRestoredEvent() {
            GitHubProjectItemDTO dto = new GitHubProjectItemDTO(
                    null,
                    ITEM_DB_ID,
                    NODE_ID,
                    null,
                    "DRAFT_ISSUE",
                    null,
                    null,
                    "Title",
                    null,
                    true,
                    null,
                    Collections.emptyList(),
                    false,
                    0,
                    null,
                    Instant.parse("2025-01-01T00:00:00Z"),
                    Instant.parse("2025-01-02T00:00:00Z"));
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            entity.setArchived(false);
            stubSuccessfulProcess(false, entity);

            ProjectItem result = processor.processRestored(dto, project, context);

            assertNotNull(result);

            // Verify that archived=false was forced via withArchived(false)
            verify(projectItemRepository)
                    .upsertCore(
                            eq(ITEM_DB_ID),
                            eq(PROVIDER_ID),
                            eq(NODE_ID),
                            eq(PROJECT_ID),
                            eq("DRAFT_ISSUE"),
                            isNull(),
                            isNull(),
                            eq("Title"),
                            isNull(),
                            eq(false), // archived forced to false
                            isNull(),
                            any(Instant.class),
                            any(Instant.class));

            // Verify both base event (Updated) and Restored event are published
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());

            List<Object> events = eventCaptor.getAllValues();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(GitHubProjectEvent.ProjectItemUpdated.class);
            assertThat(events.get(1)).isInstanceOf(GitHubProjectEvent.ProjectItemRestored.class);

            GitHubProjectEvent.ProjectItemRestored restoredEvent =
                    (GitHubProjectEvent.ProjectItemRestored) events.get(1);
            assertThat(restoredEvent.projectId()).isEqualTo(PROJECT_ID);
            assertThat(restoredEvent.item().id()).isEqualTo(ITEM_DB_ID);
        }

        @Test
        void shouldNotPublishRestoredEventWhenProcessReturnsNull() {
            ProjectItem result = processor.processRestored(null, project, context);

            assertThat(result).isNull();
            verify(eventPublisher, never()).publishEvent(any(GitHubProjectEvent.ProjectItemRestored.class));
        }

        @Test
        void shouldPassActorIdToProcessAndRestoredEvent() {
            Long actorId = 500L;
            GitHubProjectItemDTO dto = createDraftIssueDTO();
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            stubSuccessfulProcess(false, entity);

            ProjectItem result = processor.processRestored(dto, project, context, actorId);

            assertNotNull(result);
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());

            GitHubProjectEvent.ProjectItemRestored restoredEvent = (GitHubProjectEvent.ProjectItemRestored)
                    eventCaptor.getAllValues().get(1);
            assertThat(restoredEvent.item().actorId()).isEqualTo(actorId);
        }

        @Test
        void shouldBeIdempotentWhenDtoAlreadyNotArchived() {
            GitHubProjectItemDTO dto = createDraftIssueDTO(); // archived=false
            ProjectItem entity = createProjectItemEntity(ProjectItem.ContentType.DRAFT_ISSUE);
            stubSuccessfulProcess(false, entity);

            ProjectItem result = processor.processRestored(dto, project, context);

            assertNotNull(result);
            // Verify archived=false is passed
            verify(projectItemRepository)
                    .upsertCore(
                            any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(false), any(), any(),
                            any());
        }
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ExponentialBackoff;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Item;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemType;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedProjectItemsDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedProjectItemsDTO.EmbeddedProjectItem;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.EmbeddedProjectItemsDTO.EmbeddedProjectReference;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectFieldValueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectItemDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link GitHubProjectItemSyncService}.
 * <p>
 * Focuses on the embedded sync path where project items are processed inline
 * with issue/PR queries, including field value delegation to
 * {@link GitHubProjectItemFieldValueSyncService}.
 */
class GitHubProjectItemSyncServiceTest extends BaseUnitTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private GitHubProjectItemProcessor projectItemProcessor;

    @Mock
    private GitHubProjectItemFieldValueSyncService fieldValueSyncService;

    @Mock
    private GitHubGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitHubSyncProperties syncProperties;

    @Mock
    private GitHubExceptionClassifier exceptionClassifier;

    @Mock
    private TransactionTemplate transactionTemplate;

    private GitHubProjectItemSyncService service;

    private static final Long SCOPE_ID = 100L;
    private static final Long PARENT_ISSUE_ID = 500L;
    private static final String PROJECT_NODE_ID = "PVT_node1";
    private static final Long PROJECT_DB_ID = 10L;

    @BeforeEach
    void setUp() {
        // Default exception classifier stub to prevent NPEs on unexpected exceptions
        lenient()
                .when(exceptionClassifier.classifyWithDetails(any()))
                .thenReturn(ClassificationResult.of(Category.UNKNOWN, "test error"));

        service = new GitHubProjectItemSyncService(
                projectRepository,
                projectItemProcessor,
                fieldValueSyncService,
                graphQlClientProvider,
                syncProperties,
                new SyncSchedulerProperties(
                        true,
                        7,
                        "0 0 3 * * *",
                        15,
                        null,
                        null,
                        null,
                        new SyncSchedulerProperties.ProjectsProperties(true)),
                exceptionClassifier,
                transactionTemplate);
    }

    // Helper methods

    private Project createProject() {
        Project project = new Project();
        project.setId(PROJECT_DB_ID);
        project.setNodeId(PROJECT_NODE_ID);
        project.setNumber(1);
        project.setTitle("Test Project");
        return project;
    }

    private ProjectItem createProjectItem(Long id) {
        ProjectItem item = new ProjectItem();
        item.setId(id);
        item.setNodeId("PVTI_item" + id);
        return item;
    }

    private GitHubProjectItemDTO createItemDTO(String nodeId, List<GitHubProjectFieldValueDTO> fieldValues) {
        return new GitHubProjectItemDTO(
                null,
                100L,
                nodeId,
                PROJECT_NODE_ID,
                "ISSUE",
                null,
                42,
                null,
                null,
                false,
                null,
                fieldValues,
                false,
                fieldValues != null ? fieldValues.size() : 0,
                null,
                Instant.now(),
                Instant.now());
    }

    private GitHubProjectFieldValueDTO createFieldValueDTO(String fieldId, String type, @Nullable String value) {
        return new GitHubProjectFieldValueDTO(fieldId, type, value, null, null, null, null);
    }

    private EmbeddedProjectReference createProjectRef() {
        return new EmbeddedProjectReference(
                PROJECT_NODE_ID,
                PROJECT_DB_ID,
                1,
                "Test Project",
                "https://github.com/orgs/test/projects/1",
                "test-org",
                42L,
                "ORGANIZATION");
    }

    /**
     * Creates a GHProjectV2 GraphQL model with the standard project node ID,
     * used when building GHProjectV2Item objects for pagination tests.
     */
    private GHProjectV2 createGHProjectV2() {
        GHProjectV2 ghProject = new GHProjectV2();
        ghProject.setId(PROJECT_NODE_ID);
        ghProject.setFullDatabaseId(BigInteger.valueOf(PROJECT_DB_ID));
        ghProject.setNumber(1);
        ghProject.setTitle("Test Project");
        return ghProject;
    }

    // processEmbeddedItems tests

    @Nested
    class ProcessEmbeddedItems {

        @Test
        void shouldReturnZeroWhenEmbeddedItemsIsNull() {
            int result = service.processEmbeddedItems(
                    null, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            assertThat(result).isZero();
            verify(projectItemProcessor, never()).process(any(), any(), any());
        }

        @Test
        void shouldReturnZeroWhenItemsListIsEmpty() {
            EmbeddedProjectItemsDTO emptyItems = EmbeddedProjectItemsDTO.empty();

            int result = service.processEmbeddedItems(
                    emptyItems, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            assertThat(result).isZero();
            verify(projectItemProcessor, never()).process(any(), any(), any());
        }

        @Test
        void shouldProcessItemsAndDelegateFieldValues() {
            Project project = createProject();
            when(projectRepository.findByNodeId(PROJECT_NODE_ID)).thenReturn(Optional.of(project));

            GitHubProjectFieldValueDTO fv1 = createFieldValueDTO("field-1", "TEXT", "value1");
            GitHubProjectFieldValueDTO fv2 = createFieldValueDTO("field-2", "NUMBER", null);
            GitHubProjectItemDTO itemDto = createItemDTO("PVTI_1", List.of(fv1, fv2));

            EmbeddedProjectItem embeddedItem = new EmbeddedProjectItem(itemDto, createProjectRef());
            EmbeddedProjectItemsDTO embeddedItems = new EmbeddedProjectItemsDTO(List.of(embeddedItem), 1, false, null);

            ProjectItem processedItem = createProjectItem(77L);
            when(projectItemProcessor.process(any(GitHubProjectItemDTO.class), eq(project), any()))
                    .thenReturn(processedItem);

            int result = service.processEmbeddedItems(
                    embeddedItems, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            assertThat(result).isEqualTo(1);
            verify(fieldValueSyncService).processFieldValues(eq(77L), any(), eq(false), eq(null));
        }

        @Test
        void shouldPassTruncationInfoToFieldValueSyncService() {
            Project project = createProject();
            when(projectRepository.findByNodeId(PROJECT_NODE_ID)).thenReturn(Optional.of(project));

            // Create a truncated item — more field values exist than were fetched inline
            GitHubProjectFieldValueDTO fv1 = createFieldValueDTO("field-1", "TEXT", "value1");
            GitHubProjectItemDTO itemDto = new GitHubProjectItemDTO(
                    null,
                    100L,
                    "PVTI_trunc",
                    PROJECT_NODE_ID,
                    "ISSUE",
                    null,
                    42,
                    null,
                    null,
                    false,
                    null,
                    List.of(fv1),
                    true, // truncated
                    15, // totalCount > fetched
                    "cursor-abc", // endCursor for pagination
                    Instant.now(),
                    Instant.now());

            EmbeddedProjectItem embeddedItem = new EmbeddedProjectItem(itemDto, createProjectRef());
            EmbeddedProjectItemsDTO embeddedItems = new EmbeddedProjectItemsDTO(List.of(embeddedItem), 1, false, null);

            ProjectItem processedItem = createProjectItem(88L);
            when(projectItemProcessor.process(any(GitHubProjectItemDTO.class), eq(project), any()))
                    .thenReturn(processedItem);

            service.processEmbeddedItems(
                    embeddedItems, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            // Assert — truncation flag and cursor are forwarded
            verify(fieldValueSyncService).processFieldValues(eq(88L), any(), eq(true), eq("cursor-abc"));
        }

        @Test
        void shouldSkipItemsWhenProjectNotSynced() {
            // Arrange — project not found in repository
            when(projectRepository.findByNodeId(PROJECT_NODE_ID)).thenReturn(Optional.empty());

            GitHubProjectItemDTO itemDto = createItemDTO("PVTI_orphan", List.of());
            EmbeddedProjectItem embeddedItem = new EmbeddedProjectItem(itemDto, createProjectRef());
            EmbeddedProjectItemsDTO embeddedItems = new EmbeddedProjectItemsDTO(List.of(embeddedItem), 1, false, null);

            int result = service.processEmbeddedItems(
                    embeddedItems, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            assertThat(result).isZero();
            verify(projectItemProcessor, never()).process(any(), any(), any());
            verify(fieldValueSyncService, never()).processFieldValues(any(), any(), any(boolean.class), any());
        }

        @Test
        void shouldContinueProcessingWhenOneFails() {
            Project project = createProject();
            when(projectRepository.findByNodeId(PROJECT_NODE_ID)).thenReturn(Optional.of(project));

            GitHubProjectItemDTO item1 = createItemDTO("PVTI_fail", List.of());
            GitHubProjectItemDTO item2 = createItemDTO("PVTI_ok", List.of());

            EmbeddedProjectItem embedded1 = new EmbeddedProjectItem(item1, createProjectRef());
            EmbeddedProjectItem embedded2 = new EmbeddedProjectItem(item2, createProjectRef());
            EmbeddedProjectItemsDTO embeddedItems =
                    new EmbeddedProjectItemsDTO(List.of(embedded1, embedded2), 2, false, null);

            // First item throws, second succeeds
            when(projectItemProcessor.process(any(GitHubProjectItemDTO.class), eq(project), any()))
                    .thenThrow(new RuntimeException("DB error"))
                    .thenReturn(createProjectItem(99L));

            int result = service.processEmbeddedItems(
                    embeddedItems, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            // Assert — one succeeded despite the first failing
            assertThat(result).isEqualTo(1);
            verify(fieldValueSyncService).processFieldValues(eq(99L), any(), eq(false), eq(null));
        }

        @Test
        void shouldNotCallFieldValueSyncServiceWhenProcessorReturnsNull() {
            Project project = createProject();
            when(projectRepository.findByNodeId(PROJECT_NODE_ID)).thenReturn(Optional.of(project));

            GitHubProjectItemDTO itemDto = createItemDTO("PVTI_null", List.of());
            EmbeddedProjectItem embeddedItem = new EmbeddedProjectItem(itemDto, createProjectRef());
            EmbeddedProjectItemsDTO embeddedItems = new EmbeddedProjectItemsDTO(List.of(embeddedItem), 1, false, null);

            when(projectItemProcessor.process(any(GitHubProjectItemDTO.class), eq(project), any()))
                    .thenReturn(null);

            int result = service.processEmbeddedItems(
                    embeddedItems, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            assertThat(result).isZero();
            verify(fieldValueSyncService, never()).processFieldValues(any(), any(), any(boolean.class), any());
        }

        @Test
        void shouldPropagateParentIssueIdViaWithIssueId() {
            Project project = createProject();
            when(projectRepository.findByNodeId(PROJECT_NODE_ID)).thenReturn(Optional.of(project));

            // Item DTO with null issueId — simulates embedded item without content block
            GitHubProjectItemDTO itemDto = createItemDTO("PVTI_propagate", List.of());
            EmbeddedProjectItem embeddedItem = new EmbeddedProjectItem(itemDto, createProjectRef());
            EmbeddedProjectItemsDTO embeddedItems = new EmbeddedProjectItemsDTO(List.of(embeddedItem), 1, false, null);

            ProjectItem processedItem = createProjectItem(55L);
            when(projectItemProcessor.process(any(GitHubProjectItemDTO.class), eq(project), any()))
                    .thenReturn(processedItem);

            service.processEmbeddedItems(
                    embeddedItems, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            // Assert — verify that the DTO passed to processor has the parentIssueId injected
            ArgumentCaptor<GitHubProjectItemDTO> dtoCaptor = ArgumentCaptor.forClass(GitHubProjectItemDTO.class);
            verify(projectItemProcessor).process(dtoCaptor.capture(), eq(project), any());
            assertThat(dtoCaptor.getValue().issueId()).isEqualTo(PARENT_ISSUE_ID);
        }

        @Test
        void shouldCountItemAsFailedWhenFieldValueSyncServiceThrows() {
            Project project = createProject();
            when(projectRepository.findByNodeId(PROJECT_NODE_ID)).thenReturn(Optional.of(project));

            GitHubProjectFieldValueDTO fv = createFieldValueDTO("field-1", "TEXT", "val");
            GitHubProjectItemDTO item1 = createItemDTO("PVTI_fv_fail", List.of(fv));
            GitHubProjectItemDTO item2 = createItemDTO("PVTI_fv_ok", List.of());

            EmbeddedProjectItem embedded1 = new EmbeddedProjectItem(item1, createProjectRef());
            EmbeddedProjectItem embedded2 = new EmbeddedProjectItem(item2, createProjectRef());
            EmbeddedProjectItemsDTO embeddedItems =
                    new EmbeddedProjectItemsDTO(List.of(embedded1, embedded2), 2, false, null);

            ProjectItem item1Result = createProjectItem(60L);
            ProjectItem item2Result = createProjectItem(61L);
            when(projectItemProcessor.process(any(GitHubProjectItemDTO.class), eq(project), any()))
                    .thenReturn(item1Result)
                    .thenReturn(item2Result);

            // fieldValueSyncService throws on the first item
            when(fieldValueSyncService.processFieldValues(eq(60L), any(), any(boolean.class), any()))
                    .thenThrow(new RuntimeException("Field value error"));

            int result = service.processEmbeddedItems(
                    embeddedItems, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            // Assert — first item fails (caught by exception handler), second succeeds
            assertThat(result).isEqualTo(1);
            verify(fieldValueSyncService).processFieldValues(eq(61L), any(), any(boolean.class), any());
        }

        @Test
        void shouldSkipEmbeddedItemWhenItemDtoIsNull() {
            EmbeddedProjectItem embeddedItem = new EmbeddedProjectItem(null, createProjectRef());
            EmbeddedProjectItemsDTO embeddedItems = new EmbeddedProjectItemsDTO(List.of(embeddedItem), 1, false, null);

            int result = service.processEmbeddedItems(
                    embeddedItems, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            assertThat(result).isZero();
            verify(projectRepository, never()).findByNodeId(any());
        }

        @Test
        void shouldSkipEmbeddedItemWhenProjectReferenceIsNull() {
            GitHubProjectItemDTO itemDto = createItemDTO("PVTI_no_proj", List.of());
            EmbeddedProjectItem embeddedItem = new EmbeddedProjectItem(itemDto, null);
            EmbeddedProjectItemsDTO embeddedItems = new EmbeddedProjectItemsDTO(List.of(embeddedItem), 1, false, null);

            int result = service.processEmbeddedItems(
                    embeddedItems, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            assertThat(result).isZero();
            verify(projectItemProcessor, never()).process(any(), any(), any());
        }

        @Test
        void shouldProcessMultipleItemsFromDifferentProjects() {
            Project project1 = createProject();
            Project project2 = new Project();
            project2.setId(20L);
            project2.setNodeId("PVT_node2");
            project2.setNumber(2);
            project2.setTitle("Test Project 2");

            when(projectRepository.findByNodeId(PROJECT_NODE_ID)).thenReturn(Optional.of(project1));
            when(projectRepository.findByNodeId("PVT_node2")).thenReturn(Optional.of(project2));

            GitHubProjectItemDTO item1 = createItemDTO("PVTI_p1", List.of());
            GitHubProjectItemDTO item2 = new GitHubProjectItemDTO(
                    null,
                    200L,
                    "PVTI_p2",
                    "PVT_node2",
                    "PULL_REQUEST",
                    null,
                    99,
                    null,
                    null,
                    false,
                    null,
                    List.of(),
                    false,
                    0,
                    null,
                    Instant.now(),
                    Instant.now());

            EmbeddedProjectReference projectRef2 = new EmbeddedProjectReference(
                    "PVT_node2", 20L, 2, "Test Project 2", null, "test-org", 42L, "ORGANIZATION");

            EmbeddedProjectItem embedded1 = new EmbeddedProjectItem(item1, createProjectRef());
            EmbeddedProjectItem embedded2 = new EmbeddedProjectItem(item2, projectRef2);
            EmbeddedProjectItemsDTO embeddedItems =
                    new EmbeddedProjectItemsDTO(List.of(embedded1, embedded2), 2, false, null);

            when(projectItemProcessor.process(any(GitHubProjectItemDTO.class), any(Project.class), any()))
                    .thenReturn(createProjectItem(71L))
                    .thenReturn(createProjectItem(72L));

            int result = service.processEmbeddedItems(
                    embeddedItems, ProcessingContext.forSync(SCOPE_ID, (Repository) null), PARENT_ISSUE_ID);

            assertThat(result).isEqualTo(2);
            verify(projectItemProcessor).process(any(GitHubProjectItemDTO.class), eq(project1), any());
            verify(projectItemProcessor).process(any(GitHubProjectItemDTO.class), eq(project2), any());
            verify(fieldValueSyncService, times(2)).processFieldValues(any(), any(), any(boolean.class), any());
        }
    }

    // syncRemainingProjectItems tests

    @Nested
    class SyncRemainingProjectItems {

        @Mock
        private HttpGraphQlClient graphQlClient;

        @Mock
        private RequestSpec requestSpec;

        @Test
        void shouldReturnZeroWhenIssueNodeIdIsNull() {
            int result = service.syncRemainingProjectItems(
                    SCOPE_ID, null, false, new Repository(), "cursor", PARENT_ISSUE_ID);

            assertThat(result).isZero();
            verify(graphQlClientProvider, never()).forScope(any());
        }

        @Test
        void shouldReturnZeroWhenIssueNodeIdIsBlank() {
            int result = service.syncRemainingProjectItems(
                    SCOPE_ID, "  ", false, new Repository(), "cursor", PARENT_ISSUE_ID);

            assertThat(result).isZero();
            verify(graphQlClientProvider, never()).forScope(any());
        }

        @Test
        void shouldPaginateThroughRemainingItemsForIssue() {
            Repository repository = new Repository();
            when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(graphQlClient);
            when(syncProperties.graphqlTimeout()).thenReturn(Duration.ofSeconds(10));
            when(graphQlClient.documentName("GetIssueProjectItems")).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

            // Build a single page with one item, no more pages
            GHProjectV2Item ghItem = new GHProjectV2Item();
            ghItem.setId("PVTI_paginated");
            ghItem.setFullDatabaseId(BigInteger.valueOf(300));
            ghItem.setType(GHProjectV2ItemType.ISSUE);
            ghItem.setIsArchived(false);
            ghItem.setProject(createGHProjectV2());

            GHProjectV2ItemConnection connection = new GHProjectV2ItemConnection();
            connection.setNodes(List.of(ghItem));
            GHPageInfo pageInfo = pageInfo(null, false);
            connection.setPageInfo(pageInfo);

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.isValid()).thenReturn(true);
            ClientResponseField responseField = mock(ClientResponseField.class);
            when(response.field("node.projectItems")).thenReturn(responseField);
            when(responseField.toEntity(GHProjectV2ItemConnection.class)).thenReturn(connection);
            when(requestSpec.execute()).thenReturn(Mono.just(response));

            // Mock transaction template to execute the action
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, TransactionCallback.class);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });

            // The embedded item creation from GHProjectV2Item will yield a DTO;
            // project lookup should find the project
            Project project = createProject();
            when(projectRepository.findByNodeId(any())).thenReturn(Optional.of(project));
            when(projectItemProcessor.process(any(GitHubProjectItemDTO.class), any(Project.class), any()))
                    .thenReturn(createProjectItem(150L));

            int result = service.syncRemainingProjectItems(
                    SCOPE_ID, "I_nodeId123", false, repository, "startCursor", PARENT_ISSUE_ID);

            assertThat(result).isEqualTo(1);
            // Verify issue-specific query was used
            verify(graphQlClient).documentName("GetIssueProjectItems");
            verify(requestSpec).variable("issueId", "I_nodeId123");
        }

        @Test
        void shouldUsePrSpecificQueryForPullRequests() {
            Repository repository = new Repository();
            when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(graphQlClient);
            when(syncProperties.graphqlTimeout()).thenReturn(Duration.ofSeconds(10));
            when(graphQlClient.documentName("GetPullRequestProjectItems")).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

            // Empty connection — no more items
            GHProjectV2ItemConnection connection = new GHProjectV2ItemConnection();
            connection.setNodes(List.of());
            connection.setPageInfo(new GHPageInfo());

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.isValid()).thenReturn(true);
            ClientResponseField responseField = mock(ClientResponseField.class);
            when(response.field("node.projectItems")).thenReturn(responseField);
            when(responseField.toEntity(GHProjectV2ItemConnection.class)).thenReturn(connection);
            when(requestSpec.execute()).thenReturn(Mono.just(response));

            int result = service.syncRemainingProjectItems(
                    SCOPE_ID, "PR_nodeId456", true, repository, "startCursor", PARENT_ISSUE_ID);

            assertThat(result).isZero();
            verify(graphQlClient).documentName("GetPullRequestProjectItems");
            verify(requestSpec).variable("pullRequestId", "PR_nodeId456");
        }

        @Test
        void shouldStopOnRateLimitCritical() throws InterruptedException {
            Repository repository = new Repository();
            when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(graphQlClient);
            when(syncProperties.graphqlTimeout()).thenReturn(Duration.ofSeconds(10));
            when(graphQlClient.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.isValid()).thenReturn(true);
            when(requestSpec.execute()).thenReturn(Mono.just(response));

            // Rate limit becomes critical after first request and wait fails
            when(graphQlClientProvider.isRateLimitCritical(SCOPE_ID)).thenReturn(true);
            when(graphQlClientProvider.waitIfRateLimitLow(SCOPE_ID)).thenThrow(new InterruptedException("rate limit"));

            int result = service.syncRemainingProjectItems(
                    SCOPE_ID, "I_nodeId", false, repository, "cursor", PARENT_ISSUE_ID);

            assertThat(result).isZero();
            verify(graphQlClientProvider).trackRateLimit(eq(SCOPE_ID), eq(response));
        }

        @Test
        void shouldHandleEmptyResponseWhenAllItemsInEmbeddedList() {
            Repository repository = new Repository();
            when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(graphQlClient);
            when(syncProperties.graphqlTimeout()).thenReturn(Duration.ofSeconds(10));
            when(graphQlClient.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

            // Response with null connection
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.isValid()).thenReturn(true);
            ClientResponseField responseField = mock(ClientResponseField.class);
            when(response.field("node.projectItems")).thenReturn(responseField);
            when(responseField.toEntity(GHProjectV2ItemConnection.class)).thenReturn(null);
            when(requestSpec.execute()).thenReturn(Mono.just(response));

            int result = service.syncRemainingProjectItems(
                    SCOPE_ID, "I_nodeId", false, repository, "cursor", PARENT_ISSUE_ID);

            assertThat(result).isZero();
            verify(transactionTemplate, never()).execute(any());
        }

        @Test
        void shouldHandleInvalidGraphQlResponseAndBreak() {
            Repository repository = new Repository();
            when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(graphQlClient);
            when(syncProperties.graphqlTimeout()).thenReturn(Duration.ofSeconds(10));
            when(graphQlClient.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.isValid()).thenReturn(false);
            when(requestSpec.execute()).thenReturn(Mono.just(response));

            int result = service.syncRemainingProjectItems(
                    SCOPE_ID, "I_nodeId", false, repository, "cursor", PARENT_ISSUE_ID);

            assertThat(result).isZero();
        }

        @Test
        void shouldStopAfterThreeRetriesWithoutPersistingItems() {
            Repository repository = new Repository();
            when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(graphQlClient);
            when(syncProperties.graphqlTimeout()).thenReturn(Duration.ofSeconds(10));
            when(graphQlClient.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

            when(requestSpec.execute()).thenReturn(Mono.error(new RuntimeException("Network error")));

            when(exceptionClassifier.classifyWithDetails(any()))
                    .thenReturn(ClassificationResult.of(GitHubExceptionClassifier.Category.RETRYABLE, "Network error"));

            try (var backoff = mockStatic(ExponentialBackoff.class)) {
                int result = service.syncRemainingProjectItems(
                        SCOPE_ID, "I_nodeId", false, repository, "cursor", PARENT_ISSUE_ID);

                assertThat(result).isZero();
                verify(requestSpec, times(4)).execute();
                verify(transactionTemplate, never()).execute(any());
                backoff.verify(() -> ExponentialBackoff.sleep(1));
                backoff.verify(() -> ExponentialBackoff.sleep(2));
                backoff.verify(() -> ExponentialBackoff.sleep(3));
                backoff.verifyNoMoreInteractions();
            }
        }

        @Test
        void shouldPaginateThroughMultiplePages() {
            Repository repository = new Repository();
            when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(graphQlClient);
            when(syncProperties.graphqlTimeout()).thenReturn(Duration.ofSeconds(10));
            when(graphQlClient.documentName(anyString())).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

            // Page 1: one item, has next page
            GHProjectV2Item ghItem1 = new GHProjectV2Item();
            ghItem1.setId("PVTI_page1");
            ghItem1.setFullDatabaseId(BigInteger.valueOf(301));
            ghItem1.setType(GHProjectV2ItemType.ISSUE);
            ghItem1.setIsArchived(false);
            ghItem1.setProject(createGHProjectV2());

            GHProjectV2ItemConnection page1 = new GHProjectV2ItemConnection();
            page1.setNodes(List.of(ghItem1));
            page1.setPageInfo(pageInfo("cursor-page2", true));

            // Page 2: one item, no more pages
            GHProjectV2Item ghItem2 = new GHProjectV2Item();
            ghItem2.setId("PVTI_page2");
            ghItem2.setFullDatabaseId(BigInteger.valueOf(302));
            ghItem2.setType(GHProjectV2ItemType.PULL_REQUEST);
            ghItem2.setIsArchived(false);
            ghItem2.setProject(createGHProjectV2());

            GHProjectV2ItemConnection page2 = new GHProjectV2ItemConnection();
            page2.setNodes(List.of(ghItem2));
            page2.setPageInfo(pageInfo(null, false));

            ClientGraphQlResponse response1 = mock(ClientGraphQlResponse.class);
            when(response1.isValid()).thenReturn(true);
            ClientResponseField field1 = mock(ClientResponseField.class);
            when(response1.field("node.projectItems")).thenReturn(field1);
            when(field1.toEntity(GHProjectV2ItemConnection.class)).thenReturn(page1);

            ClientGraphQlResponse response2 = mock(ClientGraphQlResponse.class);
            when(response2.isValid()).thenReturn(true);
            ClientResponseField field2 = mock(ClientResponseField.class);
            when(response2.field("node.projectItems")).thenReturn(field2);
            when(field2.toEntity(GHProjectV2ItemConnection.class)).thenReturn(page2);

            when(requestSpec.execute()).thenReturn(Mono.just(response1)).thenReturn(Mono.just(response2));

            // Mock transaction template
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                var callback = invocation.getArgument(0, TransactionCallback.class);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });

            Project project = createProject();
            when(projectRepository.findByNodeId(any())).thenReturn(Optional.of(project));
            when(projectItemProcessor.process(any(GitHubProjectItemDTO.class), any(Project.class), any()))
                    .thenReturn(createProjectItem(160L))
                    .thenReturn(createProjectItem(161L));

            int result = service.syncRemainingProjectItems(
                    SCOPE_ID, "I_nodeId", false, repository, "startCursor", PARENT_ISSUE_ID);

            assertThat(result).isEqualTo(2);
            verify(graphQlClientProvider, times(2)).trackRateLimit(eq(SCOPE_ID), any());
        }
    }

    private static GHPageInfo pageInfo(@Nullable String endCursor, boolean hasNextPage) {
        GHPageInfo pageInfo = new GHPageInfo();
        pageInfo.setHasNextPage(hasNextPage);
        if (endCursor != null) pageInfo.setEndCursor(endCursor);
        return pageInfo;
    }
}

package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.BackfillProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.FilterProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Connection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Field;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Item;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldTextValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldValueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemType;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.dto.GitHubProjectDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link GitHubProjectSyncService}.
 */
class GitHubProjectSyncServiceTest extends BaseUnitTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private GitHubGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitHubProjectProcessor projectProcessor;

    @Mock
    private GitHubProjectItemProcessor itemProcessor;

    @Mock
    private GitHubProjectStatusUpdateProcessor statusUpdateProcessor;

    @Mock
    private GitHubProjectItemFieldValueSyncService fieldValueSyncService;

    @Mock
    private GitHubProjectBackfillStateProvider backfillStateProvider;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private GitHubExceptionClassifier exceptionClassifier;

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private GraphQlClient.RequestSpec requestSpec;

    @Mock
    private PlatformTransactionManager transactionManager;

    private GitHubProjectSyncService service;

    private static final Long SCOPE_ID = 100L;
    private static final String ORG_LOGIN = "test-org";
    private static final Long ORG_ID = 42L;
    private static final Long ORG_DB_ID = 1L;

    private GitHubSyncProperties syncProperties;
    private SyncSchedulerProperties syncSchedulerProperties;

    @BeforeEach
    void setUp() {
        syncProperties = new GitHubSyncProperties(
                Duration.ofSeconds(30), // graphqlTimeout
                Duration.ofSeconds(60), // extendedGraphqlTimeout
                Duration.ofSeconds(120), // backfillGraphqlTimeout
                Duration.ZERO, // paginationThrottle - zero to avoid Thread.sleep
                true, // incrementalSyncEnabled
                Duration.ofMinutes(5), // incrementalSyncBuffer
                10 // backfillPrPageSize
                );

        syncSchedulerProperties = new SyncSchedulerProperties(
                true, // runOnStartup
                7, // timeframeDays
                "0 0 3 * * *", // cron
                15, // cooldownMinutes
                new BackfillProperties(false, 50, 100, 60),
                new FilterProperties(Set.of(), Set.of(), Set.of()), // empty = all allowed
                null,
                new SyncSchedulerProperties.ProjectsProperties(true) // projects enabled for these tests
                );

        // TransactionTemplate mocking: execute callbacks immediately
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient()
                .doAnswer(invocation -> {
                    Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        lenient().when(transactionTemplate.getTransactionManager()).thenReturn(transactionManager);

        // Default exception classifier stub to prevent NPEs on unexpected exceptions
        lenient()
                .when(exceptionClassifier.classifyWithDetails(any()))
                .thenReturn(ClassificationResult.of(Category.UNKNOWN, "test error"));

        service = new GitHubProjectSyncService(
                projectRepository,
                organizationRepository,
                graphQlClientProvider,
                projectProcessor,
                itemProcessor,
                statusUpdateProcessor,
                fieldValueSyncService,
                backfillStateProvider,
                transactionTemplate,
                syncProperties,
                syncSchedulerProperties,
                exceptionClassifier);
    }

    // Helper methods

    private Organization createOrganization() {
        IdentityProvider provider = new IdentityProvider();
        provider.setId(1L);
        provider.setType(IdentityProviderType.GITHUB);
        provider.setServerUrl("https://api.github.com");

        Organization org = new Organization();
        org.setId(ORG_DB_ID);
        org.setNativeId(ORG_ID);
        org.setLogin(ORG_LOGIN);
        org.setName("Test Organization");
        org.setHtmlUrl("https://github.com/test-org");
        org.setProvider(provider);
        return org;
    }

    private Project createProject(Long id, String nodeId, int number) {
        IdentityProvider provider = new IdentityProvider();
        provider.setId(1L);
        provider.setType(IdentityProviderType.GITHUB);
        provider.setServerUrl("https://api.github.com");

        Project project = new Project();
        project.setId(id);
        project.setNodeId(nodeId);
        project.setOwnerType(Project.OwnerType.ORGANIZATION);
        project.setOwnerId(ORG_DB_ID);
        project.setNumber(number);
        project.setTitle("Project " + number);
        project.setProvider(provider);
        return project;
    }

    private GHProjectV2 createGraphQlProject(long databaseId, String nodeId, int number) {
        GHProjectV2 ghProject = new GHProjectV2();
        ghProject.setFullDatabaseId(BigInteger.valueOf(databaseId));
        ghProject.setId(nodeId);
        ghProject.setNumber(number);
        ghProject.setTitle("Project " + number);
        ghProject.setClosed(false);
        ghProject.setPublic(true);
        ghProject.setTemplate(false);
        return ghProject;
    }

    private void mockGraphQlClientForScope() {
        when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(client);
    }

    private void mockGraphQlRequestChain() {
        mockGraphQlRequestChain(anyString());
    }

    private void mockGraphQlRequestChain(String documentName) {
        when(client.documentName(documentName)).thenReturn(requestSpec);
        when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
    }

    private ClientGraphQlResponse mockValidGraphQlResponse(String fieldPath, @Nullable Object entity) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField field = mock(ClientResponseField.class);
        lenient().when(response.isValid()).thenReturn(true);
        lenient().when(response.field(fieldPath)).thenReturn(field);
        lenient().when(field.toEntity(ArgumentMatchers.<Class<Object>>any())).thenReturn(entity);
        // Mock the parent "node" field for deleted-project detection checks
        if (fieldPath.startsWith("node.")) {
            ClientResponseField nodeField = mock(ClientResponseField.class);
            lenient().when(nodeField.getValue()).thenReturn(new Object());
            lenient().when(response.field("node")).thenReturn(nodeField);
        }
        return response;
    }

    private ClientGraphQlResponse mockInvalidGraphQlResponse() {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        when(response.isValid()).thenReturn(false);
        when(response.getErrors()).thenReturn(List.of());
        return response;
    }

    private GHProjectV2Connection createProjectConnection(List<GHProjectV2> nodes, boolean hasNextPage) {
        GHPageInfo pageInfo = new GHPageInfo();
        pageInfo.setHasNextPage(hasNextPage);
        if (hasNextPage) pageInfo.setEndCursor("cursor1");
        GHProjectV2Connection connection = new GHProjectV2Connection();
        connection.setNodes(nodes);
        connection.setPageInfo(pageInfo);
        return connection;
    }

    // syncProjectsForOrganization tests

    @Nested
    class SyncProjectsForOrganization {

        @Test
        void shouldReturnCompletedZeroWhenOrgLoginIsNull() {
            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, null);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            assertThat(result.count()).isZero();
        }

        @Test
        void shouldReturnCompletedZeroWhenOrgLoginIsBlank() {
            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, "   ");

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            assertThat(result.count()).isZero();
        }

        @Test
        void shouldReturnCompletedZeroWhenOrganizationNotFound() {
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.empty());

            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            assertThat(result.count()).isZero();
        }

        @Test
        void shouldSyncSinglePageOfProjectsSuccessfully() {
            Organization org = createOrganization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.of(org));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain("GetOrganizationProjects");

            GHProjectV2 ghProject = createGraphQlProject(100L, "PVT_node1", 1);
            GHProjectV2Connection connection = createProjectConnection(List.of(ghProject), false);
            ClientGraphQlResponse response = mockValidGraphQlResponse("organization.projectsV2", connection);
            when(requestSpec.execute()).thenReturn(Mono.just(response));

            Project processedProject = createProject(100L, "PVT_node1", 1);
            when(projectRepository.findById(100L)).thenReturn(Optional.empty());
            when(projectProcessor.process(
                            any(GitHubProjectDTO.class),
                            eq(Project.OwnerType.ORGANIZATION),
                            eq(ORG_DB_ID),
                            any(ProcessingContext.class)))
                    .thenReturn(processedProject);
            when(projectRepository.save(any(Project.class))).thenReturn(processedProject);

            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            assertThat(result.count()).isEqualTo(1);
            verify(projectProcessor)
                    .process(
                            any(GitHubProjectDTO.class),
                            eq(Project.OwnerType.ORGANIZATION),
                            eq(ORG_DB_ID),
                            any(ProcessingContext.class));
            verify(projectRepository).save(any(Project.class));
        }

        @Test
        void shouldSkipProjectsWithinCooldownPeriod() {
            Organization org = createOrganization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.of(org));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain("GetOrganizationProjects");

            GHProjectV2 ghProject = createGraphQlProject(100L, "PVT_node1", 1);
            GHProjectV2Connection connection = createProjectConnection(List.of(ghProject), false);
            ClientGraphQlResponse response = mockValidGraphQlResponse("organization.projectsV2", connection);
            when(requestSpec.execute()).thenReturn(Mono.just(response));

            // Project was synced 5 minutes ago (within 15-minute cooldown)
            Project existingProject = createProject(100L, "PVT_node1", 1);
            existingProject.setLastSyncAt(Instant.now().minusSeconds(5 * 60));
            when(projectRepository.findById(100L)).thenReturn(Optional.of(existingProject));

            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            assertThat(result.count()).isZero(); // Skipped due to cooldown
            verify(projectProcessor, never()).process(any(), any(), any(), any());
        }

        @Test
        void shouldAbortWhenRateLimitCritical() throws InterruptedException {
            Organization org = createOrganization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.of(org));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain("GetOrganizationProjects");

            GHProjectV2 ghProject = createGraphQlProject(100L, "PVT_node1", 1);
            GHProjectV2Connection connection = createProjectConnection(List.of(ghProject), true);
            ClientGraphQlResponse response = mockValidGraphQlResponse("organization.projectsV2", connection);
            when(requestSpec.execute()).thenReturn(Mono.just(response));

            // Rate limit becomes critical after first response and wait fails
            when(graphQlClientProvider.isRateLimitCritical(SCOPE_ID)).thenReturn(true);
            when(graphQlClientProvider.waitIfRateLimitLow(SCOPE_ID)).thenThrow(new InterruptedException("rate limit"));

            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN);

            assertThat(result.status()).isEqualTo(SyncResult.Status.ABORTED_RATE_LIMIT);
            verify(graphQlClientProvider).trackRateLimit(eq(SCOPE_ID), any(ClientGraphQlResponse.class));
        }

        @Test
        void shouldAbortWhenGraphQlResponseInvalid() {
            Organization org = createOrganization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.of(org));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain("GetOrganizationProjects");

            ClientGraphQlResponse invalidResponse = mockInvalidGraphQlResponse();
            when(requestSpec.execute()).thenReturn(Mono.just(invalidResponse));

            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN);

            assertThat(result.status()).isEqualTo(SyncResult.Status.ABORTED_ERROR);
            assertThat(result.count()).isZero();
        }

        @Test
        void shouldRethrowInstallationNotFoundException() {
            Organization org = createOrganization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.of(org));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain("GetOrganizationProjects");

            when(requestSpec.execute()).thenReturn(Mono.error(new InstallationNotFoundException(SCOPE_ID)));

            assertThatThrownBy(() -> service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN))
                    .isInstanceOf(InstallationNotFoundException.class);
        }

        @Test
        void shouldRemoveStaleProjectsOnCompleteSync() {
            Organization org = createOrganization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.of(org));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain("GetOrganizationProjects");

            // Return one project from GraphQL
            GHProjectV2 ghProject = createGraphQlProject(100L, "PVT_node1", 1);
            GHProjectV2Connection connection = createProjectConnection(List.of(ghProject), false);
            ClientGraphQlResponse response = mockValidGraphQlResponse("organization.projectsV2", connection);
            when(requestSpec.execute()).thenReturn(Mono.just(response));

            Project processedProject = createProject(100L, "PVT_node1", 1);
            when(projectRepository.findById(100L)).thenReturn(Optional.empty());
            when(projectProcessor.process(any(GitHubProjectDTO.class), any(), any(), any()))
                    .thenReturn(processedProject);
            when(projectRepository.save(any(Project.class))).thenReturn(processedProject);

            // Database has a stale project that was not returned by GraphQL
            Project staleProject = createProject(999L, "PVT_stale", 99);
            when(projectRepository.findAllByOwnerTypeAndOwnerId(Project.OwnerType.ORGANIZATION, ORG_DB_ID))
                    .thenReturn(List.of(processedProject, staleProject));

            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            verify(projectProcessor).delete(eq(999L), any(ProcessingContext.class));
            verify(projectProcessor, never()).delete(eq(100L), any(ProcessingContext.class));
        }

        @Test
        void shouldNotRemoveStaleProjectsOnAbortedSync() throws InterruptedException {
            Organization org = createOrganization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.of(org));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain("GetOrganizationProjects");

            // First page has next page, but rate limit becomes critical
            GHProjectV2 ghProject = createGraphQlProject(100L, "PVT_node1", 1);
            GHProjectV2Connection connection = createProjectConnection(List.of(ghProject), true);
            ClientGraphQlResponse response = mockValidGraphQlResponse("organization.projectsV2", connection);
            when(requestSpec.execute()).thenReturn(Mono.just(response));
            when(graphQlClientProvider.isRateLimitCritical(SCOPE_ID)).thenReturn(true);
            when(graphQlClientProvider.waitIfRateLimitLow(SCOPE_ID)).thenThrow(new InterruptedException("rate limit"));

            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN);

            assertThat(result.status()).isEqualTo(SyncResult.Status.ABORTED_RATE_LIMIT);
            verify(projectProcessor, never()).delete(anyLong(), any(ProcessingContext.class));
        }

        @Test
        void shouldAbortRateLimitOnRateLimitedException() {
            Organization org = createOrganization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.of(org));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain("GetOrganizationProjects");

            RuntimeException rateLimitEx = new RuntimeException("Rate limited");
            when(requestSpec.execute()).thenReturn(Mono.error(rateLimitEx));
            when(exceptionClassifier.classifyWithDetails(rateLimitEx))
                    .thenReturn(ClassificationResult.of(Category.RATE_LIMITED, "Rate limited"));

            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN);

            assertThat(result.status()).isEqualTo(SyncResult.Status.ABORTED_RATE_LIMIT);
        }

        @Test
        void shouldFilterProjectsBasedOnAllowedProjectsConfig() {
            syncSchedulerProperties = new SyncSchedulerProperties(
                    true,
                    7,
                    "0 0 3 * * *",
                    15,
                    new BackfillProperties(false, 50, 100, 60),
                    new FilterProperties(Set.of(), Set.of(), Set.of("test-org/1")), // Only project #1
                    null,
                    new SyncSchedulerProperties.ProjectsProperties(true));
            service = new GitHubProjectSyncService(
                    projectRepository,
                    organizationRepository,
                    graphQlClientProvider,
                    projectProcessor,
                    itemProcessor,
                    statusUpdateProcessor,
                    fieldValueSyncService,
                    backfillStateProvider,
                    transactionTemplate,
                    syncProperties,
                    syncSchedulerProperties,
                    exceptionClassifier);

            Organization org = createOrganization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.of(org));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain("GetOrganizationProjects");

            GHProjectV2 allowedProject = createGraphQlProject(100L, "PVT_node1", 1);
            GHProjectV2 filteredProject = createGraphQlProject(200L, "PVT_node2", 2);
            GHProjectV2Connection connection = createProjectConnection(List.of(allowedProject, filteredProject), false);
            ClientGraphQlResponse response = mockValidGraphQlResponse("organization.projectsV2", connection);
            when(requestSpec.execute()).thenReturn(Mono.just(response));

            Project processedProject = createProject(100L, "PVT_node1", 1);
            when(projectRepository.findById(100L)).thenReturn(Optional.empty());
            when(projectProcessor.process(any(GitHubProjectDTO.class), any(), any(), any()))
                    .thenReturn(processedProject);
            when(projectRepository.save(any(Project.class))).thenReturn(processedProject);

            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            assertThat(result.count()).isEqualTo(1); // Only 1 project synced, #2 filtered
        }

        @Test
        void shouldRethrowWhenExceptionClassifiedAsAuthError() {
            Organization org = createOrganization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.of(org));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain("GetOrganizationProjects");

            RuntimeException authEx = new RuntimeException("Bad credentials");
            when(requestSpec.execute()).thenReturn(Mono.error(authEx));
            when(exceptionClassifier.classifyWithDetails(authEx))
                    .thenReturn(ClassificationResult.of(Category.AUTH_ERROR, "Auth failed"));

            assertThatThrownBy(() -> service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN))
                    .isSameAs(authEx);
        }

        @Test
        void shouldProcessMultiplePagesOfProjects() {
            Organization org = createOrganization();
            when(organizationRepository.findByLoginIgnoreCaseAndProvider_Type(ORG_LOGIN, IdentityProviderType.GITHUB))
                    .thenReturn(Optional.of(org));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain("GetOrganizationProjects");

            // Page 1: hasNextPage=true
            GHProjectV2 project1 = createGraphQlProject(100L, "PVT_1", 1);
            GHProjectV2Connection page1 = createProjectConnection(List.of(project1), true);
            ClientGraphQlResponse response1 = mockValidGraphQlResponse("organization.projectsV2", page1);

            // Page 2: hasNextPage=false
            GHProjectV2 project2 = createGraphQlProject(200L, "PVT_2", 2);
            GHProjectV2Connection page2 = createProjectConnection(List.of(project2), false);
            ClientGraphQlResponse response2 = mockValidGraphQlResponse("organization.projectsV2", page2);

            when(requestSpec.execute()).thenReturn(Mono.just(response1)).thenReturn(Mono.just(response2));

            Project p1 = createProject(100L, "PVT_1", 1);
            Project p2 = createProject(200L, "PVT_2", 2);
            when(projectRepository.findById(100L)).thenReturn(Optional.empty());
            when(projectRepository.findById(200L)).thenReturn(Optional.empty());
            when(projectProcessor.process(any(GitHubProjectDTO.class), any(), any(), any()))
                    .thenReturn(p1)
                    .thenReturn(p2);
            when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

            SyncResult result = service.syncProjectsForOrganization(SCOPE_ID, ORG_LOGIN);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            assertThat(result.count()).isEqualTo(2);
            verify(projectProcessor, times(2)).process(any(GitHubProjectDTO.class), any(), any(), any());
        }
    }

    // syncProjectItems tests

    @Nested
    class SyncProjectItems {

        @Test
        void shouldReturnCompletedZeroWhenProjectIsNull() {
            SyncResult result = service.syncProjectItems(SCOPE_ID, null);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            assertThat(result.count()).isZero();
        }

        @Test
        void shouldReturnCompletedZeroWhenProjectNodeIdIsNull() {
            Project project = new Project();
            project.setId(1L);

            SyncResult result = service.syncProjectItems(SCOPE_ID, project);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            assertThat(result.count()).isZero();
        }

        @Test
        void shouldCompleteWithAllPhasesWhenEmpty() {
            Project project = createProject(10L, "PVT_node10", 1);
            when(projectRepository.findById(10L)).thenReturn(Optional.of(project));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain();

            // Phase 1: Fields - return empty connection (success)
            ClientGraphQlResponse fieldsResponse = mockValidGraphQlResponse("node.fields", null);
            // Phase 2: Status updates - return empty connection (success)
            ClientGraphQlResponse statusUpdatesResponse = mockValidGraphQlResponse("node.statusUpdates", null);
            // Phase 3: Items - return empty connection
            ClientGraphQlResponse itemsResponse = mockValidGraphQlResponse("node.items", null);

            when(requestSpec.execute())
                    .thenReturn(Mono.just(fieldsResponse))
                    .thenReturn(Mono.just(statusUpdatesResponse))
                    .thenReturn(Mono.just(itemsResponse));

            when(backfillStateProvider.getProjectItemSyncCursor(10L)).thenReturn(Optional.empty());
            when(backfillStateProvider.getProjectItemsSyncedAt(10L)).thenReturn(Optional.empty());

            SyncResult result = service.syncProjectItems(SCOPE_ID, project);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            assertThat(result.count()).isZero();
        }

        @Test
        void shouldReturnCompletedWithWarningsWhenFieldSyncFails() {
            Project project = createProject(10L, "PVT_node10", 1);
            when(projectRepository.findById(10L)).thenReturn(Optional.of(project));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain();

            // Phase 1: Fields - return invalid response (fail)
            ClientGraphQlResponse invalidFieldsResponse = mockInvalidGraphQlResponse();
            // Phase 2: Status updates - return empty (success)
            ClientGraphQlResponse statusUpdatesResponse = mockValidGraphQlResponse("node.statusUpdates", null);
            // Phase 3: Items - return empty (success)
            ClientGraphQlResponse itemsResponse = mockValidGraphQlResponse("node.items", null);

            when(requestSpec.execute())
                    .thenReturn(Mono.just(invalidFieldsResponse))
                    .thenReturn(Mono.just(statusUpdatesResponse))
                    .thenReturn(Mono.just(itemsResponse));

            when(backfillStateProvider.getProjectItemSyncCursor(10L)).thenReturn(Optional.empty());
            when(backfillStateProvider.getProjectItemsSyncedAt(10L)).thenReturn(Optional.empty());

            SyncResult result = service.syncProjectItems(SCOPE_ID, project);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED_WITH_WARNINGS);
        }

        // Issue/PR field value backfill tests

        /**
         * Creates a GHProjectV2Item of ISSUE type with optional field values.
         */
        private GHProjectV2Item createIssueItem(
                String nodeId,
                long fullDbId,
                OffsetDateTime updatedAt,
                List<GHProjectV2ItemFieldTextValue> fieldValues,
                boolean hasNextPage,
                @Nullable String endCursor) {
            GHIssue ghIssue = new GHIssue();
            ghIssue.setFullDatabaseId(BigInteger.valueOf(fullDbId));
            ghIssue.setNumber(42);

            GHPageInfo fvPageInfo = new GHPageInfo();
            fvPageInfo.setHasNextPage(hasNextPage);
            if (endCursor != null) fvPageInfo.setEndCursor(endCursor);

            GHProjectV2ItemFieldValueConnection fvConnection = new GHProjectV2ItemFieldValueConnection();
            fvConnection.setNodes(fieldValues != null ? new ArrayList<>(fieldValues) : List.of());
            fvConnection.setPageInfo(fvPageInfo);
            fvConnection.setTotalCount(fieldValues != null ? fieldValues.size() : 0);

            GHProjectV2Item item = new GHProjectV2Item();
            item.setId(nodeId);
            item.setFullDatabaseId(BigInteger.valueOf(9000 + fullDbId));
            item.setType(GHProjectV2ItemType.ISSUE);
            item.setContent(ghIssue);
            item.setIsArchived(false);
            item.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            item.setUpdatedAt(updatedAt);
            item.setFieldValues(fvConnection);
            return item;
        }

        /**
         * Creates a text field value with the given field ID and text.
         */
        private GHProjectV2ItemFieldTextValue createTextFieldValue(String fieldNodeId, String text) {
            GHProjectV2Field field = new GHProjectV2Field();
            field.setId(fieldNodeId);

            GHProjectV2ItemFieldTextValue textValue = new GHProjectV2ItemFieldTextValue();
            textValue.setField(field);
            textValue.setText(text);
            return textValue;
        }

        /**
         * Creates a GHProjectV2ItemConnection with the given items and no next page.
         */
        private GHProjectV2ItemConnection createItemConnection(List<GHProjectV2Item> items) {
            GHPageInfo pageInfo = new GHPageInfo();
            pageInfo.setHasNextPage(false);

            GHProjectV2ItemConnection connection = new GHProjectV2ItemConnection();
            connection.setNodes(items);
            connection.setPageInfo(pageInfo);
            connection.setTotalCount(items.size());
            return connection;
        }

        /**
         * Mocks the three sequential GraphQL phases (fields, status updates, items)
         * that syncProjectItems calls. Returns the mocked responses for assertion setup.
         */
        private void mockThreePhasesWithItems(Long projectId, GHProjectV2ItemConnection itemsConnection) {
            mockGraphQlClientForScope();
            mockGraphQlRequestChain();

            // Phase 1: Fields - empty success (node exists but fields connection is null)
            ClientGraphQlResponse fieldsResponse = mockValidGraphQlResponse("node.fields", null);
            // Phase 2: Status updates - empty success
            ClientGraphQlResponse statusUpdatesResponse = mockValidGraphQlResponse("node.statusUpdates", null);
            // Phase 3: Items - return the provided connection
            ClientGraphQlResponse itemsResponse = mockValidGraphQlResponse("node.items", itemsConnection);

            when(requestSpec.execute())
                    .thenReturn(Mono.just(fieldsResponse))
                    .thenReturn(Mono.just(statusUpdatesResponse))
                    .thenReturn(Mono.just(itemsResponse));

            when(backfillStateProvider.getProjectItemSyncCursor(projectId)).thenReturn(Optional.empty());
            when(backfillStateProvider.getProjectItemsSyncedAt(projectId)).thenReturn(Optional.empty());
        }

        @Test
        void shouldProcessIssuePrItemsViaItemProcessor() {
            Long projectId = 10L;
            Project project = createProject(projectId, "PVT_node10", 1);

            GHProjectV2ItemFieldTextValue textFv = createTextFieldValue("PVTF_status", "In Progress");
            GHProjectV2Item issueItem = createIssueItem(
                    "PVTI_issue1", 12345L, OffsetDateTime.now(ZoneOffset.UTC), List.of(textFv), false, null);

            GHProjectV2ItemConnection itemsConnection = createItemConnection(List.of(issueItem));
            mockThreePhasesWithItems(projectId, itemsConnection);

            // The transaction lambda calls findById to get the project
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

            // itemProcessor.process() creates/updates the item and returns it
            ProjectItem processedItem = new ProjectItem();
            processedItem.setId(500L);
            processedItem.setNodeId("PVTI_issue1");
            when(itemProcessor.process(any(), eq(project), any(), isNull())).thenReturn(processedItem);

            // processFieldValues is now delegated to fieldValueSyncService
            when(fieldValueSyncService.processFieldValues(eq(500L), any(), eq(false), isNull()))
                    .thenReturn(List.of("PVTF_status"));

            SyncResult result = service.syncProjectItems(SCOPE_ID, project);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            // Verify itemProcessor.process was called for the Issue/PR item
            verify(itemProcessor).process(any(), eq(project), any(), isNull());
            // Verify field values were delegated to fieldValueSyncService
            verify(fieldValueSyncService).processFieldValues(eq(500L), any(), eq(false), isNull());
        }

        @Test
        void shouldSkipItemsBelowIncrementalSyncThreshold() {
            Long projectId = 10L;
            Project project = createProject(projectId, "PVT_node10", 1);

            // Item updated 2 hours ago — will be before the threshold
            GHProjectV2Item oldItem = createIssueItem(
                    "PVTI_old_issue",
                    12345L,
                    OffsetDateTime.now(ZoneOffset.UTC).minusHours(2),
                    List.of(createTextFieldValue("PVTF_field1", "value")),
                    false,
                    null);

            GHProjectV2ItemConnection itemsConnection = createItemConnection(List.of(oldItem));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain();

            ClientGraphQlResponse fieldsResponse = mockValidGraphQlResponse("node.fields", null);
            ClientGraphQlResponse statusUpdatesResponse = mockValidGraphQlResponse("node.statusUpdates", null);
            ClientGraphQlResponse itemsResponse = mockValidGraphQlResponse("node.items", itemsConnection);

            when(requestSpec.execute())
                    .thenReturn(Mono.just(fieldsResponse))
                    .thenReturn(Mono.just(statusUpdatesResponse))
                    .thenReturn(Mono.just(itemsResponse));

            when(backfillStateProvider.getProjectItemSyncCursor(projectId)).thenReturn(Optional.empty());
            // Previous sync was 10 minutes ago → threshold = 10min - 5min buffer = 5 min ago
            // Item updated 2 hours ago → well before threshold → should be skipped
            when(backfillStateProvider.getProjectItemsSyncedAt(projectId))
                    .thenReturn(Optional.of(Instant.now().minusSeconds(10 * 60)));

            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

            SyncResult result = service.syncProjectItems(SCOPE_ID, project);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            // Field values should NOT be processed because the item was skipped
            verify(fieldValueSyncService, never()).processFieldValues(any(), any(), anyBoolean(), any());
            verify(itemProcessor, never()).process(any(), any(), any(), any());
        }

        @Test
        void shouldPassFilterQueryVariableDuringIncrementalSync() {
            Long projectId = 10L;
            Project project = createProject(projectId, "PVT_node10", 1);

            GHProjectV2Item recentItem = createIssueItem(
                    "PVTI_recent",
                    12345L,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    List.of(createTextFieldValue("PVTF_field1", "value")),
                    false,
                    null);

            GHProjectV2ItemConnection itemsConnection = createItemConnection(List.of(recentItem));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain();

            ClientGraphQlResponse fieldsResponse = mockValidGraphQlResponse("node.fields", null);
            ClientGraphQlResponse statusUpdatesResponse = mockValidGraphQlResponse("node.statusUpdates", null);
            ClientGraphQlResponse itemsResponse = mockValidGraphQlResponse("node.items", itemsConnection);

            when(requestSpec.execute())
                    .thenReturn(Mono.just(fieldsResponse))
                    .thenReturn(Mono.just(statusUpdatesResponse))
                    .thenReturn(Mono.just(itemsResponse));

            when(backfillStateProvider.getProjectItemSyncCursor(projectId)).thenReturn(Optional.empty());
            // Previous sync was 10 minutes ago → filterQuery should be set with a date
            when(backfillStateProvider.getProjectItemsSyncedAt(projectId))
                    .thenReturn(Optional.of(Instant.now().minusSeconds(10 * 60)));

            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

            ProjectItem processedItem = new ProjectItem();
            processedItem.setId(800L);
            processedItem.setNodeId("PVTI_recent");
            when(itemProcessor.process(any(), eq(project), any(), isNull())).thenReturn(processedItem);

            when(fieldValueSyncService.processFieldValues(eq(800L), any(), eq(false), isNull()))
                    .thenReturn(List.of("PVTF_field1"));

            service.syncProjectItems(SCOPE_ID, project);

            // Assert - verify that filterQuery variable was passed (should start with "updated:>")
            var filterQueryCaptor = ArgumentCaptor.forClass(String.class);
            // The variable("filterQuery", ...) call is made during the items phase (3rd execute call)
            // Since mockGraphQlRequestChain stubs variable(anyString(), any()), we capture via ArgumentCaptor
            verify(requestSpec).variable(eq("filterQuery"), filterQueryCaptor.capture());
            String capturedFilterQuery = filterQueryCaptor.getValue();
            assertNotNull(capturedFilterQuery);
            assertThat(capturedFilterQuery).startsWith("updated:>");
        }

        @Test
        void shouldSkipStaleRemovalWhenServerSideFilteringIsActive() {
            Long projectId = 10L;
            Project project = createProject(projectId, "PVT_node10", 1);

            GHProjectV2Item recentItem = createIssueItem(
                    "PVTI_recent",
                    12345L,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    List.of(createTextFieldValue("PVTF_field1", "value")),
                    false,
                    null);

            GHProjectV2ItemConnection itemsConnection = createItemConnection(List.of(recentItem));
            mockGraphQlClientForScope();
            mockGraphQlRequestChain();

            ClientGraphQlResponse fieldsResponse = mockValidGraphQlResponse("node.fields", null);
            ClientGraphQlResponse statusUpdatesResponse = mockValidGraphQlResponse("node.statusUpdates", null);
            ClientGraphQlResponse itemsResponse = mockValidGraphQlResponse("node.items", itemsConnection);

            when(requestSpec.execute())
                    .thenReturn(Mono.just(fieldsResponse))
                    .thenReturn(Mono.just(statusUpdatesResponse))
                    .thenReturn(Mono.just(itemsResponse));

            when(backfillStateProvider.getProjectItemSyncCursor(projectId)).thenReturn(Optional.empty());
            // Set previous sync timestamp → triggers server-side filtering
            when(backfillStateProvider.getProjectItemsSyncedAt(projectId))
                    .thenReturn(Optional.of(Instant.now().minusSeconds(10 * 60)));

            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

            ProjectItem processedItem = new ProjectItem();
            processedItem.setId(900L);
            processedItem.setNodeId("PVTI_recent");
            when(itemProcessor.process(any(), eq(project), any(), isNull())).thenReturn(processedItem);

            when(fieldValueSyncService.processFieldValues(eq(900L), any(), eq(false), isNull()))
                    .thenReturn(List.of("PVTF_field1"));

            SyncResult result = service.syncProjectItems(SCOPE_ID, project);

            // Assert - sync should complete but stale removal should NOT run
            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            verify(itemProcessor, never()).removeStaleDraftIssues(anyLong(), any(), any());
            verify(itemProcessor, never()).removeStaleIssuePrItems(anyLong(), any(), any());
        }

        @Test
        void shouldNotPassFilterQueryOnFirstSync() {
            Long projectId = 10L;
            Project project = createProject(projectId, "PVT_node10", 1);

            GHProjectV2Item item = createIssueItem(
                    "PVTI_item1",
                    12345L,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    List.of(createTextFieldValue("PVTF_field1", "value")),
                    false,
                    null);

            GHProjectV2ItemConnection itemsConnection = createItemConnection(List.of(item));
            mockThreePhasesWithItems(projectId, itemsConnection);

            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

            ProjectItem processedItem = new ProjectItem();
            processedItem.setId(1000L);
            processedItem.setNodeId("PVTI_item1");
            when(itemProcessor.process(any(), eq(project), any(), isNull())).thenReturn(processedItem);

            when(fieldValueSyncService.processFieldValues(eq(1000L), any(), eq(false), isNull()))
                    .thenReturn(List.of("PVTF_field1"));

            SyncResult result = service.syncProjectItems(SCOPE_ID, project);

            // Assert - filterQuery should be null (no previous sync), stale removal SHOULD run
            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            verify(requestSpec).variable(eq("filterQuery"), isNull());
            verify(itemProcessor).removeStaleDraftIssues(eq(projectId), any(), any());
            verify(itemProcessor).removeStaleIssuePrItems(eq(projectId), any(), any());
        }

        @Test
        void shouldQueueItemsWithTruncatedFieldValuesForPagination() {
            Long projectId = 10L;
            Project project = createProject(projectId, "PVT_node10", 1);

            // Item with truncated field values (hasNextPage=true, endCursor set)
            GHProjectV2Item truncatedItem = createIssueItem(
                    "PVTI_truncated",
                    12345L,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    List.of(createTextFieldValue("PVTF_field1", "value1")),
                    true, // truncated
                    "cursor_fv_page2");

            GHProjectV2ItemConnection itemsConnection = createItemConnection(List.of(truncatedItem));
            mockThreePhasesWithItems(projectId, itemsConnection);

            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

            // itemProcessor.process() returns the processed item
            ProjectItem processedItem = new ProjectItem();
            processedItem.setId(600L);
            processedItem.setNodeId("PVTI_truncated");
            when(itemProcessor.process(any(), eq(project), any(), isNull())).thenReturn(processedItem);

            when(fieldValueSyncService.processFieldValues(eq(600L), any(), eq(true), isNull()))
                    .thenReturn(List.of("PVTF_field1"));

            SyncResult result = service.syncProjectItems(SCOPE_ID, project);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            // Verify itemProcessor.process was called
            verify(itemProcessor).process(any(), eq(project), any(), isNull());
            // Verify field values were processed for the first page via delegation
            verify(fieldValueSyncService).processFieldValues(eq(600L), any(), eq(true), isNull());
            // The truncated item should trigger syncRemainingFieldValues on the fieldValueSyncService.
            // Only 3 execute() calls happen from the main service: fields, statusUpdates, items.
            // The follow-up pagination is handled inside fieldValueSyncService (which is mocked).
            verify(requestSpec, times(3)).execute();
            verify(fieldValueSyncService)
                    .syncRemainingFieldValues(
                            eq(SCOPE_ID),
                            eq("PVTI_truncated"),
                            eq(600L),
                            eq("cursor_fv_page2"),
                            eq(List.of("PVTF_field1")));
        }

        @Test
        void shouldCreateIssuePrItemsThatDontExistLocally() {
            Long projectId = 10L;
            Project project = createProject(projectId, "PVT_node10", 1);

            GHProjectV2Item newItem = createIssueItem(
                    "PVTI_not_synced",
                    99999L,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    List.of(createTextFieldValue("PVTF_field1", "value")),
                    false,
                    null);

            GHProjectV2ItemConnection itemsConnection = createItemConnection(List.of(newItem));
            mockThreePhasesWithItems(projectId, itemsConnection);

            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

            // itemProcessor.process() creates the new item
            ProjectItem createdItem = new ProjectItem();
            createdItem.setId(700L);
            createdItem.setNodeId("PVTI_not_synced");
            when(itemProcessor.process(any(), eq(project), any(), isNull())).thenReturn(createdItem);

            when(fieldValueSyncService.processFieldValues(eq(700L), any(), eq(false), isNull()))
                    .thenReturn(List.of("PVTF_field1"));

            SyncResult result = service.syncProjectItems(SCOPE_ID, project);

            assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
            // Verify itemProcessor.process was called to create the item
            verify(itemProcessor).process(any(), eq(project), any(), isNull());
            // Verify field values were delegated to fieldValueSyncService
            verify(fieldValueSyncService).processFieldValues(eq(700L), any(), eq(false), isNull());
        }
    }

    // getProjectsNeedingItemSync tests

    @Nested
    class GetProjectsNeedingItemSync {

        @Test
        void shouldDelegateToRepositoryWithCorrectCooldownThreshold() {
            Long organizationId = 42L;
            Project project1 = createProject(1L, "PVT_1", 1);
            Project project2 = createProject(2L, "PVT_2", 2);
            when(projectRepository.findProjectsNeedingItemSync(
                            eq(Project.OwnerType.ORGANIZATION), eq(organizationId), any(Instant.class)))
                    .thenReturn(List.of(project1, project2));

            List<Project> result = service.getProjectsNeedingItemSync(organizationId);

            assertThat(result).hasSize(2);
            var instantCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(projectRepository)
                    .findProjectsNeedingItemSync(
                            eq(Project.OwnerType.ORGANIZATION), eq(organizationId), instantCaptor.capture());
            Instant capturedThreshold = instantCaptor.getValue();
            // The threshold should be approximately now minus the cooldown period
            assertThat(capturedThreshold).isBefore(Instant.now());
            assertThat(capturedThreshold)
                    .isAfter(Instant.now().minusSeconds(syncSchedulerProperties.cooldownMinutes() * 60L + 5));
        }

        @Test
        void shouldReturnEmptyListWhenNoProjectsNeedSync() {
            Long organizationId = 42L;
            when(projectRepository.findProjectsNeedingItemSync(
                            eq(Project.OwnerType.ORGANIZATION), eq(organizationId), any(Instant.class)))
                    .thenReturn(List.of());

            List<Project> result = service.getProjectsNeedingItemSync(organizationId);

            assertThat(result).isEmpty();
        }
    }
}

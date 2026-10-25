package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemSourceAccess;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issue.GitLabIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuedependency.GitLabIssueDependencySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.label.GitLabLabelSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.milestone.GitLabMilestoneSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.backfill.GitLabHistoricalBackfillService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRepositoryMonitors;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceInitializationService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.GitLabRepositorySelectionService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.GitLabStoppedProjectService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ScmWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceSyncTargetProvider;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlRequest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClientInterceptor;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class RepositoryProviderScopeIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final String NAME = "shared-org/shared-repo";
    private static final long NATIVE_ID = 7001L;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private WorkspaceSyncTargetProvider syncTargets;

    @Autowired
    private ItemIndexService itemWriter;

    @Autowired
    private ItemRepository itemRows;

    @Autowired
    private GitLabStoppedProjectService stoppedProjects;

    @Autowired
    private ScmItemSourceAccess sourceAccess;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private ScmWorkspaceContentEraser eraser;

    @Autowired
    private WorkspaceRepositoryMonitorService monitorService;

    @Autowired
    private GitLabRepositorySelectionService selectionService;

    @Autowired
    private GitLabRepositoryMonitors gitLabMonitors;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private GitLabWorkspaceInitializationService initialization;

    @Autowired
    private GitLabIssueSyncService issueSync;

    @Autowired
    private GitLabLabelSyncService labelSync;

    @Autowired
    private GitLabMilestoneSyncService milestoneSync;

    @Autowired
    private LabelRepository labels;

    @Autowired
    private MilestoneRepository milestones;

    @Autowired
    private GitLabRouteAdmission routeAdmission;

    @Autowired
    private GitLabProjectAdmission projectAdmission;

    @Autowired
    private GitLabHistoricalBackfillService backfill;

    @Autowired
    private GitLabProjectSyncService projects;

    @Autowired
    private GitLabProperties gitLabProperties;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private CredentialBundleConverter credentialConverter;

    @MockitoBean
    private GitLabGraphQlClientProvider clients;

    @MockitoBean
    private GitLabSyncServiceHolder syncServices;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldDiscardHeldIssueResponseAfterStopWithoutBlockingAllowedSibling(boolean reinclude) throws Exception {
        User owner = persistUser("held-owner");
        Workspace own = workspace("held-project", owner);
        own.setRepositorySelection(RepositorySelection.ALL);
        workspaces.saveAndFlush(own);
        Workspace other = workspace("held-shared", owner);
        IdentityProvider source = provider("https://held.example.test");
        long sourceProviderId = Objects.requireNonNull(source.getId());
        connect(own, source);
        connect(other, source);
        Repository stopped = repository(source, NATIVE_ID, NAME);
        Repository sibling = repository(source, NATIVE_ID + 1, "shared-org/healthy-sibling");
        monitor(other, NATIVE_ID);
        gitLabMonitors.monitorAllowed(own, List.of(stopped, sibling));
        long oldMonitorId = monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME)
                .orElseThrow()
                .getId();

        CountDownLatch requested = new CountDownLatch(1);
        CompletableFuture<String> held = new CompletableFuture<>();
        when(clients.forScope(own.getId()))
                .thenReturn(heldProjectClient(
                        requested,
                        held,
                        source.getServerUrl(),
                        issueResponse(source.getServerUrl(), "shared-org/healthy-sibling", 99002)));
        when(syncServices.getIssueSyncService()).thenReturn(issueSync);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> initialization.syncFullData(own));
            try {
                assertThat(requested.await(10, TimeUnit.SECONDS))
                        .as("provider read began before Stop")
                        .isTrue();
                monitorService.removeRepositoryFromMonitor(own.getWorkspaceSlug(), NAME);
                if (reinclude) monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), NAME);
                held.complete(issueResponse(source.getServerUrl(), NAME, 99001));

                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(SyncResult.completed(1));
                assertSoftly(softly -> {
                    softly.assertThat(issues.findAllIssuesByRepositoryId(stopped.getId()))
                            .isEmpty();
                    softly.assertThat(issues.findAllIssuesByRepositoryId(sibling.getId()))
                            .extracting(Issue::getTitle)
                            .containsExactly("Healthy sibling issue");
                    softly.assertThat(userRepository.findByLoginAndProviderId("late-excluded-author", sourceProviderId))
                            .isEmpty();
                    softly.assertThat(monitors.findById(oldMonitorId)).isEmpty();
                    softly.assertThat(monitors.findByWorkspaceIdAndNameWithOwner(other.getId(), NAME))
                            .isPresent();
                });
                if (reinclude) {
                    var fresh = monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME)
                            .orElseThrow();
                    assertThat(fresh.getId()).isNotEqualTo(oldMonitorId);
                    var target = syncTargets.findSyncTargetById(fresh.getId()).orElseThrow();
                    assertThat(target.lastIssuesSyncedAt()).isNull();
                    assertThat(target.lastFullSyncAt()).isNull();
                }
                var healthy = monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), sibling.getNameWithOwner())
                        .orElseThrow();
                assertThat(syncTargets
                                .findSyncTargetById(healthy.getId())
                                .orElseThrow()
                                .lastIssuesSyncedAt())
                        .isNotNull();
            } finally {
                held.complete(issueResponse(source.getServerUrl(), NAME, 99001));
                result.cancel(true);
            }
        }
    }

    @Test
    void shouldDiscardHeldBackfillResponseAfterReincludeWithoutAdvancingReplacement() throws Exception {
        User owner = persistUser("held-backfill-owner");
        Workspace own = workspace("held-backfill", owner);
        own.setRepositorySelection(RepositorySelection.ALL);
        workspaces.saveAndFlush(own);
        Workspace other = workspace("held-backfill-shared", owner);
        IdentityProvider source = provider("https://backfill-held.example.test");
        connect(own, source);
        connect(other, source);
        Repository stopped = repository(source, NATIVE_ID, NAME);
        Repository sibling = repository(source, NATIVE_ID + 1, "shared-org/healthy-sibling");
        monitor(other, NATIVE_ID);
        gitLabMonitors.monitorAllowed(own, List.of(stopped, sibling));
        var original = monitors.findByWorkspaceId(own.getId());
        for (var target : original) syncTargets.updateSyncTimestamp(target.getId(), SyncType.ISSUES, Instant.EPOCH);
        long oldMonitorId = monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME)
                .orElseThrow()
                .getId();

        CountDownLatch requested = new CountDownLatch(1);
        CompletableFuture<String> held = new CompletableFuture<>();
        when(clients.forScope(own.getId()))
                .thenReturn(heldProjectClient(
                        requested,
                        held,
                        source.getServerUrl(),
                        issueResponse(source.getServerUrl(), "shared-org/healthy-sibling", 99002)));
        when(syncServices.getIssueSyncService()).thenReturn(issueSync);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> backfill.runBackfillPass(own.getId(), null));
            try {
                assertThat(requested.await(10, TimeUnit.SECONDS)).isTrue();
                monitorService.removeRepositoryFromMonitor(own.getWorkspaceSlug(), NAME);
                monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), NAME);
                held.complete(issueResponse(source.getServerUrl(), NAME, 99001));

                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(issues.findAllIssuesByRepositoryId(stopped.getId())).isEmpty();
                assertThat(issues.findAllIssuesByRepositoryId(sibling.getId())).hasSize(1);
                assertThat(monitors.findById(oldMonitorId)).isEmpty();
                var replacement = monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME)
                        .orElseThrow();
                var fresh = syncTargets.findSyncTargetById(replacement.getId()).orElseThrow();
                assertThat(fresh.issueSyncCursor()).isNull();
                assertThat(fresh.issueBackfillCheckpoint()).isNull();
                assertThat(fresh.lastIssuesSyncedAt()).isNull();
                assertThat(fresh.lastFullSyncAt()).isNull();
                assertThat(replacement.getHistoricalBackfillSyncError()).isNull();
            } finally {
                held.complete(issueResponse(source.getServerUrl(), NAME, 99001));
                result.cancel(true);
            }
        }
    }

    @Test
    void shouldBindUnresolvedStopBeforeProjectPersistenceAndPermitUnrelatedPathReuse() {
        Workspace own = workspace("unresolved-stop", persistUser("unresolved-owner"));
        own.setRepositorySelection(RepositorySelection.ALL);
        workspaces.saveAndFlush(own);
        String origin = gitLabProperties.defaultServerUrl();
        IdentityProvider source = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, origin)
                .orElseGet(() -> provider(origin));
        long sourceProviderId = Objects.requireNonNull(source.getId());
        Connection connection = connect(own, source);
        monitor(own, null);
        monitorService.removeRepositoryFromMonitor(own.getWorkspaceSlug(), NAME);
        assertThat(repositories.findByNameWithOwnerAndProviderId(NAME, sourceProviderId))
                .isEmpty();
        var answers =
                new ArrayDeque<>(List.of(projectResponse(origin, NATIVE_ID), projectResponse(origin, NATIVE_ID + 1)));
        when(clients.forScope(own.getId())).thenReturn(projectClient(origin, answers));

        assertThat(projects.syncProject(own.getId(), NAME)).isEmpty();
        assertThat(repositories.findByNameWithOwnerAndProviderId(NAME, sourceProviderId))
                .isEmpty();
        var bound = (ConnectionConfig.GitLabConfig)
                connections.findById(connection.getId()).orElseThrow().getConfig();
        assertThat(bound.projectExclusions().nativeIds()).containsExactly(NATIVE_ID);
        assertThat(bound.projectExclusions().unresolvedPaths()).isEmpty();

        var reused = projects.syncProject(own.getId(), NAME).orElseThrow();
        assertThat(reused.getNativeId()).isEqualTo(NATIVE_ID + 1);
        assertThat(repositories.findByNativeIdAndProviderId(NATIVE_ID, sourceProviderId))
                .isEmpty();
        assertThat(gitLabMonitors.monitorAllowed(
                        workspaces.findById(own.getId()).orElseThrow(), List.of(reused)))
                .isEqualTo(1);
        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME)
                        .orElseThrow()
                        .getNativeId())
                .isEqualTo(NATIVE_ID + 1);
    }

    @Test
    void shouldAddAnUnmirroredPathWithoutResolvingAnUnrelatedUnresolvedExclusion() {
        Workspace own = workspace("unrelated-pending-add", persistUser("unrelated-add-owner"));
        String origin = gitLabProperties.defaultServerUrl();
        IdentityProvider source = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, origin)
                .orElseGet(() -> provider(origin));
        long providerId = Objects.requireNonNull(source.getId());
        Connection connection = connect(own, source);
        monitor(own, null);
        monitorService.removeRepositoryFromMonitor(own.getWorkspaceSlug(), NAME);
        String addedPath = "shared-org/new-project";
        // GitLab confirms the added project to this workspace's credential once, then cannot be asked again.
        var answers = new ArrayDeque<>(List.of("""
                {"data":{"project":{"id":"gid://gitlab/Project/%s","fullPath":"%s","name":"new-project",
                  "webUrl":"%s/%s","visibility":"private","group":null}}}
                """.formatted(NATIVE_ID + 5, addedPath, origin, addedPath)));
        when(clients.forScope(own.getId()))
                .thenReturn(projectClient(origin, answers))
                .thenThrow(new IllegalStateException("Canonical lookup unavailable"));

        monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), addedPath);

        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), addedPath))
                .hasValueSatisfying(added -> assertThat(added.getNativeId()).isEqualTo(NATIVE_ID + 5));
        assertThat(repositories.findByNameWithOwnerAndProviderId(addedPath, providerId))
                .isEmpty();
        assertThatThrownBy(() -> monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), NAME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Canonical lookup unavailable");
        var current = (ConnectionConfig.GitLabConfig)
                connections.findById(connection.getId()).orElseThrow().getConfig();
        assertThat(current.projectExclusions().nativeIds()).isEmpty();
        assertThat(current.projectExclusions().unresolvedPaths()).containsExactly(NAME);
        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME))
                .isEmpty();
    }

    @Test
    void shouldResolveExplicitReincludeAfterStopDeletesTheLastMirror() {
        Workspace own = workspace("orphan-reinclude", persistUser("orphan-include-owner"));
        String origin = gitLabProperties.defaultServerUrl();
        IdentityProvider source = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, origin)
                .orElseGet(() -> provider(origin));
        Connection connection = connect(own, source);
        Repository stopped = repository(source, NATIVE_ID, NAME);
        monitor(own, NATIVE_ID);
        monitorService.removeRepositoryFromMonitor(own.getWorkspaceSlug(), NAME);
        assertThat(repositories.findById(stopped.getId())).isEmpty();
        var answers = new ArrayDeque<>(List.of(projectResponse(origin, NATIVE_ID), projectResponse(origin, NATIVE_ID)));
        when(clients.forScope(own.getId())).thenReturn(projectClient(origin, answers));

        monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), NAME);

        var current =
                monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME).orElseThrow();
        assertThat(current.getNativeId()).isEqualTo(NATIVE_ID);
        var included = (ConnectionConfig.GitLabConfig)
                connections.findById(connection.getId()).orElseThrow().getConfig();
        assertThat(included.projectExclusions().nativeIds()).isEmpty();
        Repository recreated = projects.syncProject(own.getId(), NAME).orElseThrow();
        assertThat(recreated.getId()).isNotEqualTo(stopped.getId());
        assertThat(recreated.getNativeId()).isEqualTo(NATIVE_ID);
        assertThat(repositories.findAllByWorkspaceMonitors(own.getId()))
                .extracting(Repository::getId)
                .containsExactly(recreated.getId());
    }

    @Test
    void shouldResolveExplicitReincludeAfterRemoveDeletesTheLastKeptMirror() {
        Workspace own = workspace("kept-reinclude", persistUser("kept-include-owner"));
        String origin = gitLabProperties.defaultServerUrl();
        IdentityProvider source = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, origin)
                .orElseGet(() -> provider(origin));
        Connection connection = connect(own, source);
        Repository stopped = repository(source, NATIVE_ID, NAME);
        syncTargets.reconcileSyncTargetIdentity(monitor(own, NATIVE_ID).getId(), NATIVE_ID, NAME);
        long root = itemWriter
                .findSource(own.getId(), "scm.repository", stopped.getId())
                .orElseThrow()
                .itemId();
        monitorService.removeRepositoryFromMonitor(own.getWorkspaceSlug(), NAME);
        assertThat(repositories.findById(stopped.getId())).isPresent();
        var kept = stoppedProjects.getStoppedProjects(own.getId());
        assertThat(kept)
                .singleElement()
                .satisfies(project -> assertThat(project.repositoryItemId()).isEqualTo(String.valueOf(root)));

        stoppedProjects.removeStoppedProject(
                own.getId(),
                root,
                kept.getFirst().stopReceipt(),
                Objects.requireNonNull(persistAccount("kept-reinclude-admin-" + UUID.randomUUID())
                        .getId()));
        assertThat(repositories.findById(stopped.getId())).isEmpty();
        var answers = new ArrayDeque<>(List.of(projectResponse(origin, NATIVE_ID), projectResponse(origin, NATIVE_ID)));
        when(clients.forScope(own.getId())).thenReturn(projectClient(origin, answers));

        monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), NAME);

        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME)
                        .orElseThrow()
                        .getNativeId())
                .isEqualTo(NATIVE_ID);
        var included = (ConnectionConfig.GitLabConfig)
                connections.findById(connection.getId()).orElseThrow().getConfig();
        assertThat(included.projectExclusions().nativeIds()).isEmpty();
        assertThat(included.projectExclusions().stopReceipt(NATIVE_ID)).isEmpty();
        Repository recreated = projects.syncProject(own.getId(), NAME).orElseThrow();
        assertThat(recreated.getId()).isNotEqualTo(stopped.getId());
        assertThat(recreated.getNativeId()).isEqualTo(NATIVE_ID);
        // The removed item names the deleted source row, so the new row never reactivates it.
        assertThat(itemRows.findByIdAndWorkspaceId(root, own.getId())
                        .orElseThrow()
                        .getRemovedAt())
                .isNotNull();
        assertThat(stoppedProjects.getStoppedProjects(own.getId())).isEmpty();
    }

    @Test
    void shouldPreserveStopWhenAConfigWriterLoadedTheConnectionBeforeStop() throws Exception {
        User owner = persistUser("stale-config-owner");
        Workspace own = workspace("stale-config-stop", owner);
        Workspace other = workspace("stale-config-shared", owner);
        IdentityProvider source = provider("https://stale-config.example.test");
        Connection connection = connect(own, source);
        connect(other, source);
        repository(source, NATIVE_ID, NAME);
        monitor(own, NATIVE_ID);
        monitor(other, NATIVE_ID);
        CountDownLatch loaded = new CountDownLatch(1);
        CompletableFuture<Boolean> resume = new CompletableFuture<>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> transactions.execute(status -> {
                var cached = connections
                        .findByIdAndWorkspaceId(connection.getId(), own.getId())
                        .orElseThrow();
                assertThat(((ConnectionConfig.GitLabConfig) cached.getConfig())
                                .projectExclusions()
                                .nativeIds())
                        .isEmpty();
                loaded.countDown();
                resume.join();
                return connectionService.updateConfig(
                        own.getId(),
                        IntegrationKind.GITLAB,
                        config -> ((ConnectionConfig.GitLabConfig) config).withGitlabWebhookId(123L));
            }));
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
                monitorService.removeRepositoryFromMonitor(own.getWorkspaceSlug(), NAME);
                resume.complete(true);
                assertThat(writer.get(20, TimeUnit.SECONDS)).isPresent();
                var saved = (ConnectionConfig.GitLabConfig)
                        connections.findById(connection.getId()).orElseThrow().getConfig();
                assertThat(saved.gitlabWebhookId()).isEqualTo(123L);
                assertThat(saved.projectExclusions().nativeIds()).containsExactly(NATIVE_ID);
                assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME))
                        .isEmpty();
            } finally {
                resume.complete(true);
                writer.cancel(true);
            }
        }
    }

    @Test
    void shouldKeepTheProjectSelectionWhenASyncTimestampWriterLoadedTheWorkspaceBeforeTheChange() throws Exception {
        User owner = persistUser("stale-selection-owner");
        Workspace own = workspace("stale-selection", owner);
        own.setRepositorySelection(RepositorySelection.ALL);
        workspaces.saveAndFlush(own);
        connect(own, provider("https://stale-selection.example.test"));
        monitor(own, NATIVE_ID);
        Instant syncedAt = Instant.parse("2026-10-22T08:00:00Z");
        CountDownLatch loaded = new CountDownLatch(1);
        CompletableFuture<Boolean> resume = new CompletableFuture<>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var sync = executor.submit(() -> transactions.executeWithoutResult(status -> {
                // The sync's transaction manages the workspace as it stood before the admin's change.
                Workspace cached = workspaces.findById(own.getId()).orElseThrow();
                assertThat(cached.getRepositorySelection()).isEqualTo(RepositorySelection.ALL);
                loaded.countDown();
                resume.join();
                syncTargets.updateUsersSyncTimestamp(own.getId(), syncedAt);
            }));
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(selectionService.updateGitLabRepositorySelection(
                                WorkspaceContext.fromWorkspace(own, null, null),
                                RepositorySelection.SELECTED,
                                accountId(owner)))
                        .isEqualTo(RepositorySelection.SELECTED);
                assertThat(workspaces
                                .findMonitorPolicy(own.getId())
                                .orElseThrow()
                                .getRepositorySelection())
                        .isEqualTo(RepositorySelection.SELECTED);
                resume.complete(true);
                sync.get(20, TimeUnit.SECONDS);

                assertThat(workspaces.findById(own.getId()).orElseThrow().getUsersSyncedAt())
                        .isEqualTo(syncedAt);
                assertThat(workspaces
                                .findMonitorPolicy(own.getId())
                                .orElseThrow()
                                .getRepositorySelection())
                        .isEqualTo(RepositorySelection.SELECTED);
            } finally {
                resume.complete(true);
                sync.cancel(true);
            }
        }
    }

    @Test
    void shouldRefuseAWithdrawnLabelWriteInItsOwnTransactionAndKeepTheSiblingAllowed() throws Exception {
        User owner = persistUser("held-label-owner");
        Workspace own = workspace("held-label", owner);
        Workspace other = workspace("held-label-shared", owner);
        IdentityProvider source = provider("https://label-held.example.test");
        connect(own, source);
        connect(other, source);
        Repository stopped = repository(source, NATIVE_ID, NAME);
        Repository sibling = repository(source, NATIVE_ID + 1, "shared-org/healthy-sibling");
        monitor(own, NATIVE_ID);
        monitor(other, NATIVE_ID);
        var allowed = new RepositoryToMonitor();
        allowed.setWorkspace(own);
        allowed.setNameWithOwner(sibling.getNameWithOwner());
        allowed.setNativeId(sibling.getNativeId());
        monitors.saveAndFlush(allowed);
        var requested = new CountDownLatch(1);
        var held = new CompletableFuture<String>();
        when(clients.forScope(own.getId()))
                .thenReturn(heldProjectClient(
                        requested,
                        held,
                        source.getServerUrl(),
                        inventoryResponse(SyncType.LABELS, 2, "Allowed label", false)));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> labelSync.syncLabelsForRepository(own.getId(), stopped));
            try {
                assertThat(requested.await(10, TimeUnit.SECONDS)).isTrue();
                monitorService.removeRepositoryFromMonitor(own.getWorkspaceSlug(), NAME);
                monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), NAME);
                held.complete(inventoryResponse(SyncType.LABELS, 1, "Late label", false));
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(SyncResult.completed(0));
                assertThat(labels.findAllByRepository_Id(stopped.getId())).isEmpty();
                assertThat(labelSync.syncLabelsForRepository(own.getId(), sibling))
                        .isEqualTo(SyncResult.completed(1));
                assertThat(labels.findAllByRepository_Id(sibling.getId()))
                        .extracting(Label::getName)
                        .containsExactly("Allowed label");
            } finally {
                held.complete(inventoryResponse(SyncType.LABELS, 1, "Late label", false));
                result.cancel(true);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = SyncType.class,
            names = {"LABELS", "MILESTONES"})
    void shouldRetainProviderPresentInventoryWhenTheConnectionResumesAfterARefusedWrite(SyncType phase) {
        Workspace own = workspace("inventory-" + phase.name().toLowerCase(Locale.ROOT), persistUser("inventory-owner"));
        IdentityProvider source = provider("https://inventory.example.test");
        Connection connection = connect(own, source);
        Repository repository = repository(source, NATIVE_ID, NAME);
        monitor(own, NATIVE_ID);
        for (int iid : List.of(1, 3)) {
            String title = iid == 1 ? "Provider present" : "Actually stale";
            if (phase == SyncType.LABELS) {
                var label = new Label();
                label.setNativeId(1000L + iid);
                label.setProvider(source);
                label.setRepository(repository);
                label.setName(title);
                label.setColor("000000");
                labels.saveAndFlush(label);
            } else {
                var milestone = new Milestone();
                milestone.setNativeId(1000L + iid);
                milestone.setProvider(source);
                milestone.setRepository(repository);
                milestone.setNumber(iid);
                milestone.setTitle(title);
                milestone.setHtmlUrl(source.getServerUrl() + "/" + NAME + "/-/milestones/" + iid);
                milestone.setState(Milestone.State.OPEN);
                milestones.saveAndFlush(milestone);
            }
        }
        var page = new AtomicInteger();
        var transport = projectClient(source.getServerUrl(), new ArrayDeque<>())
                .mutate()
                .webClient(builder -> builder.exchangeFunction(request -> {
                    boolean first = page.getAndIncrement() == 0;
                    connectionService.transition(
                            connection,
                            new ConnectionService.TransitionRequest(
                                    first ? IntegrationState.SUSPENDED : IntegrationState.ACTIVE,
                                    first ? "TEST_PAUSED" : "TEST_RESUMED",
                                    "SYSTEM",
                                    "inventory-test",
                                    UUID.randomUUID().toString(),
                                    "Test connection state"));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(inventoryResponse(
                                    phase, first ? 1 : 2, first ? "Provider present" : "New provider item", first))
                            .build());
                }))
                .build();
        when(clients.forScope(own.getId())).thenReturn(transport);
        SyncResult result = phase == SyncType.LABELS
                ? labelSync.syncLabelsForRepository(own.getId(), repository)
                : milestoneSync.syncMilestonesForRepository(own.getId(), repository);
        assertThat(result).isEqualTo(SyncResult.completed(1));
        List<String> titles = phase == SyncType.LABELS
                ? labels.findAllByRepository_Id(repository.getId()).stream()
                        .map(Label::getName)
                        .toList()
                : milestones.findAllByRepository_Id(repository.getId()).stream()
                        .map(Milestone::getTitle)
                        .toList();
        assertThat(titles).containsExactlyInAnyOrder("Provider present", "New provider item");
        assertThat(page).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldNotApplyAnOldNegativeProjectEventToAReplacementMonitor(boolean unavailable) throws Exception {
        User owner = persistUser("held-project-event-owner");
        Workspace own = workspace("held-project-event", owner);
        Workspace other = workspace("held-project-event-shared", owner);
        String origin = gitLabProperties.defaultServerUrl();
        IdentityProvider source = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, origin)
                .orElseGet(() -> provider(origin));
        Connection connection = connect(own, source);
        connect(other, source);
        connectionService.updateConfig(
                own.getId(),
                IntegrationKind.GITLAB,
                config -> ((ConnectionConfig.GitLabConfig) config).withGitlabGroupId(42L));
        repository(source, NATIVE_ID, NAME);
        monitor(own, NATIVE_ID);
        monitor(other, NATIVE_ID);
        var requested = new CountDownLatch(1);
        var held = new CompletableFuture<ClientResponse>();
        when(clients.forScope(own.getId()))
                .thenReturn(projectClient(origin, new ArrayDeque<>())
                        .mutate()
                        .webClient(builder -> builder.exchangeFunction(request -> {
                            if (requested.getCount() == 0) {
                                // The Add after the Stop confirms the project with this workspace's credential.
                                return Mono.just(ClientResponse.create(HttpStatus.OK)
                                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                        .body(projectResponse(origin, NATIVE_ID))
                                        .build());
                            }
                            requested.countDown();
                            return Mono.fromFuture(held);
                        }))
                        .build());
        Headers headers = new Headers();
        new GitLabRouteCredential.Route(
                        Objects.requireNonNull(connection.getId()), own.getId(), origin, 42L, own.getAccountLogin())
                .headers()
                .forEach(headers::put);
        Message event = mock(Message.class);
        when(event.getSubject()).thenReturn("gitlab.?connection." + connection.getId() + ".project");
        when(event.getHeaders()).thenReturn(headers);
        when(event.getData())
                .thenReturn(("{\"event_name\":\"project_transfer\",\"project_id\":" + NATIVE_ID + "}")
                        .getBytes(StandardCharsets.UTF_8));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> routeAdmission.admit(own.getId(), event, () -> {}));
            try {
                assertThat(requested.await(10, TimeUnit.SECONDS)).isTrue();
                monitorService.removeRepositoryFromMonitor(own.getWorkspaceSlug(), NAME);
                monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), NAME);
                long replacement = monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME)
                        .orElseThrow()
                        .getId();
                var response = ClientResponse.create(unavailable ? HttpStatus.NOT_FOUND : HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(
                                unavailable
                                        ? "{\"message\":\"404 Project Not Found\"}"
                                        : "{\"data\":{\"projects\":{\"nodes\":[{\"id\":\"gid://gitlab/Project/7001\","
                                                + "\"fullPath\":\"elsewhere/moved\",\"name\":\"moved\",\"visibility\":\"private\"}]}}}")
                        .build();
                held.complete(response);
                assertThat(result.get(30, TimeUnit.SECONDS)).isFalse();
                assertThat(monitors.findById(replacement)).isPresent();
                assertThat(syncTargets.isRepositoryUnavailable(own.getId(), replacement))
                        .isFalse();
                assertThat(monitors.findByWorkspaceIdAndNameWithOwner(other.getId(), NAME))
                        .isPresent();
            } finally {
                held.complete(ClientResponse.create(HttpStatus.NOT_FOUND).build());
                result.cancel(true);
            }
        }
    }

    @Test
    void shouldRetainTheOriginalLegacyMonitorWhenDiscoveryMergesItsNativeDuplicate() {
        Workspace own = workspace("duplicate-lifetime", persistUser("duplicate-owner"));
        IdentityProvider source = provider("https://duplicate.example.test");
        connect(own, source);
        Repository stored = repository(source, NATIVE_ID, NAME);
        var legacy = monitor(own, null);
        var duplicate = new RepositoryToMonitor();
        duplicate.setWorkspace(own);
        duplicate.setNativeId(NATIVE_ID);
        duplicate.setNameWithOwner("shared-org/old-path");
        duplicate = monitors.saveAndFlush(duplicate);
        var original = projectAdmission
                .captureConnection(own.getId(), Objects.requireNonNull(source.getId()))
                .orElseThrow();
        gitLabMonitors.monitorAllowed(own, List.of(stored));
        assertThat(monitors.findById(duplicate.getId())).isEmpty();
        assertThat(projectAdmission.capture(original, stored).orElseThrow().monitorId())
                .isEqualTo(legacy.getId());
    }

    private static String inventoryResponse(SyncType phase, int iid, String title, boolean hasNext) {
        String nodes = phase == SyncType.LABELS
                ? "{\"id\":\"gid://gitlab/ProjectLabel/" + (1000 + iid) + "\",\"title\":\"" + title
                        + "\",\"color\":\"#123456\"}"
                : "{\"id\":\"gid://gitlab/Milestone/" + (1000 + iid) + "\",\"iid\":\"" + iid + "\",\"title\":\"" + title
                        + "\",\"state\":\"active\"}";
        return "{\"data\":{\"project\":{\"" + (phase == SyncType.LABELS ? "labels" : "milestones")
                + "\":{\"nodes\":[" + nodes + "],\"pageInfo\":{\"hasNextPage\":" + hasNext
                + ",\"endCursor\":" + (hasNext ? "\"next\"" : "null") + "}}}}}";
    }

    private HttpGraphQlClient projectClient(String origin, Deque<String> answers) {
        return HttpGraphQlClient.builder(WebClient.builder()
                        .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .body(answers.removeFirst())
                                .build())))
                .url(origin + "/api/graphql")
                .documentSource(new FragmentMergingDocumentSource(
                        List.of(
                                new ClassPathResource("graphql/gitlab/operations/"),
                                new ClassPathResource("graphql/gitlab/fragments/")),
                        List.of(".graphql", ".gql"),
                        List.of(new ClassPathResource("graphql/gitlab/fragments/GitLabUserFields.graphql"))))
                .build();
    }

    private static String projectResponse(String origin, long nativeId) {
        return """
                {"data":{"project":{"id":"gid://gitlab/Project/%s","fullPath":"%s","name":"shared-repo",
                  "webUrl":"%s/%s","visibility":"private","group":null}}}
                """.formatted(nativeId, NAME, origin, NAME);
    }

    private HttpGraphQlClient heldProjectClient(
            CountDownLatch requested, CompletableFuture<String> held, String origin, String siblingResponse) {
        var web = WebClient.builder()
                .exchangeFunction(request -> Mono.deferContextual(context -> {
                    String path = context.get("projectPath");
                    Mono<String> body;
                    if (Boolean.TRUE.equals(context.<Boolean>getOrDefault("projectLookup", false))) {
                        // An explicit Add confirms the project with this workspace's credential; only the sync is held.
                        body = Mono.just(projectResponse(origin, NATIVE_ID));
                    } else if (NAME.equals(path)) {
                        requested.countDown();
                        body = Mono.fromFuture(held);
                    } else {
                        body = Mono.just(siblingResponse);
                    }
                    return body.map(json -> ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(json)
                            .build());
                }));
        GraphQlClientInterceptor projects = new GraphQlClientInterceptor() {
            @Override
            public Mono<ClientGraphQlResponse> intercept(ClientGraphQlRequest request, Chain chain) {
                Object path = Objects.requireNonNull(request.getVariables().get("fullPath"));
                boolean lookup = request.getDocument().contains("query GetProject(");
                return chain.next(request)
                        .contextWrite(
                                context -> context.put("projectPath", path).put("projectLookup", lookup));
            }
        };
        return HttpGraphQlClient.builder(web)
                .url(origin + "/api/graphql")
                .documentSource(new FragmentMergingDocumentSource(
                        List.of(
                                new ClassPathResource("graphql/gitlab/operations/"),
                                new ClassPathResource("graphql/gitlab/fragments/")),
                        List.of(".graphql", ".gql"),
                        List.of(new ClassPathResource("graphql/gitlab/fragments/GitLabUserFields.graphql"))))
                .interceptor(projects)
                .build();
    }

    private static String issueResponse(String origin, String path, long nativeId) {
        boolean excluded = NAME.equals(path);
        String author = excluded ? """
                {"id":"gid://gitlab/User/88001","username":"late-excluded-author","name":"Late author",
                 "avatarUrl":"https://example.test/avatar.png","webUrl":"https://example.test/late-author","bot":false}
                """ : "null";
        return """
                {"data":{"project":{"issues":{"count":1,"nodes":[
                  {"id":"gid://gitlab/Issue/%s","iid":"1","title":"%s","state":"opened","confidential":false,
                   "webUrl":"%s/%s/-/issues/1","createdAt":"2026-10-14T10:00:00Z","updatedAt":"2026-10-14T10:00:00Z",
                   "author":%s,"userNotesCount":0,"labels":{"nodes":[],"count":0},"assignees":{"nodes":[],"count":0}}
                ],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
                """.formatted(nativeId, excluded ? "Withdrawn issue" : "Healthy sibling issue", origin, path, author);
    }

    @Test
    void shouldKeepAnExplicitlyStoppedGitLabProjectExcludedWhenAllDiscoveryReconcilesAgain() {
        User owner = persistUser("exclusion-owner");
        Workspace own = workspace("excluded-project", owner);
        own.setRepositorySelection(RepositorySelection.ALL);
        workspaces.saveAndFlush(own);
        Workspace shared = workspace("shared-project", owner);
        IdentityProvider source = provider("https://exclusion.example.test");
        connect(own, source);
        connect(shared, source);
        Repository excluded = repository(source, NATIVE_ID, NAME);
        Repository sibling = repository(source, NATIVE_ID + 1, "shared-org/allowed-sibling");
        monitor(shared, NATIVE_ID);
        assertThat(workspaces.findById(own.getId()).orElseThrow().getRepositorySelection())
                .isEqualTo(RepositorySelection.ALL);
        assertThat(gitLabMonitors.monitorAllowed(own, List.of(excluded, sibling)))
                .isEqualTo(2);

        monitorService.removeRepositoryFromMonitor(own.getWorkspaceSlug(), NAME);

        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME))
                .isEmpty();
        assertThat(repositories.findById(excluded.getId())).isPresent();
        assertThat(repositories.findAllByWorkspaceMonitors(own.getId()))
                .extracting(Repository::getId)
                .containsExactly(sibling.getId());

        Repository newlyDiscovered = repository(source, NATIVE_ID + 2, "shared-org/new-sibling");
        Workspace current = workspaces.findById(own.getId()).orElseThrow();
        var reported = repositories.findAllById(List.of(excluded.getId(), sibling.getId(), newlyDiscovered.getId()));

        int created = gitLabMonitors.monitorAllowed(current, reported);

        assertSoftly(softly -> {
            softly.assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME))
                    .as("Stop monitoring must survive an independent later ALL discovery")
                    .isEmpty();
            softly.assertThat(created)
                    .as("only the newly discovered allowed sibling is added")
                    .isEqualTo(1);
            softly.assertThat(repositories.findAllByWorkspaceMonitors(own.getId()))
                    .extracting(Repository::getId)
                    .as("this workspace keeps its allowed sources without regaining the excluded source")
                    .containsExactlyInAnyOrder(sibling.getId(), newlyDiscovered.getId());
            softly.assertThat(monitors.findByWorkspaceIdAndNameWithOwner(shared.getId(), NAME))
                    .as("another workspace keeps its legitimate shared-source monitor")
                    .isPresent();
            softly.assertThat(repositories.findById(excluded.getId())).isPresent();
            softly.assertThat(workspaces.findById(own.getId()).orElseThrow().getRepositorySelection())
                    .as("Stop monitoring must preserve ALL mode for siblings and future projects")
                    .isEqualTo(RepositorySelection.ALL);
        });
    }

    @Test
    void shouldRestoreAStoppedGitLabProjectWhenItIsExplicitlyAddedAgain() {
        User owner = persistUser("include-owner");
        Workspace own = workspace("included-project", owner);
        own.setRepositorySelection(RepositorySelection.ALL);
        workspaces.saveAndFlush(own);
        Workspace shared = workspace("retained-project", owner);
        IdentityProvider source = provider("https://include.example.test");
        connect(own, source);
        connect(shared, source);
        Repository included = repository(source, NATIVE_ID, NAME);
        Repository sibling = repository(source, NATIVE_ID + 1, "shared-org/allowed-sibling");
        monitor(shared, NATIVE_ID);
        assertThat(gitLabMonitors.monitorAllowed(own, List.of(included, sibling)))
                .isEqualTo(2);
        long siblingMonitorId = monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), sibling.getNameWithOwner())
                .orElseThrow()
                .getId();

        monitorService.removeRepositoryFromMonitor(own.getWorkspaceSlug(), NAME);
        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME))
                .isEmpty();
        when(clients.forScope(own.getId()))
                .thenReturn(projectClient(
                        source.getServerUrl(),
                        new ArrayDeque<>(List.of(projectResponse(source.getServerUrl(), NATIVE_ID)))));

        monitorService.addRepositoryToMonitor(own.getWorkspaceSlug(), NAME);

        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(own.getId(), NAME))
                .hasValueSatisfying(
                        restored -> assertThat(restored.getNativeId()).isEqualTo(NATIVE_ID));
        assertThat(monitors.findById(siblingMonitorId)).isPresent();
        assertThat(monitors.findByWorkspaceIdAndNameWithOwner(shared.getId(), NAME))
                .isPresent();
        assertThat(repositories.findAllByWorkspaceMonitors(own.getId()))
                .extracting(Repository::getId)
                .containsExactlyInAnyOrder(included.getId(), sibling.getId());
        assertThat(workspaces.findById(own.getId()).orElseThrow().getRepositorySelection())
                .isEqualTo(RepositorySelection.ALL);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @WithMentorUser
    void shouldExposeOnlyTheConnectedInstanceWhenNamesAndNativeIdsCollide(boolean knownId) {
        User caller = persistUser("mentor");
        Workspace own = workspace("source-a", caller);
        Workspace other = workspace("source-b", caller);
        IdentityProvider first = provider("HTTPS://A.example.test:443/");
        IdentityProvider second = provider("https://b.example.test");
        connect(own, first);
        connect(other, second);
        Repository mine = repository(first, NATIVE_ID, NAME);
        Repository theirs = repository(second, NATIVE_ID, NAME);
        monitor(own, knownId ? NATIVE_ID : null);
        monitor(other, knownId ? NATIVE_ID : null);

        assertThat(repositories.findAllByWorkspaceMonitors(own.getId()))
                .extracting(Repository::getId)
                .containsExactly(mine.getId());
        assertThat(repositories.findAllByWorkspaceMonitors(other.getId()))
                .extracting(Repository::getId)
                .containsExactly(theirs.getId());
        Issue ownWork = issue(mine);
        Issue foreignWork = issue(theirs);
        assertWorkFacts(own, ownWork);
        assertWorkFacts(other, foreignWork);
        assertNoWorkFacts(own, foreignWork);
        assertNoWorkFacts(other, ownWork);
    }

    @Test
    @WithMentorUser
    void shouldExposeNoRepositoryWhenTheWorkspaceHasNoSourceConnection() {
        Workspace workspace = workspace("unconnected", persistUser("mentor"));
        Repository repository = repository(provider("https://unconnected.example.test"), NATIVE_ID, NAME);
        monitor(workspace, null);
        assertThat(repositories.findAllByWorkspaceMonitors(workspace.getId())).isEmpty();
        assertNoWorkFacts(workspace, issue(repository));
    }

    @Test
    @WithMentorUser
    void shouldRetainMonitoredWorkAndRefuseDeliveryWhilePausedThenRestoreAccessOnResume() {
        Workspace workspace = workspace("default-source", persistUser("mentor"));
        Repository mine = repository(ensureGitHubProvider(), NATIVE_ID, NAME);
        IdentityProvider enterprise = gitProviderRepository.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITHUB, "https://enterprise.example.test"));
        repository(enterprise, NATIVE_ID, NAME);
        Connection pending = connections.saveAndFlush(new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "pat",
                new ConnectionConfig.GitHubPatConfig("shared-org", null, Set.of())));
        Connection active = connectionService.transition(
                pending,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "TEST_CONNECTED",
                        "SYSTEM",
                        "provider-scope-test",
                        UUID.randomUUID().toString(),
                        "Source connected"));
        monitor(workspace, null);
        Issue work = issue(mine);
        assertWorkFacts(workspace, work);

        Connection suspended = connectionService.transition(
                active,
                new ConnectionService.TransitionRequest(
                        IntegrationState.SUSPENDED,
                        "TEST_PAUSED",
                        "SYSTEM",
                        "provider-scope-test",
                        UUID.randomUUID().toString(),
                        "Source paused"));
        assertThat(repositories.findAllByWorkspaceMonitors(workspace.getId()))
                .extracting(Repository::getId)
                .containsExactly(mine.getId());
        assertNoWorkFacts(workspace, work);
        connectionService.transition(
                suspended,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "TEST_RESUMED",
                        "SYSTEM",
                        "provider-scope-test",
                        UUID.randomUUID().toString(),
                        "Source resumed"));
        assertWorkFacts(workspace, work);
    }

    @Test
    void shouldHealOnlyMonitorsOnTheRenamedRepositoriesProvider() {
        User owner = persistUser("scope-owner");
        Workspace own = workspace("rename-a", owner);
        Workspace sameSource = workspace("rename-shared", owner);
        Workspace other = workspace("rename-b", owner);
        IdentityProvider first = provider("https://rename-a.example.test");
        IdentityProvider second = provider("https://rename-b.example.test");
        connect(own, first);
        connect(sameSource, first);
        connect(other, second);
        repository(first, NATIVE_ID, NAME);
        repository(second, NATIVE_ID, NAME);
        RepositoryToMonitor mine = monitor(own, NATIVE_ID);
        RepositoryToMonitor shared = monitor(sameSource, NATIVE_ID);
        RepositoryToMonitor theirs = monitor(other, NATIVE_ID);

        syncTargets.reconcileSyncTargetsForRepository(
                Objects.requireNonNull(first.getId()), NATIVE_ID, "shared-org/renamed");

        assertThat(monitors.findById(mine.getId()).orElseThrow().getNameWithOwner())
                .isEqualTo("shared-org/renamed");
        assertThat(monitors.findById(shared.getId()).orElseThrow().getNameWithOwner())
                .isEqualTo("shared-org/renamed");
        assertThat(monitors.findById(theirs.getId()).orElseThrow().getNameWithOwner())
                .isEqualTo(NAME);
    }

    @Test
    void shouldRepairAProvenForeignInstanceIdButPreserveAnOwnInstanceIdUnderAnotherName() {
        Workspace workspace = workspace("repair", persistUser("repair-owner"));
        IdentityProvider own = provider("https://repair-a.example.test");
        IdentityProvider foreign = provider("https://repair-b.example.test");
        connect(workspace, own);
        repository(own, 9001L, NAME);
        Repository foreignOld = repository(foreign, 701L, NAME);
        RepositoryToMonitor monitor = monitor(workspace, 701L);
        syncTargets.reconcileSyncTargetIdentity(monitor.getId(), 9001L, NAME);
        assertThat(monitors.findById(monitor.getId()).orElseThrow().getNativeId())
                .isEqualTo(9001L);

        repository(own, 702L, "shared-org/reassigned");
        foreignOld.setNameWithOwner("shared-org/previous");
        repositories.saveAndFlush(foreignOld);
        repository(foreign, 702L, NAME);
        monitor.setNativeId(702L);
        monitors.saveAndFlush(monitor);
        syncTargets.reconcileSyncTargetIdentity(monitor.getId(), 9001L, NAME);
        assertThat(monitors.findById(monitor.getId()).orElseThrow().getNativeId())
                .isEqualTo(702L);
    }

    @Test
    void shouldNotInferAReplacementFromForeignRowsWithoutAConnectedSource() {
        Workspace workspace = workspace("unknown-repair", persistUser("unknown-owner"));
        repository(provider("https://unknown.example.test"), 701L, NAME);
        RepositoryToMonitor monitor = monitor(workspace, 701L);
        syncTargets.reconcileSyncTargetIdentity(monitor.getId(), 9001L, NAME);
        assertThat(monitors.findById(monitor.getId()).orElseThrow().getNativeId())
                .isEqualTo(701L);
    }

    @Test
    @WithMentorUser
    void shouldDenyPendingAndRetiredSourcesAfterAWorkspaceReconnects() {
        Workspace workspace = workspace("reconnected", persistUser("mentor"));
        IdentityProvider old = provider("https://retired.example.test");
        IdentityProvider current = provider("https://current.example.test");
        Workspace sharedOld = workspace("retained-old-source", persistUser("retained-owner"));
        connect(sharedOld, old);
        monitor(sharedOld, NATIVE_ID);
        Repository oldRepository = repository(old, NATIVE_ID, NAME);
        Repository currentRepository = repository(current, NATIVE_ID, NAME);
        Connection pending = connections.saveAndFlush(new Connection(
                workspace,
                IntegrationKind.GITLAB,
                old.getServerUrl(),
                new ConnectionConfig.GitLabConfig(
                        old.getServerUrl(),
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of(),
                        null)));
        monitor(workspace, NATIVE_ID);
        assertThat(repositories.findAllByWorkspaceMonitors(workspace.getId())).isEmpty();
        assertNoWorkFacts(workspace, issue(oldRepository));

        Connection active = connectionService.transition(
                pending,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "TEST_CONNECTED",
                        "SYSTEM",
                        "source-lifecycle-test",
                        UUID.randomUUID().toString(),
                        "Source connected"));
        eraser.eraseWorkspaceScmMirror(workspace.getId());
        connectionService.transition(
                active,
                new ConnectionService.TransitionRequest(
                        IntegrationState.UNINSTALLED,
                        "TEST_UNINSTALLED",
                        "SYSTEM",
                        "source-lifecycle-test",
                        UUID.randomUUID().toString(),
                        "Source disconnected"));
        connect(workspace, current);
        monitor(workspace, NATIVE_ID);
        assertThat(repositories.findAllByWorkspaceMonitors(workspace.getId()))
                .extracting(Repository::getId)
                .containsExactly(currentRepository.getId());
        assertWorkFacts(workspace, issue(currentRepository));
    }

    @Test
    void shouldRetainAReplacementMonitorWhenAnInstallationRemovalNamesTheOldNativeId() {
        Workspace workspace = workspace("installation-identity", persistUser("installation-owner"));
        Repository replacement = repository(ensureGitHubProvider(), NATIVE_ID + 1, NAME);
        long installationId = 881234L;
        Connection pending = connections.saveAndFlush(new Connection(
                workspace,
                IntegrationKind.GITHUB,
                Long.toString(installationId),
                new ConnectionConfig.GitHubAppConfig(installationId, "shared-org", null, Set.of())));
        connectionService.transition(
                pending,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "TEST_CONNECTED",
                        "SYSTEM",
                        "installation-identity-test",
                        UUID.randomUUID().toString(),
                        "Source connected"));
        RepositoryToMonitor monitor = monitor(workspace, NATIVE_ID + 1);
        monitorService.removeRepositoryMonitorForInstallation(installationId, NAME, NATIVE_ID);
        assertThat(monitors.findById(monitor.getId())).isPresent();
        assertThat(repositories.findById(replacement.getId())).isPresent();
        monitor.setNativeId(null);
        monitors.saveAndFlush(monitor);
        monitorService.removeRepositoryMonitorForInstallation(installationId, NAME, NATIVE_ID);
        assertThat(monitors.findById(monitor.getId())).isPresent();
        monitorService.removeRepositoryMonitorForInstallation(installationId, NAME, NATIVE_ID + 1);
        assertThat(monitors.findById(monitor.getId())).isEmpty();
        assertThat(repositories.findById(replacement.getId())).isEmpty();
    }

    @Test
    void shouldEraseOnlyTheExactOrphanAndKeepSharedAndForeignRepositories() {
        User owner = persistUser("erase-owner");
        Workspace first = workspace("erase-a", owner);
        Workspace shared = workspace("erase-shared", owner);
        Workspace foreign = workspace("erase-b", owner);
        IdentityProvider source = provider("https://erase-a.example.test");
        IdentityProvider other = provider("https://erase-b.example.test");
        connect(first, source);
        connect(shared, source);
        connect(foreign, other);
        Repository ownRepository = repository(source, NATIVE_ID, NAME);
        Repository foreignRepository = repository(other, NATIVE_ID, NAME);
        monitor(first, NATIVE_ID);
        monitor(shared, NATIVE_ID);
        RepositoryToMonitor foreignMonitor = monitor(foreign, NATIVE_ID);

        eraser.eraseWorkspaceScmMirror(first.getId());
        assertThat(repositories.findById(ownRepository.getId())).isPresent();
        assertThat(repositories.findById(foreignRepository.getId())).isPresent();

        eraser.eraseWorkspaceScmMirror(shared.getId());
        assertThat(repositories.findById(ownRepository.getId())).isEmpty();
        assertThat(repositories.findById(foreignRepository.getId())).isPresent();
        assertThat(monitors.findById(foreignMonitor.getId()).orElseThrow().getNameWithOwner())
                .isEqualTo(NAME);
    }

    @Test
    void shouldNotDeleteAnySameNamedMirrorWhenTheErasedMonitorHasNoSourceAuthority() {
        Workspace unknown = workspace("unknown-erase", persistUser("unknown-erase-owner"));
        Repository first = repository(provider("https://unknown-erase-a.example.test"), NATIVE_ID, NAME);
        Repository second = repository(provider("https://unknown-erase-b.example.test"), NATIVE_ID, NAME);
        RepositoryToMonitor monitor = monitor(unknown, null);
        eraser.eraseWorkspaceScmMirror(unknown.getId());
        assertThat(monitors.findById(monitor.getId())).isEmpty();
        assertThat(repositories.findById(first.getId())).isPresent();
        assertThat(repositories.findById(second.getId())).isPresent();
    }

    @ParameterizedTest
    @CsvSource({"SELECTED,false", "SELECTED,true", "ALL,false"})
    void shouldRelateOnlyTheLinkedProjectsIssueAndOnlyWhenTheWorkspaceMayReferToIt(
            RepositorySelection selection, boolean monitorsLinkedProject) {
        User owner = persistUser("links-owner");
        Workspace own = workspace("links-own", owner);
        own.setRepositorySelection(selection);
        workspaces.saveAndFlush(own);
        Workspace holder = workspace("links-holder", owner);
        IdentityProvider source = provider("https://links.example.test");
        connect(own, source);
        connect(holder, source);
        Repository ownRepository = repository(source, 8101L, "shared-org/links-own");
        Repository linkedRepository = repository(source, 8102L, "shared-org/links-linked");
        monitorOf(own, ownRepository);
        monitorOf(holder, linkedRepository);
        if (monitorsLinkedProject) monitorOf(own, linkedRepository);
        // Both projects number their issues from 1, so #5 and #6 exist in each.
        Issue linking = numberedIssue(ownRepository, 1, 810_101L);
        Issue ownFive = numberedIssue(ownRepository, 5, 810_105L);
        Issue ownSix = numberedIssue(ownRepository, 6, 810_106L);
        Issue linkedFive = numberedIssue(linkedRepository, 5, 810_205L);
        Issue linkedSix = numberedIssue(linkedRepository, 6, 810_206L);
        linkedSix.getBlockedBy().add(linkedFive);
        issues.saveAndFlush(linkedSix);
        GitLabProjectAdmission.ProjectRead read =
                projectAdmission.capture(own.getId(), ownRepository).orElseThrow();
        GitLabTokenService tokens = mock(GitLabTokenService.class);
        when(tokens.resolveServerUrl(own.getId())).thenReturn(source.getServerUrl());
        when(tokens.getAccessToken(own.getId())).thenReturn("glpat-test");
        String links = """
                [{"iid":5,"project_id":8102,"link_type":"is_blocked_by"},
                 {"iid":6,"project_id":8102,"link_type":"blocks"}]
                """;
        // GitLab answers only for #1; the other issues fail their read and stay as they are.
        var dependencies = new GitLabIssueDependencySyncService(
                issues,
                repositories,
                tokens,
                mock(GitLabRateLimitTracker.class),
                WebClient.builder()
                        .exchangeFunction(request -> Mono.just(
                                request.url().getPath().endsWith("/projects/8101/issues/1/links")
                                        ? ClientResponse.create(HttpStatus.OK)
                                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                                .body(links)
                                                .build()
                                        : ClientResponse.create(HttpStatus.NOT_FOUND)
                                                .build())),
                projectAdmission);

        dependencies.syncDependenciesForRepository(own.getId(), ownRepository, null, read);

        boolean related = selection == RepositorySelection.ALL || monitorsLinkedProject;
        assertSoftly(softly -> {
            softly.assertThat(blockerIds(linking))
                    .containsExactlyInAnyOrderElementsOf(related ? List.of(linkedFive.getId()) : List.of());
            softly.assertThat(blockerIds(linkedSix))
                    .containsExactlyInAnyOrderElementsOf(
                            related ? List.of(linkedFive.getId(), linking.getId()) : List.of(linkedFive.getId()));
            softly.assertThat(blockerIds(ownFive)).isEmpty();
            softly.assertThat(blockerIds(ownSix)).isEmpty();
            softly.assertThat(issues.findAllIssuesByRepositoryId(linkedRepository.getId()))
                    .extracting(Issue::getId)
                    .containsExactlyInAnyOrder(linkedFive.getId(), linkedSix.getId());
            softly.assertThat(monitors.findByWorkspaceIdAndNameWithOwner(
                            holder.getId(), linkedRepository.getNameWithOwner()))
                    .isPresent();
        });
    }

    @ParameterizedTest
    @CsvSource({"SELECTED,REPORTED", "SELECTED,EMPTY", "SELECTED,NO_BODY", "ALL,REPORTED", "ALL,EMPTY", "ALL,NO_BODY"})
    void shouldKeepAnOutOfScopeEdgeAnotherWorkspaceStoredWhileClearingStaleInScopeEdges(
            RepositorySelection selection, String answer) {
        User owner = persistUser("edges-owner");
        Workspace own = workspace("edges-own", owner);
        own.setRepositorySelection(selection);
        workspaces.saveAndFlush(own);
        Workspace holder = workspace("edges-holder", owner);
        holder.setRepositorySelection(RepositorySelection.ALL);
        workspaces.saveAndFlush(holder);
        IdentityProvider source = provider("https://edges.example.test");
        connect(own, source);
        connect(holder, source);
        Repository sourceA = repository(source, 8201L, "shared-org/edges-a");
        Repository sourceB = repository(source, 8202L, "shared-org/edges-b");
        monitorOf(own, sourceA);
        monitorOf(holder, sourceA);
        monitorOf(holder, sourceB);
        Issue blocked = numberedIssue(sourceA, 1, 820_101L);
        Issue ownBlocker = numberedIssue(sourceA, 5, 820_105L);
        Issue sharedBlocker = numberedIssue(sourceB, 5, 820_205L);
        Issue sharedBlocked = numberedIssue(sourceB, 6, 820_206L);
        GitLabTokenService tokens = mock(GitLabTokenService.class);
        for (Workspace reader : List.of(own, holder)) {
            when(tokens.resolveServerUrl(reader.getId())).thenReturn(source.getServerUrl());
            when(tokens.getAccessToken(reader.getId())).thenReturn("glpat-test");
        }

        // The workspace that follows both projects stores the edges GitLab reports for A#1.
        dependencyLinks(tokens, 8201L, """
                        [{"iid":5,"project_id":8202,"link_type":"is_blocked_by"},
                         {"iid":5,"project_id":8201,"link_type":"is_blocked_by"},
                         {"iid":6,"project_id":8202,"link_type":"blocks"}]
                        """)
                .syncDependenciesForRepository(
                        holder.getId(),
                        sourceA,
                        null,
                        projectAdmission.capture(holder.getId(), sourceA).orElseThrow());
        assertThat(blockerIds(blocked)).containsExactlyInAnyOrder(sharedBlocker.getId(), ownBlocker.getId());
        assertThat(blockerIds(sharedBlocked)).containsExactly(blocked.getId());

        // GitLab later drops A#1's link to A#5 and still reports B#5, or reports no links, or answers without a body.
        String links = switch (answer) {
            case "REPORTED" -> "[{\"iid\":5,\"project_id\":8202,\"link_type\":\"is_blocked_by\"}]";
            case "EMPTY" -> "[]";
            case "NO_BODY" -> null;
            default -> throw new IllegalArgumentException(answer);
        };
        dependencyLinks(tokens, 8201L, links)
                .syncDependenciesForRepository(
                        own.getId(),
                        sourceA,
                        null,
                        projectAdmission.capture(own.getId(), sourceA).orElseThrow());

        // No body says nothing, so both edges stay. A workspace that may not refer to B cannot tell whether B's edge
        // is gone, so it stays; one that may, and every stale in-scope edge, follow GitLab.
        List<Long> expected = switch (answer) {
            case "NO_BODY" -> List.of(sharedBlocker.getId(), ownBlocker.getId());
            case "EMPTY" -> selection == RepositorySelection.SELECTED ? List.of(sharedBlocker.getId()) : List.of();
            default -> List.of(sharedBlocker.getId());
        };
        assertSoftly(softly -> {
            softly.assertThat(blockerIds(blocked)).containsExactlyInAnyOrderElementsOf(expected);
            softly.assertThat(blockerIds(sharedBlocked)).containsExactly(blocked.getId());
            softly.assertThat(issues.findAllIssuesByRepositoryId(sourceB.getId()))
                    .extracting(Issue::getId)
                    .containsExactlyInAnyOrder(sharedBlocker.getId(), sharedBlocked.getId());
            softly.assertThat(monitors.findByWorkspaceIdAndNameWithOwner(holder.getId(), sourceA.getNameWithOwner()))
                    .isPresent();
            softly.assertThat(monitors.findByWorkspaceIdAndNameWithOwner(holder.getId(), sourceB.getNameWithOwner()))
                    .isPresent();
        });
    }

    /**
     * The dependency sync over a GitLab that answers only for issue #1 of {@code projectId}: with {@code links}, or
     * with 204 and no body when {@code links} is null.
     */
    private GitLabIssueDependencySyncService dependencyLinks(
            GitLabTokenService tokens, long projectId, @Nullable String links) {
        return new GitLabIssueDependencySyncService(
                issues,
                repositories,
                tokens,
                mock(GitLabRateLimitTracker.class),
                WebClient.builder()
                        .exchangeFunction(request -> Mono.just(
                                !request.url().getPath().endsWith("/projects/" + projectId + "/issues/1/links")
                                        ? ClientResponse.create(HttpStatus.NOT_FOUND)
                                                .build()
                                        : links == null
                                                ? ClientResponse.create(HttpStatus.NO_CONTENT)
                                                        .build()
                                                : ClientResponse.create(HttpStatus.OK)
                                                        .header(
                                                                HttpHeaders.CONTENT_TYPE,
                                                                MediaType.APPLICATION_JSON_VALUE)
                                                        .body(links)
                                                        .build())),
                projectAdmission);
    }

    private List<Long> blockerIds(Issue issue) {
        return issues.findByIdWithBlockedBy(issue.getId()).orElseThrow().getBlockedBy().stream()
                .map(Issue::getId)
                .toList();
    }

    private RepositoryToMonitor monitorOf(Workspace workspace, Repository repository) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        monitor.setNativeId(repository.getNativeId());
        return monitors.saveAndFlush(monitor);
    }

    private Issue numberedIssue(Repository repository, int number, long nativeId) {
        Issue issue = new Issue();
        issue.setProvider(repository.getProvider());
        issue.setRepository(repository);
        issue.setNativeId(nativeId);
        issue.setNumber(number);
        issue.setTitle("Issue " + number);
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/issues/" + number);
        return issues.saveAndFlush(issue);
    }

    private Workspace workspace(String slug, User owner) {
        return createWorkspace(slug, slug, "shared-org", AccountType.ORG, owner);
    }

    private IdentityProvider provider(String url) {
        return gitProviderRepository.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, url));
    }

    private Connection connect(Workspace workspace, IdentityProvider provider) {
        Connection unsaved = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                provider.getServerUrl(),
                new ConnectionConfig.GitLabConfig(
                        provider.getServerUrl(),
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of(),
                        null));
        // A synthetic token, so an explicit Add can ask GitLab with the token and server its connection stores.
        String token = "synthetic-token-" + workspace.getWorkspaceSlug();
        unsaved.setCredentials(new BearerToken(token, null), credentialConverter);
        Connection connection = connections.saveAndFlush(unsaved);
        // That client answers as each test arranged this workspace's scoped client, but only for this token and server.
        long workspaceId = workspace.getId();
        when(clients.forScope(eq(workspaceId), eq(token), eq(provider.getServerUrl())))
                .thenAnswer(invocation -> clients.forScope(workspaceId));
        return connectionService.transition(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "TEST_CONNECTED",
                        "SYSTEM",
                        "provider-scope-test",
                        UUID.randomUUID().toString(),
                        "Source connected"));
    }

    private Repository repository(IdentityProvider provider, long nativeId, String name) {
        Repository repository = new Repository();
        repository.setProvider(provider);
        repository.setNativeId(nativeId);
        repository.setNameWithOwner(name);
        repository.setName(name.substring(name.lastIndexOf('/') + 1));
        repository.setHtmlUrl(provider.getServerUrl() + "/" + name);
        repository.setDefaultBranch("main");
        return repositories.saveAndFlush(repository);
    }

    private RepositoryToMonitor monitor(Workspace workspace, @Nullable Long nativeId) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(NAME);
        monitor.setNativeId(nativeId);
        return monitors.saveAndFlush(monitor);
    }

    private Issue issue(Repository repository) {
        Issue issue = new Issue();
        issue.setProvider(repository.getProvider());
        issue.setRepository(repository);
        issue.setNativeId(7L);
        issue.setNumber(7);
        issue.setTitle("Captured work");
        issue.setState(Issue.State.OPEN);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/issues/7");
        return issues.saveAndFlush(issue);
    }

    private void assertWorkFacts(Workspace workspace, Issue issue) {
        assertThat(sourceAccess.permits(item(workspace, issue), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isTrue();
    }

    private void assertNoWorkFacts(Workspace workspace, Issue issue) {
        assertThat(sourceAccess.permits(item(workspace, issue), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
    }

    private ItemRef item(Workspace workspace, Issue issue) {
        var sourceRepository = Objects.requireNonNull(issue.getRepository());
        var repository = itemWriter.upsert(new ItemRegistration(
                workspace.getId(), new ItemKind("scm.repository"), sourceRepository.getId(), null, null));
        return itemWriter.upsert(new ItemRegistration(
                workspace.getId(), new ItemKind("scm.issue"), issue.getId(), repository.itemId(), null));
    }
}

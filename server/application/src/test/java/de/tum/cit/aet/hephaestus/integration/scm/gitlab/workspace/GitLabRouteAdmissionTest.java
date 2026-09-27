package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabDescendantGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class GitLabRouteAdmissionTest {

    private static final String ORIGIN = "https://gitlab.lrz.de";
    private static final String GROUP = "hephaestustest/introcourse";
    private static final long CONNECTION_ID = 7L;
    private static final long WORKSPACE_ID = 3L;
    private static final long GROUP_ID = 42L;
    private static final long PROVIDER_ID = 5L;
    private static final long PROJECT_A = 100L;
    private static final long PROJECT_B = 200L;

    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final IdentityProviderRepository identityProviderRepository = mock(IdentityProviderRepository.class);
    private final RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
    private final RepositoryToMonitorRepository monitors = mock(RepositoryToMonitorRepository.class);
    private final WorkspaceScopeFilter scopeFilter = mock(WorkspaceScopeFilter.class);
    private final GitLabRepositoryMonitors repositoryMonitors = mock(GitLabRepositoryMonitors.class);
    private final GitLabProjectSyncService projectSync = mock(GitLabProjectSyncService.class);
    private final GitLabTeamSyncService teamSync = mock(GitLabTeamSyncService.class);
    private final GitLabUserService userService = mock(GitLabUserService.class);
    private final SyncTargetProvider syncTargets = mock(SyncTargetProvider.class);
    private final Connection connection = mock(Connection.class);
    private final Workspace workspace = new Workspace();
    private final IdentityProvider provider = new IdentityProvider();
    private final GitLabRouteAdmission.AdmittedRoute route =
            new GitLabRouteAdmission.AdmittedRoute(CONNECTION_ID, WORKSPACE_ID, PROVIDER_ID, GROUP_ID, GROUP);

    private GitLabRouteAdmission admission;

    @BeforeEach
    void setUp() {
        GitLabProperties properties = mock(GitLabProperties.class);
        when(properties.defaultServerUrl()).thenReturn(ORIGIN + "/");
        admission = new GitLabRouteAdmission(
                connectionRepository,
                identityProviderRepository,
                repositoryRepository,
                monitors,
                scopeFilter,
                repositoryMonitors,
                projectSync,
                teamSync,
                userService,
                syncTargets,
                properties,
                new ObjectMapper(),
                mock(PlatformTransactionManager.class));

        provider.setId(PROVIDER_ID);
        provider.setType(IdentityProviderType.GITLAB);
        when(identityProviderRepository.findByTypeAndServerUrl(IdentityProviderType.GITLAB, ORIGIN + "/"))
                .thenReturn(Optional.of(provider));
        ReflectionTestUtils.setField(workspace, "id", WORKSPACE_ID);
        workspace.setAccountLogin(GROUP);
        when(scopeFilter.isWorkspaceAllowed(workspace)).thenReturn(true);
        when(connection.getKind()).thenReturn(IntegrationKind.GITLAB);
        when(connection.getState()).thenReturn(IntegrationState.ACTIVE);
        when(connection.getWorkspace()).thenReturn(workspace);
        when(connection.getConfig()).thenReturn(config(ORIGIN, GROUP_ID));
        when(connectionRepository.findByIdAndWorkspaceId(CONNECTION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(connection));
    }

    @Nested
    class Route {

        @Test
        void shouldHandleWithTheSignedRouteWhenItMatchesTheActiveConnection() {
            AtomicReference<GitLabRouteAdmission.@Nullable AdmittedRoute> seen = new AtomicReference<>();

            boolean admitted = admission.admit(
                    WORKSPACE_ID,
                    message(NEUTRAL_EVENT, headers -> {}),
                    () -> seen.set(GitLabRouteAdmission.current().orElseThrow()));

            assertThat(admitted).isTrue();
            assertThat(seen.get()).isEqualTo(route);
            assertThat(GitLabRouteAdmission.current()).isEmpty();
            verify(connectionRepository).acquireLifecycleLock(CONNECTION_ID, WORKSPACE_ID);
        }

        @Test
        void shouldNotAdmitARouteThatNoLongerMatches() {
            assertNotAdmitted(WORKSPACE_ID, h -> h.put(GitLabRouteCredential.HEADER_WORKSPACE, "4"));
            assertNotAdmitted(WORKSPACE_ID, h -> h.put(GitLabRouteCredential.HEADER_GROUP_ID, "43"));
            assertNotAdmitted(WORKSPACE_ID, h -> h.put(GitLabRouteCredential.HEADER_GROUP_PATH, "hephaestustest"));
            assertNotAdmitted(4L, h -> {});
            assertNotAdmitted(WORKSPACE_ID, h -> h.remove(GitLabRouteCredential.HEADER_ORIGIN));

            when(connection.getState()).thenReturn(IntegrationState.SUSPENDED);
            assertNotAdmitted(WORKSPACE_ID, h -> {});
            when(connectionRepository.findByIdAndWorkspaceId(CONNECTION_ID, WORKSPACE_ID))
                    .thenReturn(Optional.empty());
            assertNotAdmitted(WORKSPACE_ID, h -> {});
        }

        @Test
        void shouldNotAdmitTheSameGroupPathOnAnotherInstance() {
            when(connection.getConfig()).thenReturn(config("https://gitlab.example.com", GROUP_ID));
            assertNotAdmitted(
                    WORKSPACE_ID, h -> h.put(GitLabRouteCredential.HEADER_ORIGIN, "https://gitlab.example.com"));
            assertNotAdmitted(WORKSPACE_ID, h -> {});
        }

        @Test
        void shouldLetAFailedLookupPropagateSoTheDeliveryIsRedelivered() {
            when(connectionRepository.acquireLifecycleLock(CONNECTION_ID, WORKSPACE_ID))
                    .thenThrow(new IllegalStateException("database unavailable"));
            Runnable handling = mock(Runnable.class);

            assertThatThrownBy(() -> admission.admit(WORKSPACE_ID, message(NEUTRAL_EVENT, h -> {}), handling))
                    .hasMessage("database unavailable");
            verify(handling, never()).run();
        }
    }

    @Nested
    class Project {

        @Test
        void shouldNotMoveAnotherGroupsProjectNamedByItsIdUnderAConnectedPath() {
            when(repositoryRepository.findByNativeIdAndProviderId(PROJECT_B, PROVIDER_ID))
                    .thenReturn(Optional.of(repository(PROJECT_B, "other-group/b")));
            when(projectSync.fetchProjectById(WORKSPACE_ID, PROJECT_B))
                    .thenReturn(Optional.of(reported(PROJECT_B, "other-group/b")));

            assertProjectNotAdmitted(projectEvent("project_rename", PROJECT_B, GROUP + "/a"));
            assertProjectNotAdmitted(projectEvent("project_transfer", PROJECT_B, GROUP + "/a"));
            verify(projectSync, never()).persistProject(any());
            verify(syncTargets, never()).reconcileSyncTargetIdentity(anyLong(), any(), any());
            verify(syncTargets, never()).removeSyncTarget(anyLong());
        }

        @Test
        void shouldNotAdmitWorkThatNamesAnotherProjectByIdUnderAConnectedPath() {
            when(repositoryRepository.findByNameWithOwnerAndProviderId(GROUP + "/a", PROVIDER_ID))
                    .thenReturn(Optional.of(repository(PROJECT_A, GROUP + "/a")));
            when(projectSync.fetchProject(WORKSPACE_ID, GROUP + "/a"))
                    .thenReturn(Optional.of(reported(PROJECT_A, GROUP + "/a")));

            assertProjectNotAdmitted(workEvent(PROJECT_B, GROUP + "/a"));
            verify(projectSync, never()).persistProject(any());
        }

        @Test
        void shouldAdmitWorkOnAProjectThisWorkspaceMonitorsWithoutAskingGitLab() {
            when(repositoryRepository.findByNameWithOwnerAndProviderId(GROUP + "/a", PROVIDER_ID))
                    .thenReturn(Optional.of(repository(PROJECT_A, GROUP + "/a")));
            when(monitors.existsByWorkspaceIdAndNameWithOwner(WORKSPACE_ID, GROUP + "/a"))
                    .thenReturn(true);

            assertProjectAdmitted(workEvent(PROJECT_A, GROUP + "/a"));
            verify(projectSync, never()).fetchProject(any(), anyString());
        }

        @Test
        void shouldNotResumeMonitoringAProjectMovedOutWhenWorkStillNamesItsOldPath() {
            // The shared row keeps the old path after the move; this workspace no longer monitors it.
            when(repositoryRepository.findByNameWithOwnerAndProviderId(GROUP + "/a", PROVIDER_ID))
                    .thenReturn(Optional.of(repository(PROJECT_A, GROUP + "/a")));
            when(projectSync.fetchProject(WORKSPACE_ID, GROUP + "/a"))
                    .thenReturn(Optional.of(reported(PROJECT_A, "elsewhere/a")))
                    .thenReturn(Optional.empty());

            assertProjectNotAdmitted(workEvent(PROJECT_A, GROUP + "/a"));
            assertProjectNotAdmitted(workEvent(PROJECT_A, GROUP + "/a"));
            verify(projectSync, never()).persistProject(any());
            verify(repositoryMonitors, never()).monitorAllowed(any(), any());
        }

        @Test
        void shouldStoreANewNestedProjectAsGitLabReportsItBeforeItsFirstWork() {
            GitLabProjectResponse nested = reported(PROJECT_A, GROUP + "/sub/new");
            Repository stored = repository(PROJECT_A, GROUP + "/sub/new");
            when(projectSync.fetchProject(WORKSPACE_ID, GROUP + "/sub/new")).thenReturn(Optional.of(nested));
            when(projectSync.persistProject(nested)).thenReturn(Optional.of(stored));
            when(repositoryMonitors.isAllowed(workspace, GROUP + "/sub/new")).thenReturn(true);

            assertProjectAdmitted(workEvent(PROJECT_A, GROUP + "/sub/new"));
            verify(repositoryMonitors).monitorAllowed(workspace, List.of(stored));
            assertProjectNotAdmitted(workEvent(PROJECT_A, "hephaestustest/introcourse-other/a"));
            assertProjectNotAdmitted(workEvent(PROJECT_A, GROUP + "/hidden"));
        }

        @Test
        void shouldRedeliverWhenGitLabCannotAnswer() {
            when(projectSync.fetchProjectById(WORKSPACE_ID, PROJECT_A))
                    .thenThrow(new IllegalStateException("GitLab unavailable"));
            Runnable handling = mock(Runnable.class);

            assertThatThrownBy(() -> admission.admit(
                            WORKSPACE_ID,
                            message(projectEvent("project_destroy", PROJECT_A, GROUP + "/a"), h -> {}),
                            handling))
                    .hasMessage("GitLab unavailable");
            verify(handling, never()).run();
            verify(syncTargets, never()).removeSyncTarget(anyLong());
        }

        @Test
        void shouldFollowThePathGitLabReportsNotTheOneAStaleRenameNames() {
            when(repositoryRepository.findByNativeIdAndProviderId(PROJECT_A, PROVIDER_ID))
                    .thenReturn(Optional.of(repository(PROJECT_A, GROUP + "/old")));
            GitLabProjectResponse current = reported(PROJECT_A, GROUP + "/sub/current");
            when(projectSync.fetchProjectById(WORKSPACE_ID, PROJECT_A)).thenReturn(Optional.of(current));
            when(projectSync.persistProject(current))
                    .thenReturn(Optional.of(repository(PROJECT_A, GROUP + "/sub/current")));
            when(monitors.findByWorkspaceIdAndNameWithOwner(WORKSPACE_ID, GROUP + "/old"))
                    .thenReturn(Optional.of(monitor(55L)));

            assertProjectAdmitted(projectEvent("project_rename", PROJECT_A, GROUP + "/stale"));
            verify(syncTargets).reconcileSyncTargetIdentity(55L, PROJECT_A, GROUP + "/sub/current");
        }

        @Test
        void shouldOnlyStopMonitoringAProjectGitLabReportsElsewhereOrNotAtAll() {
            when(repositoryRepository.findByNativeIdAndProviderId(PROJECT_A, PROVIDER_ID))
                    .thenReturn(Optional.of(repository(PROJECT_A, GROUP + "/a")));
            when(monitors.findByWorkspaceIdAndNameWithOwner(WORKSPACE_ID, GROUP + "/a"))
                    .thenReturn(Optional.of(monitor(56L)));
            when(projectSync.fetchProjectById(WORKSPACE_ID, PROJECT_A))
                    .thenReturn(Optional.of(reported(PROJECT_A, "elsewhere/a")))
                    .thenReturn(Optional.empty());

            // A destroy event for a project that was only moved, then one GitLab no longer reports to this connection.
            assertProjectNotAdmitted(projectEvent("project_destroy", PROJECT_A, GROUP + "/a"));
            assertProjectNotAdmitted(projectEvent("project_destroy", PROJECT_A, GROUP + "/a"));
            verify(syncTargets, times(2)).removeSyncTarget(56L);
            verify(projectSync, never()).persistProject(any());
            verify(repositoryRepository, never()).delete(any());
        }

        @Test
        void shouldLeaveAMonitorWithoutIdAloneWhenOnlyTheEventNamesItsPath() {
            // This workspace's monitor predates native ids; the event claims another project used to live there.
            when(monitors.findByWorkspaceIdAndNameWithOwner(WORKSPACE_ID, GROUP + "/a"))
                    .thenReturn(Optional.of(monitor(57L)));
            when(repositoryRepository.findByNativeIdAndProviderId(PROJECT_B, PROVIDER_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(repository(PROJECT_B, "other-group/b")));
            when(projectSync.fetchProjectById(WORKSPACE_ID, PROJECT_B))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(reported(PROJECT_B, "other-group/b")));
            String forged = """
                    {"event_name":"project_transfer","project_id":%d,"path_with_namespace":"other-group/b",\
                    "old_path_with_namespace":"%s/a"}""".formatted(PROJECT_B, GROUP);

            assertProjectNotAdmitted(forged);
            assertProjectNotAdmitted(forged);
            verify(syncTargets, never()).removeSyncTarget(anyLong());
            verify(syncTargets, never()).reconcileSyncTargetIdentity(anyLong(), any(), any());
        }

        @Test
        void shouldWriteNothingWhenTheConnectionLeavesBetweenGitLabsAnswerAndTheWrite() {
            GitLabProjectResponse current = reported(PROJECT_A, GROUP + "/a");
            when(projectSync.fetchProjectById(WORKSPACE_ID, PROJECT_A)).thenReturn(Optional.of(current));
            // Active when the route is resolved, disconnected by the time the reported project would be stored.
            when(connection.getState()).thenReturn(IntegrationState.ACTIVE, IntegrationState.SUSPENDED);

            assertProjectNotAdmitted(projectEvent("project_create", PROJECT_A, GROUP + "/a"));
            verify(projectSync, never()).persistProject(any());
        }
    }

    @Nested
    class Identity {

        @Test
        void shouldStoreTheUserGitLabReportsWhateverThePayloadClaims() {
            GitLabUserLookup canonical = new GitLabUserLookup(
                    "gid://gitlab/User/9", "alice", "Alice", null, "https://gitlab.lrz.de/alice", null);
            when(userService.fetchCanonicalUsers(WORKSPACE_ID, List.of(9L))).thenReturn(Map.of(9L, canonical));
            UserRepository users = mock(UserRepository.class);
            when(users.tryAcquireLoginLock("alice", PROVIDER_ID)).thenReturn(true);
            GitLabProperties properties = mock(GitLabProperties.class);
            when(properties.defaultServerUrl()).thenReturn(ORIGIN);
            GitLabUserService realUsers = new GitLabUserService(users, properties, mock(), mock());
            GitLabWebhookUser forged = new GitLabWebhookUser(9L, "bob", "Bob", "https://evil/avatar", "bob@evil");

            assertThat(admission.admit(
                            WORKSPACE_ID,
                            message("{\"object_kind\":\"note\",\"user\":{\"id\":9,\"username\":\"bob\"}}", h -> {}),
                            () -> realUsers.findOrCreateUser(forged, PROVIDER_ID)))
                    .isTrue();

            verify(users).freeLoginConflicts("alice", 9L, PROVIDER_ID);
            verify(users, never()).freeLoginConflicts(eq("bob"), anyLong(), any());
            verify(users)
                    .upsertUser(
                            eq(9L),
                            eq(PROVIDER_ID),
                            eq("alice"),
                            eq("Alice"),
                            any(),
                            eq("https://gitlab.lrz.de/alice"),
                            any(),
                            isNull(),
                            isNull(),
                            isNull());
            verify(users, never()).upsertUser(any(), any(), eq("bob"), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        void shouldReportTheMembershipGitLabHoldsAndIgnoreAGroupItDoesNotReport() {
            String member = """
                    {"event_name":"user_remove_from_group","group_id":%d,"user_id":9}""";
            GitLabGroupMemberResponse maintainer = new GitLabGroupMemberResponse(
                    new GitLabGroupMemberResponse.GitLabMemberUser("gid://gitlab/User/9", "alice", null, null, null),
                    new GitLabGroupMemberResponse.GitLabAccessLevel("MAINTAINER", 40));
            when(teamSync.fetchMembership(WORKSPACE_ID, GROUP, GROUP_ID, 9L, true))
                    .thenReturn(Optional.of(List.of(maintainer)));
            AtomicReference<GitLabRouteAdmission.@Nullable ReportedMembership> seen = new AtomicReference<>();

            // A removal GitLab contradicts is handled as the access GitLab reports now.
            assertThat(admission.admit(
                            WORKSPACE_ID,
                            message(member.formatted(GROUP_ID), h -> {}),
                            () -> seen.set(
                                    GitLabRouteAdmission.reportedMembership().orElseThrow())))
                    .isTrue();
            assertThat(seen.get())
                    .isEqualTo(new GitLabRouteAdmission.ReportedMembership(GROUP_ID, List.of(maintainer)));

            // A subgroup GitLab does not report inside the connected group changes nothing.
            assertProjectNotAdmitted(member.formatted(77L));
            when(teamSync.fetchGroup(WORKSPACE_ID, 77L))
                    .thenReturn(Optional.of(new GitLabDescendantGroupResponse(
                            "gid://gitlab/Group/77", "other-group/team", "team", null, null, null, null)));
            assertProjectNotAdmitted(member.formatted(77L));
            verify(teamSync, never())
                    .fetchMembership(any(), eq("other-group/team"), anyLong(), anyLong(), anyBoolean());
        }
    }

    @Nested
    class RepositoryPolicy {

        @Test
        void shouldHandleWorkOnlyOnARepositoryOfTheInstanceThisWorkspaceMonitorsInsideTheGroup() {
            when(repositoryMonitors.isAllowed(workspace, GROUP + "/sub/unmonitored"))
                    .thenReturn(true);

            assertThat(admission.admitRepository(route, repository(PROJECT_A, "hephaestustest/introcourse-other/p")))
                    .isFalse();
            Repository elsewhere = repository(PROJECT_A, GROUP + "/sub/unmonitored");
            IdentityProvider github = new IdentityProvider();
            github.setId(PROVIDER_ID + 1);
            elsewhere.setProvider(github);
            assertThat(admission.admitRepository(route, elsewhere)).isFalse();
            // Monitoring starts only where GitLab has just reported the project, never at the handler.
            assertThat(admission.admitRepository(route, repository(PROJECT_A, GROUP + "/sub/unmonitored")))
                    .isFalse();
            verify(repositoryMonitors, never()).monitorAllowed(any(), any());
        }

        @Test
        void shouldKeepHandlingAMonitoredRepositoryTheFilterStillAllows() {
            when(monitors.existsByWorkspaceIdAndNameWithOwner(WORKSPACE_ID, GROUP + "/selected"))
                    .thenReturn(true);
            when(scopeFilter.isRepositoryAllowed(workspace, GROUP + "/selected"))
                    .thenReturn(true);

            assertThat(admission.admitRepository(route, repository(PROJECT_A, GROUP + "/selected")))
                    .isTrue();
            verify(repositoryMonitors, never()).monitorAllowed(any(), any());
        }

        @Test
        void shouldWriteNothingOnceTheConnectionLeftActive() {
            when(connection.getState()).thenReturn(IntegrationState.SUSPENDED);

            assertThat(admission.admitRepository(route, repository(PROJECT_A, GROUP + "/a")))
                    .isFalse();
            verify(connectionRepository).acquireLifecycleLock(CONNECTION_ID, WORKSPACE_ID);
            verify(repositoryMonitors, never()).monitorAllowed(any(), any());
        }
    }

    private static final String NEUTRAL_EVENT = """
            {"object_kind":"pipeline"}""";

    private static String workEvent(long projectId, String path) {
        return """
                {"object_kind":"issue","project":{"id":%d,"path_with_namespace":"%s"}}""".formatted(projectId, path);
    }

    private static String projectEvent(String eventName, long projectId, String path) {
        return """
                {"event_name":"%s","project_id":%d,"path_with_namespace":"%s"}""".formatted(eventName, projectId, path);
    }

    private static GitLabProjectResponse reported(long nativeId, String fullPath) {
        return new GitLabProjectResponse(
                "gid://gitlab/Project/" + nativeId,
                fullPath,
                fullPath.substring(fullPath.lastIndexOf('/') + 1),
                ORIGIN + "/" + fullPath,
                null,
                "private",
                false,
                null,
                null,
                null,
                null);
    }

    private void assertProjectAdmitted(String body) {
        Runnable handling = mock(Runnable.class);
        assertThat(admission.admit(WORKSPACE_ID, message(body, h -> {}), handling))
                .isTrue();
        verify(handling).run();
    }

    private void assertProjectNotAdmitted(String body) {
        assertNotAdmitted(body, WORKSPACE_ID, h -> {});
    }

    private void assertNotAdmitted(long scopeId, Consumer<Headers> change) {
        assertNotAdmitted(NEUTRAL_EVENT, scopeId, change);
    }

    private void assertNotAdmitted(String body, long scopeId, Consumer<Headers> change) {
        Runnable handling = mock(Runnable.class);
        assertThat(admission.admit(scopeId, message(body, change), handling)).isFalse();
        verify(handling, never()).run();
    }

    private static Message message(String body, Consumer<Headers> change) {
        Headers headers = new Headers();
        new GitLabRouteCredential.Route(CONNECTION_ID, WORKSPACE_ID, ORIGIN, GROUP_ID, GROUP)
                .headers()
                .forEach(headers::put);
        change.accept(headers);
        Message message = mock(Message.class);
        when(message.getSubject()).thenReturn("gitlab.?connection." + CONNECTION_ID + ".issue");
        when(message.getHeaders()).thenReturn(headers);
        when(message.getData()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    private static ConnectionConfig.GitLabConfig config(String serverUrl, long groupId) {
        return new ConnectionConfig.GitLabConfig(
                serverUrl, groupId, 99L, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of());
    }

    private Repository repository(long nativeId, String nameWithOwner) {
        Repository repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setNameWithOwner(nameWithOwner);
        repository.setProvider(provider);
        return repository;
    }

    private static RepositoryToMonitor monitor(long id) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setId(id);
        return monitor;
    }
}

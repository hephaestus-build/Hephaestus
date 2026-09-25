package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.LlmConnectionPlatform;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class WorkspaceLlmConnectionServiceTest extends BaseUnitTest {

    @Mock
    private WorkspaceLlmConnectionRepository connectionRepository;

    @Mock
    private WorkspaceLlmModelRepository modelRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private EgressPolicy egressPolicy;

    @Mock
    private InstanceLlmSettingsService instanceLlmSettingsService;

    @Mock
    private LlmConnectionProbeService probeService;

    @Mock
    private ConfigAuditPort configAudit;

    @InjectMocks
    private WorkspaceLlmConnectionService connectionService;

    private WorkspaceContext workspaceContext;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(1L);
        workspace.setWorkspaceSlug("test-workspace");
        workspaceContext = new WorkspaceContext(
                1L, "test-workspace", "Test Workspace", AccountType.ORG, null, false, false, Set.of());
    }

    private void byoEnabled(boolean enabled) {
        InstanceLlmSettings settings = new InstanceLlmSettings();
        settings.setAllowWorkspaceConnections(enabled);
        lenient().when(instanceLlmSettingsService.get()).thenReturn(settings);
    }

    private CreateWorkspaceLlmConnectionRequestDTO createRequest() {
        return new CreateWorkspaceLlmConnectionRequestDTO(
                "openai-prod",
                "OpenAI",
                "https://api.openai.com",
                "openai-completions",
                LlmAuthMode.BEARER,
                "sk-abc",
                null,
                null);
    }

    @Nested
    class ByoGate {

        @Test
        void createIsRejectedWhenWorkspaceConnectionsAreDisabled() {
            byoEnabled(false);

            assertThatThrownBy(() -> connectionService.create(workspaceContext, createRequest()))
                    .isInstanceOf(AccessForbiddenException.class);
            verify(connectionRepository, never()).save(any());
        }

        @Test
        void listRemainsAvailableWhenWorkspaceConnectionsAreDisabled() {
            // Viewing must survive an instance admin later disabling the feature, or existing connections
            // become inexplicably invisible to the workspace that owns them.
            when(connectionRepository.findByWorkspaceId(1L)).thenReturn(List.of());

            List<WorkspaceLlmConnection> result = connectionService.list(workspaceContext);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class Create {

        @Test
        void rejectsDuplicateSlugInTheSameWorkspace() {
            byoEnabled(true);
            when(connectionRepository.findByWorkspaceIdAndSlug(1L, "openai-prod"))
                    .thenReturn(Optional.of(new WorkspaceLlmConnection()));

            assertThatThrownBy(() -> connectionService.create(workspaceContext, createRequest()))
                    .isInstanceOf(LlmConnectionSlugConflictException.class);
            verify(connectionRepository, never()).save(any());
        }

        @Test
        void doesNotCreateAConnectionWhoseBaseUrlTheEgressPolicyRejects() {
            byoEnabled(true);
            when(connectionRepository.findByWorkspaceIdAndSlug(1L, "openai-prod"))
                    .thenReturn(Optional.empty());
            doThrow(new IllegalArgumentException("Base URL must be a public https:// address"))
                    .when(egressPolicy)
                    .validate("https://api.openai.com");

            assertThatThrownBy(() -> connectionService.create(workspaceContext, createRequest()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("public https");

            verify(connectionRepository, never()).save(any());
            verifyNoInteractions(configAudit);
        }

        @Test
        void createdConnectionIsScopedToTheCallingWorkspace() {
            byoEnabled(true);
            when(connectionRepository.findByWorkspaceIdAndSlug(1L, "openai-prod"))
                    .thenReturn(Optional.empty());
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkspaceLlmConnection result = connectionService.create(workspaceContext, createRequest());

            assertThat(result.getWorkspace()).isEqualTo(workspace);
            assertThat(result.getSlug()).isEqualTo("openai-prod");
        }

        @Test
        void createdConnectionIsRecordedOnTheWorkspaceConfigAuditTrailWithoutTheApiKey() {
            byoEnabled(true);
            when(connectionRepository.findByWorkspaceIdAndSlug(1L, "openai-prod"))
                    .thenReturn(Optional.empty());
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            connectionService.create(workspaceContext, createRequest());

            ArgumentCaptor<ConfigAuditEntry> entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
            verify(configAudit).record(entry.capture());
            assertThat(entry.getValue().entityType()).isEqualTo(ConfigAuditEntityType.WORKSPACE_LLM_CONNECTION);
            assertThat(entry.getValue().workspaceId()).isEqualTo(1L);
            assertThat(entry.getValue().action()).isEqualTo(ConfigAuditAction.CREATED);
            assertThat(entry.getValue().after())
                    .asString()
                    .contains("openai-prod")
                    .doesNotContain("sk-abc");
        }
    }

    @Nested
    class Update {

        @Test
        void declaredConnectionPlatformCanBeChangedAndCleared() {
            WorkspaceLlmConnection connection = new WorkspaceLlmConnection();
            connection.setId(5L);
            when(connectionRepository.findByIdAndWorkspaceIdForUpdate(5L, 1L)).thenReturn(Optional.of(connection));
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            connectionService.update(
                    workspaceContext,
                    5L,
                    new UpdateWorkspaceLlmConnectionRequestDTO(
                            null, null, null, null, LlmConnectionPlatform.AZURE, null));
            assertThat(connection.getConnectionPlatform()).isEqualTo(LlmConnectionPlatform.AZURE);
            assertThat(WorkspaceLlmConnectionDTO.from(connection).connectionPlatform())
                    .isEqualTo(LlmConnectionPlatform.AZURE);

            connectionService.update(
                    workspaceContext,
                    5L,
                    new UpdateWorkspaceLlmConnectionRequestDTO(
                            null, null, null, null, LlmConnectionPlatform.OPENAI, true));
            assertThat(connection.getConnectionPlatform()).isNull();
        }

        @Test
        @DisplayName("a PATCH serializes on the connection row, never a plain tenancy lookup")
        void patchTakesTheLockingRead() {
            // A PATCH writes back every column. Without the lock, a concurrent PATCH that only flips
            // `enabled` reverts this one's key clearing — leaving a credential live that the admin was
            // told was deleted.
            WorkspaceLlmConnection connection = new WorkspaceLlmConnection();
            connection.setId(5L);
            connection.setSlug("openai-prod");
            connection.setApiKey("sk-abc");
            connection.setEnabled(true);
            when(connectionRepository.findByIdAndWorkspaceIdForUpdate(5L, 1L)).thenReturn(Optional.of(connection));
            when(connectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkspaceLlmConnection result = connectionService.update(
                    workspaceContext,
                    5L,
                    new UpdateWorkspaceLlmConnectionRequestDTO(null, null, true, null, null, null));

            assertThat(result.getApiKey()).isNull();
            verify(connectionRepository).findByIdAndWorkspaceIdForUpdate(5L, 1L);
            verify(connectionRepository, never()).findByIdAndWorkspaceId(anyLong(), anyLong());
        }

        @Test
        void throwsNotFoundWhenTheConnectionBelongsToAnotherWorkspace() {
            when(connectionRepository.findByIdAndWorkspaceIdForUpdate(5L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> connectionService.update(
                            workspaceContext,
                            5L,
                            new UpdateWorkspaceLlmConnectionRequestDTO("Renamed", null, null, null, null, null)))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    class Delete {

        @Test
        void rejectsDeleteWhileAWorkspaceModelStillReferencesTheConnection() {
            byoEnabled(true);
            WorkspaceLlmConnection connection = new WorkspaceLlmConnection();
            connection.setId(5L);
            when(connectionRepository.findByIdAndWorkspaceId(5L, 1L)).thenReturn(Optional.of(connection));
            when(modelRepository.existsByConnectionIdAndWorkspaceId(5L, 1L)).thenReturn(true);

            assertThatThrownBy(() -> connectionService.delete(workspaceContext, 5L))
                    .isInstanceOf(LlmConnectionInUseException.class);
            verify(connectionRepository, never()).delete(any());
        }

        @Test
        void deletesAnUnreferencedConnection() {
            byoEnabled(true);
            WorkspaceLlmConnection connection = new WorkspaceLlmConnection();
            connection.setId(5L);
            connection.setSlug("openai-prod");
            when(connectionRepository.findByIdAndWorkspaceId(5L, 1L)).thenReturn(Optional.of(connection));
            when(modelRepository.existsByConnectionIdAndWorkspaceId(5L, 1L)).thenReturn(false);

            connectionService.delete(workspaceContext, 5L);

            verify(connectionRepository).delete(connection);
            ArgumentCaptor<ConfigAuditEntry> entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
            verify(configAudit).record(entry.capture());
            assertThat(entry.getValue().entityType()).isEqualTo(ConfigAuditEntityType.WORKSPACE_LLM_CONNECTION);
            assertThat(entry.getValue().action()).isEqualTo(ConfigAuditAction.DELETED);
        }
    }

    @Nested
    class Get {

        @Test
        void throwsNotFoundForAConnectionOwnedByAnotherWorkspace() {
            when(connectionRepository.findByIdAndWorkspaceId(5L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> connectionService.get(workspaceContext, 5L))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    class Probe {

        @Test
        void reachableProbeReportsOnlyTheModelCountNeverTheRawList() {
            byoEnabled(true);
            // A projection, not the entity: the probe runs outside any transaction, so it must not
            // depend on a managed entity still being attached.
            when(connectionRepository.findProbeTargetByIdAndWorkspaceId(5L, 1L))
                    .thenReturn(
                            Optional.of(new LlmProbeTarget("https://api.openai.com", LlmAuthMode.BEARER, "sk-abc")));
            when(probeService.probeCredential("https://api.openai.com", LlmAuthMode.BEARER, "sk-abc"))
                    .thenReturn(LlmProbeResultDTO.reachable(List.of("gpt-5", "gpt-5-mini"), 200));

            WorkspaceLlmProbeResultDTO result = connectionService.probe(workspaceContext, 5L);

            assertThat(result.reachable()).isTrue();
            assertThat(result.modelCount()).isEqualTo(2);
        }

        @Test
        void unreachableProbeCarriesTheAdvisoryMessage() {
            byoEnabled(true);
            when(connectionRepository.findProbeTargetByIdAndWorkspaceId(5L, 1L))
                    .thenReturn(Optional.of(new LlmProbeTarget("https://api.openai.com", LlmAuthMode.BEARER, null)));
            when(probeService.probeCredential(any(), any(), any()))
                    .thenReturn(LlmProbeResultDTO.unreachable(503, "Provider returned HTTP 503"));

            WorkspaceLlmProbeResultDTO result = connectionService.probe(workspaceContext, 5L);

            assertThat(result.reachable()).isFalse();
            assertThat(result.modelCount()).isEqualTo(0);
            assertThat(result.message()).isEqualTo("Provider returned HTTP 503");
        }

        /**
         * A stored connection is re-checked on every probe: the instance allowlist can tighten after it
         * was created, and DNS for a host that was public then can point inside the network now.
         */
        @Test
        void doesNotProbeAStoredConnectionWhoseBaseUrlTheEgressPolicyNowRejects() {
            byoEnabled(true);
            when(connectionRepository.findProbeTargetByIdAndWorkspaceId(5L, 1L))
                    .thenReturn(
                            Optional.of(new LlmProbeTarget("https://api.openai.com", LlmAuthMode.BEARER, "sk-abc")));
            doThrow(new IllegalArgumentException("Base URL must be a public https:// address"))
                    .when(egressPolicy)
                    .validate("https://api.openai.com");

            assertThatThrownBy(() -> connectionService.probe(workspaceContext, 5L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("public https");

            verifyNoInteractions(probeService);
        }
    }
}

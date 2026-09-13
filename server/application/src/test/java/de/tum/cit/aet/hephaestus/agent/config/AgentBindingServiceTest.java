package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataRetention;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class AgentBindingServiceTest extends BaseUnitTest {

    @Mock
    private WorkspaceAgentBindingRepository bindingRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private LlmModelRepository llmModelRepository;

    @Mock
    private WorkspaceLlmModelRepository workspaceLlmModelRepository;

    @Mock
    private LlmModelResolver llmModelResolver;

    @Mock
    private ConfigAuditPort configAudit;

    @InjectMocks
    private AgentBindingService service;

    private WorkspaceContext context() {
        WorkspaceContext ctx = mock(WorkspaceContext.class);
        when(ctx.id()).thenReturn(1L);
        return ctx;
    }

    private Workspace workspace() {
        Workspace w = new Workspace();
        w.setId(1L);
        return w;
    }

    @Test
    void upsertBindsAnAvailableInstanceModel() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        LlmModel model = new LlmModel();
        model.setId(99L);
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceAgentBinding.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new AgentBindingRequestDTO(99L, null, 300, 2, true, true);
        WorkspaceAgentBinding saved =
                service.upsertBinding(context(), AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED, request);

        assertThat(saved.getWorkspace()).isSameAs(w);
        assertThat(saved.getPurpose()).isEqualTo(AgentPurpose.PRACTICE_REVIEW);
        var instanceModel = saved.getInstanceModel();
        assertThat(instanceModel).isNotNull();
        assertThat(instanceModel.getId()).isEqualTo(99L);
        assertThat(saved.getWorkspaceModel()).isNull();
        assertThat(saved.getTimeoutSeconds()).isEqualTo(300);
        assertThat(saved.getMaxConcurrentJobs()).isEqualTo(2);
        assertThat(saved.isAllowInternet()).isTrue();
        assertThat(saved.isEnabled()).isTrue();
        verify(llmModelResolver).isAvailable(any(WorkspaceAgentBinding.class));

        // The two model columns are interchangeable in shape but not in meaning — an entry that filed
        // the id under workspaceModelId would claim the workspace pays for a model the instance owns.
        ArgumentCaptor<ConfigAuditEntry> entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit).record(entry.capture());
        assertThat(entry.getValue().entityType()).isEqualTo(ConfigAuditEntityType.AGENT_BINDING);
        assertThat(entry.getValue().entityId()).isEqualTo(AgentPurpose.PRACTICE_REVIEW.name());
        assertThat(entry.getValue().workspaceId()).isEqualTo(1L);
        assertThat(entry.getValue().action()).isEqualTo(ConfigAuditAction.UPDATED);
        assertThat(entry.getValue().before()).hasFieldOrPropertyWithValue("instanceModelId", null);
        assertThat(entry.getValue().after())
                .hasFieldOrPropertyWithValue("instanceModelId", 99L)
                .hasFieldOrPropertyWithValue("workspaceModelId", null)
                .hasFieldOrPropertyWithValue("enabled", true);
    }

    @Test
    void upsertRejectsAModelThatIsNotAvailableToTheWorkspace() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        LlmModel model = new LlmModel();
        model.setId(99L);
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceAgentBinding.class))).thenReturn(false);

        var request = new AgentBindingRequestDTO(99L, null, null, null, null, true);
        assertThatThrownBy(() -> service.upsertBinding(
                        context(), AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED, request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void upsertRejectsAModelWhoseDeclaredTierDiffersFromTheSlot() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.PROVIDER_NOT_KEPT))
                .thenReturn(Optional.empty());
        LlmModel model = new LlmModel();
        model.setId(99L);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, LlmDataRetention.NONE, null));
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));

        var request = new AgentBindingRequestDTO(99L, null, null, null, null, true);
        assertThatThrownBy(() -> service.upsertBinding(
                        context(), AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.PROVIDER_NOT_KEPT, request))
                .isInstanceOf(AgentBindingSlotMismatchException.class)
                .hasMessage("This model is declared as a different tier; assign it to that row.")
                .extracting("declaredTier")
                .isEqualTo(DataHandlingTier.IN_HOUSE);
        verify(llmModelResolver, never()).isAvailable(any(WorkspaceAgentBinding.class));
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void upsertRejectsAnUndeclaredModelInATierSlotWithoutNamingATier() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.MENTOR, DataHandlingTier.IN_HOUSE))
                .thenReturn(Optional.empty());
        LlmModel model = new LlmModel();
        model.setId(99L);
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));

        var request = new AgentBindingRequestDTO(99L, null, null, null, null, true);
        assertThatThrownBy(
                        () -> service.upsertBinding(context(), AgentPurpose.MENTOR, DataHandlingTier.IN_HOUSE, request))
                .isInstanceOf(AgentBindingSlotMismatchException.class)
                .hasMessage("This model's data handling isn't declared yet. "
                        + "Declare it first, or assign it to Members who haven't chosen.")
                .extracting("declaredTier")
                .isNull();
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void upsertFillsATierSlotWithAModelDeclaredAtThatTier() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.PROVIDER_NOT_KEPT))
                .thenReturn(Optional.empty());
        LlmModel model = new LlmModel();
        model.setId(99L);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, LlmDataRetention.NONE, null));
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceAgentBinding.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new AgentBindingRequestDTO(99L, null, null, null, null, true);
        WorkspaceAgentBinding saved = service.upsertBinding(
                context(), AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.PROVIDER_NOT_KEPT, request);

        assertThat(saved.getDataHandlingTier()).isEqualTo(DataHandlingTier.PROVIDER_NOT_KEPT);
        ArgumentCaptor<ConfigAuditEntry> entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit).record(entry.capture());
        assertThat(entry.getValue().after())
                .hasFieldOrPropertyWithValue("dataHandlingTier", DataHandlingTier.PROVIDER_NOT_KEPT);
    }

    @Test
    void upsertAcceptsAnyModelInTheUndeclaredSlot() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        LlmModel model = new LlmModel();
        model.setId(99L);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, LlmDataRetention.FOR_SAFETY_CHECKS, null));
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceAgentBinding.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new AgentBindingRequestDTO(99L, null, null, null, null, true);
        WorkspaceAgentBinding saved =
                service.upsertBinding(context(), AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED, request);

        assertThat(saved.getDataHandlingTier()).isEqualTo(DataHandlingTier.UNDECLARED);
        var bound = saved.getInstanceModel();
        assertThat(bound).isNotNull();
        assertThat(bound.getId()).isEqualTo(99L);
    }

    @Test
    void upsertRejectsWhenNotExactlyOneModelIsProvided() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());

        var bothNull = new AgentBindingRequestDTO(null, null, null, null, null, true);
        assertThatThrownBy(() ->
                        service.upsertBinding(context(), AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED, bothNull))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteRemovesTheBindingAndFilesADeletedAuditRowNamingTheLostModel() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        WorkspaceAgentBinding existing = new WorkspaceAgentBinding();
        LlmModel bound = new LlmModel();
        bound.setId(42L);
        existing.setInstanceModel(bound);
        existing.setEnabled(true);
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.of(existing));

        service.deleteBinding(context(), AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED);

        verify(bindingRepository).delete(existing);

        ArgumentCaptor<ConfigAuditEntry> entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit).record(entry.capture());
        assertThat(entry.getValue().entityType()).isEqualTo(ConfigAuditEntityType.AGENT_BINDING);
        assertThat(entry.getValue().entityId()).isEqualTo(AgentPurpose.MENTOR.name());
        assertThat(entry.getValue().workspaceId()).isEqualTo(1L);
        assertThat(entry.getValue().action())
                .as("removing a binding is a deletion, not an update to an all-null snapshot")
                .isEqualTo(ConfigAuditAction.DELETED);
        assertThat(entry.getValue().after()).isNull();
        assertThat(entry.getValue().before())
                .as("the pre-delete state must name the model the workspace lost")
                .hasFieldOrPropertyWithValue("instanceModelId", 42L)
                .hasFieldOrPropertyWithValue("workspaceModelId", null)
                .hasFieldOrPropertyWithValue("enabled", true);
    }

    /**
     * The positive leg. Without it every {@code isReady} assertion in this class survives replacing the
     * method body with {@code return false}, and the UI would report every workspace unable to run.
     */
    @Test
    void isReadyIsTrueWhenAnEnabledBindingStillResolves() {
        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setEnabled(true);
        when(llmModelResolver.isAvailable(binding)).thenReturn(true);

        assertThat(service.isReady(binding)).isTrue();
    }

    @Test
    void isReadyIsFalseForADisabledBindingWithoutTouchingTheResolver() {
        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setEnabled(false);

        assertThat(service.isReady(binding)).isFalse();
        verify(llmModelResolver, never()).isAvailable(any(WorkspaceAgentBinding.class));
    }

    @Test
    void isReadyIsFalseWhenTheBoundModelIsNoLongerAvailable() {
        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setEnabled(true);
        when(llmModelResolver.isAvailable(binding)).thenReturn(false);

        assertThat(service.isReady(binding)).isFalse();
    }
}

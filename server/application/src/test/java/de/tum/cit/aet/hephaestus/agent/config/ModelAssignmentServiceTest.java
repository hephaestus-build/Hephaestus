package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspacePortFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

class ModelAssignmentServiceTest extends BaseUnitTest {

    @Mock
    private WorkspaceModelAssignmentRepository bindingRepository;

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

    @Mock
    private EntityManager entityManager;

    private ModelAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new ModelAssignmentService(
                bindingRepository,
                WorkspacePortFixtures.queries(workspaceRepository),
                llmModelRepository,
                workspaceLlmModelRepository,
                llmModelResolver,
                configAudit);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        lenient()
                .when(entityManager.getReference(eq(Workspace.class), anyLong()))
                .thenAnswer(invocation -> {
                    var workspace = new Workspace();
                    workspace.setId(invocation.getArgument(1));
                    return workspace;
                });
    }

    private WorkspaceContext context() {
        WorkspaceContext ctx = mock(WorkspaceContext.class);
        when(ctx.id()).thenReturn(1L);
        return ctx;
    }

    private static LlmModel model(long id, LlmApiProtocol apiProtocol) {
        LlmConnection connection = new LlmConnection();
        connection.setApiProtocol(apiProtocol);
        LlmModel model = new LlmModel();
        model.setId(id);
        model.setConnection(connection);
        return model;
    }

    private Workspace workspace() {
        Workspace w = new Workspace();
        w.setId(1L);
        return w;
    }

    @Test
    void shouldStartANewHephaestusBindingAtTheHephaestusTurnCeilingAndRefuseALongerTimeout() {
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace()));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, ModelPurpose.CONVERSATION, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model(99L, LlmApiProtocol.OPENAI_COMPLETIONS)));
        when(llmModelResolver.isAvailable(any(WorkspaceModelAssignment.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceModelAssignment saved = service.upsertBinding(
                context(),
                ModelPurpose.CONVERSATION,
                DataHandlingTier.UNDECLARED,
                new ModelAssignmentRequestDTO(99L, null, null, true));
        assertThat(saved.getTimeoutSeconds()).isEqualTo(ModelAssignmentLimits.MAX_CONVERSATION_TURN_SECONDS);

        assertThatThrownBy(() -> service.upsertBinding(
                        context(),
                        ModelPurpose.CONVERSATION,
                        DataHandlingTier.UNDECLARED,
                        new ModelAssignmentRequestDTO(
                                99L, null, ModelAssignmentLimits.MAX_CONVERSATION_TURN_SECONDS + 1, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hephaestus stops an answer");
    }

    @Test
    void shouldShowAnOlderHephaestusBindingWithTheTimeoutThatApplies() {
        WorkspaceModelAssignment binding = new WorkspaceModelAssignment();
        binding.setPurpose(ModelPurpose.CONVERSATION);
        binding.setDataHandlingTier(DataHandlingTier.UNDECLARED);
        binding.setTimeoutSeconds(ModelAssignmentLimits.MAX_TIMEOUT_SECONDS);
        assertThat(ModelAssignmentDTO.from(binding, true, List.of()).timeoutSeconds())
                .isEqualTo(ModelAssignmentLimits.MAX_CONVERSATION_TURN_SECONDS);

        binding.setPurpose(ModelPurpose.PRACTICE_REVIEW);
        assertThat(ModelAssignmentDTO.from(binding, true, List.of()).timeoutSeconds())
                .isEqualTo(ModelAssignmentLimits.MAX_TIMEOUT_SECONDS);
    }

    @Test
    void upsertBindsAnAvailableInstanceModel() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(entityManager.getReference(Workspace.class, 1L)).thenReturn(w);
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, ModelPurpose.CONVERSATION, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceModelAssignment.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new ModelAssignmentRequestDTO(99L, null, 300, true);
        WorkspaceModelAssignment saved =
                service.upsertBinding(context(), ModelPurpose.CONVERSATION, DataHandlingTier.UNDECLARED, request);

        assertThat(saved.getWorkspace()).isSameAs(w);
        assertThat(saved.getPurpose()).isEqualTo(ModelPurpose.CONVERSATION);
        var instanceModel = saved.getInstanceModel();
        assertThat(instanceModel).isNotNull();
        assertThat(instanceModel.getId()).isEqualTo(99L);
        assertThat(saved.getWorkspaceModel()).isNull();
        assertThat(saved.getTimeoutSeconds()).isEqualTo(300);
        assertThat(saved.isEnabled()).isTrue();
        verify(llmModelResolver).isAvailable(any(WorkspaceModelAssignment.class));

        // The two model columns are interchangeable in shape but not in meaning — an entry that filed
        // the id under workspaceModelId would claim the workspace pays for a model the instance owns.
        ArgumentCaptor<ConfigAuditEntry> entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit).record(entry.capture());
        assertThat(entry.getValue().entityType()).isEqualTo(ConfigAuditEntityType.MODEL_ASSIGNMENT);
        assertThat(entry.getValue().entityId()).isEqualTo(ModelPurpose.CONVERSATION.name());
        assertThat(entry.getValue().workspaceId()).isEqualTo(1L);
        assertThat(entry.getValue().action()).isEqualTo(ConfigAuditAction.UPDATED);
        assertThat(entry.getValue().before()).hasFieldOrPropertyWithValue("instanceModelId", null);
        assertThat(entry.getValue().after())
                .hasFieldOrPropertyWithValue("instanceModelId", 99L)
                .hasFieldOrPropertyWithValue("workspaceModelId", null)
                .hasFieldOrPropertyWithValue("enabled", true);
    }

    private void stubNewUndeclaredBinding(ModelPurpose purpose, LlmModel model) {
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace()));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(1L, purpose, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        when(llmModelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceModelAssignment.class))).thenReturn(true);
    }

    @ParameterizedTest(name = "{0} accepts a {1} model")
    @CsvSource({
        "PRACTICE_REVIEW, OPENAI_RESPONSES",
        "PRACTICE_REVIEW, OPENAI_COMPLETIONS",
        "PRACTICE_REVIEW, ANTHROPIC_MESSAGES"
    })
    void upsertBindsAModelWhenItsKindFitsThePurpose(ModelPurpose purpose, LlmApiProtocol apiProtocol) {
        stubNewUndeclaredBinding(purpose, model(99L, apiProtocol));
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new ModelAssignmentRequestDTO(99L, null, null, true);
        WorkspaceModelAssignment saved =
                service.upsertBinding(context(), purpose, DataHandlingTier.UNDECLARED, request);

        assertThat(saved.getPurpose()).isEqualTo(purpose);
        var bound = saved.getInstanceModel();
        assertThat(bound).isNotNull();
        assertThat(bound.getId()).isEqualTo(99L);
    }

    @Test
    void upsertRejectsAModelThatIsNotAvailableToTheWorkspace() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, ModelPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceModelAssignment.class))).thenReturn(false);

        var request = new ModelAssignmentRequestDTO(99L, null, null, true);
        assertThatThrownBy(() -> service.upsertBinding(
                        context(), ModelPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED, request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void upsertRejectsAModelWhoseDeclaredTierDiffersFromTheSlot() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, ModelPurpose.PRACTICE_REVIEW, DataHandlingTier.CLOUD))
                .thenReturn(Optional.empty());
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, null));
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));

        var request = new ModelAssignmentRequestDTO(99L, null, null, true);
        assertThatThrownBy(() ->
                        service.upsertBinding(context(), ModelPurpose.PRACTICE_REVIEW, DataHandlingTier.CLOUD, request))
                .isInstanceOf(ModelAssignmentSlotMismatchException.class)
                .hasMessage("This model is declared as a different tier. Assign it to that row.")
                .extracting("declaredTier")
                .isEqualTo(DataHandlingTier.IN_HOUSE);
        verify(llmModelResolver, never()).isAvailable(any(WorkspaceModelAssignment.class));
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void upsertRejectsAnUndeclaredModelInATierSlotWithoutNamingATier() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, ModelPurpose.CONVERSATION, DataHandlingTier.IN_HOUSE))
                .thenReturn(Optional.empty());
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));

        var request = new ModelAssignmentRequestDTO(99L, null, null, true);
        assertThatThrownBy(() ->
                        service.upsertBinding(context(), ModelPurpose.CONVERSATION, DataHandlingTier.IN_HOUSE, request))
                .isInstanceOf(ModelAssignmentSlotMismatchException.class)
                .hasMessage("This model’s data handling is not declared yet. "
                        + "Declare it first, or assign it to Members who have not chosen.")
                .extracting("declaredTier")
                .isNull();
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void upsertFillsATierSlotWithAModelDeclaredAtThatTier() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, ModelPurpose.PRACTICE_REVIEW, DataHandlingTier.CLOUD))
                .thenReturn(Optional.empty());
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, null));
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceModelAssignment.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new ModelAssignmentRequestDTO(99L, null, null, true);
        WorkspaceModelAssignment saved =
                service.upsertBinding(context(), ModelPurpose.PRACTICE_REVIEW, DataHandlingTier.CLOUD, request);

        assertThat(saved.getDataHandlingTier()).isEqualTo(DataHandlingTier.CLOUD);
        ArgumentCaptor<ConfigAuditEntry> entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit).record(entry.capture());
        assertThat(entry.getValue().after()).hasFieldOrPropertyWithValue("dataHandlingTier", DataHandlingTier.CLOUD);
    }

    @Test
    void upsertAcceptsAnyModelInTheUndeclaredSlot() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, ModelPurpose.CONVERSATION, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, null));
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceModelAssignment.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new ModelAssignmentRequestDTO(99L, null, null, true);
        WorkspaceModelAssignment saved =
                service.upsertBinding(context(), ModelPurpose.CONVERSATION, DataHandlingTier.UNDECLARED, request);

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
                        1L, ModelPurpose.CONVERSATION, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());

        var bothNull = new ModelAssignmentRequestDTO(null, null, null, true);
        assertThatThrownBy(() -> service.upsertBinding(
                        context(), ModelPurpose.CONVERSATION, DataHandlingTier.UNDECLARED, bothNull))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteRemovesTheBindingAndFilesADeletedAuditRowNamingTheLostModel() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        WorkspaceModelAssignment existing = new WorkspaceModelAssignment();
        LlmModel bound = new LlmModel();
        bound.setId(42L);
        existing.setInstanceModel(bound);
        existing.setEnabled(true);
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, ModelPurpose.CONVERSATION, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.of(existing));

        service.deleteBinding(context(), ModelPurpose.CONVERSATION, DataHandlingTier.UNDECLARED);

        verify(bindingRepository).delete(existing);

        ArgumentCaptor<ConfigAuditEntry> entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit).record(entry.capture());
        assertThat(entry.getValue().entityType()).isEqualTo(ConfigAuditEntityType.MODEL_ASSIGNMENT);
        assertThat(entry.getValue().entityId()).isEqualTo(ModelPurpose.CONVERSATION.name());
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
        WorkspaceModelAssignment binding = new WorkspaceModelAssignment();
        binding.setEnabled(true);
        when(llmModelResolver.isAvailable(binding)).thenReturn(true);

        assertThat(service.isReady(binding)).isTrue();
    }

    @Test
    void isReadyIsFalseForADisabledBindingWithoutTouchingTheResolver() {
        WorkspaceModelAssignment binding = new WorkspaceModelAssignment();
        binding.setEnabled(false);

        assertThat(service.isReady(binding)).isFalse();
        verify(llmModelResolver, never()).isAvailable(any(WorkspaceModelAssignment.class));
    }

    @Test
    void isReadyIsFalseWhenTheBoundModelIsNoLongerAvailable() {
        WorkspaceModelAssignment binding = new WorkspaceModelAssignment();
        binding.setEnabled(true);
        when(llmModelResolver.isAvailable(binding)).thenReturn(false);

        assertThat(service.isReady(binding)).isFalse();
    }
}

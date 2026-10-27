package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
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

    private AgentBindingService service;

    @BeforeEach
    void setUp() {
        service = new AgentBindingService(
                bindingRepository,
                workspaceRepository,
                llmModelRepository,
                workspaceLlmModelRepository,
                llmModelResolver,
                configAudit);
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
    void shouldStartANewHephBindingAtTheHephTurnCeilingAndRefuseALongerTimeout() {
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace()));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model(99L, LlmApiProtocol.OPENAI_COMPLETIONS)));
        when(llmModelResolver.isAvailable(any(WorkspaceAgentBinding.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceAgentBinding saved = service.upsertBinding(
                context(),
                AgentPurpose.MENTOR,
                DataHandlingTier.UNDECLARED,
                new AgentBindingRequestDTO(99L, null, null, null, true, null, null));
        assertThat(saved.getTimeoutSeconds()).isEqualTo(AgentBindingLimits.MAX_HEPH_TURN_SECONDS);
        assertThat(saved.getReasoningEffort()).isEqualTo(ReasoningEffort.LOW);

        assertThatThrownBy(() -> service.upsertBinding(
                        context(),
                        AgentPurpose.MENTOR,
                        DataHandlingTier.UNDECLARED,
                        new AgentBindingRequestDTO(
                                99L, null, AgentBindingLimits.MAX_HEPH_TURN_SECONDS + 1, null, true, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Heph stops an answer");
    }

    @Test
    void shouldAcceptAHephTimeoutUpToTheTurnCeiling() {
        stubNewUndeclaredBinding(AgentPurpose.MENTOR, model(99L, LlmApiProtocol.OPENAI_COMPLETIONS));
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceAgentBinding saved = service.upsertBinding(
                context(),
                AgentPurpose.MENTOR,
                DataHandlingTier.UNDECLARED,
                new AgentBindingRequestDTO(99L, null, 900, null, true, null, null));

        assertThat(saved.getTimeoutSeconds()).isEqualTo(900);
    }

    @Test
    void shouldStartANewReviewBindingWithoutAReasoningEffortSoTheProviderDefaultApplies() {
        stubNewUndeclaredBinding(AgentPurpose.PRACTICE_REVIEW, model(99L, LlmApiProtocol.OPENAI_COMPLETIONS));
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceAgentBinding saved = service.upsertBinding(
                context(),
                AgentPurpose.PRACTICE_REVIEW,
                DataHandlingTier.UNDECLARED,
                new AgentBindingRequestDTO(99L, null, null, null, true, null, null));

        assertThat(saved.getReasoningEffort()).isNull();
        assertThat(saved.getTimeoutSeconds()).isEqualTo(AgentBindingLimits.MAX_TIMEOUT_SECONDS);
    }

    @Test
    void shouldSetKeepAndClearTheReasoningEffortOfAnExistingBindingAndAuditEachChange() {
        WorkspaceAgentBinding existing = new WorkspaceAgentBinding();
        existing.setWorkspace(workspace());
        existing.setPurpose(AgentPurpose.MENTOR);
        existing.setReasoningEffort(ReasoningEffort.LOW);
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace()));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.of(existing));
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model(99L, LlmApiProtocol.OPENAI_COMPLETIONS)));
        when(llmModelResolver.isAvailable(any(WorkspaceAgentBinding.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsertBinding(
                context(),
                AgentPurpose.MENTOR,
                DataHandlingTier.UNDECLARED,
                new AgentBindingRequestDTO(99L, null, null, null, null, ReasoningEffort.MEDIUM, null));
        assertThat(existing.getReasoningEffort()).isEqualTo(ReasoningEffort.MEDIUM);

        service.upsertBinding(
                context(),
                AgentPurpose.MENTOR,
                DataHandlingTier.UNDECLARED,
                new AgentBindingRequestDTO(99L, null, 600, null, null, null, null));
        assertThat(existing.getReasoningEffort())
                .as("an omitted effort keeps the current one")
                .isEqualTo(ReasoningEffort.MEDIUM);

        service.upsertBinding(
                context(),
                AgentPurpose.MENTOR,
                DataHandlingTier.UNDECLARED,
                new AgentBindingRequestDTO(99L, null, null, null, null, null, true));
        assertThat(existing.getReasoningEffort()).isNull();

        ArgumentCaptor<ConfigAuditEntry> entries = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit, times(3)).record(entries.capture());
        assertThat(entries.getAllValues().getFirst().before())
                .hasFieldOrPropertyWithValue("reasoningEffort", ReasoningEffort.LOW);
        assertThat(entries.getAllValues().getFirst().after())
                .hasFieldOrPropertyWithValue("reasoningEffort", ReasoningEffort.MEDIUM);
        assertThat(entries.getAllValues().getLast().after()).hasFieldOrPropertyWithValue("reasoningEffort", null);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "PRACTICE_EMBEDDING, OPENAI_EMBEDDINGS, LOW, , Embedding and reranking models take no reasoning effort.",
        "PRACTICE_RERANKING, COHERE_RERANK, LOW, , Embedding and reranking models take no reasoning effort.",
        "MENTOR, OPENAI_COMPLETIONS, LOW, true, 'Set a reasoning effort or clear it, not both.'"
    })
    void shouldRefuseAReasoningEffortThatNoCallWouldSendOrThatIsAlsoCleared(
            AgentPurpose purpose,
            LlmApiProtocol apiProtocol,
            ReasoningEffort effort,
            @Nullable Boolean clear,
            String message) {
        stubNewUndeclaredBinding(purpose, model(99L, apiProtocol));

        var request = new AgentBindingRequestDTO(99L, null, null, null, true, effort, clear);
        assertThatThrownBy(() -> service.upsertBinding(context(), purpose, DataHandlingTier.UNDECLARED, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        verify(bindingRepository, never()).save(any());
    }

    @Test
    void upsertBindsAnAvailableInstanceModel() {
        Workspace w = workspace();
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(w));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceAgentBinding.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new AgentBindingRequestDTO(99L, null, 300, 2, true, null, null);
        WorkspaceAgentBinding saved =
                service.upsertBinding(context(), AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED, request);

        assertThat(saved.getWorkspace()).isSameAs(w);
        assertThat(saved.getPurpose()).isEqualTo(AgentPurpose.MENTOR);
        var instanceModel = saved.getInstanceModel();
        assertThat(instanceModel).isNotNull();
        assertThat(instanceModel.getId()).isEqualTo(99L);
        assertThat(saved.getWorkspaceModel()).isNull();
        assertThat(saved.getTimeoutSeconds()).isEqualTo(300);
        assertThat(saved.getMaxConcurrentJobs()).isEqualTo(2);
        assertThat(saved.isEnabled()).isTrue();
        verify(llmModelResolver).isAvailable(any(WorkspaceAgentBinding.class));

        // The two model columns are interchangeable in shape but not in meaning — an entry that filed
        // the id under workspaceModelId would claim the workspace pays for a model the instance owns.
        ArgumentCaptor<ConfigAuditEntry> entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit).record(entry.capture());
        assertThat(entry.getValue().entityType()).isEqualTo(ConfigAuditEntityType.AGENT_BINDING);
        assertThat(entry.getValue().entityId()).isEqualTo(AgentPurpose.MENTOR.name());
        assertThat(entry.getValue().workspaceId()).isEqualTo(1L);
        assertThat(entry.getValue().action()).isEqualTo(ConfigAuditAction.UPDATED);
        assertThat(entry.getValue().before()).hasFieldOrPropertyWithValue("instanceModelId", null);
        assertThat(entry.getValue().after())
                .hasFieldOrPropertyWithValue("instanceModelId", 99L)
                .hasFieldOrPropertyWithValue("workspaceModelId", null)
                .hasFieldOrPropertyWithValue("enabled", true);
    }

    private void stubNewUndeclaredBinding(AgentPurpose purpose, LlmModel model) {
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace()));
        when(bindingRepository.findByWorkspaceIdAndPurposeAndDataHandlingTier(1L, purpose, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        when(llmModelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceAgentBinding.class))).thenReturn(true);
    }

    @ParameterizedTest(name = "{0} refuses a {1} model")
    @CsvSource({
        "PRACTICE_REVIEW, OPENAI_EMBEDDINGS, This is an embedding model. Choose a chat model here.",
        "MENTOR, COHERE_RERANK, This is a reranking model. Choose a chat model here.",
        "PRACTICE_EMBEDDING, OPENAI_COMPLETIONS, This is a chat model. Choose an embedding model here.",
        "PRACTICE_RERANKING, OPENAI_EMBEDDINGS, This is an embedding model. Choose a reranking model here.",
        "PRACTICE_DECISION, OPENAI_RESPONSES, This is a chat model. Choose a decision model or a chat completions"
                + " model here."
    })
    void upsertRejectsAModelWhenItsKindDoesNotFitThePurpose(
            AgentPurpose purpose, LlmApiProtocol apiProtocol, String message) {
        stubNewUndeclaredBinding(purpose, model(99L, apiProtocol));

        var request = new AgentBindingRequestDTO(99L, null, null, null, true, null, null);
        assertThatThrownBy(() -> service.upsertBinding(context(), purpose, DataHandlingTier.UNDECLARED, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
        verify(bindingRepository, never()).save(any());
        verify(configAudit, never()).record(any());
    }

    @ParameterizedTest(name = "{0} accepts a {1} model")
    @CsvSource({
        "PRACTICE_DECISION, OPENAI_DECISIONS",
        "PRACTICE_DECISION, OPENAI_COMPLETIONS",
        "PRACTICE_EMBEDDING, OPENAI_EMBEDDINGS",
        "PRACTICE_RERANKING, COHERE_RERANK",
        "PRACTICE_REVIEW, OPENAI_RESPONSES"
    })
    void upsertBindsAModelWhenItsKindFitsThePurpose(AgentPurpose purpose, LlmApiProtocol apiProtocol) {
        stubNewUndeclaredBinding(purpose, model(99L, apiProtocol));
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new AgentBindingRequestDTO(99L, null, null, null, true, null, null);
        WorkspaceAgentBinding saved = service.upsertBinding(context(), purpose, DataHandlingTier.UNDECLARED, request);

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
                        1L, AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceAgentBinding.class))).thenReturn(false);

        var request = new AgentBindingRequestDTO(99L, null, null, null, true, null, null);
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
                        1L, AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.CLOUD))
                .thenReturn(Optional.empty());
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, null));
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));

        var request = new AgentBindingRequestDTO(99L, null, null, null, true, null, null);
        assertThatThrownBy(() ->
                        service.upsertBinding(context(), AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.CLOUD, request))
                .isInstanceOf(AgentBindingSlotMismatchException.class)
                .hasMessage("This model is declared as a different tier. Assign it to that row.")
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
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));

        var request = new AgentBindingRequestDTO(99L, null, null, null, true, null, null);
        assertThatThrownBy(
                        () -> service.upsertBinding(context(), AgentPurpose.MENTOR, DataHandlingTier.IN_HOUSE, request))
                .isInstanceOf(AgentBindingSlotMismatchException.class)
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
                        1L, AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.CLOUD))
                .thenReturn(Optional.empty());
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, null));
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceAgentBinding.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new AgentBindingRequestDTO(99L, null, null, null, true, null, null);
        WorkspaceAgentBinding saved =
                service.upsertBinding(context(), AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.CLOUD, request);

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
                        1L, AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.empty());
        LlmModel model = model(99L, LlmApiProtocol.OPENAI_COMPLETIONS);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, null));
        when(llmModelRepository.findById(99L)).thenReturn(Optional.of(model));
        when(llmModelResolver.isAvailable(any(WorkspaceAgentBinding.class))).thenReturn(true);
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new AgentBindingRequestDTO(99L, null, null, null, true, null, null);
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

        var bothNull = new AgentBindingRequestDTO(null, null, null, null, true, null, null);
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

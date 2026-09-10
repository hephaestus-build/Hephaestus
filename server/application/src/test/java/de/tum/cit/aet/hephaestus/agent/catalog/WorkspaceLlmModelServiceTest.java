package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.dao.DataIntegrityViolationException;

class WorkspaceLlmModelServiceTest extends BaseUnitTest {

    @Mock
    private WorkspaceLlmModelRepository modelRepository;

    @Mock
    private WorkspaceLlmConnectionRepository connectionRepository;

    @Mock
    private LlmModelRepository instanceModelRepository;

    @Mock
    private LlmModelPriceRepository instancePriceRepository;

    @Mock
    private WorkspaceAgentBindingRepository agentBindingRepository;

    @Mock
    private InstanceLlmSettingsService instanceLlmSettingsService;

    @Mock
    private ConfigAuditPort configAudit;

    @InjectMocks
    private WorkspaceLlmModelService modelService;

    private WorkspaceContext workspaceContext;

    @BeforeEach
    void setUp() {
        workspaceContext = new WorkspaceContext(
                1L, "test-workspace", "Test Workspace", AccountType.ORG, null, false, false, Set.of());
    }

    private void byoEnabled(boolean enabled) {
        InstanceLlmSettings settings = new InstanceLlmSettings();
        settings.setAllowWorkspaceConnections(enabled);
        lenient().when(instanceLlmSettingsService.get()).thenReturn(settings);
    }

    private WorkspaceLlmConnection connection() {
        Workspace workspace = new Workspace();
        workspace.setId(1L);
        WorkspaceLlmConnection connection = new WorkspaceLlmConnection();
        connection.setId(50L);
        connection.setWorkspace(workspace);
        connection.setDisplayName("My Provider");
        return connection;
    }

    private CreateWorkspaceLlmModelRequestDTO unpricedCreateRequest() {
        return createRequest(null, null, null);
    }

    private CreateWorkspaceLlmModelRequestDTO enabledUnpricedCreateRequest() {
        return new CreateWorkspaceLlmModelRequestDTO(
                "gpt-5", "GPT-5", "gpt-5", null, null, null, null, true, null, null, null, null, null, null);
    }

    private CreateWorkspaceLlmModelRequestDTO createRequest(
            @Nullable PricingMode pricingMode,
            @Nullable BigDecimal per1mInputUsd,
            @Nullable BigDecimal per1mOutputUsd) {
        return new CreateWorkspaceLlmModelRequestDTO(
                "gpt-5",
                "GPT-5",
                "gpt-5",
                null,
                null,
                null,
                null,
                null,
                pricingMode,
                per1mInputUsd,
                per1mOutputUsd,
                null,
                null,
                null);
    }

    @Nested
    class ByoGate {

        @Test
        void createIsRejectedWhenWorkspaceConnectionsAreDisabled() {
            byoEnabled(false);

            assertThatThrownBy(() -> modelService.create(workspaceContext, 50L, unpricedCreateRequest()))
                    .isInstanceOf(AccessForbiddenException.class);
            verify(modelRepository, never()).saveAndFlush(any());
        }

        @Test
        void availableModelsRemainsAvailableWhenWorkspaceConnectionsAreDisabled() {
            // The gate guards writes only: a workspace whose BYO switch is off can still read the
            // catalogue, so existing bindings stay explicable.
            byoEnabled(false);
            when(instanceModelRepository.findVisibleEnabledModels(1L)).thenReturn(List.of());
            when(modelRepository.findEnabledWithEnabledConnection(1L)).thenReturn(List.of());

            List<AvailableLlmModelDTO> result = modelService.availableModels(workspaceContext);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class Create {

        @Test
        void rejectsAConnectionOwnedByAnotherWorkspace() {
            byoEnabled(true);
            when(connectionRepository.findByIdAndWorkspaceId(50L, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> modelService.create(workspaceContext, 50L, unpricedCreateRequest()))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void rejectsDuplicateSlugInTheSameWorkspace() {
            byoEnabled(true);
            when(connectionRepository.findByIdAndWorkspaceId(50L, 1L)).thenReturn(Optional.of(connection()));
            when(modelRepository.findByWorkspaceIdAndSlug(1L, "gpt-5"))
                    .thenReturn(Optional.of(new WorkspaceLlmModel()));

            assertThatThrownBy(() -> modelService.create(workspaceContext, 50L, unpricedCreateRequest()))
                    .isInstanceOf(LlmModelSlugConflictException.class);
            verify(modelRepository, never()).saveAndFlush(any());
        }

        @Test
        void defaultsToUnpricedWhenNoPricingModeGiven() {
            byoEnabled(true);
            when(connectionRepository.findByIdAndWorkspaceId(50L, 1L)).thenReturn(Optional.of(connection()));
            when(modelRepository.findByWorkspaceIdAndSlug(1L, "gpt-5")).thenReturn(Optional.empty());
            when(modelRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkspaceLlmModel result = modelService.create(workspaceContext, 50L, unpricedCreateRequest());

            assertThat(result.getPricingMode()).isEqualTo(PricingMode.UNPRICED);

            ArgumentCaptor<ConfigAuditEntry> entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
            verify(configAudit).record(entry.capture());
            assertThat(entry.getValue().entityType()).isEqualTo(ConfigAuditEntityType.WORKSPACE_LLM_MODEL);
            assertThat(entry.getValue().workspaceId()).isEqualTo(1L);
            assertThat(entry.getValue().action()).isEqualTo(ConfigAuditAction.CREATED);
        }

        @Test
        void pricedModeReusesTheSharedPriceValidationAndRejectsAMissingOutputRate() {
            byoEnabled(true);
            when(connectionRepository.findByIdAndWorkspaceId(50L, 1L)).thenReturn(Optional.of(connection()));
            when(modelRepository.findByWorkspaceIdAndSlug(1L, "gpt-5")).thenReturn(Optional.empty());

            CreateWorkspaceLlmModelRequestDTO request = createRequest(PricingMode.PRICED, new BigDecimal("3.00"), null);

            assertThatThrownBy(() -> modelService.create(workspaceContext, 50L, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("an input rate and an output rate");
            verify(modelRepository, never()).saveAndFlush(any());
        }

        @Test
        void refusesToCreateAnActiveModelWithNoPrice() {
            byoEnabled(true);
            // Connection deliberately enabled: only the missing price may be the reason for the refusal,
            // otherwise this test would pass on the other conjunct of the same guard.
            WorkspaceLlmConnection live = connection();
            live.setEnabled(true);
            when(connectionRepository.findByIdAndWorkspaceId(50L, 1L)).thenReturn(Optional.of(live));
            when(modelRepository.findByWorkspaceIdAndSlug(1L, "gpt-5")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> modelService.create(workspaceContext, 50L, enabledUnpricedCreateRequest()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("configure a price before activating the model");
            verify(modelRepository, never()).saveAndFlush(any());
            verifyNoInteractions(configAudit);
        }

        @Test
        void refusesToCreateAnActiveModelOnADisabledConnection() {
            byoEnabled(true);
            // Priced, so only the switched-off connection may be the reason for the refusal.
            WorkspaceLlmConnection disabled = connection();
            disabled.setEnabled(false);
            when(connectionRepository.findByIdAndWorkspaceId(50L, 1L)).thenReturn(Optional.of(disabled));
            when(modelRepository.findByWorkspaceIdAndSlug(1L, "gpt-5")).thenReturn(Optional.empty());

            CreateWorkspaceLlmModelRequestDTO pricedButOffline = new CreateWorkspaceLlmModelRequestDTO(
                    "gpt-5",
                    "GPT-5",
                    "gpt-5",
                    null,
                    null,
                    null,
                    null,
                    true,
                    PricingMode.PRICED,
                    new BigDecimal("3.00"),
                    new BigDecimal("6.00"),
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> modelService.create(workspaceContext, 50L, pricedButOffline))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Activate the connection");
            verify(modelRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    class Update {

        @Test
        void pricingFieldsAreUntouchedWhenPricingModeIsAbsent() {
            byoEnabled(true);
            WorkspaceLlmModel existing = new WorkspaceLlmModel();
            existing.setId(7L);
            existing.setWorkspace(connection().getWorkspace());
            existing.setConnection(connection());
            existing.setPricingMode(PricingMode.UNPRICED);
            when(modelRepository.findByIdAndWorkspaceIdForUpdate(7L, 1L)).thenReturn(Optional.of(existing));
            when(modelRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            UpdateWorkspaceLlmModelRequestDTO request = new UpdateWorkspaceLlmModelRequestDTO(
                    "New name", null, null, null, null, null, null, null, null, null, null, null);

            WorkspaceLlmModel result = modelService.update(workspaceContext, 7L, request);

            assertThat(result.getDisplayName()).isEqualTo("New name");
            assertThat(result.getPricingMode()).isEqualTo(PricingMode.UNPRICED);
        }
    }

    @Nested
    class UpstreamIdConflict {

        @Test
        void createRejectsADuplicateUpstreamIdOnTheSameConnection() {
            byoEnabled(true);
            when(connectionRepository.findByIdAndWorkspaceId(50L, 1L)).thenReturn(Optional.of(connection()));
            when(modelRepository.findByWorkspaceIdAndSlug(1L, "gpt-5")).thenReturn(Optional.empty());
            when(modelRepository.existsByConnectionIdAndUpstreamModelId(50L, "gpt-5"))
                    .thenReturn(true);

            assertThatThrownBy(() -> modelService.create(workspaceContext, 50L, unpricedCreateRequest()))
                    .isInstanceOf(LlmModelUpstreamIdConflictException.class);
            verify(modelRepository, never()).saveAndFlush(any());
        }

        @Test
        void createSucceedsWhenTheUpstreamIdIsUniqueOnTheConnection() {
            byoEnabled(true);
            when(connectionRepository.findByIdAndWorkspaceId(50L, 1L)).thenReturn(Optional.of(connection()));
            when(modelRepository.findByWorkspaceIdAndSlug(1L, "gpt-5")).thenReturn(Optional.empty());
            when(modelRepository.existsByConnectionIdAndUpstreamModelId(50L, "gpt-5"))
                    .thenReturn(false);
            when(modelRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkspaceLlmModel result = modelService.create(workspaceContext, 50L, unpricedCreateRequest());

            assertThat(result.getUpstreamModelId()).isEqualTo("gpt-5");
        }

        @Test
        void updateKeepsImmutableUpstreamModelId() {
            byoEnabled(true);
            WorkspaceLlmModel existing = new WorkspaceLlmModel();
            existing.setId(7L);
            existing.setWorkspace(connection().getWorkspace());
            existing.setConnection(connection());
            existing.setUpstreamModelId("gpt-5");
            when(modelRepository.findByIdAndWorkspaceIdForUpdate(7L, 1L)).thenReturn(Optional.of(existing));
            when(modelRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            UpdateWorkspaceLlmModelRequestDTO request = new UpdateWorkspaceLlmModelRequestDTO(
                    "Renamed", null, null, null, null, null, null, null, null, null, null, null);

            WorkspaceLlmModel result = modelService.update(workspaceContext, 7L, request);

            assertThat(result.getUpstreamModelId()).isEqualTo("gpt-5");
        }

        /**
         * The fast-path {@code existsByConnectionIdAndUpstreamModelId} check is racy; the unique
         * constraint {@code ux_ws_llm_model_connection_upstream} is the real backstop but only fires on
         * an actual flush, which is why the service calls {@code saveAndFlush()} inside the try/catch.
         */
        @Test
        void createTranslatesAFlushTimeConstraintViolationInto409() {
            byoEnabled(true);
            when(connectionRepository.findByIdAndWorkspaceId(50L, 1L)).thenReturn(Optional.of(connection()));
            when(modelRepository.findByWorkspaceIdAndSlug(1L, "gpt-5")).thenReturn(Optional.empty());
            when(modelRepository.existsByConnectionIdAndUpstreamModelId(50L, "gpt-5"))
                    .thenReturn(false);
            when(modelRepository.saveAndFlush(any())).thenThrow(upstreamIdConstraintViolation());

            assertThatThrownBy(() -> modelService.create(workspaceContext, 50L, unpricedCreateRequest()))
                    .isInstanceOf(LlmModelUpstreamIdConflictException.class);
        }

        private DataIntegrityViolationException upstreamIdConstraintViolation() {
            org.hibernate.exception.ConstraintViolationException cve =
                    new org.hibernate.exception.ConstraintViolationException(
                            "duplicate", new java.sql.SQLException("duplicate"), "ux_ws_llm_model_connection_upstream");
            return new DataIntegrityViolationException("duplicate", cve);
        }
    }

    @Nested
    class Delete {

        @Test
        void rejectsDeleteWhileAnAgentBindingStillBindsTheModel() {
            byoEnabled(true);
            WorkspaceLlmModel model = new WorkspaceLlmModel();
            model.setId(7L);
            when(modelRepository.findByIdAndWorkspaceIdWithConnection(7L, 1L)).thenReturn(Optional.of(model));
            when(agentBindingRepository.existsByWorkspaceModelIdAndWorkspaceId(7L, 1L))
                    .thenReturn(true);

            assertThatThrownBy(() -> modelService.delete(workspaceContext, 7L))
                    .isInstanceOf(LlmModelInUseException.class);
            verify(modelRepository, never()).delete(any());
        }

        @Test
        void deletesAnUnboundModel() {
            byoEnabled(true);
            WorkspaceLlmModel model = new WorkspaceLlmModel();
            model.setId(7L);
            when(modelRepository.findByIdAndWorkspaceIdWithConnection(7L, 1L)).thenReturn(Optional.of(model));
            when(agentBindingRepository.existsByWorkspaceModelIdAndWorkspaceId(7L, 1L))
                    .thenReturn(false);

            modelService.delete(workspaceContext, 7L);

            verify(modelRepository).delete(model);
            ArgumentCaptor<ConfigAuditEntry> entry = ArgumentCaptor.forClass(ConfigAuditEntry.class);
            verify(configAudit).record(entry.capture());
            assertThat(entry.getValue().entityType()).isEqualTo(ConfigAuditEntityType.WORKSPACE_LLM_MODEL);
            assertThat(entry.getValue().action()).isEqualTo(ConfigAuditAction.DELETED);
        }
    }

    @Nested
    class AvailableModels {

        @Test
        void unionsVisibleInstanceModelsAndTheWorkspacesOwnModels() {
            LlmConnection instanceConnection = new LlmConnection();
            instanceConnection.setId(200L);
            instanceConnection.setDisplayName("Shared Provider");
            LlmModel sharedModel = new LlmModel();
            sharedModel.setId(1L);
            sharedModel.setDisplayName("Shared GPT");
            sharedModel.setConnection(instanceConnection);
            sharedModel.setVisibility(ModelVisibility.PUBLIC);
            when(instanceModelRepository.findVisibleEnabledModels(1L)).thenReturn(List.of(sharedModel));

            LlmModelPrice price = new LlmModelPrice();
            price.setModel(sharedModel);
            price.setPricingMode(PricingMode.PRICED);
            price.setPer1mInputUsd(new BigDecimal("3.00"));
            price.setPer1mOutputUsd(new BigDecimal("9.00"));
            when(instancePriceRepository.findByModelIdInAndEffectiveToIsNull(List.of(1L)))
                    .thenReturn(List.of(price));

            WorkspaceLlmModel byoModel = new WorkspaceLlmModel();
            byoModel.setId(9L);
            byoModel.setDisplayName("My Own GPT");
            byoModel.setConnection(connection());
            byoModel.setPricingMode(PricingMode.UNPRICED);
            when(modelRepository.findEnabledWithEnabledConnection(1L)).thenReturn(List.of(byoModel));

            List<AvailableLlmModelDTO> result = modelService.availableModels(workspaceContext);

            assertThat(result).hasSize(2);
            AvailableLlmModelDTO shared = result.stream()
                    .filter(dto -> dto.scope() == LlmModelScope.SHARED)
                    .findFirst()
                    .orElseThrow();
            assertThat(shared.id()).isEqualTo(1L);
            assertThat(shared.connectionDisplayName()).isEqualTo("Shared Provider");
            assertThat(shared.pricingMode()).isEqualTo(PricingMode.PRICED);
            assertThat(shared.per1mInputUsd()).isEqualByComparingTo("3.00");

            AvailableLlmModelDTO own = result.stream()
                    .filter(dto -> dto.scope() == LlmModelScope.WORKSPACE)
                    .findFirst()
                    .orElseThrow();
            assertThat(own.id()).isEqualTo(9L);
            assertThat(own.connectionDisplayName()).isEqualTo("My Provider");
        }
    }
}

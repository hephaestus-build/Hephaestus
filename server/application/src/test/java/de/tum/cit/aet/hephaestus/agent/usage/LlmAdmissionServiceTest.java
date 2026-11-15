package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.ModelPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignment;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignmentRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class LlmAdmissionServiceTest extends BaseUnitTest {

    private static WorkspaceLlmModel workspaceModel(WorkspaceModelAssignment binding) {
        WorkspaceLlmModel model = binding.getWorkspaceModel();
        assertThat(model).isNotNull();
        return model;
    }

    private static LlmModel instanceModel(WorkspaceModelAssignment binding) {
        LlmModel model = binding.getInstanceModel();
        assertThat(model).isNotNull();
        return model;
    }

    @Mock
    private LlmModelResolver resolver;

    @Mock
    private WorkspaceModelAssignmentRepository bindingRepository;

    @Mock
    private LlmModelPriceRepository priceRepository;

    @Mock
    private LlmModelRepository modelRepository;

    @Mock
    private WorkspaceLlmModelRepository workspaceModelRepository;

    @InjectMocks
    private LlmAdmissionService service;

    private static LlmModel instanceModel() {
        LlmModel model = new LlmModel();
        model.setId(20L);
        return model;
    }

    private static WorkspaceModelAssignment binding(ModelPurpose purpose) {
        Workspace workspace = new Workspace();
        workspace.setId(30L);
        WorkspaceModelAssignment binding = new WorkspaceModelAssignment();
        binding.setId(1L);
        binding.setWorkspace(workspace);
        binding.setPurpose(purpose);
        binding.setEnabled(true);
        binding.setInstanceModel(instanceModel());
        return binding;
    }

    private static WorkspaceModelAssignment byoBinding() {
        WorkspaceModelAssignment binding = binding(ModelPurpose.PRACTICE_REVIEW);
        binding.setInstanceModel(null);
        WorkspaceLlmModel model = new WorkspaceLlmModel();
        model.setId(21L);
        model.setPricingMode(PricingMode.PRICED);
        model.setPer1mInputUsd(new BigDecimal("0.50"));
        model.setPer1mOutputUsd(new BigDecimal("2.00"));
        binding.setWorkspaceModel(model);
        return binding;
    }

    @Test
    void freezesAuthoritativeInstancePriceAtAdmission() {
        WorkspaceModelAssignment binding = binding(ModelPurpose.PRACTICE_REVIEW);
        when(bindingRepository.findByWorkspaceIdAndIdForUpdate(30L, binding.getId()))
                .thenReturn(Optional.of(binding));
        when(modelRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(instanceModel(binding)));
        ResolvedLlmModel resolved = new ResolvedLlmModel(
                "https://api.example/v1", "openai-responses", "gpt-authoritative", null, null, null, false);
        when(resolver.resolve(binding)).thenReturn(resolved);
        when(resolver.connectionRef(binding))
                .thenReturn(new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 10L, 20L, 30L));
        LlmModelPrice price = new LlmModelPrice();
        price.setId(40L);
        price.setPricingMode(PricingMode.PRICED);
        price.setPer1mInputUsd(new BigDecimal("1.25"));
        price.setPer1mOutputUsd(new BigDecimal("5.00"));
        when(priceRepository.findByModelIdAndEffectiveToIsNull(20L)).thenReturn(Optional.of(price));

        AdmittedLlmModel admitted = service.admit(binding);

        assertThat(admitted.resolved().upstreamModelId()).isEqualTo("gpt-authoritative");
        assertThat(admitted.price().fundingSource()).isEqualTo(UsageFundingSource.INSTANCE);
        assertThat(admitted.price().pricingState()).isEqualTo(UsagePricingState.PRICED);
        assertThat(admitted.price().appliedPriceId()).isEqualTo(40L);
        assertThat(admitted.price().per1mInputUsd()).isEqualByComparingTo("1.25");
    }

    /**
     * A workspace-owned model must be priced from the workspace's own catalog row and stamped
     * WORKSPACE, or the workspace's provider spend would be charged against the host's shared-model
     * cap.
     */
    @Test
    void freezesTheWorkspacesOwnPriceAndFundingSourceForABoundByoModel() {
        WorkspaceModelAssignment binding = byoBinding();
        when(bindingRepository.findByWorkspaceIdAndIdForUpdate(30L, binding.getId()))
                .thenReturn(Optional.of(binding));
        // The row lock the instance arm takes on llm_model is taken on workspace_llm_model here, and
        // it is scoped to the owning workspace: a model id alone must not be admissible cross-tenant.
        when(workspaceModelRepository.findByIdAndWorkspaceIdForUpdate(21L, 30L))
                .thenReturn(Optional.of(workspaceModel(binding)));
        when(resolver.resolve(binding))
                .thenReturn(new ResolvedLlmModel(
                        "https://byo.example/v1", "openai-responses", "byo-model", null, null, null, false));
        when(resolver.connectionRef(binding))
                .thenReturn(new LlmModelResolver.ConnectionRef(UsageFundingSource.WORKSPACE, 11L, 21L, 30L));
        when(workspaceModelRepository.findByIdAndWorkspaceId(21L, 30L))
                .thenReturn(Optional.of(workspaceModel(binding)));

        AdmittedLlmModel admitted = service.admit(binding);

        assertThat(admitted.price().fundingSource()).isEqualTo(UsageFundingSource.WORKSPACE);
        assertThat(admitted.price().pricingState()).isEqualTo(UsagePricingState.PRICED);
        assertThat(admitted.price().appliedWorkspaceModelId()).isEqualTo(21L);
        assertThat(admitted.price().appliedPriceId()).isNull();
        assertThat(admitted.price().per1mInputUsd()).isEqualByComparingTo("0.50");
        assertThat(admitted.price().per1mOutputUsd()).isEqualByComparingTo("2.00");
        verifyNoInteractions(priceRepository);
    }

    /**
     * A model whose price nobody has filled in cannot be metered, so it must not run at all. The
     * structural block on unverifiable spend: admitting it would produce usage rows the ledger cannot
     * cost, and neither cap could ever refuse the work.
     */
    @Test
    void refusesToAdmitAModelWhosePriceIsUnknown() {
        WorkspaceModelAssignment binding = byoBinding();
        workspaceModel(binding).setPricingMode(PricingMode.UNPRICED);
        when(bindingRepository.findByWorkspaceIdAndIdForUpdate(30L, binding.getId()))
                .thenReturn(Optional.of(binding));
        when(workspaceModelRepository.findByIdAndWorkspaceIdForUpdate(21L, 30L))
                .thenReturn(Optional.of(workspaceModel(binding)));
        when(resolver.resolve(binding))
                .thenReturn(new ResolvedLlmModel(
                        "https://byo.example/v1", "openai-responses", "byo-model", null, null, null, false));
        when(resolver.connectionRef(binding))
                .thenReturn(new LlmModelResolver.ConnectionRef(UsageFundingSource.WORKSPACE, 11L, 21L, 30L));
        when(workspaceModelRepository.findByIdAndWorkspaceId(21L, 30L))
                .thenReturn(Optional.of(workspaceModel(binding)));

        assertThatThrownBy(() -> service.admit(binding))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no usable price");
    }

    @Test
    void rejectsBeforePricingWhenBoundModelIsUnavailable() {
        WorkspaceModelAssignment binding = binding(ModelPurpose.CONVERSATION);
        when(bindingRepository.findByWorkspaceIdAndIdForUpdate(30L, binding.getId()))
                .thenReturn(Optional.of(binding));
        when(modelRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(instanceModel(binding)));
        when(resolver.resolve(binding)).thenThrow(new IllegalStateException("model revoked"));

        assertThatThrownBy(() -> service.admit(binding)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(priceRepository);
    }

    @Test
    void rejectsADisabledBindingWithoutResolvingOrPricingIt() {
        WorkspaceModelAssignment binding = binding(ModelPurpose.CONVERSATION);
        binding.setEnabled(false);
        when(bindingRepository.findByWorkspaceIdAndIdForUpdate(30L, binding.getId()))
                .thenReturn(Optional.of(binding));

        assertThatThrownBy(() -> service.admit(binding)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(resolver, priceRepository);
    }

    @Test
    void shouldFreezeThePriceWhenAModelIsAdmittedIfAvailable() {
        WorkspaceModelAssignment binding = binding(ModelPurpose.PRACTICE_REVIEW);
        when(bindingRepository.findByWorkspaceIdAndIdForUpdate(30L, binding.getId()))
                .thenReturn(Optional.of(binding));
        when(resolver.isAvailable(binding)).thenReturn(true);
        when(modelRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(instanceModel(binding)));
        when(resolver.resolve(binding))
                .thenReturn(new ResolvedLlmModel(
                        "https://api.example/v1", "openai-responses", "gpt-5-mini", null, null, null, false));
        when(resolver.connectionRef(binding))
                .thenReturn(new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 10L, 20L, 30L));
        LlmModelPrice price = new LlmModelPrice();
        price.setId(41L);
        price.setPricingMode(PricingMode.PRICED);
        price.setPer1mInputUsd(new BigDecimal("0.02"));
        when(priceRepository.findByModelIdAndEffectiveToIsNull(20L)).thenReturn(Optional.of(price));

        AdmittedLlmModel admitted = service.admitIfAvailable(binding).orElseThrow();

        assertThat(admitted.resolved().upstreamModelId()).isEqualTo("gpt-5-mini");
        assertThat(admitted.price().appliedPriceId()).isEqualTo(41L);
        assertThat(admitted.price().per1mInputUsd()).isEqualByComparingTo("0.02");
    }

    // A failure escaping the transactional proxy would mark the claim rollback-only.
    @Test
    void shouldAdmitNothingWithoutThrowingWhenAModelHasNoPrice() {
        WorkspaceModelAssignment binding = byoBinding();
        workspaceModel(binding).setPricingMode(PricingMode.UNPRICED);
        when(bindingRepository.findByWorkspaceIdAndIdForUpdate(30L, binding.getId()))
                .thenReturn(Optional.of(binding));
        when(resolver.isAvailable(binding)).thenReturn(true);
        when(workspaceModelRepository.findByIdAndWorkspaceIdForUpdate(21L, 30L))
                .thenReturn(Optional.of(workspaceModel(binding)));
        when(resolver.resolve(binding))
                .thenReturn(new ResolvedLlmModel(
                        "https://byo.example/v1", "openai-completions", "byo-chat", null, null, null, false));
        when(resolver.connectionRef(binding))
                .thenReturn(new LlmModelResolver.ConnectionRef(UsageFundingSource.WORKSPACE, 11L, 21L, 30L));
        when(workspaceModelRepository.findByIdAndWorkspaceId(21L, 30L))
                .thenReturn(Optional.of(workspaceModel(binding)));

        assertThat(service.admitIfAvailable(binding)).isEmpty();
    }

    @Test
    void shouldAdmitNothingWithoutResolvingWhenAModelIsUnavailable() {
        WorkspaceModelAssignment binding = binding(ModelPurpose.PRACTICE_REVIEW);
        when(bindingRepository.findByWorkspaceIdAndIdForUpdate(30L, binding.getId()))
                .thenReturn(Optional.of(binding));
        when(resolver.isAvailable(binding)).thenReturn(false);

        assertThat(service.admitIfAvailable(binding)).isEmpty();
        verify(resolver, never()).resolve(any());
        verifyNoInteractions(priceRepository);
    }
}

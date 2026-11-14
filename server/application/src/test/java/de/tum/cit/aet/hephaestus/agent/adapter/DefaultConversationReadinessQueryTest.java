package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.ModelPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignment;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignmentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspacePortFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class DefaultConversationReadinessQueryTest extends BaseUnitTest {

    @Mock
    private WorkspaceModelAssignmentRepository modelAssignmentRepository;

    @Mock
    private LlmModelResolver llmModelResolver;

    @Mock
    private WorkspaceRepository workspaceRepository;

    private DefaultConversationReadinessQuery query;

    @BeforeEach
    void setUp() {
        Workspace workspace = new Workspace();
        workspace.resume();
        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
        query = new DefaultConversationReadinessQuery(
                modelAssignmentRepository, llmModelResolver, WorkspacePortFixtures.queries(workspaceRepository));
    }

    @Test
    void shouldReportReadyWhenAnEnabledBindingResolvesToAModel() {
        WorkspaceModelAssignment binding = new WorkspaceModelAssignment();
        binding.setId(10L);
        binding.setPurpose(ModelPurpose.CONVERSATION);
        binding.setEnabled(true);
        when(modelAssignmentRepository.findByWorkspaceIdWithModels(1L)).thenReturn(List.of(binding));
        when(llmModelResolver.isAvailable(binding)).thenReturn(true);

        assertThat(query.isReady(1L)).isTrue();
    }

    @Test
    void shouldNotReportReadyWhenConversationIsUnconfigured() {
        when(modelAssignmentRepository.findByWorkspaceIdWithModels(1L)).thenReturn(List.of());
        assertThat(query.isReady(1L)).isFalse();
    }

    @Test
    void shouldNotReportReadyWhenBoundModelIsUnavailable() {
        WorkspaceModelAssignment binding = new WorkspaceModelAssignment();
        binding.setId(10L);
        binding.setPurpose(ModelPurpose.CONVERSATION);
        binding.setEnabled(true);
        when(modelAssignmentRepository.findByWorkspaceIdWithModels(1L)).thenReturn(List.of(binding));
        when(llmModelResolver.isAvailable(binding)).thenReturn(false);

        assertThat(query.isReady(1L)).isFalse();
    }

    @Test
    void shouldNotReportReadyWhenBindingIsDisabled() {
        WorkspaceModelAssignment disabled = new WorkspaceModelAssignment();
        disabled.setId(10L);
        disabled.setPurpose(ModelPurpose.CONVERSATION);
        disabled.setEnabled(false);
        when(modelAssignmentRepository.findByWorkspaceIdWithModels(1L)).thenReturn(List.of(disabled));

        assertThat(query.isReady(1L)).isFalse();
    }

    @Test
    void shouldNotReportReadyWithoutLookingAtBindingsWhenWorkspaceIsSuspended() {
        Workspace workspace = new Workspace();
        workspace.suspend();
        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

        assertThat(query.isReady(1L)).isFalse();
        verifyNoInteractions(modelAssignmentRepository, llmModelResolver);
    }

    @Test
    void shouldNotReportReadyWhenWorkspaceIsMissing() {
        when(workspaceRepository.findById(1L)).thenReturn(Optional.empty());

        assertThat(query.isReady(1L)).isFalse();
    }

    @Test
    void shouldFailClosedWhenWorkspaceCannotBeRead() {
        when(workspaceRepository.findById(1L)).thenThrow(new IllegalStateException("database unavailable"));

        assertThat(query.isReady(1L)).isFalse();
    }
}

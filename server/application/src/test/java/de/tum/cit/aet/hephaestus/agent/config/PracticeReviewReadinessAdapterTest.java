package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class PracticeReviewReadinessAdapterTest extends BaseUnitTest {

    @Mock
    private WorkspaceModelAssignmentRepository bindingRepository;

    @Mock
    private LlmModelResolver resolver;

    private PracticeReviewReadinessAdapter checker;

    @BeforeEach
    void setUp() {
        checker = new PracticeReviewReadinessAdapter(bindingRepository, resolver);
    }

    private WorkspaceModelAssignment binding(boolean enabled) {
        WorkspaceModelAssignment b = new WorkspaceModelAssignment();
        b.setPurpose(ModelPurpose.PRACTICE_REVIEW);
        b.setEnabled(enabled);
        return b;
    }

    @Test
    void unboundPracticeIsNotRunnable() {
        when(bindingRepository.findByWorkspaceIdWithModels(1L)).thenReturn(List.of());
        assertThat(checker.hasRunnableAgent(1L)).isFalse();
    }

    @Test
    void disabledBindingIsNotRunnable() {
        when(bindingRepository.findByWorkspaceIdWithModels(1L)).thenReturn(List.of(binding(false)));
        assertThat(checker.hasRunnableAgent(1L)).isFalse();
    }

    @Test
    void enabledBindingWithRevokedModelIsNotRunnable() {
        WorkspaceModelAssignment b = binding(true);
        when(bindingRepository.findByWorkspaceIdWithModels(1L)).thenReturn(List.of(b));
        when(resolver.isAvailable(b)).thenReturn(false);
        assertThat(checker.hasRunnableAgent(1L)).isFalse();
    }

    @Test
    void enabledBindingWithAvailableModelIsRunnable() {
        WorkspaceModelAssignment b = binding(true);
        when(bindingRepository.findByWorkspaceIdWithModels(1L)).thenReturn(List.of(b));
        when(resolver.isAvailable(b)).thenReturn(true);
        assertThat(checker.hasRunnableAgent(1L)).isTrue();
    }
}

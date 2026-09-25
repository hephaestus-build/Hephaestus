package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class PracticeReviewReadinessAdapterTest extends BaseUnitTest {

    @Mock
    private WorkspaceAgentBindingRepository bindingRepository;

    @Mock
    private LlmModelResolver resolver;

    private PracticeReviewReadinessAdapter checker;

    @BeforeEach
    void setUp() {
        checker = new PracticeReviewReadinessAdapter(bindingRepository, resolver);
    }

    private WorkspaceAgentBinding binding(boolean enabled) {
        WorkspaceAgentBinding b = new WorkspaceAgentBinding();
        b.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        b.setEnabled(enabled);
        return b;
    }

    @Test
    void unboundPracticeIsNotRunnable() {
        when(bindingRepository.findByWorkspaceIdWithModels(1L)).thenReturn(java.util.List.of());
        assertThat(checker.hasRunnableAgent(1L)).isFalse();
    }

    @Test
    void disabledBindingIsNotRunnable() {
        when(bindingRepository.findByWorkspaceIdWithModels(1L)).thenReturn(java.util.List.of(binding(false)));
        assertThat(checker.hasRunnableAgent(1L)).isFalse();
    }

    @Test
    void enabledBindingWithRevokedModelIsNotRunnable() {
        WorkspaceAgentBinding b = binding(true);
        when(bindingRepository.findByWorkspaceIdWithModels(1L)).thenReturn(java.util.List.of(b));
        when(resolver.isAvailable(b)).thenReturn(false);
        assertThat(checker.hasRunnableAgent(1L)).isFalse();
    }

    @Test
    void enabledBindingWithAvailableModelIsRunnable() {
        WorkspaceAgentBinding b = binding(true);
        when(bindingRepository.findByWorkspaceIdWithModels(1L)).thenReturn(java.util.List.of(b));
        when(resolver.isAvailable(b)).thenReturn(true);
        assertThat(checker.hasRunnableAgent(1L)).isTrue();
    }
}

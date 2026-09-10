package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmProcessingLocation;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class MemberAiRoutingAdapterTest extends BaseUnitTest {
    @Mock
    private WorkspaceAgentBindingRepository bindings;

    @Mock
    private MemberAiPreferences preferences;

    @Mock
    private LlmModelResolver models;

    @Mock
    private WorkspaceRepository workspaces;

    private MemberAiRoutingAdapter routing;

    @BeforeEach
    void setUp() {
        routing = new MemberAiRoutingAdapter(bindings, preferences, models, workspaces);
    }

    @Test
    void shouldNeverResolveAModelWhenMemberChoosesNoAi() {
        when(preferences.forDeveloper(1L, 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, MemberAiChoice.NO_AI));
        assertThat(routing.binding(1L, AgentPurpose.PRACTICE_REVIEW, 20L)).isEmpty();
        assertThat(routing.binding(1L, AgentPurpose.MENTOR, 20L)).isEmpty();
        assertThat(routing.allows(1L, 20L, LlmModelResolver.ConnectionRef.NONE)).isFalse();
        verifyNoInteractions(bindings, models);
    }

    @Test
    void shouldFailClosedWhenChoiceIsRequiredAndDeveloperIsUnknown() {
        when(preferences.forDeveloper(1L, null)).thenReturn(new MemberAiPreferences.Decision(true, null));
        assertThat(routing.binding(1L, AgentPurpose.PRACTICE_REVIEW, null)).isEmpty();
        verifyNoInteractions(bindings, models);
    }

    @Test
    void shouldNotFallBackWhenChosenLocationHasNoBinding() {
        when(preferences.forDeveloper(1L, 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, MemberAiChoice.ON_PREMISES));
        assertThat(routing.binding(1L, AgentPurpose.PRACTICE_REVIEW, 20L)).isEmpty();
        verify(bindings)
                .findByWorkspaceIdAndPurposeAndProcessingLocation(
                        1L, AgentPurpose.PRACTICE_REVIEW, LlmProcessingLocation.ON_PREMISES);
        verifyNoMoreInteractions(bindings);
    }

    @Test
    void shouldKeepChoicesSeparateAcrossWorkspaces() {
        when(preferences.forDeveloper(1L, 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, MemberAiChoice.NO_AI));
        when(preferences.forDeveloper(2L, 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, MemberAiChoice.PRIVATE_CLOUD));
        var binding = new WorkspaceAgentBinding();
        binding.setEnabled(true);
        when(bindings.findByWorkspaceIdAndPurposeAndProcessingLocation(
                        2L, AgentPurpose.MENTOR, LlmProcessingLocation.PRIVATE_CLOUD))
                .thenReturn(Optional.of(binding));
        when(models.isAvailable(binding)).thenReturn(true);
        assertThat(routing.binding(1L, AgentPurpose.MENTOR, 20L)).isEmpty();
        assertThat(routing.binding(2L, AgentPurpose.MENTOR, 20L)).contains(binding);
    }

    @Test
    void shouldRejectAModelReclassifiedAfterTheChoice() {
        var model = new LlmModelResolver.ConnectionRef(FundingSource.WORKSPACE, 8L, 9L, 1L);
        when(preferences.forDeveloper(1L, 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, MemberAiChoice.ON_PREMISES));
        when(models.processingLocation(model))
                .thenReturn(LlmProcessingLocation.ON_PREMISES, LlmProcessingLocation.PRIVATE_CLOUD);
        assertThat(routing.allows(1L, 20L, model)).isTrue();
        assertThat(routing.allows(1L, 20L, model)).isFalse();
    }

    @Test
    void shouldPreserveTheDefaultOnlyForWorkspacesThatNeverRequiredAChoice() {
        when(preferences.forDeveloper(1L, 20L)).thenReturn(new MemberAiPreferences.Decision(false, null));
        var binding = new WorkspaceAgentBinding();
        binding.setEnabled(true);
        when(bindings.findByWorkspaceIdAndPurposeAndProcessingLocation(
                        1L, AgentPurpose.MENTOR, LlmProcessingLocation.UNCLASSIFIED))
                .thenReturn(Optional.of(binding));
        when(models.isAvailable(binding)).thenReturn(true);
        assertThat(routing.binding(1L, AgentPurpose.MENTOR, 20L)).contains(binding);
    }

    @Test
    void shouldShowOnlyReadyPurposesWhenOfferingALocation() {
        var workspace = new Workspace();
        workspace.getFeatures().setMentorEnabled(true);
        workspace.getFeatures().setPracticesEnabled(false);
        when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));
        var binding = new WorkspaceAgentBinding();
        binding.setEnabled(true);
        when(bindings.findByWorkspaceIdAndPurposeAndProcessingLocation(
                        1L, AgentPurpose.MENTOR, LlmProcessingLocation.ON_PREMISES))
                .thenReturn(Optional.of(binding));
        when(models.isAvailable(binding)).thenReturn(true);
        assertThat(routing.options(1L))
                .containsExactly(
                        new de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability.Option(
                                MemberAiChoice.ON_PREMISES, false, true),
                        new de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability.Option(
                                MemberAiChoice.PRIVATE_CLOUD, false, false));
    }
}

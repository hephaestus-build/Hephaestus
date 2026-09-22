package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability;
import java.util.List;
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

    private WorkspaceAgentBinding ready(DataHandlingTier tier) {
        var binding = new WorkspaceAgentBinding();
        binding.setDataHandlingTier(tier);
        binding.setEnabled(true);
        lenient().when(models.isAvailable(binding)).thenReturn(true);
        return binding;
    }

    private void chose(MemberAiChoice choice) {
        when(preferences.forDeveloper(1L, 20L)).thenReturn(new MemberAiPreferences.Decision(true, choice));
    }

    @Test
    void shouldNeverResolveAModelWhenMemberChoosesNoAi() {
        chose(MemberAiChoice.NO_AI);
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
    void shouldPickTheLoosestBindingWithinTheChosenCeiling() {
        chose(MemberAiChoice.CLOUD);
        var inHouse = ready(DataHandlingTier.IN_HOUSE);
        var cloud = ready(DataHandlingTier.CLOUD);
        var undeclared = ready(DataHandlingTier.UNDECLARED);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(List.of(undeclared, inHouse, cloud));
        assertThat(routing.binding(1L, AgentPurpose.PRACTICE_REVIEW, 20L)).contains(cloud);
    }

    @Test
    void shouldServeACloudMemberFromTheInHouseBindingWhenTheCloudTierIsUnbound() {
        chose(MemberAiChoice.CLOUD);
        var inHouse = ready(DataHandlingTier.IN_HOUSE);
        var undeclared = ready(DataHandlingTier.UNDECLARED);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.MENTOR)).thenReturn(List.of(undeclared, inHouse));
        assertThat(routing.binding(1L, AgentPurpose.MENTOR, 20L)).contains(inHouse);
    }

    @Test
    void shouldNotFallBackToALooserOrUndeclaredBinding() {
        chose(MemberAiChoice.IN_HOUSE_ONLY);
        var cloud = ready(DataHandlingTier.CLOUD);
        var undeclared = ready(DataHandlingTier.UNDECLARED);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(List.of(cloud, undeclared));
        assertThat(routing.binding(1L, AgentPurpose.PRACTICE_REVIEW, 20L)).isEmpty();
    }

    @Test
    void shouldSkipABindingThatIsOff() {
        chose(MemberAiChoice.CLOUD);
        var off = ready(DataHandlingTier.CLOUD);
        off.setEnabled(false);
        var inHouse = ready(DataHandlingTier.IN_HOUSE);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.MENTOR)).thenReturn(List.of(off, inHouse));
        assertThat(routing.binding(1L, AgentPurpose.MENTOR, 20L)).contains(inHouse);
    }

    @Test
    void shouldSkipABindingWhoseModelIsNotAvailable() {
        chose(MemberAiChoice.CLOUD);
        var unavailable = ready(DataHandlingTier.CLOUD);
        when(models.isAvailable(unavailable)).thenReturn(false);
        var inHouse = ready(DataHandlingTier.IN_HOUSE);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.MENTOR)).thenReturn(List.of(unavailable, inHouse));
        assertThat(routing.binding(1L, AgentPurpose.MENTOR, 20L)).contains(inHouse);
    }

    @Test
    void shouldKeepChoicesSeparateAcrossWorkspaces() {
        chose(MemberAiChoice.NO_AI);
        when(preferences.forDeveloper(2L, 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, MemberAiChoice.CLOUD));
        var binding = ready(DataHandlingTier.CLOUD);
        when(bindings.findByWorkspaceIdAndPurpose(2L, AgentPurpose.MENTOR)).thenReturn(List.of(binding));
        assertThat(routing.binding(1L, AgentPurpose.MENTOR, 20L)).isEmpty();
        assertThat(routing.binding(2L, AgentPurpose.MENTOR, 20L)).contains(binding);
    }

    @Test
    void shouldAllowAModelWithinTheCeilingAndRejectOneThatLoosenedAfterTheChoice() {
        var model = new LlmModelResolver.ConnectionRef(FundingSource.WORKSPACE, 8L, 9L, 1L);
        chose(MemberAiChoice.IN_HOUSE_ONLY);
        when(models.dataHandlingTier(model))
                .thenReturn(DataHandlingTier.IN_HOUSE, DataHandlingTier.CLOUD, DataHandlingTier.UNDECLARED);
        assertThat(routing.allows(1L, 20L, model)).isTrue();
        assertThat(routing.allows(1L, 20L, model)).isFalse();
        assertThat(routing.allows(1L, 20L, model)).isFalse();
    }

    @Test
    void shouldServeTheUndeclaredSlotOnlyToMembersWhoNeverHadToChoose() {
        when(preferences.forDeveloper(1L, 20L)).thenReturn(new MemberAiPreferences.Decision(false, null));
        var binding = ready(DataHandlingTier.UNDECLARED);
        when(bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.of(binding));
        assertThat(routing.binding(1L, AgentPurpose.MENTOR, 20L)).contains(binding);
        assertThat(routing.allows(1L, 20L, LlmModelResolver.ConnectionRef.NONE)).isTrue();
        verify(bindings, never()).findByWorkspaceIdAndPurpose(anyLong(), any());
    }

    @Test
    void shouldOfferTheTwoAiChoicesWithReadinessHonouringTheCeilingAndFeatureFlags() {
        var workspace = new Workspace();
        workspace.getFeatures().setMentorEnabled(true);
        workspace.getFeatures().setPracticesEnabled(false);
        when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));
        var cloud = ready(DataHandlingTier.CLOUD);
        var undeclared = ready(DataHandlingTier.UNDECLARED);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.MENTOR)).thenReturn(List.of(cloud, undeclared));
        assertThat(routing.options(1L))
                .containsExactly(
                        new WorkspaceAiAvailability.Option(MemberAiChoice.IN_HOUSE_ONLY, false, false),
                        new WorkspaceAiAvailability.Option(MemberAiChoice.CLOUD, false, true));
        // One query per enabled purpose, shared by both choices; a disabled purpose loads nothing.
        verify(bindings, times(1)).findByWorkspaceIdAndPurpose(1L, AgentPurpose.MENTOR);
        verify(bindings, never()).findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW);
    }

    @Test
    void shouldNotSuggestModelsExistWhenNothingIsSetUp() {
        var workspace = new Workspace();
        workspace.getFeatures().setMentorEnabled(false);
        workspace.getFeatures().setPracticesEnabled(false);
        when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));
        assertThat(routing.options(1L)).allSatisfy(option -> {
            assertThat(option.practiceReviewsReady()).isFalse();
            assertThat(option.mentorReady()).isFalse();
        });
    }
}

package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.AiModelBrand;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.LlmConnectionPlatform;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    @Mock
    private PersonProcessingSuppression suppression;

    private MemberAiRoutingAdapter routing;

    @BeforeEach
    void setUp() {
        routing = new MemberAiRoutingAdapter(bindings, preferences, models, workspaces, suppression);
    }

    private WorkspaceAgentBinding ready(DataHandlingTier tier) {
        var binding = new WorkspaceAgentBinding();
        binding.setDataHandlingTier(tier);
        binding.setEnabled(true);
        lenient().when(models.isAvailable(binding)).thenReturn(true);
        return binding;
    }

    private WorkspaceAgentBinding ready(AgentPurpose purpose, DataHandlingTier tier) {
        var binding = ready(tier);
        binding.setPurpose(purpose);
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
                .thenReturn(Optional.of(DataHandlingTier.IN_HOUSE))
                .thenReturn(Optional.of(DataHandlingTier.CLOUD))
                .thenReturn(Optional.empty());
        assertThat(routing.allows(1L, 20L, model)).isTrue();
        assertThat(routing.allows(1L, 20L, model)).isFalse();
        assertThat(routing.allows(1L, 20L, model)).isFalse();
    }

    @Test
    void shouldServeTheUndeclaredSlotOnlyToMembersWhoNeverHadToChoose() {
        when(preferences.forDeveloper(1L, 20L)).thenReturn(new MemberAiPreferences.Decision(false, null));
        var binding = ready(DataHandlingTier.UNDECLARED);
        var model = new LlmModel();
        binding.setInstanceModel(model);
        when(bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.of(binding));
        assertThat(routing.binding(1L, AgentPurpose.MENTOR, 20L)).contains(binding);
        model.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, null));
        assertThat(routing.binding(1L, AgentPurpose.MENTOR, 20L)).isEmpty();
        var ref = new LlmModelResolver.ConnectionRef(FundingSource.INSTANCE, 8L, 9L, 1L);
        when(models.dataHandlingTier(ref))
                .thenReturn(Optional.of(DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.of(DataHandlingTier.CLOUD));
        assertThat(routing.allows(1L, 20L, ref)).isTrue();
        assertThat(routing.allows(1L, 20L, ref)).isFalse();
        assertThat(routing.allows(1L, 20L, LlmModelResolver.ConnectionRef.NONE)).isFalse();
        verify(bindings, never()).findByWorkspaceIdAndPurpose(anyLong(), any());
    }

    @Test
    void shouldOfferTheTwoAiChoicesWithReadinessHonouringTheCeilingAndReviewSwitch() {
        var workspace = new Workspace();
        workspace.getFeatures().setPracticesEnabled(false);
        when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));
        var cloud = ready(DataHandlingTier.CLOUD);
        var undeclared = ready(DataHandlingTier.UNDECLARED);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.MENTOR)).thenReturn(List.of(cloud, undeclared));
        assertThat(routing.options(1L))
                .containsExactly(
                        new WorkspaceAiAvailability.Option(MemberAiChoice.IN_HOUSE_ONLY, false, false, List.of()),
                        new WorkspaceAiAvailability.Option(MemberAiChoice.CLOUD, false, true, List.of()));
        // One query per purpose, shared by both choices; reviews that are off load nothing.
        verify(bindings, times(1)).findByWorkspaceIdAndPurpose(1L, AgentPurpose.MENTOR);
        verify(bindings, never()).findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW);
    }

    @Test
    void shouldNotSuggestModelsExistWhenNothingIsSetUp() {
        var workspace = new Workspace();
        workspace.getFeatures().setPracticesEnabled(false);
        when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));
        assertThat(routing.options(1L)).allSatisfy(option -> {
            assertThat(option.practiceReviewsReady()).isFalse();
            assertThat(option.mentorReady()).isFalse();
        });
    }

    @Test
    void shouldUseDeclaredBrandWithoutInferringHost() {
        var workspace = new Workspace();
        workspace.getFeatures().setPracticesEnabled(true);
        when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));
        var inHouse = ready(DataHandlingTier.IN_HOUSE);
        var inHouseModel =
                model("Llama 3.3", "meta-llama/Llama-3.3-70B", "https://org-operated-vm.example/v1", AiModelBrand.META);
        inHouseModel.setDataHandling(DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, null));
        inHouseModel.getConnection().setConnectionPlatform(LlmConnectionPlatform.AZURE);
        inHouse.setInstanceModel(inHouseModel);
        var cloud = ready(DataHandlingTier.CLOUD);
        var azureModel = model("GPT-6 Luna", "gpt-6-luna", "https://acme.openai.azure.com/openai", AiModelBrand.OPENAI);
        azureModel.getConnection().setConnectionPlatform(LlmConnectionPlatform.AZURE);
        azureModel.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, null));
        cloud.setInstanceModel(azureModel);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(List.of(inHouse, cloud));
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.MENTOR)).thenReturn(List.of(inHouse));
        var options = routing.options(1L);
        assertThat(options.get(0).models())
                .containsExactly(new WorkspaceAiAvailability.Model(
                        "Llama 3.3", AiModelBrand.META, LlmConnectionPlatform.AZURE, DataHandlingTier.IN_HOUSE));
        // Cloud serves reviews from the cloud row and Heph from the in-house row, each named once.
        assertThat(options.get(1).models())
                .containsExactly(
                        new WorkspaceAiAvailability.Model(
                                "GPT-6 Luna", AiModelBrand.OPENAI, LlmConnectionPlatform.AZURE, DataHandlingTier.CLOUD),
                        new WorkspaceAiAvailability.Model(
                                "Llama 3.3",
                                AiModelBrand.META,
                                LlmConnectionPlatform.AZURE,
                                DataHandlingTier.IN_HOUSE));
    }

    @Test
    void shouldLeaveBrandUnknownWhenAdminDidNotDeclareIt() {
        var workspace = new Workspace();
        when(workspaces.findById(1L)).thenReturn(Optional.of(workspace));
        var cloud = ready(DataHandlingTier.CLOUD);
        var cloudModel = model("Custom model", "gpt-5", "https://api.openai.com/v1", null);
        cloudModel.setDataHandling(DataHandlingFacts.of(LlmDataOperator.PROVIDER, null));
        cloud.setInstanceModel(cloudModel);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.MENTOR)).thenReturn(List.of(cloud));
        assertThat(routing.options(1L).get(1).models())
                .containsExactly(new WorkspaceAiAvailability.Model("Custom model", null, null, DataHandlingTier.CLOUD));
    }

    private static LlmModel model(String name, String upstreamId, String baseUrl, @Nullable AiModelBrand brand) {
        var connection = new LlmConnection();
        connection.setBaseUrl(baseUrl);
        var model = new LlmModel();
        model.setDisplayName(name);
        model.setBrand(brand);
        model.setUpstreamModelId(upstreamId);
        model.setConnection(connection);
        return model;
    }

    @Test
    void shouldNameEveryTierNoReadyBindingServesIncludingMembersWhoMayLeaveTheChoiceOpen() {
        when(preferences.forDeveloper(1L, null)).thenReturn(new MemberAiPreferences.Decision(false, null));
        var off = ready(DataHandlingTier.UNDECLARED);
        off.setEnabled(false);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_DECISION))
                .thenReturn(List.of(off));

        assertThat(routing.unservedTiers(1L, AgentPurpose.PRACTICE_DECISION))
                .containsExactly(DataHandlingTier.IN_HOUSE, DataHandlingTier.CLOUD, DataHandlingTier.UNDECLARED);
    }

    @Test
    void shouldCountACloudMemberServedByTheInHouseBindingAndIgnoreTheOpenChoiceWhenItIsRequired() {
        when(preferences.forDeveloper(1L, null)).thenReturn(new MemberAiPreferences.Decision(true, null));
        var inHouse = ready(DataHandlingTier.IN_HOUSE);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_EMBEDDING))
                .thenReturn(List.of(inHouse));

        assertThat(routing.unservedTiers(1L, AgentPurpose.PRACTICE_EMBEDDING)).isEmpty();
    }

    @Test
    void shouldLeaveInHouseMembersUnservedByACloudBinding() {
        when(preferences.forDeveloper(1L, null)).thenReturn(new MemberAiPreferences.Decision(false, null));
        var cloud = ready(DataHandlingTier.CLOUD);
        var undeclared = ready(DataHandlingTier.UNDECLARED);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_RERANKING))
                .thenReturn(List.of(cloud, undeclared));

        assertThat(routing.unservedTiers(1L, AgentPurpose.PRACTICE_RERANKING))
                .containsExactly(DataHandlingTier.IN_HOUSE);
    }

    @Test
    void shouldGiveACloudMemberNoCloudPrecomputeModelWhenTheirReviewRunsInHouse() {
        chose(MemberAiChoice.CLOUD);
        var review = ready(DataHandlingTier.IN_HOUSE);
        var decision = ready(DataHandlingTier.CLOUD);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(List.of(review));
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_DECISION))
                .thenReturn(List.of(decision));

        assertThat(routing.precomputeBinding(1L, AgentPurpose.PRACTICE_DECISION, 20L))
                .isEmpty();
    }

    @Test
    void shouldGiveACloudMemberAStricterPrecomputeModelThanTheirCloudReview() {
        chose(MemberAiChoice.CLOUD);
        var review = ready(DataHandlingTier.CLOUD);
        var decision = ready(DataHandlingTier.IN_HOUSE);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(List.of(review));
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_DECISION))
                .thenReturn(List.of(decision));

        assertThat(routing.precomputeBinding(1L, AgentPurpose.PRACTICE_DECISION, 20L))
                .contains(decision);
    }

    @Test
    void shouldGiveAnInHouseMemberNoPrecomputeModelWhenOnlyACloudOneIsAssigned() {
        chose(MemberAiChoice.IN_HOUSE_ONLY);
        var inHouseReview = ready(DataHandlingTier.IN_HOUSE);
        var cloudReview = ready(DataHandlingTier.CLOUD);
        var decision = ready(DataHandlingTier.CLOUD);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(List.of(inHouseReview, cloudReview));
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_DECISION))
                .thenReturn(List.of(decision));

        assertThat(routing.precomputeBinding(1L, AgentPurpose.PRACTICE_DECISION, 20L))
                .isEmpty();
    }

    @Test
    void shouldGiveNoPrecomputeModelWhenNoReviewModelServesTheMember() {
        chose(MemberAiChoice.CLOUD);
        var decision = ready(DataHandlingTier.CLOUD);
        lenient()
                .when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_DECISION))
                .thenReturn(List.of(decision));

        assertThat(routing.precomputeBinding(1L, AgentPurpose.PRACTICE_DECISION, 20L))
                .isEmpty();
    }

    /** With no review model, no review runs for those members, and the review purpose reports that gap. */
    @Test
    void shouldJudgeMembersAtTheirOwnCeilingWhenNoReviewModelServesThem() {
        when(preferences.forDeveloper(1L, null)).thenReturn(new MemberAiPreferences.Decision(true, null));
        var decision = ready(DataHandlingTier.CLOUD);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_DECISION))
                .thenReturn(List.of(decision));

        assertThat(routing.unservedTiers(1L, AgentPurpose.PRACTICE_DECISION))
                .containsExactly(DataHandlingTier.IN_HOUSE);
    }

    @Test
    void shouldNameTheMembersEachBindingServesWithThePrecomputeBindingCappedAtTheirReview() {
        when(preferences.forDeveloper(1L, null)).thenReturn(new MemberAiPreferences.Decision(false, null));
        var review = ready(AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.IN_HOUSE);
        var undeclaredReview = ready(AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED);
        var inHouseDecision = ready(AgentPurpose.PRACTICE_DECISION, DataHandlingTier.IN_HOUSE);
        var cloudDecision = ready(AgentPurpose.PRACTICE_DECISION, DataHandlingTier.CLOUD);
        var cloudHeph = ready(AgentPurpose.MENTOR, DataHandlingTier.CLOUD);
        when(bindings.findByWorkspaceIdWithModels(1L))
                .thenReturn(List.of(review, undeclaredReview, inHouseDecision, cloudDecision, cloudHeph));

        assertThat(routing.servedTiers(1L))
                .containsOnlyKeys(AgentPurpose.PRACTICE_REVIEW, AgentPurpose.PRACTICE_DECISION, AgentPurpose.MENTOR)
                .containsEntry(
                        AgentPurpose.PRACTICE_REVIEW,
                        Map.of(
                                DataHandlingTier.IN_HOUSE,
                                List.of(DataHandlingTier.IN_HOUSE, DataHandlingTier.CLOUD),
                                DataHandlingTier.UNDECLARED,
                                List.of(DataHandlingTier.UNDECLARED)))
                .containsEntry(
                        AgentPurpose.PRACTICE_DECISION,
                        Map.of(DataHandlingTier.IN_HOUSE, List.of(DataHandlingTier.IN_HOUSE, DataHandlingTier.CLOUD)))
                .containsEntry(AgentPurpose.MENTOR, Map.of(DataHandlingTier.CLOUD, List.of(DataHandlingTier.CLOUD)));
    }

    /**
     * The binding view, the needs, and the route of a review's precompute model must give the same answers, or
     * the AI models page shows a model that the review never uses. Each row names, for the members who chose
     * in-house and for those who chose cloud, the decision binding that serves them and the one that their review
     * routes to. A member whom no review binding serves still has a serving decision binding, but no route.
     */
    @ParameterizedTest
    @MethodSource("assignments")
    void shouldAnswerAsTheNeedsAndThePrecomputeRouteDoWhenTheBindingViewNamesTheMembersServed(
            Set<DataHandlingTier> reviewTiers,
            Set<DataHandlingTier> decisionTiers,
            @Nullable DataHandlingTier inHouseSlot,
            @Nullable DataHandlingTier inHouseRoute,
            @Nullable DataHandlingTier cloudSlot,
            @Nullable DataHandlingTier cloudRoute) {
        lenient().when(preferences.forDeveloper(1L, null)).thenReturn(new MemberAiPreferences.Decision(true, null));
        var reviews = reviewTiers.stream()
                .map(tier -> ready(AgentPurpose.PRACTICE_REVIEW, tier))
                .toList();
        var decisions = decisionTiers.stream()
                .map(tier -> ready(AgentPurpose.PRACTICE_DECISION, tier))
                .toList();
        lenient()
                .when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(reviews);
        lenient()
                .when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_DECISION))
                .thenReturn(decisions);
        lenient()
                .when(bindings.findByWorkspaceIdWithModels(1L))
                .thenReturn(Stream.concat(reviews.stream(), decisions.stream()).toList());

        var served = routing.servedTiers(1L).getOrDefault(AgentPurpose.PRACTICE_DECISION, Map.of());
        var unserved = routing.unservedTiers(1L, AgentPurpose.PRACTICE_DECISION);
        for (var expected : List.of(
                new Served(MemberAiChoice.IN_HOUSE_ONLY, inHouseSlot, inHouseRoute),
                new Served(MemberAiChoice.CLOUD, cloudSlot, cloudRoute))) {
            var choice = expected.choice();
            var member = choice.ceiling().orElseThrow();
            var slot = expected.slot();
            var route = expected.route();
            assertThat(served.entrySet().stream()
                            .filter(entry -> entry.getValue().contains(member))
                            .map(Map.Entry::getKey)
                            .findFirst())
                    .as("slot of %s", member)
                    .isEqualTo(Optional.ofNullable(slot));
            assertThat(unserved.contains(member)).as("unserved %s", member).isEqualTo(slot == null);
            lenient()
                    .when(preferences.forDeveloper(1L, 20L))
                    .thenReturn(new MemberAiPreferences.Decision(true, choice));
            assertThat(routing.precomputeBinding(1L, AgentPurpose.PRACTICE_DECISION, 20L)
                            .map(WorkspaceAgentBinding::getDataHandlingTier))
                    .as("route of %s", member)
                    .isEqualTo(Optional.ofNullable(route));
        }
    }

    private record Served(
            MemberAiChoice choice,
            @Nullable DataHandlingTier slot,
            @Nullable DataHandlingTier route) {}

    /** Reviews, decisions, then the slot and the route of an in-house member and of a cloud member. */
    static Stream<Arguments> assignments() {
        var none = Set.<DataHandlingTier>of();
        var inHouse = Set.of(DataHandlingTier.IN_HOUSE);
        var cloud = Set.of(DataHandlingTier.CLOUD);
        var both = Set.of(DataHandlingTier.IN_HOUSE, DataHandlingTier.CLOUD);
        DataHandlingTier i = DataHandlingTier.IN_HOUSE;
        DataHandlingTier c = DataHandlingTier.CLOUD;
        return Stream.of(
                Arguments.of(none, none, null, null, null, null),
                Arguments.of(none, inHouse, i, null, i, null),
                Arguments.of(none, cloud, null, null, c, null),
                Arguments.of(none, both, i, null, c, null),
                Arguments.of(inHouse, none, null, null, null, null),
                Arguments.of(inHouse, inHouse, i, i, i, i),
                // The cloud member's review runs in-house, so a cloud decision model would see in-house work.
                Arguments.of(inHouse, cloud, null, null, null, null),
                Arguments.of(inHouse, both, i, i, i, i),
                Arguments.of(cloud, none, null, null, null, null),
                Arguments.of(cloud, inHouse, i, null, i, i),
                Arguments.of(cloud, cloud, null, null, c, c),
                Arguments.of(cloud, both, i, null, c, c),
                Arguments.of(both, none, null, null, null, null),
                Arguments.of(both, inHouse, i, i, i, i),
                Arguments.of(both, cloud, null, null, c, c),
                Arguments.of(both, both, i, i, c, c));
    }

    @Test
    void shouldGiveAMemberWhoHasNotChosenTheUndeclaredPrecomputeModelBesideTheUndeclaredReview() {
        when(preferences.forDeveloper(1L, 20L)).thenReturn(new MemberAiPreferences.Decision(false, null));
        var review = ready(DataHandlingTier.UNDECLARED);
        var decision = ready(DataHandlingTier.UNDECLARED);
        when(bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.of(review));
        when(bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        1L, AgentPurpose.PRACTICE_DECISION, DataHandlingTier.UNDECLARED))
                .thenReturn(Optional.of(decision));

        assertThat(routing.precomputeBinding(1L, AgentPurpose.PRACTICE_DECISION, 20L))
                .contains(decision);
    }

    @Test
    void shouldReportCloudMembersUnservedWhenTheirReviewRunsInHouseAndTheDecisionModelIsCloud() {
        when(preferences.forDeveloper(1L, null)).thenReturn(new MemberAiPreferences.Decision(true, null));
        var review = ready(DataHandlingTier.IN_HOUSE);
        var decision = ready(DataHandlingTier.CLOUD);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(List.of(review));
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_DECISION))
                .thenReturn(List.of(decision));

        assertThat(routing.unservedTiers(1L, AgentPurpose.PRACTICE_DECISION))
                .containsExactly(DataHandlingTier.IN_HOUSE, DataHandlingTier.CLOUD);
    }

    @Test
    void shouldReportCloudMembersServedWhenTheirCloudReviewHasACloudDecisionModel() {
        when(preferences.forDeveloper(1L, null)).thenReturn(new MemberAiPreferences.Decision(true, null));
        var inHouseReview = ready(DataHandlingTier.IN_HOUSE);
        var cloudReview = ready(DataHandlingTier.CLOUD);
        var decision = ready(DataHandlingTier.CLOUD);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(List.of(inHouseReview, cloudReview));
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_DECISION))
                .thenReturn(List.of(decision));

        assertThat(routing.unservedTiers(1L, AgentPurpose.PRACTICE_DECISION))
                .containsExactly(DataHandlingTier.IN_HOUSE);
    }

    @Test
    void shouldNotRouteErasedIdentityToReviewsOrHephEvenWhenAiChoiceIsOptional() {
        when(suppression.isUserSuppressed(20L)).thenReturn(true);
        assertThat(routing.binding(1L, AgentPurpose.PRACTICE_REVIEW, 20L)).isEmpty();
        assertThat(routing.binding(1L, AgentPurpose.MENTOR, 20L)).isEmpty();
        assertThat(routing.allows(1L, 20L, LlmModelResolver.ConnectionRef.NONE)).isFalse();
        verifyNoInteractions(preferences, bindings, models);
    }
}

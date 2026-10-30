package de.tum.cit.aet.hephaestus.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ConfigSnapshotTest extends BaseUnitTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void roundTripStoresOneModelWithTypedProtocolAndNoCredentials() {
        var snapshot = snapshot(600)
                .withPriceSnapshot(LlmPriceSnapshot.unpricedInstance())
                .withPracticeScript(Map.of(ModelKind.EMBEDDING, model(LlmApiProtocol.OPENAI_EMBEDDINGS)));
        var json = snapshot.toJson(mapper);
        assertThat(ConfigSnapshot.fromJson(json, mapper)).isEqualTo(snapshot);
        assertThat(json.path("model").path("apiProtocol").asString()).isEqualTo("openai-responses");
        assertThat(json.has("apiProtocol")).isFalse();
        assertThat(json.toString()).doesNotContain("apiKey", "authHeaderName", "authValuePrefix");
        assertThat(snapshot.model().priceSnapshot()).isEqualTo(LlmPriceSnapshot.unpricedInstance());
    }

    @Test
    void freezesOneResolutionAndPreservesModelIdentityWhenRepriced() {
        var binding = mock(WorkspaceAgentBinding.class);
        var resolver = mock(LlmModelResolver.class);
        when(binding.getTimeoutSeconds()).thenReturn(600);
        when(binding.getDataHandlingTier()).thenReturn(DataHandlingTier.IN_HOUSE);
        when(resolver.resolve(binding))
                .thenReturn(new ResolvedLlmModel(
                        "https://models.example/v1",
                        "openai-responses",
                        "model-1",
                        16000,
                        1000,
                        ReasoningEffort.HIGH,
                        false));
        when(resolver.connectionRef(binding))
                .thenReturn(new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 7L, 9L, null));
        var snapshot = ConfigSnapshot.from(binding, resolver);
        assertThat(snapshot.contextWindow()).isEqualTo(16000);
        assertThat(snapshot.maxOutputTokens()).isEqualTo(1000);
        assertThat(snapshot.model().apiProtocol()).isEqualTo(LlmApiProtocol.OPENAI_RESPONSES);
        assertThat(snapshot.schemaVersion()).isEqualTo(9);
        assertThat(snapshot.toJson(mapper).has("allowInternet")).isFalse();
        assertThat(snapshot.withPriceSnapshot(LlmPriceSnapshot.unpricedInstance())
                        .model()
                        .connectionRef())
                .isEqualTo(snapshot.model().connectionRef());
        verify(resolver).resolve(binding);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 5, 7, 8, 10})
    void rejectsEveryOtherSchemaVersion(int version) {
        var json = (ObjectNode) snapshot(600).toJson(mapper);
        json.put("schemaVersion", version);
        assertThatThrownBy(() -> ConfigSnapshot.fromJson(json, mapper)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnknownProtocolAtTheJsonBoundary() {
        var json = (ObjectNode) snapshot(600).toJson(mapper);
        ((ObjectNode) json.path("model")).put("apiProtocol", "unknown");
        assertThatThrownBy(() -> ConfigSnapshot.fromJson(json, mapper)).isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void freezesScriptSlotsAgainstCallerMutation() {
        var slots = new EnumMap<ModelKind, FrozenModel>(ModelKind.class);
        slots.put(ModelKind.EMBEDDING, model(LlmApiProtocol.OPENAI_EMBEDDINGS));
        var snapshot = snapshot(600).withPracticeScript(slots);
        slots.clear();
        assertThat(snapshot.practiceScriptSlot(ModelKind.EMBEDDING)).isNotNull();
        assertThat(snapshot.practiceScriptSlot(ModelKind.RERANKING)).isNull();
    }

    @Test
    void enforcesTheRunnerTimeoutFloorAndRejectsNonpositiveTimeouts() {
        assertThat(snapshot(1).timeoutSeconds()).isEqualTo(PracticePiAdapter.MIN_TIMEOUT_SECONDS);
        assertThatThrownBy(() -> snapshot(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ConfigSnapshot snapshot(int timeout) {
        return new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION, model(LlmApiProtocol.OPENAI_RESPONSES), 16000, 1000, timeout, null);
    }

    private static FrozenModel model(LlmApiProtocol protocol) {
        return new FrozenModel(
                protocol,
                "https://models.example/v1",
                "model-1",
                UsageFundingSource.INSTANCE,
                7L,
                9L,
                null,
                DataHandlingTier.IN_HOUSE,
                ReasoningEffort.HIGH,
                null,
                false);
    }
}

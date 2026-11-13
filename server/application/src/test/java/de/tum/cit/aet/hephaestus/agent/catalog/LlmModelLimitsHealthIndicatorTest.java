package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class LlmModelLimitsHealthIndicatorTest extends BaseUnitTest {

    @Test
    void shouldNameEachEnabledModelThatCannotRunAndStayUp() {
        var connection = LlmCatalogTestFixtures.connection("vllm");
        connection.setDisplayName("Self-hosted");
        var unbounded = LlmCatalogTestFixtures.model(connection, "qwen", "Qwen/Qwen3.8-27B");
        unbounded.setDisplayName("Qwen");
        var discovered = LlmCatalogTestFixtures.model(connection, "oss", "openai/gpt-oss-120b");
        discovered.setDiscoveredContextWindow(131_072);
        var off = LlmCatalogTestFixtures.model(connection, "off", "off", ModelVisibility.PUBLIC, false);
        var models = mock(LlmModelRepository.class);
        var workspaceModels = mock(WorkspaceLlmModelRepository.class);
        when(models.findAllWithConnection()).thenReturn(List.of(unbounded, discovered, off));
        var own = new WorkspaceLlmModel();
        own.setUpstreamModelId("llama3.2");
        when(workspaceModels.findAllEnabledWithEnabledConnection()).thenReturn(List.of(own));

        var health = new LlmModelLimitsHealthIndicator(models, workspaceModels).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("modelsWithoutOutputLimit", List.of("Self-hosted / Qwen"))
                .containsEntry("workspaceModelsWithoutOutputLimit", 1L)
                .containsEntry("fix", LlmModelLimitsHealthIndicator.FIX);
    }

    @Test
    void shouldNameNothingWhenEveryModelHasALimit() {
        var models = mock(LlmModelRepository.class);
        var workspaceModels = mock(WorkspaceLlmModelRepository.class);
        when(models.findAllWithConnection()).thenReturn(List.of());
        when(workspaceModels.findAllEnabledWithEnabledConnection()).thenReturn(List.of());

        var health = new LlmModelLimitsHealthIndicator(models, workspaceModels).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).doesNotContainKey("fix");
    }
}

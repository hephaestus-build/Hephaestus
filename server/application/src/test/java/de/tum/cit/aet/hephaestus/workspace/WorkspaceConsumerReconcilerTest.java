package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
class WorkspaceConsumerReconcilerTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(WorkspaceQueryService.class, () -> mock(WorkspaceQueryService.class))
            .withBean(IntegrationNatsConsumer.class, () -> mock(IntegrationNatsConsumer.class))
            .withUserConfiguration(WorkspaceConsumerReconciler.class);

    @Test
    void shouldRunOnlyOnTheServerWithNatsEnabled() {
        context.withPropertyValues("hephaestus.runtime.server.enabled=true", "hephaestus.sync.nats.enabled=true")
                .run(application -> assertThat(application).hasSingleBean(WorkspaceConsumerReconciler.class));
        context.withPropertyValues("hephaestus.runtime.server.enabled=false", "hephaestus.sync.nats.enabled=true")
                .run(application -> assertThat(application).doesNotHaveBean(WorkspaceConsumerReconciler.class));
        context.withPropertyValues("hephaestus.runtime.server.enabled=true", "hephaestus.sync.nats.enabled=false")
                .run(application -> assertThat(application).doesNotHaveBean(WorkspaceConsumerReconciler.class));
    }
}

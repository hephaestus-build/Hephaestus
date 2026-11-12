package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

class ProductFeedbackModelGatewayTest extends BaseUnitTest {
    @Test
    void shouldCarryTheOutputBoundAndCapacityRefusalThroughTheSharedProxy() {
        var proxy = mock(LlmProxyService.class);
        var mapper = new ObjectMapper();
        var gateway = new ProductFeedbackModelGateway(proxy, mapper);
        var model = new LlmModelResolver.InstanceModel(
                new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 7L, 8L, null),
                new LlmModelResolver.ProxyCredential(
                        "https://model.example",
                        "openai-completions",
                        LlmAuthMode.BEARER,
                        "draft-model",
                        "synthetic",
                        1L),
                LlmApiProtocol.OPENAI_COMPLETIONS,
                null,
                null,
                700);
        doReturn(ResponseEntity.status(503)
                        .header(LlmProxyService.REFUSAL_HEADER, ProxyRefusal.CAPACITY.name())
                        .body("busy"))
                .when(proxy)
                .serve(any(), any(), isNull(), any(), any());
        UUID report = UUID.randomUUID();
        var answer = gateway.draft(
                UUID.randomUUID(),
                new ProductFeedbackDrafts.Payload(report, 3),
                model,
                Instant.now().plusSeconds(30),
                900,
                new byte[0]);
        assertThat(answer.refusal()).isEqualTo(ProxyRefusal.CAPACITY);
        var route = ArgumentCaptor.forClass(ProxyRouting.class);
        verify(proxy).serve(route.capture(), any(), isNull(), any(), any());
        assertThat(route.getValue().workspaceId()).isNull();
        assertThat(route.getValue().attempt().runId()).isNull();
        assertThat(route.getValue().attempt().sourceType()).isEqualTo(UsageSourceType.PRODUCT_FEEDBACK_DRAFT);
        assertThat(route.getValue().attempt().sourceId()).isEqualTo(report);
        assertThat(route.getValue().outputLimit()).isEqualTo(700L);
    }
}
